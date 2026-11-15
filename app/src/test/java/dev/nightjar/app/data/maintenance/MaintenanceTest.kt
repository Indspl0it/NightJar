package dev.nightjar.app.data.maintenance

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nightjar.app.core.log.NoopALog
import dev.nightjar.app.core.settings.DataStoreSettingsStore
import dev.nightjar.app.core.settings.InMemoryPreferencesDataStore
import dev.nightjar.app.core.settings.Retention
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.RoomAuditLog
import dev.nightjar.app.data.db.NightjarDatabase
import dev.nightjar.app.data.db.StagedEventEntity
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.data.repository.IncomingEvent
import dev.nightjar.app.data.repository.PduPayload
import dev.nightjar.app.data.repository.RoomMessageStore
import dev.nightjar.app.data.repository.TracePayload
import dev.nightjar.app.data.staging.StagingSink
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Retention rules, the count of rows that a retention run would delete, and "delete all". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MaintenanceTest {
    private val day = 24L * 60 * 60 * 1000
    private lateinit var context: Context
    private lateinit var db: NightjarDatabase
    private lateinit var store: RoomMessageStore
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: SettingsStore
    private lateinit var audit: RoomAuditLog

    // 2026-11-05T00:00:00Z: a realistic "now" so the 2-year counter window and the 365-day audit window are testable.
    private var now =
        java.time.Instant
            .parse("2026-11-05T00:00:00Z")
            .toEpochMilli()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, NightjarDatabase::class.java).allowMainThreadQueries().build()
        store = RoomMessageStore(db, db.messageDao(), db.pduDao(), db.traceStepDao())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = InMemoryPreferencesDataStore()
        audit = RoomAuditLog(db.auditDao(), Clock { now })
        settings = DataStoreSettingsStore(dataStore, audit, Clock { now }, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun housekeeping() =
        RoomHousekeeping(
            db.messageDao(),
            db.counterDao(),
            db.hookStatusDao(),
            db.selfTestRunDao(),
            db.auditDao(),
            db.detectionDao(),
            db.muteDao(),
            settings,
            NoopALog,
            context,
        )

    /** Counts `clear()` calls; staging needs SQLCipher, which a JVM test does not have. */
    private class FakeStaging : StagingSink {
        var cleared = 0

        override suspend fun stage(
            eventId: String,
            json: String,
            receivedAt: Long,
        ): Boolean = true

        override suspend fun drain(consume: suspend (StagedEventEntity) -> Boolean): Int = 0

        override suspend fun clear(): Int {
            cleared++
            return 0
        }
    }

    private fun wiper(staging: StagingSink = FakeStaging()) = RoomDataWiper(db, audit, NoopALog, Clock { now }, staging, context)

    @Test
    fun `retention deletes old rows but keeps quarantined and starred`() =
        runBlocking {
            settings.setRetention(Retention.DAYS_90)
            store.upsertBatch(
                listOf(
                    event("old", now - 100 * day),
                    event("old-quarantined", now - 100 * day).copy(isQuarantined = true),
                    event("old-starred", now - 100 * day).copy(isStarred = true),
                    event("new", now - 10 * day),
                ),
            )
            val report = housekeeping().run(now)
            assertEquals(1, report.messagesDeleted)
            assertEquals(3, db.messageDao().countAll())
            assertTrue(db.messageDao().findByEventId("old") == null)
        }

    @Test
    fun `forever retention deletes nothing`() =
        runBlocking {
            settings.setRetention(Retention.FOREVER)
            store.upsertBatch(listOf(event("ancient", 1)))
            assertEquals(0, housekeeping().run(now).messagesDeleted)
            assertEquals(1, db.messageDao().countAll())
        }

    @Test
    fun `dlg16 count matches what a run would delete`() =
        runBlocking {
            store.upsertBatch(
                listOf(
                    event("a", now - 200 * day),
                    event("b", now - 200 * day),
                    event("c", now - 10 * day),
                ),
            )
            assertEquals(2, housekeeping().countForRetention(Retention.DAYS_90, now))
            assertEquals(0, housekeeping().countForRetention(Retention.FOREVER, now))
            // 30 days keeps the 10-day-old row: only the two 200-day-old rows are older than every setting.
            assertEquals(2, housekeeping().countForRetention(Retention.DAYS_30, now))
            assertEquals(3, housekeeping().countForRetention(Retention.DAYS_30, now + 21 * day))
        }

    @Test
    fun `counters are kept two years`() =
        runBlocking {
            db.counterDao().add(20200101, 1, "IN", ClassId.NORMAL.wire(), 4)
            db.counterDao().add(20261105, 1, "IN", ClassId.NORMAL.wire(), 7)
            val report = housekeeping().run(now)
            assertEquals(1, report.countersDeleted)
            assertEquals(
                7L,
                db
                    .counterDao()
                    .from(0)
                    .single()
                    .count,
            )
        }

    @Test
    fun `audit older than a year is purged`() =
        runBlocking {
            db.auditDao().insert(
                dev.nightjar.app.data.db
                    .AuditEntity(0, now - 400 * day, "USER", "EXPORT", null, null, null),
            )
            db.auditDao().insert(
                dev.nightjar.app.data.db
                    .AuditEntity(0, now - 10 * day, "USER", "EXPORT", null, null, null),
            )
            assertEquals(1, housekeeping().run(now).auditRowsDeleted)
            assertEquals(1, db.auditDao().count())
        }

    @Test
    fun `an old detection without members is removed, also when it was not reviewed`() =
        runBlocking {
            val dao = db.detectionDao()

            fun detection(lastAt: Long) =
                dev.nightjar.app.data.db.DetectionEntity(
                    detectorId = "DET-01",
                    mergeKey = null,
                    severity = Severity.NOTABLE.wire(),
                    summary = "s",
                    firstAt = lastAt,
                    lastAt = lastAt,
                    count = 1,
                    isReviewed = false,
                    isOpen = true,
                )
            dao.insert(detection(now - 3 * day))
            dao.insert(detection(now - 1_000))
            val report = housekeeping().run(now)
            assertEquals(1, report.detectionsDeleted)
            assertEquals(1, dao.observeOpen().first().size)
        }

    @Test
    fun `expired mutes are removed`() =
        runBlocking {
            db.muteDao().put("ORIGINATOR", "+919000000000", now - 1)
            db.muteDao().put("CLASS", ClassId.SILENT.wire(), now + day)
            assertEquals(1, housekeeping().run(now).mutesDeleted)
            assertEquals(1, db.muteDao().active(now).size)
        }

    @Test
    fun `delete all keeps rules settings known senders and trusted packages`() =
        runBlocking {
            store.upsertBatch(listOf(event("m1"), event("m2")))
            db.ruleDao().upsertAll(
                listOf(
                    dev.nightjar.app.data.db
                        .RuleEntity(1, 1, true, "r", "{}", Verdict.BLOCK.wire(), "USER", 0, 0, 0, null),
                ),
            )
            db.classActionDao().upsertAll(
                listOf(
                    dev.nightjar.app.data.db
                        .ClassActionEntity(ClassId.WAP_CP.wire(), Verdict.QUARANTINE.wire(), "USER"),
                ),
            )
            db.knownOriginatorDao().insert(
                dev.nightjar.app.data.db
                    .KnownOriginatorEntity(0, "+9190", "PREFIX", null, 0),
            )
            db.trustedPackageDao().upsert(
                dev.nightjar.app.data.db
                    .TrustedPackageEntity("com.example", null, 0),
            )
            db.coverageDao().markSeenFromNetwork(ClassId.SILENT.wire(), 1)
            db.savedViewDao().insert(
                dev.nightjar.app.data.db
                    .SavedViewEntity(0, "v", "{}"),
            )
            settings.setRetention(Retention.DAYS_90)
            val auditBefore = db.auditDao().count()
            val staging = FakeStaging()

            wiper(staging).deleteAll()

            // Settings stay, staging goes.
            assertEquals(Retention.DAYS_90, settings.current().retention)
            assertEquals(1, staging.cleared)

            assertEquals(0, db.messageDao().countAll())
            assertEquals(0, db.coverageDao().all().size)
            assertEquals(
                0,
                db
                    .savedViewDao()
                    .observeAll()
                    .first()
                    .size,
            )
            assertEquals(1, db.ruleDao().all().size)
            assertEquals(1, db.classActionDao().all().size)
            assertEquals(1, db.knownOriginatorDao().all().size)
            assertEquals(1, db.trustedPackageDao().all().size)
            // The audit is not wiped: the rows from before stay and one DELETE_ALL row is added.
            assertEquals(auditBefore + 1, db.auditDao().count())
            assertEquals(
                AuditAction.DELETE_ALL.name,
                db
                    .auditDao()
                    .recent(1)
                    .single()
                    .action,
            )
        }

    @Test
    fun `delete all keeps an audit row even when it is the only one`() =
        runBlocking {
            wiper().deleteAll()
            assertEquals(1, db.auditDao().count())
            assertFalse(
                db
                    .auditDao()
                    .recent(1)
                    .single()
                    .action
                    .isBlank(),
            )
        }

    @Test
    fun `database size is reported and small in tests`() =
        runBlocking {
            val report = housekeeping().run(now)
            assertTrue(report.databaseBytes >= 0)
            assertFalse(report.overSizeLimit)
        }

    private fun event(
        id: String,
        receivedAt: Long = now,
    ): IncomingEvent =
        IncomingEvent(
            eventId = id,
            revision = 1,
            sessionId = "s",
            direction = "IN",
            transport = "CS",
            source = 0,
            subId = 1,
            slot = 0,
            carrierName = null,
            classId = ClassId.SILENT.wire(),
            severity = Severity.NOTABLE.wire(),
            detectorSeverity = null,
            severityOverridden = false,
            title = "t",
            originator = "+919000000000",
            smsc = null,
            destPort = null,
            contentType = null,
            textBody = null,
            textPreview = "p",
            receivedAtWall = receivedAt,
            receivedAtElapsedNs = 1,
            modemTs = null,
            ackResult = null,
            verdict = Verdict.ALLOW.wire(),
            ruleId = null,
            fate = Fate.SHOWN.wire(),
            fateDetail = null,
            consumerPackage = null,
            callingPackage = null,
            persistFlag = null,
            concatRef = null,
            partsTotal = 1,
            partsHave = 1,
            partsJson = null,
            purgeAt = null,
            isQuarantined = false,
            isReviewed = true,
            isStarred = false,
            isReplay = false,
            isSelfTest = false,
            releasedFromId = null,
            buildFingerprint = "fp",
            payloadVersion = "1",
            pdus = listOf(PduPayload(0, byteArrayOf(1), "3gpp")),
            trace = listOf(TracePayload(0, "stage", 0, null)),
        )
}
