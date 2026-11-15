package dev.nightjar.app.feature.rules

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import dev.nightjar.app.data.db.MessageDao
import dev.nightjar.app.data.db.MessageEntity
import dev.nightjar.app.data.db.NightjarDatabase
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Subtype
import dev.nightjar.contract.Transport
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.ClampReason
import dev.nightjar.rules.StringMatch
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
 * The preview matcher over a generated 50,000-row database.
 *
 * The rows are built deterministically, so the expected match set is computed here from the same arithmetic instead of
 * from the code under test. The time is printed, not asserted: a wall-clock limit on a shared, CPU-capped build machine
 * fails for reasons that are not the code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreviewMatcherTest {
    private lateinit var db: NightjarDatabase
    private lateinit var dao: MessageDao

    /** Fixed "now" so the 30-day window is stable. */
    private val now = 1_767_000_000_000L

    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, NightjarDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a 50_000-row database previews the exact match set`() =
        runBlocking {
            val rows = 50_000
            fill(rows)
            val draft =
                RuleDraft(
                    classes = setOf(ClassId.WAP_CP),
                    fromEnabled = true,
                    fromOp = StringMatch.Op.PREFIX,
                    fromValue = "+1555",
                    slot = 0,
                    conditions = listOf(DraftCondition.Port("16000", "16999"), DraftCondition.Text("promo")),
                    action = Verdict.QUARANTINE,
                )

            val result = PreviewMatcher(dao, Clock { now }).preview(draft)

            val expected = (0 until rows).filter { matches(it) }
            assertEquals(expected.size, result.count)
            assertEquals(expected, result.matches.map { idOf(it.messageId) })
            assertEquals("scanned rows must be the pre-filter's output", scanned(rows), result.scanned)
            assertTrue(result.matches.all { it.action == Verdict.QUARANTINE })
            assertTrue(result.matches.all { it.clampReason == null })
            println("preview: ${result.scanned} rows scanned, ${result.count} matched in ${result.elapsedMs} ms")
        }

    @Test
    fun `the SQL pre-filter keeps the window, the classes and the slot`() =
        runBlocking {
            fill(2_000)
            // One row that would match everything but is 31 days old.
            dao.insert(row(99_999, receivedAtWall = now - 31 * day, text = "promo code old"))
            val draft =
                RuleDraft(
                    classes = setOf(ClassId.WAP_CP),
                    slot = 0,
                    conditions = listOf(DraftCondition.Text("promo")),
                    action = Verdict.QUARANTINE,
                )
            val result = PreviewMatcher(dao, Clock { now }).preview(draft)
            assertTrue(
                "a row older than the window must not be scanned",
                result.matches.none { it.receivedAtWall < now - 30 * day },
            )
            assertEquals(scanned(2_000), result.scanned)
        }

    @Test
    fun `a clamped match is still counted and reports the clamp`() =
        runBlocking {
            fill(1_000)
            val draft =
                RuleDraft(
                    classes = setOf(ClassId.SILENT),
                    action = Verdict.QUARANTINE,
                )
            val result = PreviewMatcher(dao, Clock { now }).preview(draft)
            // CL-SILENT never quarantines, so the engine clamps every match to ALLOW.
            assertEquals((0 until 1_000).count { it % 6 == 0 }, result.count)
            assertEquals(result.count, result.clamped)
            assertTrue(result.matches.all { it.action == Verdict.ALLOW })
            assertTrue(result.matches.all { it.clampedFrom == Verdict.QUARANTINE })
            assertTrue(result.matches.all { it.clampReason == ClampReason.ACTION_NOT_ALLOWED })
        }

    @Test
    fun `no selected class scans every class`() =
        runBlocking {
            fill(500)
            val draft = RuleDraft(action = Verdict.ALLOW)
            val result = PreviewMatcher(dao, Clock { now }).preview(draft)
            assertEquals(500, result.scanned)
            assertEquals(500, result.count)
            assertNull(result.matches.first().clampReason)
        }

    @Test
    fun `a subtype rule matches only rows with that subtype`() =
        runBlocking {
            val ram = dao.insert(row(1, classId = ClassId.SIM_DD, subtype = Subtype.SimOta.RAM))
            dao.insert(row(2, classId = ClassId.SIM_DD, subtype = Subtype.SimOta.OTHER))
            dao.insert(row(3, classId = ClassId.SIM_DD))
            val draft = RuleDraft(classes = setOf(ClassId.SIM_DD), subtypes = setOf(Subtype.SimOta.RAM), action = Verdict.ALLOW)
            val result = PreviewMatcher(dao, Clock { now }).preview(draft)
            assertEquals(3, result.scanned)
            assertEquals(listOf(ram), result.matches.map { it.messageId })
        }

    @Test
    fun `a cell broadcast rule clamps only the emergency channel`() =
        runBlocking {
            val alert = dao.insert(row(1, classId = ClassId.CB, transport = Transport.CB, cbMessageId = 4370))
            val news = dao.insert(row(2, classId = ClassId.CB, transport = Transport.CB, cbMessageId = 50))
            val draft = RuleDraft(classes = setOf(ClassId.CB), action = Verdict.BLOCK)
            val result = PreviewMatcher(dao, Clock { now }).preview(draft).matches.associateBy { it.messageId }
            assertEquals(Verdict.ALLOW, result.getValue(alert).action)
            assertEquals(ClampReason.EMERGENCY_CB, result.getValue(alert).clampReason)
            assertEquals(Verdict.BLOCK, result.getValue(news).action)
            assertNull(result.getValue(news).clampReason)
        }

    @Test
    fun `a roaming rule matches only rows stored as roaming`() =
        runBlocking {
            val roaming = dao.insert(row(1, roaming = true))
            dao.insert(row(2, roaming = false))
            dao.insert(row(3))
            val draft =
                RuleDraft(
                    classes = setOf(ClassId.WAP_CP),
                    conditions = listOf(DraftCondition.Roaming(true)),
                    action = Verdict.ALLOW,
                )
            val result = PreviewMatcher(dao, Clock { now }).preview(draft)
            assertEquals(listOf(roaming), result.matches.map { it.messageId })
        }

    // ------------------------------------------------------------------ generated rows

    /**
     * Row `i` (0-based) of the generated set:
     * - class `CL-SILENT` when `i % 6 == 0`, `CL-NORMAL` when `i % 3 == 0`, otherwise `CL-WAP-CP`;
     * - slot 0 unless `i % 4 == 0`;
     * - port 2948 when `i` is even, otherwise 16000 + `i % 100`;
     * - body "promo code i" when `i % 5 == 0`;
     * - arrival `i` × 50 s before [now], so the whole set sits inside the 30-day window.
     */
    private fun matches(i: Int): Boolean = i % 3 != 0 && i % 4 != 0 && i % 2 == 1 && i % 5 == 0

    private fun classOf(i: Int): ClassId =
        when {
            i % 6 == 0 -> ClassId.SILENT
            i % 3 == 0 -> ClassId.NORMAL
            else -> ClassId.WAP_CP
        }

    private fun scanned(rows: Int): Int = (0 until rows).count { it % 3 != 0 && it % 4 != 0 }

    private fun idOf(messageId: Long): Int = messageId.toInt() - 1

    private suspend fun fill(rows: Int) {
        db.withTransaction {
            for (i in 0 until rows) {
                dao.insert(
                    row(
                        i = i,
                        receivedAtWall = now - i * 50_000L,
                        classId = classOf(i),
                        slot = if (i % 4 == 0) 1 else 0,
                        destPort = if (i % 2 == 0) 2948 else 16000 + i % 100,
                        text = if (i % 5 == 0) "promo code $i" else null,
                    ),
                )
            }
        }
    }

    private fun row(
        i: Int,
        receivedAtWall: Long = now,
        classId: ClassId = ClassId.WAP_CP,
        slot: Int = 0,
        destPort: Int = 16000,
        text: String? = null,
        transport: Transport = Transport.CS,
        subtype: String? = null,
        cbMessageId: Int? = null,
        roaming: Boolean? = null,
    ): MessageEntity =
        MessageEntity(
            eventId = "ev-$i",
            sessionId = null,
            revision = 1,
            direction = Direction.IN.wire(),
            transport = transport.wire(),
            source = 0,
            subId = 0,
            slot = slot,
            carrierName = null,
            classId = classId.wire(),
            severity = Severity.INFO.wire(),
            detectorSeverity = null,
            severityOverridden = false,
            title = "Message $i",
            originator = "+1555" + (i % 10_000).toString().padStart(4, '0'),
            originatorNorm = "+1555" + (i % 10_000).toString().padStart(4, '0'),
            smsc = "+10000000001",
            destPort = destPort,
            contentType = "application/vnd.wap.mms-message",
            textBody = text,
            textPreview = text ?: "",
            receivedAtWall = receivedAtWall,
            receivedAtElapsedNs = i.toLong(),
            modemTs = null,
            ackResult = 0,
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
            isReviewed = false,
            isStarred = false,
            isReplay = false,
            isSelfTest = false,
            needsProcessing = false,
            releasedFromId = null,
            buildFingerprint = null,
            payloadVersion = null,
            subtype = subtype,
            cbMessageId = cbMessageId,
            roaming = roaming,
        )
}
