package dev.nightjar.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nightjar.app.core.ipc.EventJson
import dev.nightjar.app.data.audit.RoomAuditLog
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.data.repository.RoomDetectionRepository
import dev.nightjar.app.data.repository.RoomMessageRepository
import dev.nightjar.app.data.repository.RoomMessageStore
import dev.nightjar.contract.AnomalyCode
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Ipc
import dev.nightjar.contract.Subtype
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ingest mapping: a payload event goes through [EventJson] and [RoomMessageStore] into the `message` row, and
 * the detail repository reads the same values back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventFieldsStoreTest {
    private lateinit var db: NightjarDatabase
    private lateinit var store: RoomMessageStore
    private lateinit var repository: RoomMessageRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, NightjarDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        store = RoomMessageStore(db, db.messageDao(), db.pduDao(), db.traceStepDao())
        repository =
            RoomMessageRepository(
                db.messageDao(),
                db.pduDao(),
                db.traceStepDao(),
                RoomDetectionRepository(db, db.detectionDao()),
                RoomAuditLog(db.auditDao(), Clock { 1L }),
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun ingest(o: JSONObject): Long =
        runBlocking {
            val event = EventJson.parseEvent(o) { fields -> fields.classId.wire() }!!
            store.upsertBatch(listOf(event)).messageIds.single()
        }

    private fun base(id: String): JSONObject =
        JSONObject()
            .put("eventId", id)
            .put("finalClass", ClassId.SILENT.wire())
            .put("direction", "IN")

    @Test
    fun `version 2 fields are stored and read back by the detail repository`() =
        runBlocking {
            val extras = listOf("${ClassId.DATA_PORT.wire()}:UNKNOWN", ClassId.BINARY.wire())
            val anomalies = listOf(AnomalyCode.PORT_ZERO.wire(), AnomalyCode.IE_DUP.wire())
            val id =
                ingest(
                    base("v2")
                        .put(Ipc.EventKey.SUBTYPE, Subtype.Silent.TYPE0_PORT)
                        .put(Ipc.EventKey.EXTRA_SUBTYPES, JSONArray(extras))
                        .put(Ipc.EventKey.ANOMALIES, JSONArray(anomalies)),
                )

            val row = db.messageDao().find(id)!!
            assertEquals(Subtype.Silent.TYPE0_PORT, row.subtype)
            assertEquals(extras, StringListColumn.decode(row.extraSubtypes))
            assertEquals(anomalies, StringListColumn.decode(row.anomalies))

            val detail = repository.find(id)!!
            assertEquals(Subtype.Silent.TYPE0_PORT, detail.subtype)
            assertEquals(extras, detail.extraSubtypes)
            assertEquals(anomalies, detail.anomalies)
        }

    @Test
    fun `a version 1 event stores null columns and reads empty lists`() =
        runBlocking {
            val id = ingest(base("v1"))
            val row = db.messageDao().find(id)!!
            assertNull(row.subtype)
            assertNull(row.extraSubtypes)
            assertNull(row.anomalies)
            val detail = repository.find(id)!!
            assertNull(detail.subtype)
            assertTrue(detail.extraSubtypes.isEmpty())
            assertTrue(detail.anomalies.isEmpty())
        }

    @Test
    fun `a higher revision replaces the version 2 fields`() =
        runBlocking {
            val id = ingest(base("rev").put(Ipc.EventKey.ANOMALIES, JSONArray(listOf(AnomalyCode.MTI.wire()))))
            ingest(base("rev").put("revision", 2).put(Ipc.EventKey.SUBTYPE, Subtype.Silent.TYPE0))
            val row = db.messageDao().find(id)!!
            assertEquals(Subtype.Silent.TYPE0, row.subtype)
            assertNull(row.anomalies)
        }
}
