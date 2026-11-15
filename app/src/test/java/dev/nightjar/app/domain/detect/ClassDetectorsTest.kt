package dev.nightjar.app.domain.detect

import dev.nightjar.app.domain.FakeDetectionHistory
import dev.nightjar.app.domain.TestEvents
import dev.nightjar.app.domain.known.KnownOriginator
import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.app.domain.model.EmergencyCallState
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClassDetectorsTest {
    private val history = FakeDetectionHistory()

    private fun detectors(
        config: DetectorConfig = DetectorConfig.DEFAULT,
        known: KnownOriginatorMatcher = KnownOriginatorMatcher.EMPTY,
    ) = ClassDetectors(config, history, known)

    // ------------------------------------------------------------------ DET-02

    @Test
    fun `SIM OTA without integrity or ciphering is suspicious`() =
        runTest {
            val facts = SimOtaFacts.parse(spi1 = 0x00, spi2 = 0x00, tar = 0xB00001)
            val draft =
                detectors()
                    .evaluate(TestEvents.inbound(ClassId.SIM_DD), DetectContext(simOta = facts))
                    .single()
            assertEquals(DetectorId.SIM_OTA, draft.detectorId)
            assertEquals(Severity.SUSPICIOUS, draft.severity)
            assertEquals(TextKey.DET_SIM_OTA_INSECURE, draft.summaryKey)
            assertTrue(facts.insecure)
        }

    @Test
    fun `SIM OTA with a cryptographic checksum is not suspicious`() =
        runTest {
            val facts = SimOtaFacts.parse(spi1 = 0b10, spi2 = 0x00, tar = 0xB00001)
            assertEquals(SimOtaFacts.Integrity.CRYPTOGRAPHIC_CHECKSUM, facts.integrity)
            assertFalse(facts.insecure)
            assertTrue(detectors().evaluate(TestEvents.inbound(ClassId.SIM_DD), DetectContext(simOta = facts)).isEmpty())
        }

    @Test
    fun `ciphering alone is enough to avoid the insecure verdict`() =
        runTest {
            val facts = SimOtaFacts.parse(spi1 = 0b100, spi2 = 0x00, tar = 0x010203)
            assertTrue(facts.ciphering)
            assertFalse(facts.insecure)
        }

    @Test
    fun `a denylisted TAR is suspicious even with integrity`() =
        runTest {
            val facts = SimOtaFacts.parse(spi1 = 0b10, spi2 = 0x00, tar = 0xB00001)
            val draft =
                detectors(DetectorConfig(tarDenylist = setOf(0xB00001)))
                    .evaluate(TestEvents.inbound(ClassId.SIM_DD), DetectContext(simOta = facts))
                    .single()
            assertEquals(Severity.SUSPICIOUS, draft.severity)
            assertTrue(draft.paragraphKeys.contains(TextKey.DET_SIM_OTA_TAR))
            // A secure packet must not get the "no integrity check and no encryption" sentence.
            assertFalse(draft.paragraphKeys.contains(TextKey.DET_SIM_OTA_INSECURE))
            assertEquals("B00001", facts.tarHex)
        }

    @Test
    fun `SIM OTA without decoded facts keeps the class default`() =
        runTest {
            assertTrue(detectors().evaluate(TestEvents.inbound(ClassId.SIM_DD), DetectContext.NONE).isEmpty())
        }

    // ------------------------------------------------------------------ DET-03

    @Test
    fun `OMA CP from an unknown sender is suspicious and lists the values`() =
        runTest {
            val facts = OmaCpFacts(apn = "internet.evil", proxy = "10.0.0.1:8080", server = "cp.example")
            val draft =
                detectors()
                    .evaluate(TestEvents.inbound(ClassId.WAP_CP), DetectContext(omaCp = facts))
                    .single()
            assertEquals(Severity.SUSPICIOUS, draft.severity)
            assertEquals(TextKey.DET_OMA_CP_UNKNOWN, draft.summaryKey)
            assertTrue(draft.paragraphKeys.contains(TextKey.DET_OMA_CP_VALUES))
            assertEquals("internet.evil", draft.summaryArgs["apn"])
            assertEquals("10.0.0.1:8080", draft.summaryArgs["proxy"])
        }

    @Test
    fun `OMA CP from a known carrier is info`() =
        runTest {
            val known = KnownOriginatorMatcher(listOf(KnownOriginator("+15559000000002", KnownOriginator.Kind.EXACT, "carrier")))
            val draft =
                detectors(known = known)
                    .evaluate(TestEvents.inbound(ClassId.WAP_CP, originator = "+15559000000002"), DetectContext.NONE)
                    .single()
            assertEquals(Severity.INFO, draft.severity)
            assertEquals(TextKey.DET_OMA_CP_KNOWN_CARRIER, draft.summaryKey)
        }

    @Test
    fun `a known sender that is not a carrier does not clear OMA CP`() =
        runTest {
            val known = KnownOriginatorMatcher(listOf(KnownOriginator("+15559000000003", KnownOriginator.Kind.EXACT, "bank")))
            val draft =
                detectors(known = known)
                    .evaluate(TestEvents.inbound(ClassId.WAP_CP, originator = "+15559000000003"), DetectContext.NONE)
                    .single()
            assertEquals(Severity.SUSPICIOUS, draft.severity)
        }

    // ------------------------------------------------------------------ DET-04

    @Test
    fun `SUPL during an emergency call is info`() =
        runTest {
            val draft =
                detectors()
                    .evaluate(
                        TestEvents.inbound(ClassId.WAP_SUPL),
                        DetectContext(emergency = EmergencyCallState(active = true)),
                    ).single()
            assertEquals(Severity.INFO, draft.severity)
            assertEquals(TextKey.DET_SUPL_EMERGENCY, draft.summaryKey)
        }

    @Test
    fun `SUPL within the grace window is info`() =
        runTest {
            val at = 1_000_000L
            val draft =
                detectors()
                    .evaluate(
                        TestEvents.inbound(ClassId.WAP_SUPL, at = at),
                        DetectContext(emergency = EmergencyCallState(active = false, endedAtWallMs = at - 2 * 60_000L)),
                    ).single()
            assertEquals(Severity.INFO, draft.severity)
        }

    @Test
    fun `SUPL outside the grace window is suspicious`() =
        runTest {
            val at = 1_000_000L
            val draft =
                detectors()
                    .evaluate(
                        TestEvents.inbound(ClassId.WAP_SUPL, at = at),
                        DetectContext(emergency = EmergencyCallState(active = false, endedAtWallMs = at - 6 * 60_000L)),
                    ).single()
            assertEquals(Severity.SUSPICIOUS, draft.severity)
            assertEquals(TextKey.DET_SUPL_UNEXPECTED, draft.summaryKey)
        }

    @Test
    fun `the grace window follows detect supl graceMin`() =
        runTest {
            val at = 1_000_000L
            val config = DetectorConfig(suplGraceMin = 10)
            val draft =
                detectors(config)
                    .evaluate(
                        TestEvents.inbound(ClassId.WAP_SUPL, at = at),
                        DetectContext(emergency = EmergencyCallState(endedAtWallMs = at - 8 * 60_000L)),
                    ).single()
            assertEquals(Severity.INFO, draft.severity)
        }

    // ------------------------------------------------------------------ DET-05

    @Test
    fun `outbound to a short code is suspicious`() =
        runTest {
            history.seenPackages += "com.example.app"
            val draft =
                detectors()
                    .evaluate(TestEvents.outbound(ClassId.MO_DATA, destination = "56789"), DetectContext.NONE)
                    .single()
            assertEquals(DetectorId.OUTBOUND, draft.detectorId)
            assertEquals(Severity.SUSPICIOUS, draft.severity)
            assertEquals(TextKey.DET_OUTBOUND_SHORT_CODE, draft.summaryKey)
        }

    @Test
    fun `outbound to a premium pattern is suspicious`() =
        runTest {
            history.seenPackages += "com.example.app"
            val config = DetectorConfig(premium = PremiumPatterns(listOf("1900"), emptyList()))
            val draft =
                detectors(config)
                    .evaluate(TestEvents.outbound(ClassId.MO_DATA, destination = "+19005551212"), DetectContext.NONE)
                    .single()
            assertTrue(draft.paragraphKeys.contains(TextKey.DET_OUTBOUND_PREMIUM))
        }

    @Test
    fun `first send by a package is suspicious`() =
        runTest {
            val draft =
                detectors()
                    .evaluate(TestEvents.outbound(ClassId.MO_DATA, destination = "+15559876543210"), DetectContext.NONE)
                    .single()
            assertEquals(TextKey.DET_OUTBOUND_FIRST_SEND, draft.summaryKey)
        }

    @Test
    fun `a package that sent before and a normal destination is not suspicious`() =
        runTest {
            history.seenPackages += "com.example.app"
            assertTrue(
                detectors()
                    .evaluate(TestEvents.outbound(ClassId.MO_DATA, destination = "+15559876543210"), DetectContext.NONE)
                    .isEmpty(),
            )
        }

    @Test
    fun `STK sends are judged by destination, not by package`() =
        runTest {
            // The SIM pseudo-package sent before, but never to this destination: still the first send.
            history.seenPackages += ClassDetectors.INTERNAL_PACKAGE
            history.seenDestinations += "+15559876543210"
            assertTrue(
                detectors()
                    .evaluate(
                        TestEvents.outbound(ClassId.MO_STK, destination = "+15559876543210", callingPackage = null),
                        DetectContext.NONE,
                    ).isEmpty(),
            )
            val first =
                ClassDetectors(DetectorConfig.DEFAULT, FakeDetectionHistory(), KnownOriginatorMatcher.EMPTY)
                    .evaluate(
                        TestEvents.outbound(ClassId.MO_STK, destination = "+15559876543210", callingPackage = null),
                        DetectContext.NONE,
                    ).single()
            assertEquals(TextKey.DET_OUTBOUND_FIRST_SEND, first.summaryKey)
        }

    // ------------------------------------------------------------------ DET-06

    @Test
    fun `an injected message is suspicious`() =
        runTest {
            val event = TestEvents.inbound(ClassId.INJECTED).copy(callingPackage = "com.fake.sms")
            val draft = detectors().evaluate(event, DetectContext.NONE).single()
            assertEquals(DetectorId.INJECTION, draft.detectorId)
            assertEquals(Severity.SUSPICIOUS, draft.severity)
            assertEquals("com.fake.sms", draft.summaryArgs["package"])
        }

    // ------------------------------------------------------------------ config

    @Test
    fun `detector config clamps out-of-range settings`() {
        val coerced = DetectorConfig(silentBurstCount = 99, silentWindowMin = 7, suplGraceMin = 1).coerced()
        assertEquals(DetectorConfig.MAX_BURST_COUNT, coerced.silentBurstCount)
        assertEquals(DetectorConfig.DEFAULT_WINDOW_MIN, coerced.silentWindowMin)
        assertEquals(DetectorConfig.MIN_SUPL_GRACE_MIN, coerced.suplGraceMin)
    }

    @Test
    fun `premium patterns parse and ignore malformed input`() {
        val parsed = PremiumPatterns.parseJson("""{"prefixes":["1900",""],"regexes":["^15559[0-9]{9}$","["]}""")
        assertTrue(parsed.matches("19005551212"), "prefix 1900: $parsed prefixes=${parsed.isEmpty}")
        assertTrue(parsed.matches("+15559876543210"), "regex on digits")
        assertFalse(parsed.matches("+15558800000000"))
        assertTrue(PremiumPatterns.parseJson("not json").isEmpty)
        assertTrue(PremiumPatterns.parseJson(null).isEmpty)
    }
}
