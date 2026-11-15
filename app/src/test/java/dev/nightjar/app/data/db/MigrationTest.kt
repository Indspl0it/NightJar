package dev.nightjar.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nightjar.contract.AnomalyCode
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Subtype
import dev.nightjar.contract.Verdict
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A version 1 or version 2 database opens at the current version without data loss.
 *
 * The old database is built from the checked-in schema `N.json`, so the test starts from the real old schema and not
 * from a copy of it. Room then opens the file with [NightjarMigrations.ALL] and validates the result against the
 * current entities, which fails when a migration and the entity classes differ.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    private val name = "migration-test.db"
    private var db: NightjarDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(name)
    }

    @Test
    fun `version 1 opens at the current version and keeps its rows`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            createOld(context, SCHEMA_V1, 1)

            val opened = open(context)

            val row = opened.messageDao().find(1)
            assertEquals("old-event", row?.eventId)
            assertEquals(ClassId.SILENT.wire(), row?.classId)
            assertNull(row?.relatedMessageId)
            assertNull(row?.tpMr)
            assertNull(row?.deliveryStatus)
            assertNull(row?.destinationNorm)
            assertNull(row?.subtype)
            assertNull(row?.extraSubtypes)
            assertNull(row?.anomalies)

            // The new columns accept a delivery report's facts.
            opened.messageDao().setReportFacts(1, null, 5, "PENDING", "919000000002")
            assertEquals("PENDING", opened.messageDao().find(1)?.deliveryStatus)
        }

    @Test
    fun `version 2 opens at version 3 with empty subtype and anomaly columns`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            createOld(context, SCHEMA_V2, 2)

            val opened = open(context)

            val row = opened.messageDao().find(1)!!
            assertEquals("old-event", row.eventId)
            assertNull(row.subtype)
            assertNull(row.extraSubtypes)
            assertNull(row.anomalies)
            assertTrue(StringListColumn.decode(row.anomalies).isEmpty())

            // The new columns accept the IPC version 2 fields.
            val updated =
                row.copy(
                    subtype = Subtype.Silent.TYPE0,
                    anomalies = StringListColumn.encode(listOf(AnomalyCode.PID_RSV.wire())),
                )
            opened.messageDao().update(updated)
            val read = opened.messageDao().find(1)!!
            assertEquals(Subtype.Silent.TYPE0, read.subtype)
            assertEquals(listOf(AnomalyCode.PID_RSV.wire()), StringListColumn.decode(read.anomalies))

            // The migrated row has the event-field columns, null until a new revision fills them.
            assertNull(read.destination)
            assertNull(read.roaming)
            assertNull(read.cbSerial)
            assertNull(read.releaseOf)
            assertNull(read.simOtaHeader)
            // Also null on a migrated row (never processed under version 3).
            assertNull(read.emergencyCallActive)
            assertNull(read.processedRevision)
            // A migrated row is never a debug injection.
            assertNull(read.debugInjected)
            opened.messageDao().update(
                read.copy(roaming = true, cbMessageId = 4370, cbSerial = 0x3001, clampedFrom = "BLOCK", debugInjected = true),
            )
            val od24 = opened.messageDao().find(1)!!
            assertEquals(true, od24.roaming)
            assertEquals(4370, od24.cbMessageId)
            assertEquals(0x3001, od24.cbSerial)
            assertEquals("BLOCK", od24.clampedFrom)
            assertEquals(true, od24.debugInjected)
        }

    @Test
    fun `the migration list ends at the current version`() {
        assertTrue(NightjarMigrations.ALL.isNotEmpty())
        assertEquals(1, NightjarMigrations.ALL.first().startVersion)
        assertEquals(NightjarDatabase.VERSION, NightjarMigrations.ALL.last().endVersion)
        // Each step starts where the one before it ended, so no version is skipped.
        NightjarMigrations.ALL.toList().zipWithNext { a, b -> assertEquals(a.endVersion, b.startVersion) }
    }

    @Test
    fun `the schema file of the current version is checked in`() {
        val current = JSONObject(File(schemaPath(NightjarDatabase.VERSION)).readText()).getJSONObject("database")
        assertEquals(NightjarDatabase.VERSION, current.getInt("version"))
    }

    private fun open(context: Context): NightjarDatabase =
        Room
            .databaseBuilder(context, NightjarDatabase::class.java, name)
            .addMigrations(*NightjarMigrations.ALL)
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    private fun createOld(
        context: Context,
        schemaFile: String,
        version: Int,
    ) {
        val schema = JSONObject(File(schemaFile).readText()).getJSONObject("database")
        val raw = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                raw.execSQL(entity.getString("createSql").replace(TABLE_NAME, table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (n in 0 until indices.length()) {
                    raw.execSQL(indices.getJSONObject(n).getString("createSql").replace(TABLE_NAME, table))
                }
            }
            raw.execSQL(
                """
                INSERT INTO message (id, eventId, revision, direction, transport, source, subId, slot, classId, severity,
                    severityOverridden, title, textPreview, receivedAtWall, receivedAtElapsedNs, verdict, fate, partsTotal,
                    partsHave, isQuarantined, isReviewed, isStarred, isReplay, isSelfTest, needsProcessing)
                VALUES (1, 'old-event', 1, 'IN', 'CS', 0, 1, 0, '${ClassId.SILENT.wire()}', '${Severity.NOTABLE.wire()}',
                    0, 'Silent ping', 'preview', 1000, 1, '${Verdict.ALLOW.wire()}', '${Fate.SYSTEM.wire()}', 1,
                    1, 0, 1, 0, 0, 0, 0)
                """.trimIndent(),
            )
            raw.version = version
        } finally {
            raw.close()
        }
    }

    private companion object {
        /** Gradle runs the tests of `:app` with the module directory as the working directory. */
        const val SCHEMA_DIR = "schemas/dev.nightjar.app.data.db.NightjarDatabase"
        const val SCHEMA_V1 = "$SCHEMA_DIR/1.json"
        const val SCHEMA_V2 = "$SCHEMA_DIR/2.json"
        const val TABLE_NAME = "\${TABLE_NAME}"

        fun schemaPath(version: Int): String = "$SCHEMA_DIR/$version.json"
    }
}
