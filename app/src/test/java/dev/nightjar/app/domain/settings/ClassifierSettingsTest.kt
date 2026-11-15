package dev.nightjar.app.domain.settings

import dev.nightjar.app.domain.detect.DetectorConfig
import dev.nightjar.app.domain.detect.PremiumPatterns
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Keys → classifier settings, with clamping and safe fallbacks. */
class ClassifierSettingsTest {
    @Test
    fun `defaults apply when nothing is stored`() {
        val settings = ClassifierSettings.fromRaw(emptyMap())
        assertEquals(DetectorConfig.DEFAULT.silentBurstCount, settings.detectors.silentBurstCount)
        assertEquals(DetectorConfig.DEFAULT.silentWindowMin, settings.detectors.silentWindowMin)
        assertEquals(DetectorConfig.DEFAULT.suplGraceMin, settings.detectors.suplGraceMin)
        assertTrue(settings.detectors.silentFlagNewSender)
        assertTrue(settings.detectors.silentFlagRoaming)
        assertTrue(settings.notifySuspicious)
        assertFalse(settings.notifyNotable)
        assertEquals(ClassifierSettings.DEFAULT_RATE_LIMIT_MIN, settings.notifyRateLimitMin)
        assertTrue(settings.severityOverrides.isEmpty())
    }

    @Test
    fun `severity overrides are read from severity dot keys`() {
        val settings =
            ClassifierSettings.fromRaw(
                mapOf(
                    "severity.CL-WAP-CP" to "INFO",
                    "severity.CL-SILENT" to "NOTABLE",
                    "severity.CL-NOT-A-CLASS" to "INFO",
                    "severity.CL-MWI" to "NOPE",
                ),
            )
        assertEquals(Severity.INFO, settings.severityOverrides[ClassId.WAP_CP])
        assertEquals(Severity.NOTABLE, settings.severityOverrides[ClassId.SILENT])
        assertEquals(2, settings.severityOverrides.size)
    }

    @Test
    fun `detector values are clamped to their documented ranges`() {
        val tooBig = ClassifierSettings.fromRaw(mapOf(ClassifierSettings.KEY_SILENT_COUNT to "99"))
        assertEquals(DetectorConfig.MAX_BURST_COUNT, tooBig.detectors.silentBurstCount)

        val badWindow = ClassifierSettings.fromRaw(mapOf(ClassifierSettings.KEY_SILENT_WINDOW to "7"))
        assertEquals(DetectorConfig.DEFAULT_WINDOW_MIN, badWindow.detectors.silentWindowMin)

        val lowGrace = ClassifierSettings.fromRaw(mapOf(ClassifierSettings.KEY_SUPL_GRACE to "1"))
        assertEquals(DetectorConfig.MIN_SUPL_GRACE_MIN, lowGrace.detectors.suplGraceMin)
    }

    @Test
    fun `unreadable values keep their defaults`() {
        val settings =
            ClassifierSettings.fromRaw(
                mapOf(
                    ClassifierSettings.KEY_SILENT_COUNT to "many",
                    ClassifierSettings.KEY_NOTIFY_NOTABLE to "perhaps",
                    ClassifierSettings.KEY_NOTIFY_RATE to "3",
                ),
            )
        assertEquals(DetectorConfig.DEFAULT_BURST_COUNT, settings.detectors.silentBurstCount)
        assertFalse(settings.notifyNotable)
        // 3 is not an offered choice; the default stands rather than a silently different interval.
        assertEquals(ClassifierSettings.DEFAULT_RATE_LIMIT_MIN, settings.notifyRateLimitMin)
    }

    @Test
    fun `booleans accept the usual spellings`() {
        for (value in listOf("true", "1", "on", "yes")) {
            assertTrue(ClassifierSettings.fromRaw(mapOf(ClassifierSettings.KEY_NOTIFY_NOTABLE to value)).notifyNotable)
        }
        for (value in listOf("false", "0", "off", "no")) {
            assertFalse(ClassifierSettings.fromRaw(mapOf(ClassifierSettings.KEY_NOTIFY_NOTABLE to value)).notifyNotable)
        }
    }

    @Test
    fun `premium patterns and the tar denylist reach the detector config`() {
        val settings =
            ClassifierSettings.fromRaw(
                mapOf(ClassifierSettings.KEY_SUPL_GRACE to "10"),
                premium = PremiumPatterns(listOf("1900"), emptyList()),
                tarDenylist = setOf(0xB00001),
            )
        assertEquals(10, settings.detectors.suplGraceMin)
        assertTrue(settings.detectors.premium.matches("+19005551212"))
        assertTrue(settings.detectors.tarDenylist.contains(0xB00001))
    }
}
