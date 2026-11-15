package dev.nightjar.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nightjar.app.core.settings.OutgoingBlockStore
import dev.nightjar.app.core.settings.OutgoingBlocking
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.RoomAuditLog
import dev.nightjar.app.data.model.FeedChip
import dev.nightjar.app.data.model.FilterSpec
import dev.nightjar.app.data.model.toListItem
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.data.repository.DetectionRepository
import dev.nightjar.app.data.repository.IncomingEvent
import dev.nightjar.app.data.repository.MessageStore
import dev.nightjar.app.data.repository.PduPayload
import dev.nightjar.app.data.repository.RoomDetectionRepository
import dev.nightjar.app.data.repository.RoomMessageRepository
import dev.nightjar.app.data.repository.RoomMessageStore
import dev.nightjar.app.data.repository.TracePayload
import dev.nightjar.app.data.repository.WeekCounts
import dev.nightjar.app.data.repository.WeekWindow
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Verdict
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Schema and DAO behaviour on the JVM through Robolectric.
 *
 * The database is opened without the SQLCipher factory here: encryption is a property of the open helper, not of the
 * schema, and the DAO/query behaviour is what these tests pin down. [DatabaseFactory] is what wires the key in
 * production.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseTest {
    private lateinit var db: NightjarDatabase
    private lateinit var store: MessageStore
    private lateinit var detections: DetectionRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, NightjarDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        store = RoomMessageStore(db, db.messageDao(), db.pduDao(), db.traceStepDao())
        detections = RoomDetectionRepository(db, db.detectionDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `every schema table exists`() {
        val tables =
            db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
        listOf(
            "message",
            "pdu",
            "trace_step",
            "detection",
            "detection_member",
            "rule",
            "class_action",
            "known_originator",
            "trusted_package",
            "counter",
            "coverage",
            "hook_status",
            "self_test_run",
            "audit",
            "mute",
            "saved_view",
        ).forEach { table -> assertTrue("missing table $table", table in tables) }
    }

    @Test
    fun `foreign keys cascade from message to pdu and trace`() =
        runBlocking {
            val id = store.upsert(event("e-1", pdus = 2, trace = 3)).let { db.messageDao().findByEventId("e-1")!!.id }
            assertEquals(2, db.pduDao().forMessage(id).size)
            assertEquals(3, db.traceStepDao().forMessage(id).size)
            db.messageDao().deleteByIds(listOf(id))
            assertEquals(0, db.pduDao().forMessage(id).size)
            assertEquals(0, db.traceStepDao().forMessage(id).size)
        }

    @Test
    fun `duplicate eventId is acknowledged without a second row`() =
        runBlocking {
            val first = store.upsertBatch(listOf(event("dup", pdus = 1, trace = 1)))
            assertEquals(1, first.inserted)
            assertEquals(listOf("dup:1"), first.acked)
            val second = store.upsertBatch(listOf(event("dup", pdus = 1, trace = 1)))
            assertEquals(0, second.inserted)
            assertEquals(1, second.duplicates)
            assertEquals(listOf("dup:1"), second.acked)
            assertEquals(1, db.messageDao().countAll())
        }

    @Test
    fun `higher revision replaces content and parts`() =
        runBlocking {
            store.upsertBatch(listOf(event("rev", pdus = 1, trace = 1, fate = Fate.PENDING_UNLOCK)))
            val row = db.messageDao().findByEventId("rev")!!
            val result = store.upsertBatch(listOf(event("rev", pdus = 2, trace = 2, revision = 2, fate = Fate.SHOWN)))
            assertEquals(1, result.updated)
            assertEquals(listOf("rev:2"), result.acked)
            val updated = db.messageDao().findByEventId("rev")!!
            assertEquals(2, updated.revision)
            assertEquals(Fate.SHOWN.wire(), updated.fate)
            assertEquals(2, db.pduDao().forMessage(row.id).size)
            assertEquals(2, db.traceStepDao().forMessage(row.id).size)
        }

    @Test
    fun `lower revision is a duplicate`() =
        runBlocking {
            store.upsertBatch(listOf(event("low", revision = 3)))
            val result = store.upsertBatch(listOf(event("low", revision = 2)))
            assertEquals(1, result.duplicates)
            assertEquals(3, db.messageDao().findByEventId("low")!!.revision)
        }

    @Test
    fun `user state survives a higher revision`() =
        runBlocking {
            store.upsertBatch(listOf(event("state")))
            val id = db.messageDao().findByEventId("state")!!.id
            db.messageDao().setReviewed(listOf(id), true)
            db.messageDao().setStarred(listOf(id), true)
            store.upsertBatch(listOf(event("state", revision = 2)))
            val row = db.messageDao().find(id)!!
            assertTrue(row.isReviewed)
            assertTrue(row.isStarred)
        }

    @Test
    fun `preview is truncated to 120 characters`() =
        runBlocking {
            store.upsertBatch(listOf(event("long").copy(textPreview = "x".repeat(500))))
            assertEquals(
                120,
                db
                    .messageDao()
                    .findByEventId("long")!!
                    .textPreview.length,
            )
        }

    @Test
    fun `originator is stored raw and normalised`() =
        runBlocking {
            store.upsertBatch(listOf(event("num").copy(originator = "+91 90000 00031")))
            val row = db.messageDao().findByEventId("num")!!
            assertEquals("+91 90000 00031", row.originator)
            assertEquals("919000000031", row.originatorNorm)
        }

    @Test
    fun `new rows need processing until marked`() =
        runBlocking {
            store.upsertBatch(listOf(event("p1"), event("p2")))
            assertEquals(2, store.pendingProcessing().size)
            store.markProcessed(store.pendingProcessing())
            assertEquals(0, store.pendingProcessing().size)
        }

    @Test
    fun `feed excludes counters and self-test rows, and shows delivery reports by default`() =
        runBlocking {
            store.upsertBatch(
                listOf(
                    event("silent").copy(classId = ClassId.SILENT.wire()),
                    event("normal").copy(classId = ClassId.NORMAL.wire()),
                    event("movi").copy(classId = ClassId.MO_VISIBLE.wire()),
                    event("selftest").copy(classId = ClassId.SILENT.wire(), isSelfTest = true),
                    event("status").copy(classId = ClassId.STATUS.wire()),
                ),
            )
            assertEquals(2, count(FilterSpec.DEFAULT))
            assertEquals(1, count(FilterSpec(showDeliveryReports = false)))
        }

    @Test
    fun `a delivery report is in the feed with the default filters and carries its status and link`() =
        runBlocking {
            val sent = store.upsertBatch(listOf(event("sent").copy(direction = "OUT", classId = ClassId.MO_DATA.wire())))
            val report = store.upsertBatch(listOf(event("report").copy(classId = ClassId.STATUS.wire())))
            db.messageDao().setReportFacts(
                id = report.messageIds.single(),
                relatedMessageId = sent.messageIds.single(),
                tpMr = 7,
                deliveryStatus = "DELIVERED",
                destinationNorm = "919000000002",
            )

            val rows = feedRows(FilterSpec.DEFAULT)

            val row = rows.single { it.classId == ClassId.STATUS.wire() }
            assertEquals("DELIVERED", row.deliveryStatus)
            assertEquals(sent.messageIds.single(), row.relatedMessageId)
            assertEquals(1, row.repeatCount)
        }

    @Test
    fun `two delivery reports from one sender in one minute stay two rows`() =
        runBlocking {
            val minute = 1_700_000_000_000L / 60_000 * 60_000
            store.upsertBatch(
                listOf(
                    event("report-1").copy(classId = ClassId.STATUS.wire(), receivedAtWall = minute + 1_000),
                    event("report-2").copy(classId = ClassId.STATUS.wire(), receivedAtWall = minute + 2_000),
                    event("silent-1").copy(classId = ClassId.SILENT.wire(), receivedAtWall = minute + 3_000),
                    event("silent-2").copy(classId = ClassId.SILENT.wire(), receivedAtWall = minute + 4_000),
                ),
            )

            val rows = feedRows(FilterSpec.DEFAULT)

            assertEquals(2, rows.count { it.classId == ClassId.STATUS.wire() })
            assertTrue(rows.filter { it.classId == ClassId.STATUS.wire() }.all { it.repeatCount == 1 })
            // Other classes still merge: the two silent messages show as one row with a count of 2.
            assertEquals(2, rows.single { it.classId == ClassId.SILENT.wire() }.repeatCount)
            assertEquals(3, count(FilterSpec.DEFAULT))
        }

    @Test
    fun `chip filters narrow the feed`() =
        runBlocking {
            store.upsertBatch(
                listOf(
                    event("sus").copy(classId = ClassId.WAP_CP.wire(), severity = Severity.SUSPICIOUS.wire()),
                    event("high").copy(classId = ClassId.SIM_DD.wire(), severity = Severity.HIGH.wire()),
                    event("info").copy(classId = ClassId.MWI.wire(), severity = Severity.INFO.wire()),
                    event("out").copy(classId = ClassId.MO_DATA.wire(), direction = "OUT"),
                    event("cb").copy(classId = ClassId.CB.wire()),
                    event("cb-sim").copy(classId = ClassId.CB_SIM.wire()),
                ),
            )
            assertEquals(2, count(FilterSpec(chip = FeedChip.SUSPICIOUS)))
            assertEquals(1, count(FilterSpec(chip = FeedChip.OUTGOING)))
            assertEquals(2, count(FilterSpec(chip = FeedChip.BROADCASTS)))
            assertEquals(2, count(FilterSpec(classIds = setOf(ClassId.WAP_CP, ClassId.MWI))))
        }

    @Test
    fun `fate filter returns only blocked rows`() =
        runBlocking {
            store.upsertBatch(
                listOf(
                    event("f-blocked", fate = Fate.BLOCKED).copy(originator = "+919000000011"),
                    event("f-shown").copy(originator = "+919000000012"),
                    event("f-held", fate = Fate.QUARANTINED).copy(originator = "+919000000013", isQuarantined = true),
                ),
            )
            val spec = FilterSpec(fates = setOf(Fate.BLOCKED))
            assertEquals(1, count(spec))
            assertEquals(listOf("f-blocked"), feedRows(spec).map { it.eventId })
            assertEquals(3, count(FilterSpec()))
        }

    @Test
    fun `attention feed has held and suspicious rows that are not reviewed`() =
        runBlocking {
            store.upsertBatch(
                listOf(
                    event("a-held", fate = Fate.QUARANTINED)
                        .copy(originator = "+919000000021", isQuarantined = true, isReviewed = false),
                    event("a-sus")
                        .copy(originator = "+919000000022", severity = Severity.SUSPICIOUS.wire(), isReviewed = false),
                    event("a-sus-reviewed")
                        .copy(originator = "+919000000023", severity = Severity.SUSPICIOUS.wire(), isReviewed = true),
                    event("a-held-reviewed", fate = Fate.QUARANTINED)
                        .copy(originator = "+919000000024", isQuarantined = true, isReviewed = true),
                    event("a-plain").copy(originator = "+919000000025", isReviewed = false),
                    event("a-high")
                        .copy(originator = "+919000000026", severity = Severity.HIGH.wire(), isReviewed = false),
                ),
            )
            val spec = FilterSpec(attentionOnly = true)
            assertEquals(setOf("a-held", "a-sus", "a-high"), feedRows(spec).map { it.eventId }.toSet())
            assertEquals(3, count(spec))
        }

    @Test
    fun `feed ids hold every feed row with its review and hold state`() =
        runBlocking {
            store.upsertBatch(
                listOf(
                    event("i-held", fate = Fate.QUARANTINED)
                        .copy(originator = "+919000000041", isQuarantined = true, isReviewed = false),
                    event("i-held-blocked", fate = Fate.BLOCKED).copy(originator = "+919000000042", isQuarantined = true),
                    event("i-sus").copy(originator = "+919000000043", severity = Severity.SUSPICIOUS.wire()),
                    event("i-normal").copy(originator = "+919000000044", classId = ClassId.NORMAL.wire()),
                ),
            )
            val ids = repository().feedIds(FilterSpec.DEFAULT)
            assertEquals(feedRows(FilterSpec.DEFAULT).map { it.id }, ids.map { it.id })
            assertEquals(count(FilterSpec.DEFAULT), ids.size)
            val byEvent = feedRows(FilterSpec.DEFAULT).associate { it.eventId to it.id }
            val held = ids.filter { it.isHeld }.map { it.id }
            assertEquals(listOf(byEvent.getValue("i-held")), held)
            assertFalse(ids.first { it.id == byEvent.getValue("i-held") }.isReviewed)
            assertTrue(ids.first { it.id == byEvent.getValue("i-sus") }.isReviewed)
            assertEquals(1, repository().feedIds(FilterSpec(chip = FeedChip.SUSPICIOUS)).size)
        }

    @Test
    fun `search matches a normalised number and a port`() =
        runBlocking {
            store.upsertBatch(
                listOf(
                    event("a").copy(originator = "+91 90000 00031"),
                    // A short code: the default test originator would also match the "919000" prefix below (VR01-A02).
                    event("b").copy(originator = "4321", destPort = 2948, classId = ClassId.DATA_PORT.wire()),
                ),
            )
            assertEquals(1, count(FilterSpec(search = "00031")))
            assertEquals(1, count(FilterSpec(search = "00 919000")))
            assertEquals(1, count(FilterSpec(search = "2948")))
            assertEquals(0, count(FilterSpec(search = "5555")))
        }

    @Test
    fun `quarantine count and unreviewed count track rows`() =
        runBlocking {
            store.upsertBatch(
                listOf(
                    event("q").copy(isQuarantined = true, fate = Fate.QUARANTINED.wire()),
                    event("u").copy(isReviewed = false),
                ),
            )
            assertEquals(1, db.messageDao().observeQuarantinedCount().let { first(it) })
            assertEquals(1, db.messageDao().observeUnreviewedCount().let { first(it) })
            assertEquals(2, db.messageDao().countCaptured())
        }

    @Test
    fun `detection merge key collapses a burst into one open row`() =
        runBlocking {
            // detection_member.messageId is a foreign key to message, so members must exist.
            store.upsertBatch(listOf(event("burst-1"), event("burst-2")))
            val m1 = db.messageDao().findByEventId("burst-1")!!.id
            val m2 = db.messageDao().findByEventId("burst-2")!!.id
            val id1 = detections.openOrTouch("DET-01", "burst:1", Severity.SUSPICIOUS, "3 pings", listOf(m1), 100, 100, 1)
            val id2 = detections.openOrTouch("DET-01", "burst:1", Severity.SUSPICIOUS, "4 pings", listOf(m2), 100, 200, 2)
            assertEquals(id1, id2)
            val row = db.detectionDao().find(id1)!!
            assertEquals(2, row.count)
            assertEquals(200L, row.lastAt)
            assertEquals("4 pings", row.summary)
            assertEquals(2, db.detectionDao().memberIdsExcept(id1, excludeMessageId = 0).size)
        }

    @Test
    fun `detection without a merge key always opens a new row`() =
        runBlocking {
            store.upsertBatch(listOf(event("solo-1"), event("solo-2")))
            val m1 = db.messageDao().findByEventId("solo-1")!!.id
            val m2 = db.messageDao().findByEventId("solo-2")!!.id
            val a = detections.openOrTouch("DET-04", null, Severity.NOTABLE, "one", listOf(m1), 10, 10, 1)
            val b = detections.openOrTouch("DET-04", null, Severity.NOTABLE, "two", listOf(m2), 20, 20, 1)
            assertTrue(a != b)
            assertEquals(
                2,
                db
                    .detectionDao()
                    .observeOpen()
                    .let { first(it) }
                    .size,
            )
        }

    @Test
    fun `closing a detection removes it from the open list`() =
        runBlocking {
            store.upsertBatch(listOf(event("close-1")))
            val m1 = db.messageDao().findByEventId("close-1")!!.id
            val id = detections.openOrTouch("DET-01", "k", Severity.SUSPICIOUS, "s", listOf(m1), 1, 1, 1)
            detections.close(id)
            assertEquals(
                0,
                db
                    .detectionDao()
                    .observeOpen()
                    .let { first(it) }
                    .size,
            )
        }

    @Test
    fun `retention purge keeps quarantined and starred rows`() =
        runBlocking {
            store.upsertBatch(
                listOf(
                    event("old").copy(receivedAtWall = 10),
                    event("oldq").copy(receivedAtWall = 10, isQuarantined = true),
                    event("olds").copy(receivedAtWall = 10, isStarred = true),
                ),
            )
            assertEquals(1, db.messageDao().countPurgeable(1_000))
            assertEquals(1, db.messageDao().deletePurgeable(1_000))
            assertEquals(2, db.messageDao().countAll())
        }

    @Test
    fun `mute upsert keeps one row per scope and value`() =
        runBlocking {
            db.muteDao().put("ORIGINATOR", "+919000000000", 5_000)
            db.muteDao().put("ORIGINATOR", "+919000000000", 9_000)
            assertEquals(1, db.muteDao().active(1_000).size)
            assertEquals(
                9_000L,
                db
                    .muteDao()
                    .active(1_000)
                    .first()
                    .until,
            )
            assertEquals(0, db.muteDao().active(10_000).size)
        }

    @Test
    fun `counter upsert accumulates`() =
        runBlocking {
            db.counterDao().add(20261105, 1, "IN", ClassId.NORMAL.wire(), 2)
            db.counterDao().add(20261105, 1, "IN", ClassId.NORMAL.wire(), 3)
            assertEquals(
                5L,
                db
                    .counterDao()
                    .from(0)
                    .single()
                    .count,
            )
        }

    @Test
    fun `hook status keeps only the newest fingerprints`() =
        runBlocking {
            (0 until 7).forEach { i ->
                db.hookStatusDao().upsertAll(listOf(HookStatusEntity("fp$i", "HK-06", "LIVE", 0, null, i.toLong())))
            }
            db.hookStatusDao().keepOnlyNewestBuilds(5)
            assertEquals(5, db.hookStatusDao().fingerprints().size)
            assertTrue("fp0" !in db.hookStatusDao().fingerprints())
        }

    @Test
    fun `self test runs are capped`() =
        runBlocking {
            (0 until 25).forEach { i ->
                db.selfTestRunDao().insert(SelfTestRunEntity("run$i", i.toLong(), "fp", "1.0", "{}"))
            }
            db.selfTestRunDao().keepNewest(20)
            assertEquals(20, db.selfTestRunDao().recent(100).size)
        }

    @Test
    fun `known originator is unique per value and kind`() =
        runBlocking {
            assertEquals(1L, db.knownOriginatorDao().insert(KnownOriginatorEntity(0, "+9190", "PREFIX", null, 0)))
            assertEquals(-1L, db.knownOriginatorDao().insert(KnownOriginatorEntity(0, "+9190", "PREFIX", null, 1)))
            assertEquals(1, db.knownOriginatorDao().all().size)
        }

    @Test
    fun `audit rows are ordered newest first`() =
        runBlocking {
            db.auditDao().insert(AuditEntity(0, 10, "USER", "RULE_CREATE", "rule:1", null, "{}"))
            db.auditDao().insert(AuditEntity(0, 20, "USER", "RULE_DELETE", "rule:1", "{}", null))
            val rows = db.auditDao().recent(10)
            assertEquals("RULE_DELETE", rows.first().action)
            assertEquals(1, db.auditDao().deleteBefore(15))
        }

    @Test
    fun `saved view stores the filter json`() =
        runBlocking {
            db.savedViewDao().insert(SavedViewEntity(0, "Quarantine", FilterSpec(chip = FeedChip.QUARANTINED).toJson()))
            val view = db.savedViewDao().findByName("Quarantine")!!
            assertEquals(FeedChip.QUARANTINED, FilterSpec.fromJson(view.filterJson).chip)
        }

    @Test
    fun `detail exposes the clamp and the SIM OTA header`() =
        runBlocking {
            store.upsertBatch(
                listOf(
                    event("ota").copy(
                        classId = ClassId.SIM_DD.wire(),
                        clampedFrom = Verdict.BLOCK.wire(),
                        clampReason = "emergencyCall",
                        simOtaHeader = """{"spi":"1221","kic":"15","tar":"B00010"}""",
                        debugInjected = true,
                    ),
                    event("plain"),
                ),
            )
            val id = db.messageDao().findByEventId("ota")!!.id
            val detail = repository().find(id)!!

            // The Detail screen reads these.
            assertEquals(Verdict.BLOCK, detail.clampedFrom)
            assertEquals("emergencyCall", detail.clampReason)
            assertEquals(listOf("spi", "kic", "tar"), detail.simOtaHeader.keys.toList())
            assertEquals("B00010", detail.simOtaHeader["tar"])
            // The Detail screen marks a debug injection.
            assertTrue(detail.debugInjected)
            assertFalse(repository().find(db.messageDao().findByEventId("plain")!!.id)!!.debugInjected)
        }

    @Test
    fun `a processed row keeps its processed revision through a higher revision`() =
        runBlocking {
            store.upsertBatch(listOf(event("rev", revision = 1)))
            store.markProcessed(store.pendingProcessing())
            assertEquals(1, db.messageDao().findByEventId("rev")!!.processedRevision)

            store.upsertBatch(listOf(event("rev", revision = 2)))
            val row = db.messageDao().findByEventId("rev")!!
            // The pipeline sees an earlier pass (not null) and skips the additive writes and the alert.
            assertEquals(1, row.processedRevision)
            assertTrue(row.needsProcessing)

            store.markProcessed(listOf(row.id))
            assertEquals(2, db.messageDao().findByEventId("rev")!!.processedRevision)
        }

    @Test
    fun `released message links back to its original`() =
        runBlocking {
            store.upsertBatch(listOf(event("orig").copy(isQuarantined = true)))
            val original = db.messageDao().findByEventId("orig")!!
            store.upsertBatch(listOf(event("released").copy(releasedFromId = original.id, fate = Fate.RELEASED.wire())))
            val released = db.messageDao().findByEventId("released")!!
            assertEquals(original.id, released.releasedFromId)
            assertEquals(1, db.messageDao().releasedFrom(original.id).size)
        }

    @Test
    fun `a released copy named by releaseOf links back and ends the hold`() =
        runBlocking {
            store.upsertBatch(listOf(event("held").copy(isQuarantined = true, fate = Fate.QUARANTINED.wire())))
            val original = db.messageDao().findByEventId("held")!!

            // The payload names the original with flags.releaseOf; the store finds its row id.
            store.upsertBatch(listOf(event("copy").copy(releaseOf = "held", fate = Fate.RELEASED.wire())))

            assertEquals(original.id, db.messageDao().findByEventId("copy")!!.releasedFromId)
            assertFalse(db.messageDao().find(original.id)!!.isQuarantined)
        }

    @Test
    fun `blocking a held message ends the hold, marks it reviewed and audits once`() =
        runBlocking {
            store.upsertBatch(
                listOf(
                    event("b-held").copy(isQuarantined = true, fate = Fate.QUARANTINED.wire()),
                    event("b-shown"),
                ),
            )
            val held = db.messageDao().findByEventId("b-held")!!.id
            val shown = db.messageDao().findByEventId("b-shown")!!.id
            val repository = repository()

            assertTrue(repository.blockHeld(held))
            // A second press finds the message no longer on hold. Nothing changes and no second record is written.
            assertFalse(repository.blockHeld(held))
            assertFalse(repository.blockHeld(shown))
            assertFalse(repository.blockHeld(999))

            val row = db.messageDao().find(held)!!
            assertFalse(row.isQuarantined)
            assertEquals(Fate.BLOCKED.wire(), row.fate)
            assertTrue(row.isReviewed)
            assertEquals(Fate.SHOWN.wire(), db.messageDao().find(shown)!!.fate)
            val records = RoomAuditLog(db.auditDao(), Clock { 1L }).since(0)
            assertEquals(listOf(AuditAction.BLOCK_HELD.name), records.map { it.action })
            assertEquals("b-held", records.single().target)
            assertEquals(AuditActor.USER.name, records.single().actor)
        }

    @Test
    fun `week counts keep the 7 day window and the day limits across midnight`() =
        runBlocking {
            val zone = ZoneId.of("Asia/Kolkata")
            val nowWall = localWall(zone, "2026-11-13T10:00:00")
            store.upsertBatch(
                listOf(
                    // The last millisecond before the first day of the window: out.
                    event("w-before").copy(receivedAtWall = localWall(zone, "2026-11-06T23:59:59.999")),
                    // The first millisecond of the first day: day 0.
                    event("w-first").copy(receivedAtWall = localWall(zone, "2026-11-07T00:00:00")),
                    // The last millisecond of 12 November: day 5. The next millisecond is day 6.
                    event("w-late")
                        .copy(
                            receivedAtWall = localWall(zone, "2026-11-12T23:59:59.999"),
                            severity = Severity.SUSPICIOUS.wire(),
                        ),
                    event("w-blocked")
                        .copy(
                            receivedAtWall = localWall(zone, "2026-11-13T00:00:00"),
                            verdict = Verdict.BLOCK.wire(),
                            fate = Fate.BLOCKED.wire(),
                        ),
                    event("w-held")
                        .copy(
                            receivedAtWall = localWall(zone, "2026-11-13T09:00:00"),
                            verdict = Verdict.QUARANTINE.wire(),
                            fate = Fate.QUARANTINED.wire(),
                            isQuarantined = true,
                        ),
                    // The first millisecond of the day after today: out.
                    event("w-after").copy(receivedAtWall = localWall(zone, "2026-11-14T00:00:00")),
                    event("w-selftest")
                        .copy(receivedAtWall = localWall(zone, "2026-11-13T09:10:00"), isSelfTest = true),
                    event("w-normal")
                        .copy(receivedAtWall = localWall(zone, "2026-11-13T09:20:00"), classId = ClassId.NORMAL.wire()),
                ),
            )

            val week = repository().weekStats(nowWall, zone).first()

            assertEquals(
                (7 downTo 1).map { LocalDate.parse("2026-11-13").minusDays(it - 1L) },
                week.days.map { it.date },
            )
            assertEquals(listOf(1, 0, 0, 0, 0, 1, 2), week.days.map { it.counts.hidden })
            assertEquals(WeekCounts(hidden = 4, suspicious = 1, onHold = 1, blocked = 1), week.totals)
            assertEquals(1, week.days[5].counts.suspicious)
            assertEquals(1, week.days[6].counts.blocked)
            assertEquals(1, week.days[6].counts.onHold)
            assertEquals(4, week.byClass.sumOf { it.count })
        }

    @Test
    fun `the week window starts at local midnight six days before today`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val window = WeekWindow.of(localWall(zone, "2026-11-13T00:00:00"), zone)
        assertEquals(localWall(zone, "2026-11-07T00:00:00"), window.fromWall)
        assertEquals(localWall(zone, "2026-11-14T00:00:00"), window.toWall)
        assertEquals(8, window.bounds.size)
        // A time zone with a different offset moves the limit, because the limits are local.
        val utc = WeekWindow.of(localWall(ZoneId.of("UTC"), "2026-11-13T00:00:00"), ZoneId.of("UTC"))
        assertEquals(window.fromWall + 5L * 3_600_000 + 30 * 60_000, utc.fromWall)
    }

    @Test
    fun `delivery reports are not in the hidden total`() =
        runBlocking {
            val zone = ZoneId.of("UTC")
            val nowWall = localWall(zone, "2026-11-13T10:00:00")
            store.upsertBatch(
                listOf(
                    event("s-silent").copy(receivedAtWall = localWall(zone, "2026-11-13T08:00:00")),
                    event("s-report-1")
                        .copy(receivedAtWall = localWall(zone, "2026-11-13T08:01:00"), classId = ClassId.STATUS.wire()),
                    event("s-report-2")
                        .copy(
                            receivedAtWall = localWall(zone, "2026-11-12T08:01:00"),
                            classId = ClassId.STATUS.wire(),
                            severity = Severity.SUSPICIOUS.wire(),
                            fate = Fate.BLOCKED.wire(),
                        ),
                    // A debug injection counts like real traffic.
                    event("s-debug")
                        .copy(receivedAtWall = localWall(zone, "2026-11-13T08:02:00"), debugInjected = true),
                ),
            )

            val week = repository().weekStats(nowWall, zone).first()
            assertEquals(WeekCounts(hidden = 2), week.totals)
            assertEquals(listOf(ClassId.SILENT), week.byClass.map { it.classId })

            val window = WeekWindow.of(nowWall, zone)
            val row =
                db
                    .messageDao()
                    .weekCountsBetween(
                        window.fromWall,
                        window.toWall,
                        FilterSpec.ATTENTION_SEVERITIES.map { it.wire() },
                        Fate.BLOCKED.wire(),
                    ).first()
            assertEquals(2, row.hidden)
            assertEquals(0, row.suspicious)
            assertEquals(0, row.blocked)
        }

    @Test
    fun `detector history keeps debug injections and the feed marks them`() =
        runBlocking {
            store.upsertBatch(
                listOf(
                    event("h-real").copy(receivedAtWall = 1_000),
                    // Another minute, so the feed does not merge the two rows.
                    event("h-debug").copy(receivedAtWall = 121_000, debugInjected = true),
                ),
            )
            val dao = db.messageDao()

            // A debug injection is history for the detectors like a real row.
            val both = listOf("h-debug", "h-real")
            assertEquals(both, dao.historyForDetector(ClassId.SILENT.wire(), 0, 200_000, 10).map { it.eventId })
            assertEquals(both, dao.recentForDetector(ClassId.SILENT.wire(), 0, 10).map { it.eventId })
            assertEquals(true, dao.findByEventId("h-debug")!!.debugInjected)

            // The feed still shows the debug row, and its list item carries the marker.
            val items = feedRows(FilterSpec.DEFAULT).associate { it.eventId to it.toListItem().debugInjected }
            assertEquals(mapOf("h-debug" to true, "h-real" to false), items)
        }

    @Test
    fun `a sent row in the feed carries its recipient and sending app`() =
        runBlocking {
            store.upsertBatch(
                listOf(
                    event("mo-data").copy(
                        direction = Direction.OUT.wire(),
                        classId = ClassId.MO_DATA.wire(),
                        originator = null,
                        destination = "+15550000001",
                        callingPackage = "com.example.sender",
                    ),
                ),
            )

            db.packageLabelDao().putAll(listOf(PackageLabelEntity("com.example.sender", "Sender", 1L)))

            // The list row can show "To <recipient>" and the app that sent it.
            val item = feedRows(FilterSpec.DEFAULT).single().toListItem()
            assertEquals("+15550000001", item.destination)
            assertEquals("com.example.sender", item.callingPackage)
            // The label cache names the sending app.
            assertEquals("Sender", item.callingPackageLabel)
            assertEquals(null, item.originator)
        }

    @Test
    fun `rows stored as FT-UNKNOWN get their true fate and the list shows it before the repair`() =
        runBlocking {
            val unknown = Fate.UNKNOWN
            store.upsertBatch(
                listOf(
                    event("r-accepted", fate = unknown).copy(classId = ClassId.WAP_CP.wire(), ackResult = 1),
                    event("r-none", fate = unknown).copy(classId = ClassId.WAP_CP.wire(), ackResult = null),
                    event("r-replay", fate = unknown).copy(classId = ClassId.WAP_CP.wire(), ackResult = null, isReplay = true),
                    event("r-full", fate = unknown).copy(classId = ClassId.WAP_CP.wire(), ackResult = 3),
                    event("r-dup", fate = unknown).copy(classId = ClassId.WAP_CP.wire(), ackResult = 5),
                    event("r-out", fate = unknown).copy(direction = Direction.OUT.wire(), classId = ClassId.MO_DATA.wire()),
                    event("r-nodispatch", fate = unknown).copy(
                        direction = Direction.OUT.wire(),
                        classId = ClassId.MO_DATA.wire(),
                        fateDetail = """{"noDispatch":true}""",
                    ),
                    event("r-shown", fate = Fate.SHOWN),
                ),
            )
            val dao = db.messageDao()
            // The list reads an unrepaired row as its true fate already.
            assertEquals(Fate.ACCEPTED, dao.findByEventId("r-accepted")!!.toListItem().fate)

            assertEquals(
                7,
                dev.nightjar.app.data.maintenance
                    .repairUnknownFates(dao),
            )

            val fates =
                listOf("r-accepted", "r-none", "r-replay", "r-full", "r-dup", "r-out", "r-nodispatch", "r-shown")
                    .associateWith { dao.findByEventId(it)!!.fate }
            assertEquals(
                mapOf(
                    "r-accepted" to Fate.ACCEPTED.wire(),
                    "r-none" to Fate.NOT_DISPATCHED.wire(),
                    "r-replay" to Fate.ACCEPTED.wire(),
                    "r-full" to Fate.NACK_STORAGE.wire(),
                    "r-dup" to Fate.REJECTED.wire(),
                    "r-out" to Fate.SEND_UNCONFIRMED.wire(),
                    "r-nodispatch" to Fate.NOT_SENT.wire(),
                    "r-shown" to Fate.SHOWN.wire(),
                ),
                fates,
            )
            assertEquals(
                0,
                dev.nightjar.app.data.maintenance
                    .repairUnknownFates(dao),
            )
        }

    @Test
    fun `a blocked send of a counter-only class shows in the feed and counts as blocked`() =
        runBlocking {
            store.upsertBatch(
                listOf(
                    event("mo-blocked").copy(
                        direction = Direction.OUT.wire(),
                        classId = ClassId.MO_VISIBLE.wire(),
                        callingPackage = "com.example.sender",
                        verdict = Verdict.BLOCK.wire(),
                        fate = Fate.BLOCKED.wire(),
                        receivedAtWall = 1_000,
                        // The test rows are reviewed by default; both rows are unread here.
                        isReviewed = false,
                    ),
                    event("mo-allowed").copy(
                        direction = Direction.OUT.wire(),
                        classId = ClassId.MO_VISIBLE.wire(),
                        receivedAtWall = 200_000,
                        isReviewed = false,
                    ),
                ),
            )

            // The gate-blocked send is a full row; an allowed CL-MO-VISIBLE row stays a counter, also unread.
            assertEquals(listOf("mo-blocked"), feedRows(FilterSpec.DEFAULT).map { it.eventId })
            assertEquals(1, db.messageDao().observeUnreviewedCount().first())
            val week =
                db
                    .messageDao()
                    .weekCountsBetween(0, Long.MAX_VALUE, FilterSpec.ATTENTION_SEVERITIES.map { it.wire() }, Fate.BLOCKED.wire())
                    .first()
            assertEquals(1, week.hidden)
            assertEquals(1, week.blocked)
        }

    @Test
    fun `apps that sent SMS are listed newest first and only real sends count`() =
        runBlocking {
            val out = "OUT"
            store.upsertBatch(
                listOf(
                    event("o1").copy(direction = out, callingPackage = "com.example.a", receivedAtWall = 1_000),
                    event("o2").copy(direction = out, callingPackage = "com.example.a", receivedAtWall = 3_000),
                    event("o3").copy(direction = out, callingPackage = "com.example.b", receivedAtWall = 2_000),
                    // Left out: the phone process itself and an incoming row.
                    event("o4").copy(direction = out, callingPackage = null, receivedAtWall = 4_000),
                    event("i1").copy(callingPackage = "com.example.c", receivedAtWall = 5_000),
                    // A debug injection counts like a real send.
                    event("o5")
                        .copy(direction = out, callingPackage = "com.example.d", receivedAtWall = 500, debugInjected = true),
                ),
            )

            val rows = db.messageDao().observeSendingApps(Direction.OUT.wire()).first()

            assertEquals(
                listOf("com.example.a" to 2, "com.example.b" to 1, "com.example.d" to 1),
                rows.map { it.packageName to it.count },
            )
            assertEquals(3_000L, rows.first().lastSentWall)
        }

    @Test
    fun `block sending stores the package, audits once and keeps a blocked app without rows`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            store.upsertBatch(listOf(event("o1").copy(direction = "OUT", callingPackage = "com.example.a")))
            val audit = RoomAuditLog(db.auditDao(), Clock { 1L })
            val blocks = OutgoingBlockStore(context)
            val blocking = OutgoingBlocking(blocks, db.messageDao(), audit)

            blocking.setSendBlocked("com.example.a", true)
            blocking.setSendBlocked("com.example.a", true)
            blocking.setSendBlocked("com.example.gone", true)

            assertEquals(setOf("com.example.a", "com.example.gone"), blocks.blocked())
            val apps = blocking.apps.first()
            assertEquals(listOf("com.example.a" to true, "com.example.gone" to true), apps.map { it.packageName to it.blocked })
            assertNull(apps.last().lastSentWall)

            blocking.setSendBlocked("com.example.a", false)
            assertEquals(setOf("com.example.gone"), blocks.blocked())
            // The second block changed nothing, so it wrote no audit record.
            assertEquals(
                listOf(AuditAction.OUTGOING_BLOCK, AuditAction.OUTGOING_BLOCK, AuditAction.OUTGOING_UNBLOCK).map { it.name }.sorted(),
                audit.since(0).map { it.action }.sorted(),
            )
        }

    @Test
    fun `schema identity is version 3`() {
        assertEquals(3, NightjarDatabase.VERSION)
        assertEquals(2, NightjarMigrations.ALL.size)
        assertNotNull(db.openHelper)
        assertNull(runBlocking { db.messageDao().find(999) })
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun count(spec: FilterSpec): Int {
        val a = spec.toArgs()
        return db
            .messageDao()
            .observeCountFeed(
                includeStatus = a.includeStatus,
                classCount = a.classCount,
                classIds = a.classIds,
                severityCount = a.severityCount,
                severities = a.severities,
                fateCount = a.fateCount,
                fates = a.fates,
                attentionOnly = a.attentionOnly,
                attentionSeverity = a.attentionSeverity,
                quarantinedOnly = a.quarantinedOnly,
                unreviewedOnly = a.unreviewedOnly,
                starredOnly = a.starredOnly,
                direction = a.direction,
                fromWall = a.fromWall,
                toWall = a.toWall,
                subId = a.subId,
                slot = a.slot,
                searchLike = a.searchLike,
                searchNormLike = a.searchNormLike,
                searchPort = a.searchPort,
                originatorLike = a.originatorLike,
                port = a.port,
                contentTypeLike = a.contentTypeLike,
                packageLike = a.packageLike,
            ).first()
    }

    /** The rows the feed pager would load, read straight from the DAO with the same arguments as [count]. */
    private suspend fun feedRows(spec: FilterSpec): List<MessageRow> {
        val a = spec.toArgs()
        val source =
            db.messageDao().feed(
                includeStatus = a.includeStatus,
                classCount = a.classCount,
                classIds = a.classIds,
                severityCount = a.severityCount,
                severities = a.severities,
                fateCount = a.fateCount,
                fates = a.fates,
                attentionOnly = a.attentionOnly,
                attentionSeverity = a.attentionSeverity,
                quarantinedOnly = a.quarantinedOnly,
                unreviewedOnly = a.unreviewedOnly,
                starredOnly = a.starredOnly,
                direction = a.direction,
                fromWall = a.fromWall,
                toWall = a.toWall,
                subId = a.subId,
                slot = a.slot,
                searchLike = a.searchLike,
                searchNormLike = a.searchNormLike,
                searchPort = a.searchPort,
                originatorLike = a.originatorLike,
                port = a.port,
                contentTypeLike = a.contentTypeLike,
                packageLike = a.packageLike,
            )
        val page =
            source.load(
                androidx.paging.PagingSource.LoadParams
                    .Refresh(null, FEED_PAGE, false),
            )
        return (page as androidx.paging.PagingSource.LoadResult.Page).data
    }

    private fun repository() =
        RoomMessageRepository(db.messageDao(), db.pduDao(), db.traceStepDao(), detections, RoomAuditLog(db.auditDao(), Clock { 1L }))

    private fun localWall(
        zone: ZoneId,
        local: String,
    ): Long =
        LocalDateTime
            .parse(local)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun <T> first(flow: kotlinx.coroutines.flow.Flow<T>): T = runBlocking { flow.first() }

    private fun event(
        id: String,
        pdus: Int = 0,
        trace: Int = 0,
        revision: Int = 1,
        fate: Fate = Fate.SHOWN,
    ): IncomingEvent =
        IncomingEvent(
            eventId = id,
            revision = revision,
            sessionId = "s",
            direction = "IN",
            transport = "CS",
            source = 0,
            subId = 1,
            slot = 0,
            carrierName = "Test",
            classId = ClassId.SILENT.wire(),
            severity = Severity.NOTABLE.wire(),
            detectorSeverity = null,
            severityOverridden = false,
            title = "Silent ping",
            originator = "+919000000000",
            smsc = "+919000000001",
            destPort = null,
            contentType = null,
            textBody = null,
            textPreview = "preview",
            receivedAtWall = now++,
            receivedAtElapsedNs = 1,
            modemTs = null,
            ackResult = 1,
            verdict = Verdict.ALLOW.wire(),
            ruleId = null,
            fate = fate.wire(),
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
            payloadVersion = "1.0",
            pdus = (0 until pdus).map { PduPayload(it, byteArrayOf(it.toByte()), "3gpp") },
            trace = (0 until trace).map { TracePayload(it, "stage.$it", it * 1_000_000L, null) },
        )

    private companion object {
        const val FEED_PAGE = 50
    }
}
