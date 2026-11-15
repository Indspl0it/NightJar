package dev.nightjar.app.domain.ingest

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.nightjar.app.core.log.NoopALog
import dev.nightjar.app.data.db.NightjarDatabase
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.data.repository.IncomingEvent
import dev.nightjar.app.data.repository.MessageStore
import dev.nightjar.app.data.repository.PduPayload
import dev.nightjar.app.data.repository.RoomMessageStore
import dev.nightjar.app.data.repository.RoomPackageLabelRepository
import dev.nightjar.app.domain.FakeDetectionHistory
import dev.nightjar.app.domain.classify.EventClassifier
import dev.nightjar.app.domain.detect.DetectorConfig
import dev.nightjar.app.domain.detect.DetectorEngine
import dev.nightjar.app.domain.severity.SeverityConfig
import dev.nightjar.app.domain.text.PackageLabels
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Verdict
import kotlinx.coroutines.runBlocking
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
 * Ingest sets `needsProcessing` on every row it commits, and the processor is what clears it.
 *
 * The defect was that nothing ran the pipeline in production: rows kept the flag forever, so no detection, no
 * severity, no counter and no notification ever happened for a captured message, and the crash-recovery queue grew
 * without a consumer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IngestProcessorTest {
    private lateinit var db: NightjarDatabase
    private lateinit var store: MessageStore
    private lateinit var processor: IngestProcessor

    /** Severities written, keyed by message id; the pipeline's last step is the notification, which is not asserted. */
    private val severityWrites = mutableListOf<Pair<Long, Severity>>()

    /** Message ids that reached the alert step (first pass only). */
    private val alerted = mutableListOf<Long>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, NightjarDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        store = RoomMessageStore(db, db.messageDao(), db.pduDao(), db.traceStepDao())
        val history = FakeDetectionHistory()
        val config = SeverityConfig.DEFAULT
        val classifier = EventClassifier(DetectorEngine(DetectorConfig.DEFAULT, history, config.knownOriginators), config)
        val pipeline =
            ProcessingPipeline(
                classifier = classifier,
                detections = { 1L },
                coverage =
                    object : CoverageSink {
                        override suspend fun seenFromNetwork(
                            classId: ClassId,
                            atWallMs: Long,
                        ) = Unit

                        override suspend fun selfTestArrived(
                            classId: ClassId,
                            atWallMs: Long,
                        ) = Unit
                    },
                counters = { _, _, _, _, _ -> },
                ruleMatches = { _, _ -> },
                severities = { id, severity, _ -> severityWrites += id to severity },
                alerts = { alerted += it.messageId },
            )
        processor =
            IngestProcessor(
                store,
                db.messageDao(),
                PipelineFactory { pipeline },
                DeliveryReportLinker(db.messageDao(), db.pduDao()),
                NoopALog,
                PackageLabelRecorder(
                    RoomPackageLabelRepository(db.packageLabelDao(), Clock { 1L }),
                    PackageLabels { pkg -> "Label of $pkg" },
                ),
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `ingest caches the app labels of the consumer and the sender`() =
        runBlocking {
            val result =
                store.upsertBatch(
                    listOf(event("l").copy(consumerPackage = "com.example.reader", callingPackage = "com.example.sender")),
                )
            processor.process(result.messageIds)

            val labels = db.packageLabelDao().all().associate { it.packageName to it.label }
            assertEquals(
                mapOf("com.example.reader" to "Label of com.example.reader", "com.example.sender" to "Label of com.example.sender"),
                labels,
            )
        }

    @Test
    fun `a committed row is classified and leaves the queue`() =
        runBlocking {
            val result = store.upsertBatch(listOf(event("a")))
            assertEquals(listOf(result.messageIds.single()), store.pendingProcessing())

            assertEquals(1, processor.process(result.messageIds))

            assertEquals(emptyList<Long>(), store.pendingProcessing())
            assertEquals(listOf(result.messageIds.single()), severityWrites.map { it.first })
        }

    @Test
    fun `resumePending drains what a crash left behind`() =
        runBlocking {
            val first = store.upsertBatch(listOf(event("a", revision = 1)))
            store.upsertBatch(listOf(event("a", revision = 2), event("b")))
            assertTrue(store.pendingProcessing().isNotEmpty())

            assertEquals(2, processor.resumePending())

            assertEquals(emptyList<Long>(), store.pendingProcessing())
            assertEquals(setOf(first.messageIds.single()), severityWrites.take(1).map { it.first }.toSet())
            assertEquals(2, severityWrites.size)
        }

    @Test
    fun `a later revision of a processed row is classified again but not alerted again`() =
        runBlocking {
            val id = store.upsertBatch(listOf(event("a", revision = 1))).messageIds.single()
            assertEquals(1, processor.process(listOf(id)))
            store.upsertBatch(listOf(event("a", revision = 2)))

            assertEquals(1, processor.resumePending())

            // The store keeps processedRevision on the new revision, so the second pass is not the first.
            assertEquals(listOf(id, id), severityWrites.map { it.first })
            assertEquals(listOf(id), alerted)
        }

    @Test
    fun `a delivery report is linked to its sent message by TP-MR`() =
        runBlocking {
            val sentId = storeSent("sent", wall = DAY * 10, tpMr = 42, destination = "15559000000002")
            val otherRef = storeSent("other-ref", wall = DAY * 10, tpMr = 43, destination = "15559000000002")
            val otherNumber = storeSent("other-number", wall = DAY * 10, tpMr = 42, destination = "15559000000003")
            val reportId = storeReport("report", wall = DAY * 10 + MINUTE, tpMr = 42, recipient = "15559000000002", st = 0)

            assertEquals(1, processor.process(listOf(reportId)))

            val row = db.messageDao().find(reportId)!!
            assertEquals(sentId, row.relatedMessageId)
            assertEquals(42, row.tpMr)
            assertEquals("DELIVERED", row.deliveryStatus)
            assertEquals("15559000000002", row.destinationNorm)
            assertTrue(sentId != otherRef && sentId != otherNumber)
        }

    @Test
    fun `a delivery report with no sent message keeps no link and still stores its status`() =
        runBlocking {
            storeSent("sent", wall = DAY * 10, tpMr = 42, destination = "15559000000002")
            val reportId = storeReport("report", wall = DAY * 10 + MINUTE, tpMr = 99, recipient = "15559000000002", st = 0x40)

            processor.process(listOf(reportId))

            val row = db.messageDao().find(reportId)!!
            assertNull(row.relatedMessageId)
            assertEquals("FAILED", row.deliveryStatus)
        }

    @Test
    fun `a sent message older than 7 days is not linked`() =
        runBlocking {
            storeSent("old", wall = DAY * 10, tpMr = 42, destination = "15559000000002")
            val reportId = storeReport("report", wall = DAY * 18, tpMr = 42, recipient = "15559000000002", st = 0x20)

            processor.process(listOf(reportId))

            val row = db.messageDao().find(reportId)!!
            assertNull(row.relatedMessageId)
            assertEquals("PENDING", row.deliveryStatus)
        }

    /** Stores a sent message with the facts that ingest keeps for it (TP-MR and the normalised destination). */
    private suspend fun storeSent(
        id: String,
        wall: Long,
        tpMr: Int,
        destination: String,
    ): Long {
        val storedId =
            store
                .upsertBatch(
                    listOf(event(id).copy(direction = "OUT", classId = ClassId.MO_DATA.wire(), receivedAtWall = wall)),
                ).messageIds
                .single()
        val row = db.messageDao().find(storedId)!!
        db.messageDao().update(row.copy(tpMr = tpMr, destinationNorm = destination))
        return storedId
    }

    private suspend fun storeReport(
        id: String,
        wall: Long,
        tpMr: Int,
        recipient: String,
        st: Int,
    ): Long =
        store
            .upsertBatch(
                listOf(
                    event(id).copy(
                        classId = ClassId.STATUS.wire(),
                        receivedAtWall = wall,
                        pdus = listOf(PduPayload(1, StatusReportPdus.build(tpMr, recipient, st), "3gpp")),
                    ),
                ),
            ).messageIds
            .single()

    private fun event(
        id: String,
        revision: Int = 1,
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
            originator = "+15559000000000",
            smsc = "+15559000000001",
            destPort = null,
            contentType = null,
            textBody = null,
            textPreview = "preview",
            receivedAtWall = 1_000L,
            receivedAtElapsedNs = 1,
            modemTs = null,
            ackResult = 1,
            verdict = Verdict.ALLOW.wire(),
            ruleId = null,
            fate = Fate.SYSTEM.wire(),
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
            pdus = emptyList(),
            trace = emptyList(),
        )

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
