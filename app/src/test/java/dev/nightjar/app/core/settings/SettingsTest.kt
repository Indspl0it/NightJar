package dev.nightjar.app.core.settings

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Every default and range of the settings catalogue, checked without Android. */
class SettingsTest {
    @Test
    fun `defaults match the settings catalogue`() {
        val s = Settings()
        assertEquals(UiMode.SIMPLE, s.mode)
        assertEquals(ThemeMode.SYSTEM, s.theme)
        assertFalse(s.dynamicColor)
        assertTrue(s.autoReview)
        assertTrue(s.notifySuspicious)
        assertFalse(s.notifyNotable)
        assertFalse(s.notifyCb)
        // U7: the weekly digest is the default; DAILY no longer exists.
        assertEquals(DigestMode.WEEKLY, s.notifyDigest)
        assertEquals(5, s.notifyRateLimitMin)
        assertEquals(3, s.detectSilentCount)
        assertEquals(15, s.detectSilentWindowMin)
        assertTrue(s.detectSilentNewSender)
        assertTrue(s.detectSilentRoaming)
        assertEquals(5, s.detectSuplGraceMin)
        assertTrue(s.outgoingEnabled)
        assertFalse(s.outgoingLogVisible)
        assertTrue(s.cbLog)
        assertFalse(s.cbAllChannels)
        assertFalse(s.appLock)
        assertEquals(LockTimeout.FIVE_MIN, s.lockTimeout)
        assertFalse(s.maskNumbers)
        assertFalse(s.hideInNotifications)
        assertFalse(s.blockScreenshots)
        assertEquals(Retention.DAYS_90, s.retention)
        assertFalse(s.debugLogging)
        assertFalse(s.safeMode)
        assertFalse(s.onboardingDone)
        assertTrue(s.severityOverrides.isEmpty())
    }

    @Test
    fun `rate limit snaps to a documented choice`() {
        assertEquals(1, Settings.clampRateLimit(0))
        assertEquals(1, Settings.clampRateLimit(2))
        assertEquals(5, Settings.clampRateLimit(4))
        assertEquals(5, Settings.clampRateLimit(5))
        assertEquals(5, Settings.clampRateLimit(9))
        assertEquals(15, Settings.clampRateLimit(1000))
    }

    @Test
    fun `silent burst count is clamped to 2 through 20`() {
        assertEquals(2, Settings.clampSilentCount(0))
        assertEquals(2, Settings.clampSilentCount(-4))
        assertEquals(7, Settings.clampSilentCount(7))
        assertEquals(20, Settings.clampSilentCount(21))
    }

    @Test
    fun `silent window snaps to a documented choice`() {
        assertEquals(5, Settings.clampSilentWindow(1))
        assertEquals(15, Settings.clampSilentWindow(16))
        assertEquals(30, Settings.clampSilentWindow(31))
        assertEquals(60, Settings.clampSilentWindow(59))
        assertEquals(60, Settings.clampSilentWindow(600))
    }

    @Test
    fun `SUPL grace honours the G-04 floor and the catalogue ceiling`() {
        assertEquals(5, Settings.clampSuplGrace(0))
        assertEquals(5, Settings.clampSuplGrace(4))
        assertEquals(5, Settings.clampSuplGrace(5))
        assertEquals(60, Settings.clampSuplGrace(60))
        assertEquals(60, Settings.clampSuplGrace(61))
        assertEquals(30, Settings.clampSuplGrace(30))
    }

    @Test
    fun `severity override wins over the class default`() {
        val s = Settings(severityOverrides = mapOf(ClassId.SILENT to Severity.SUSPICIOUS))
        assertEquals(Severity.SUSPICIOUS, s.severityFor(ClassId.SILENT, Severity.NOTABLE))
        assertEquals(Severity.INFO, s.severityFor(ClassId.MWI, Severity.INFO))
    }

    @Test
    fun `debug logging is active only inside its window`() {
        val s = Settings(debugLogging = true, debugLoggingUntil = 1_000)
        assertTrue(s.debugLoggingActive(999))
        assertFalse(s.debugLoggingActive(1_000))
        assertFalse(Settings(debugLogging = false, debugLoggingUntil = 1_000).debugLoggingActive(0))
    }

    @Test
    fun `retention maps to days and forever`() {
        assertEquals(30, Retention.DAYS_30.days)
        assertEquals(90, Retention.DAYS_90.days)
        assertEquals(365, Retention.DAYS_365.days)
        assertEquals(null, Retention.FOREVER.days)
        assertEquals(Retention.DAYS_90, Retention.from(null))
        assertEquals(Retention.FOREVER, Retention.from("FOREVER"))
    }

    @Test
    fun `lock timeout maps to millis`() {
        assertEquals(0L, LockTimeout.IMMEDIATELY.millis)
        assertEquals(60_000L, LockTimeout.ONE_MIN.millis)
        assertEquals(300_000L, LockTimeout.FIVE_MIN.millis)
        assertEquals(900_000L, LockTimeout.FIFTEEN_MIN.millis)
        assertEquals(LockTimeout.FIVE_MIN, LockTimeout.from("garbage"))
    }

    @Test
    fun `enum wire names round-trip`() {
        UiMode.entries.forEach { assertEquals(it, UiMode.from(it.wire)) }
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.from(it.wire)) }
        DigestMode.entries.forEach { assertEquals(it, DigestMode.from(it.wire)) }
        // U7: a stored DAILY (removed) and a missing value read as WEEKLY.
        assertEquals(DigestMode.WEEKLY, DigestMode.from("DAILY"))
        assertEquals(DigestMode.WEEKLY, DigestMode.from(null))
    }
}
