package dev.nightjar.app.domain.classify

import dev.nightjar.app.domain.TestEvents
import dev.nightjar.app.domain.detect.DetectContext
import dev.nightjar.app.domain.detect.DetectorId
import dev.nightjar.app.domain.detect.SimOtaFacts
import dev.nightjar.app.domain.known.KnownOriginator
import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.app.domain.severity.SeverityConfig
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Subtype
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reclassification when `severity.<class>`, the known-originator list or the trusted packages change. */
class SeverityReclassifierTest {
    private val known =
        SeverityConfig(
            knownOriginators = KnownOriginatorMatcher(listOf(KnownOriginator("+15559876543210", KnownOriginator.Kind.EXACT))),
        )

    private val rows =
        listOf(
            // A silent SMS is Suspicious by default.
            StoredSeverityRow(1, TestEvents.inbound(ClassId.SILENT), currentSeverity = Severity.SUSPICIOUS),
            StoredSeverityRow(2, TestEvents.inbound(ClassId.WAP_CP), currentSeverity = Severity.SUSPICIOUS),
            StoredSeverityRow(
                3,
                TestEvents.inbound(ClassId.SIM_DD),
                // SPI 0: no integrity check and no ciphering.
                context = DetectContext(simOta = SimOtaFacts(spi = 0, tar = 1)),
                findings = listOf(Finding(DetectorId.SIM_OTA, Severity.SUSPICIOUS)),
                currentSeverity = Severity.SUSPICIOUS,
            ),
        )

    @Test
    fun `changing an override reclassifies matching rows`() {
        val reclassifier = SeverityReclassifier(SeverityConfig(overrides = mapOf(ClassId.SILENT to Severity.NOTABLE)))
        val changes = reclassifier.reclassify(rows)
        assertEquals(listOf(SeverityChange(1, Severity.SUSPICIOUS, Severity.NOTABLE, overridden = true)), changes)
    }

    @Test
    fun `adding a known originator downgrades stored rows`() {
        val changes = SeverityReclassifier(known).reclassify(rows)
        assertTrue(changes.any { it.id == 2L && it.to == Severity.NOTABLE })
        // The insecure SIM update keeps Suspicious (exception).
        assertFalse(changes.any { it.id == 3L })
    }

    @Test
    fun `a High subtype row keeps High after a known originator is added`() {
        // The subtype default and the evidence floor apply as they do at ingest.
        val row =
            StoredSeverityRow(
                id = 4,
                event = TestEvents.inbound(ClassId.WAP_CP).copy(subtype = Subtype.WapCp.NOSEC),
                currentSeverity = Severity.HIGH,
            )
        assertEquals(emptyList<SeverityChange>(), SeverityReclassifier(known).reclassify(listOf(row)))
    }

    @Test
    fun `a pattern finding stays a floor after a known originator is added`() {
        val row =
            StoredSeverityRow(
                id = 5,
                event = TestEvents.inbound(ClassId.SILENT),
                findings = listOf(Finding(DetectorId.PAT_SILENT_BURST, Severity.HIGH)),
                currentSeverity = Severity.HIGH,
            )
        assertEquals(emptyList<SeverityChange>(), SeverityReclassifier(known).reclassify(listOf(row)))
    }

    @Test
    fun `a detector contribution survives an override change`() {
        val row =
            StoredSeverityRow(
                id = 9,
                event = TestEvents.inbound(ClassId.SILENT),
                findings = listOf(Finding(DetectorId.SILENT_PING, Severity.SUSPICIOUS)),
                currentSeverity = Severity.SUSPICIOUS,
                currentOverridden = true,
            )
        // The override lowers the class default; the detector's escalation must still win.
        val changes =
            SeverityReclassifier(SeverityConfig(overrides = mapOf(ClassId.SILENT to Severity.INFO))).reclassify(listOf(row))
        assertEquals(emptyList<SeverityChange>(), changes)
    }

    @Test
    fun `removing an override clears the flag on the row`() {
        val row = StoredSeverityRow(6, TestEvents.inbound(ClassId.SILENT), currentSeverity = Severity.SUSPICIOUS, currentOverridden = true)
        val changes = SeverityReclassifier(SeverityConfig.DEFAULT).reclassify(listOf(row))
        assertEquals(listOf(SeverityChange(6, Severity.SUSPICIOUS, Severity.SUSPICIOUS, overridden = false)), changes)
    }

    @Test
    fun `trusting a package makes its outgoing rows Info`() {
        val row = StoredSeverityRow(7, TestEvents.outbound(ClassId.MO_DATA), currentSeverity = Severity.NOTABLE)
        val changes = SeverityReclassifier(SeverityConfig(trustedPackages = setOf("com.example.app"))).reclassify(listOf(row))
        assertEquals(Severity.INFO, changes.single().to)
    }

    @Test
    fun `an unchanged configuration writes nothing`() {
        val changes = SeverityReclassifier(SeverityConfig.DEFAULT).reclassify(rows)
        assertEquals(emptyList<SeverityChange>(), changes)
    }

    @Test
    fun `trigger applicability skips classes it cannot affect`() {
        assertTrue(ReclassifyTrigger.KNOWN_ORIGINATOR.affects(ClassId.WAP_CP))
        assertFalse(ReclassifyTrigger.KNOWN_ORIGINATOR.affects(ClassId.INJECTED))
        assertFalse(ReclassifyTrigger.KNOWN_ORIGINATOR.affects(ClassId.MO_DATA))
        assertTrue(ReclassifyTrigger.TRUSTED_PACKAGE.affects(ClassId.MO_DATA))
        assertTrue(ReclassifyTrigger.TRUSTED_PACKAGE.affects(ClassId.INJECTED))
        assertFalse(ReclassifyTrigger.TRUSTED_PACKAGE.affects(ClassId.WAP_CP))
        assertTrue(ReclassifyTrigger.SEVERITY_OVERRIDE.affects(ClassId.CB))
    }
}
