package dev.nightjar.app.domain.detect

import dev.nightjar.app.domain.FakeDetectionHistory
import dev.nightjar.app.domain.TestEvents
import dev.nightjar.app.domain.known.KnownOriginator
import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SilentPingDetectorTest {
    private val minute = 60_000L

    private fun detector(
        history: FakeDetectionHistory,
        config: DetectorConfig = DetectorConfig.DEFAULT,
        known: KnownOriginatorMatcher = KnownOriginatorMatcher.EMPTY,
    ) = SilentPingDetector(config, history, known)

    @Test
    fun `a single silent ping from a known sender is not suspicious`() =
        runTest {
            val history = FakeDetectionHistory()
            history.seenOriginators += "15559876543210"
            val draft = detector(history).evaluate(TestEvents.inbound(ClassId.SILENT))
            assertEquals(null, draft.firstOrNull())
        }

    @Test
    fun `the burst threshold makes the ping suspicious`() =
        runTest {
            val history = FakeDetectionHistory()
            history.seenOriginators += "15559876543210"
            val at = 10 * minute
            history.addPing("e1", "+15559876543210", at - 2 * minute)
            history.addPing("e2", "+15559876543210", at - minute)
            val drafts = detector(history).evaluate(TestEvents.inbound(ClassId.SILENT, at = at, eventId = "e3"))
            assertEquals(1, drafts.size)
            val draft = drafts.single()
            assertEquals(DetectorId.SILENT_PING, draft.detectorId)
            assertEquals(Severity.SUSPICIOUS, draft.severity)
            assertEquals(TextKey.DET_SILENT_BURST, draft.summaryKey)
            assertEquals("3", draft.summaryArgs["count"])
            assertEquals("2", draft.summaryArgs["minutes"])
            assertEquals("1", draft.summaryArgs["senders"])
        }

    @Test
    fun `a ping without a sender does not count as one more sender`() =
        runTest {
            val history = FakeDetectionHistory()
            val at = 10 * minute
            history.addPing("e1", "+15559876543210", at - 2 * minute)
            history.addPing("e2", "+15559876543210", at - minute)
            val drafts = detector(history).evaluate(TestEvents.inbound(ClassId.SILENT, originator = null, at = at, eventId = "e3"))
            assertEquals("1", drafts.first { it.summaryKey == TextKey.DET_SILENT_BURST }.summaryArgs["senders"])
        }

    @Test
    fun `with a threshold of 5 the fourth ping stays notable`() =
        runTest {
            val history = FakeDetectionHistory()
            history.seenOriginators += "15559876543210"
            val config = DetectorConfig(silentBurstCount = 5)
            val at = 10 * minute
            history.addPing("e1", "+15559876543210", at - 3 * minute)
            history.addPing("e2", "+15559876543210", at - 2 * minute)
            history.addPing("e3", "+15559876543210", at - minute)
            val fourth = detector(history, config).evaluate(TestEvents.inbound(ClassId.SILENT, at = at, eventId = "e4"))
            assertEquals(emptyList<DetectionDraft>(), fourth)

            history.addPing("e4", "+15559876543210", at)
            val fifth = detector(history, config).evaluate(TestEvents.inbound(ClassId.SILENT, at = at + 1, eventId = "e5"))
            assertEquals(Severity.SUSPICIOUS, fifth.single().severity)
            assertEquals("5", fifth.single().summaryArgs["count"])
        }

    @Test
    fun `pings outside the window do not form a burst`() =
        runTest {
            val history = FakeDetectionHistory()
            history.seenOriginators += "15559876543210"
            val config = DetectorConfig(silentBurstCount = 3, silentWindowMin = 15)
            history.addPing("e1", "+15559876543210", 0)
            history.addPing("e2", "+15559876543210", 20 * minute)
            val drafts = detector(history, config).evaluate(TestEvents.inbound(ClassId.SILENT, at = 40 * minute, eventId = "e3"))
            assertEquals(emptyList<DetectionDraft>(), drafts)
        }

    @Test
    fun `an unknown sender is suspicious on its own`() =
        runTest {
            val history = FakeDetectionHistory()
            val drafts = detector(history).evaluate(TestEvents.inbound(ClassId.SILENT))
            val draft = drafts.single()
            assertEquals(TextKey.DET_SILENT_NEW_SENDER, draft.summaryKey)
            assertEquals(Severity.SUSPICIOUS, draft.severity)
        }

    @Test
    fun `a known originator suppresses the new-sender rule`() =
        runTest {
            val history = FakeDetectionHistory()
            val known = KnownOriginatorMatcher(listOf(KnownOriginator("+15559876543210", KnownOriginator.Kind.EXACT)))
            assertTrue(detector(history, known = known).evaluate(TestEvents.inbound(ClassId.SILENT)).isEmpty())
        }

    @Test
    fun `the new-sender rule can be switched off`() =
        runTest {
            val history = FakeDetectionHistory()
            val config = DetectorConfig(silentFlagNewSender = false)
            assertTrue(detector(history, config).evaluate(TestEvents.inbound(ClassId.SILENT)).isEmpty())
        }

    @Test
    fun `roaming makes a silent ping suspicious`() =
        runTest {
            val history = FakeDetectionHistory()
            history.seenOriginators += "15559876543210"
            val event = TestEvents.inbound(ClassId.SILENT).copy(roaming = true)
            val draft = detector(history).evaluate(event).single()
            assertEquals(TextKey.DET_SILENT_ROAMING, draft.summaryKey)
            // And the roaming rule is switchable.
            val off = detector(history, DetectorConfig(silentFlagRoaming = false)).evaluate(event)
            assertTrue(off.isEmpty())
        }

    @Test
    fun `a continuing burst grows one detection`() =
        runTest {
            val history = FakeDetectionHistory()
            history.seenOriginators += "15559876543210"
            val config = DetectorConfig(silentBurstCount = 3, silentWindowMin = 15)
            val at = 10 * minute
            history.addPing("e1", "+15559876543210", at - 3 * minute)
            history.addPing("e2", "+15559876543210", at - 2 * minute)
            history.addOpen(
                OpenDetection(
                    detectorId = DetectorId.SILENT_PING,
                    mergeKey = "burst-1",
                    firstAtWallMs = at - 3 * minute,
                    lastAtWallMs = at - minute,
                    count = 3,
                ),
            )
            val draft = detector(history, config).evaluate(TestEvents.inbound(ClassId.SILENT, at = at, eventId = "e4")).single()
            assertEquals("burst-1", draft.mergeKey)
            assertEquals(4, draft.count)
            assertEquals(at - 3 * minute, draft.firstAtWallMs)
            assertEquals(listOf("e4"), draft.eventIds)
        }

    @Test
    fun `a burst after a long gap starts a new detection`() =
        runTest {
            val history = FakeDetectionHistory()
            history.seenOriginators += "15559876543210"
            val config = DetectorConfig(silentBurstCount = 3, silentWindowMin = 15)
            val at = 60 * minute
            history.addOpen(
                OpenDetection(
                    detectorId = DetectorId.SILENT_PING,
                    mergeKey = "burst-old",
                    firstAtWallMs = 0,
                    lastAtWallMs = 5 * minute,
                    count = 6,
                ),
            )
            history.addPing("e1", "+15559876543210", at - 2 * minute)
            history.addPing("e2", "+15559876543210", at - minute)
            val draft = detector(history, config).evaluate(TestEvents.inbound(ClassId.SILENT, at = at, eventId = "e3")).single()
            assertEquals(3, draft.count)
            assertTrue(draft.mergeKey != "burst-old", "a new burst must not extend a stale detection")
        }

    @Test
    fun `other classes are ignored`() =
        runTest {
            val history = FakeDetectionHistory()
            assertNull(detector(history).evaluate(TestEvents.inbound(ClassId.WAP_CP)).firstOrNull())
        }
}
