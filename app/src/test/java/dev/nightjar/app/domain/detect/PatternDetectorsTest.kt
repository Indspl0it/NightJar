package dev.nightjar.app.domain.detect

import dev.nightjar.app.domain.TestEvents
import dev.nightjar.app.domain.known.KnownOriginator
import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.app.domain.model.EmergencyCallState
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.app.domain.model.EventFlags
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Subtype
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** One group of tests for each suspicious pattern (PAT-01 to PAT-09). All times are fixed. */
class PatternDetectorsTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    /** 2026-01-01T00:00Z. The clock stands one day later, so it never limits a window unless a test says so. */
    private val base = 1_767_225_600_000L
    private val fixedClock = WallClock { base + 24 * hour }

    // Synthetic senders (fictional 555 range), never a real number.
    private val senderA = "+15550001"
    private val senderB = "+15550002"

    private val knownA = KnownOriginatorMatcher(listOf(KnownOriginator(senderA, KnownOriginator.Kind.EXACT)))
    private val carrierA =
        KnownOriginatorMatcher(
            listOf(KnownOriginator(senderA, KnownOriginator.Kind.EXACT, label = KnownOriginator.LABEL_CARRIER)),
        )

    private fun detector(
        history: PatternHistory = FakePatternHistory(),
        known: KnownOriginatorMatcher = KnownOriginatorMatcher.EMPTY,
        clock: WallClock = fixedClock,
        config: DetectorConfig = DetectorConfig.DEFAULT,
    ) = PatternDetectors(config.coerced(), history, known, clock)

    private fun inbound(
        classId: ClassId,
        at: Long,
        eventId: String,
        originator: String? = senderA,
        subtype: String? = null,
    ): EventFacts = TestEvents.inbound(classId, originator = originator, at = at, eventId = eventId).copy(subtype = subtype)

    private suspend fun PatternDetectors.only(
        event: EventFacts,
        context: DetectContext = DetectContext.NONE,
    ): DetectionDraft? = evaluate(event, context).singleOrNull()

    // ------------------------------------------------------------------ PAT-01 silent SMS burst

    @Test
    fun `three silent SMS from one sender in 60 minutes are high`() =
        runTest {
            val history = FakePatternHistory()
            history.add("s1", ClassId.SILENT, base, senderA, Subtype.Silent.TYPE0)
            history.add("s2", ClassId.SILENT, base + 30 * minute, senderA, Subtype.Silent.TYPE0)
            val event = inbound(ClassId.SILENT, base + 60 * minute, "s3", subtype = Subtype.Silent.TYPE0)
            history.add("s3", ClassId.SILENT, event.receivedAtWallMs, senderA, Subtype.Silent.TYPE0)

            val draft = detector(history, known = knownA).only(event)!!
            assertEquals(DetectorId.PAT_SILENT_BURST, draft.detectorId)
            assertEquals(Severity.HIGH, draft.severity)
            assertEquals(TextKey.PAT_SILENT_BURST, draft.summaryKey)
            assertEquals("3", draft.summaryArgs["count"])
            assertEquals(3, draft.count)
            assertEquals(base, draft.firstAtWallMs)
            // The burst lists every member, and later messages of the same burst grow one detection.
            assertEquals(setOf("s1", "s2", "s3"), draft.eventIds.toSet())
            val fourth = inbound(ClassId.SILENT, base + 60 * minute, "s4", subtype = Subtype.Silent.TYPE0)
            history.add("s4", ClassId.SILENT, fourth.receivedAtWallMs, senderA, Subtype.Silent.TYPE0)
            val grown = detector(history, known = knownA).only(fourth)!!
            assertEquals(draft.mergeKey, grown.mergeKey)
            assertTrue(draft.mergeKey!!.startsWith(DetectorId.PAT_SILENT_BURST.wire))
            assertFalse(draft.mergeKey!!.contains("5550001"), "the merge key never holds the number")
        }

    @Test
    fun `two silent SMS are not a burst`() =
        runTest {
            val history = FakePatternHistory()
            history.add("s1", ClassId.SILENT, base, senderA)
            val event = inbound(ClassId.SILENT, base + minute, "s2")
            assertEquals(null, detector(history, known = knownA).only(event))
        }

    @Test
    fun `a silent SMS older than 60 minutes is not in the burst`() =
        runTest {
            val history = FakePatternHistory()
            history.add("s1", ClassId.SILENT, base, senderA)
            history.add("s2", ClassId.SILENT, base + 30 * minute, senderA)
            val event = inbound(ClassId.SILENT, base + 60 * minute + 1, "s3")
            assertEquals(null, detector(history, known = knownA).only(event))
        }

    @Test
    fun `silent SMS from another sender do not count for the burst`() =
        runTest {
            val history = FakePatternHistory()
            history.add("s1", ClassId.SILENT, base, senderB)
            history.add("s2", ClassId.SILENT, base + minute, senderB)
            val event = inbound(ClassId.SILENT, base + 2 * minute, "s3")
            assertEquals(null, detector(history, known = knownA).only(event))
        }

    @Test
    fun `the burst replaces the unknown-sender finding`() =
        runTest {
            val history = FakePatternHistory()
            history.add("s1", ClassId.SILENT, base, senderA)
            history.add("s2", ClassId.SILENT, base + minute, senderA)
            val drafts = detector(history).evaluate(inbound(ClassId.SILENT, base + 2 * minute, "s3"), DetectContext.NONE)
            assertEquals(listOf(DetectorId.PAT_SILENT_BURST), drafts.map { it.detectorId })
        }

    // ------------------------------------------------------------------ PAT-02 silent SMS from an unknown sender

    @Test
    fun `a silent SMS from a sender not in Known senders is suspicious`() =
        runTest {
            val draft = detector().only(inbound(ClassId.SILENT, base, "s1", subtype = Subtype.Silent.TYPE0))!!
            assertEquals(DetectorId.PAT_SILENT_UNKNOWN_SENDER, draft.detectorId)
            assertEquals(Severity.SUSPICIOUS, draft.severity)
            assertEquals(TextKey.PAT_SILENT_UNKNOWN_SENDER, draft.summaryKey)
        }

    @Test
    fun `a silent SMS from a known sender gives no pattern`() =
        runTest {
            assertEquals(null, detector(known = knownA).only(inbound(ClassId.SILENT, base, "s1")))
        }

    // ------------------------------------------------------------------ PAT-03 location request from an unknown sender

    @Test
    fun `a location request from a sender that is not a known carrier is high`() =
        runTest {
            for (classId in listOf(ClassId.WAP_SUPL, ClassId.LOC_SUPL)) {
                // A known sender without the carrier label is still not a known carrier number.
                val draft = detector(known = knownA).only(inbound(classId, base, "l-$classId"))!!
                assertEquals(DetectorId.PAT_SUPL_UNKNOWN_SENDER, draft.detectorId, "$classId")
                assertEquals(Severity.HIGH, draft.severity, "$classId")
                assertEquals(TextKey.PAT_SUPL_UNKNOWN_SENDER, draft.summaryKey, "$classId")
            }
        }

    @Test
    fun `a location request from a known carrier gives no pattern`() =
        runTest {
            assertEquals(null, detector(known = carrierA).only(inbound(ClassId.WAP_SUPL, base, "l1")))
        }

    @Test
    fun `a location request during or just after an emergency call gives no pattern`() =
        runTest {
            val at = base + hour
            val active = DetectContext(emergency = EmergencyCallState(active = true))
            assertEquals(null, detector().only(inbound(ClassId.WAP_SUPL, at, "l1"), active))
            val ended = DetectContext(emergency = EmergencyCallState(endedAtWallMs = at - 2 * minute))
            assertEquals(null, detector().only(inbound(ClassId.WAP_SUPL, at, "l2"), ended))
            // After the grace period (default 5 minutes) the rule applies again.
            val late = DetectContext(emergency = EmergencyCallState(endedAtWallMs = at - 6 * minute))
            assertEquals(Severity.HIGH, detector().only(inbound(ClassId.WAP_SUPL, at, "l3"), late)!!.severity)
        }

    // ------------------------------------------------------------------ PAT-04 settings push without network PIN

    @Test
    fun `a settings push with NOSEC or USERPIN from an unknown sender is high`() =
        runTest {
            for (subtype in listOf(Subtype.WapCp.NOSEC, Subtype.WapCp.USERPIN)) {
                val draft = detector().only(inbound(ClassId.WAP_CP, base, "c-$subtype", subtype = subtype))!!
                assertEquals(DetectorId.PAT_CP_NO_NETWORK_PIN, draft.detectorId, subtype)
                assertEquals(Severity.HIGH, draft.severity, subtype)
                assertEquals(TextKey.PAT_CP_NO_NETWORK_PIN, draft.summaryKey, subtype)
            }
        }

    @Test
    fun `a settings push with a network PIN gives no pattern`() =
        runTest {
            for (subtype in listOf(Subtype.WapCp.NETWPIN, Subtype.WapCp.USERNETWPIN, Subtype.WapCp.USERPINMAC, null)) {
                assertEquals(null, detector().only(inbound(ClassId.WAP_CP, base, "c-$subtype", subtype = subtype)), "$subtype")
            }
        }

    @Test
    fun `a settings push without network PIN from a known sender gives no pattern`() =
        runTest {
            val event = inbound(ClassId.WAP_CP, base, "c1", subtype = Subtype.WapCp.NOSEC)
            assertEquals(null, detector(known = knownA).only(event))
        }

    // ------------------------------------------------------------------ PAT-05 SIM browser command

    @Test
    fun `a command for the S@T browser is high`() =
        runTest {
            val draft = detector().only(inbound(ClassId.SIM_DD, base, "o1", subtype = Subtype.SimOta.SAT))!!
            assertEquals(DetectorId.PAT_SIM_BROWSER, draft.detectorId)
            assertEquals(Severity.HIGH, draft.severity)
            assertEquals(TextKey.PAT_SIM_BROWSER_SAT, draft.summaryKey)
        }

    @Test
    fun `a command for the WIB browser is high, also as an extra subtype`() =
        runTest {
            val primary = detector().only(inbound(ClassId.SIM_DD, base, "o1", subtype = Subtype.SimOta.WIB))!!
            assertEquals(TextKey.PAT_SIM_BROWSER_WIB, primary.summaryKey)
            assertEquals(Severity.HIGH, primary.severity)

            val bare =
                inbound(ClassId.SIM_DD, base, "o2", subtype = Subtype.SimOta.NOSEC).copy(extraSubtypes = listOf(Subtype.SimOta.WIB))
            assertEquals(TextKey.PAT_SIM_BROWSER_WIB, detector().only(bare)!!.summaryKey)

            val qualified =
                inbound(ClassId.SIM_DD, base, "o3", subtype = Subtype.SimOta.OTHER)
                    .copy(extraSubtypes = listOf(ClassId.SIM_DD.wire() + ":" + Subtype.SimOta.WIB))
            assertEquals(TextKey.PAT_SIM_BROWSER_WIB, detector().only(qualified)!!.summaryKey)
        }

    @Test
    fun `other SIM updates give no pattern`() =
        runTest {
            for (subtype in listOf(Subtype.SimOta.RFM, Subtype.SimOta.RAM, Subtype.SimOta.NOSEC, Subtype.SimOta.OTHER, null)) {
                assertEquals(null, detector().only(inbound(ClassId.SIM_DD, base, "o-$subtype", subtype = subtype)), "$subtype")
            }
        }

    // ------------------------------------------------------------------ PAT-06 SIM sends after a SIM update

    @Test
    fun `the SIM sends a message 2 minutes after a SIM update - high`() =
        runTest {
            val history = FakePatternHistory()
            history.add("o1", ClassId.SIM_DD, base, senderB, Subtype.SimOta.RFM)
            val send = TestEvents.outbound(ClassId.MO_STK, callingPackage = null, at = base + 2 * minute, eventId = "m1")

            val draft = detector(history).only(send)!!
            assertEquals(DetectorId.PAT_SIM_SENDS_AFTER_UPDATE, draft.detectorId)
            assertEquals(Severity.HIGH, draft.severity)
            assertEquals(TextKey.PAT_SIM_SENDS_AFTER_UPDATE, draft.summaryKey)
            assertEquals(listOf("m1", "o1"), draft.eventIds)
            assertEquals(base, draft.firstAtWallMs)
            assertEquals(base + 2 * minute, draft.lastAtWallMs)
        }

    @Test
    fun `the SIM sends a message more than 10 minutes after a SIM update - no pattern`() =
        runTest {
            val history = FakePatternHistory()
            history.add("o1", ClassId.SIM_DD, base, senderB)
            val send = TestEvents.outbound(ClassId.MO_STK, callingPackage = null, at = base + 10 * minute + 1, eventId = "m1")
            assertEquals(null, detector(history).only(send))
        }

    @Test
    fun `a SIM send without a SIM update gives no pattern`() =
        runTest {
            val send = TestEvents.outbound(ClassId.MO_STK, callingPackage = null, at = base, eventId = "m1")
            assertEquals(null, detector(FakePatternHistory()).only(send))
        }

    // ------------------------------------------------------------------ PAT-07 repeated messages to an unknown port

    @Test
    fun `three messages to an unknown port from one sender in 24 hours are suspicious`() =
        runTest {
            val history = FakePatternHistory()
            history.add("p1", ClassId.DATA_PORT, base, senderA, Subtype.DataPort.UNKNOWN)
            history.add("p2", ClassId.DATA_PORT, base + hour, senderA, Subtype.DataPort.UNKNOWN)
            val event = inbound(ClassId.DATA_PORT, base + 2 * hour, "p3", subtype = Subtype.DataPort.UNKNOWN)

            val draft = detector(history).only(event)!!
            assertEquals(DetectorId.PAT_UNKNOWN_PORT_REPEAT, draft.detectorId)
            assertEquals(Severity.SUSPICIOUS, draft.severity)
            assertEquals(TextKey.PAT_UNKNOWN_PORT_REPEAT, draft.summaryKey)
            assertEquals("3", draft.summaryArgs["count"])
        }

    @Test
    fun `unknown-port messages outside 24 hours, from another sender or to a known port do not count`() =
        runTest {
            val history = FakePatternHistory()
            history.add("p1", ClassId.DATA_PORT, base, senderA, Subtype.DataPort.UNKNOWN)
            history.add("p2", ClassId.DATA_PORT, base + 23 * hour, senderB, Subtype.DataPort.UNKNOWN)
            history.add("p3", ClassId.DATA_PORT, base + 23 * hour, senderA, Subtype.DataPort.WAP_PUSH)
            history.add("p4", ClassId.DATA_PORT, base + 24 * hour, senderA, Subtype.DataPort.UNKNOWN)
            val event = inbound(ClassId.DATA_PORT, base + 24 * hour + 1, "p5", subtype = Subtype.DataPort.UNKNOWN)
            assertEquals(null, detector(history, clock = { base + 48 * hour }).only(event))
        }

    @Test
    fun `a message to a known port gives no pattern`() =
        runTest {
            val history = FakePatternHistory()
            history.add("p1", ClassId.DATA_PORT, base, senderA, Subtype.DataPort.WAP_PUSH)
            history.add("p2", ClassId.DATA_PORT, base + minute, senderA, Subtype.DataPort.WAP_PUSH)
            val event = inbound(ClassId.DATA_PORT, base + 2 * minute, "p3", subtype = Subtype.DataPort.WAP_PUSH)
            assertEquals(null, detector(history).only(event))
        }

    // ------------------------------------------------------------------ PAT-08 malformed burst

    @Test
    fun `two malformed messages in 10 minutes are high, from any sender`() =
        runTest {
            val history = FakePatternHistory()
            history.add("x1", ClassId.WAP_UNREADABLE, base, senderB)
            val event = inbound(ClassId.MALFORMED_SMS, base + 2 * minute, "x2", originator = null)

            val draft = detector(history).only(event)!!
            assertEquals(DetectorId.PAT_MALFORMED_BURST, draft.detectorId)
            assertEquals(Severity.HIGH, draft.severity)
            assertEquals(TextKey.PAT_MALFORMED_BURST, draft.summaryKey)
            assertEquals("2", draft.summaryArgs["count"])
        }

    @Test
    fun `one malformed message, or two more than 10 minutes apart, gives no pattern`() =
        runTest {
            assertEquals(null, detector(FakePatternHistory()).only(inbound(ClassId.MALFORMED_SMS, base, "x1")))

            val history = FakePatternHistory()
            history.add("x1", ClassId.MALFORMED_SMS, base, senderA)
            assertEquals(null, detector(history).only(inbound(ClassId.MALFORMED_SMS, base + 10 * minute + 1, "x2")))
        }

    // ------------------------------------------------------------------ PAT-09 replacement without a first message

    @Test
    fun `a replacement message with no earlier message of its type is suspicious`() =
        runTest {
            val history = FakePatternHistory()
            // An earlier replacement of another type from the same sender is not a first message for type 1.
            history.add("r0", ClassId.REPLACE, base, senderA, Subtype.Replace.TYPE2)
            val event = inbound(ClassId.REPLACE, base + hour, "r1", subtype = Subtype.Replace.TYPE1)
            history.add("r1", ClassId.REPLACE, event.receivedAtWallMs, senderA, Subtype.Replace.TYPE1)

            val draft = detector(history).only(event)!!
            assertEquals(DetectorId.PAT_REPLACE_NO_FIRST, draft.detectorId)
            assertEquals(Severity.SUSPICIOUS, draft.severity)
            assertEquals(TextKey.PAT_REPLACE_NO_FIRST, draft.summaryKey)
        }

    @Test
    fun `a replacement message after a message of its type gives no pattern`() =
        runTest {
            val history = FakePatternHistory()
            history.add("r0", ClassId.REPLACE, base, senderA, Subtype.Replace.TYPE1)
            val event = inbound(ClassId.REPLACE, base + 30 * 24 * hour, "r1", subtype = Subtype.Replace.TYPE1)
            assertEquals(null, detector(history, clock = { base + 60 * 24 * hour }).only(event))
        }

    @Test
    fun `the replacement rule is off without stored history or without a sender`() =
        runTest {
            val event = inbound(ClassId.REPLACE, base, "r1", subtype = Subtype.Replace.TYPE1)
            assertEquals(null, detector(PatternHistory.NONE).only(event))
            assertEquals(null, detector(FakePatternHistory()).only(event.copy(originator = null)))
        }

    // ------------------------------------------------------------------ all patterns

    @Test
    fun `self tests, replays and released copies never count as a pattern`() =
        runTest {
            val history = FakePatternHistory()
            history.add("x1", ClassId.MALFORMED_SMS, base, senderA)
            val event = inbound(ClassId.MALFORMED_SMS, base + minute, "x2")
            for (flags in listOf(EventFlags(selfTest = true), EventFlags(replay = true), EventFlags(releaseOf = "x0"))) {
                assertTrue(detector(history).evaluate(event.copy(flags = flags), DetectContext.NONE).isEmpty(), "$flags")
            }
        }

    @Test
    fun `a future arrival time is limited by the clock`() =
        runTest {
            val history = FakePatternHistory()
            history.add("x1", ClassId.MALFORMED_SMS, base, senderA)
            // The phone clock says base + 5 minutes; the event claims a time 1 hour later. The window ends at the clock.
            val event = inbound(ClassId.MALFORMED_SMS, base + hour, "x2")
            val draft = detector(history, clock = { base + 5 * minute }).only(event)!!
            assertEquals(base + 5 * minute, draft.lastAtWallMs)
        }

    @Test
    fun `classes without a pattern give nothing`() =
        runTest {
            for (classId in listOf(ClassId.NORMAL, ClassId.FLASH, ClassId.WAP_MMS, ClassId.CB)) {
                assertTrue(detector().evaluate(inbound(classId, base, "n-$classId"), DetectContext.NONE).isEmpty(), "$classId")
            }
        }

    @Test
    fun `the engine keeps one line for a silent SMS from a new sender`() =
        runTest {
            // Explains a new sender already; the pattern line would only repeat it.
            val engine =
                DetectorEngine(
                    DetectorConfig.DEFAULT,
                    dev.nightjar.app.domain
                        .FakeDetectionHistory(),
                    KnownOriginatorMatcher.EMPTY,
                    FakePatternHistory(),
                    fixedClock,
                )
            val drafts = engine.evaluate(inbound(ClassId.SILENT, base, "s1"), DetectContext.NONE)
            assertEquals(listOf(DetectorId.SILENT_PING), drafts.map { it.detectorId })
        }

    @Test
    fun `every pattern id is a pattern and no DET id is`() {
        val patterns = DetectorId.entries.filter { it.pattern }
        assertEquals(9, patterns.size)
        assertTrue(patterns.all { it.name.startsWith("PAT_") })
        assertTrue(DetectorId.entries.filterNot { it.pattern }.all { it.wire.startsWith("DET-") })
    }
}
