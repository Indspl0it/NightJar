package dev.nightjar.app.domain.notify

import dev.nightjar.app.core.settings.DigestMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The digest posts at most once a week, never when OFF, never with nothing to report. */
class DigestScheduleTest {
    private val week = DigestCounts(hidden = 4, suspicious = 1, held = 1, blocked = 2)
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `off never posts`() {
        assertFalse(DigestSchedule.shouldPost(DigestMode.OFF, week, null, now))
        assertFalse(DigestSchedule.shouldPost(DigestMode.OFF, week, now - 30 * day, now))
    }

    @Test
    fun `weekly posts when a week has passed`() {
        assertTrue(DigestSchedule.shouldPost(DigestMode.WEEKLY, week, null, now))
        assertTrue(DigestSchedule.shouldPost(DigestMode.WEEKLY, week, now - 7 * day, now))
        assertTrue(DigestSchedule.shouldPost(DigestMode.WEEKLY, week, now - 30 * day, now))
    }

    @Test
    fun `weekly never posts twice in one week`() {
        assertFalse(DigestSchedule.shouldPost(DigestMode.WEEKLY, week, now - 1 * day, now))
        assertFalse(DigestSchedule.shouldPost(DigestMode.WEEKLY, week, now - 6 * day, now))
        assertFalse(DigestSchedule.shouldPost(DigestMode.WEEKLY, week, now, now))
    }

    @Test
    fun `a job that starts a little early still posts`() {
        val almostAWeek = 7 * day - DigestSchedule.SLACK_MS
        assertTrue(DigestSchedule.shouldPost(DigestMode.WEEKLY, week, now - almostAWeek, now))
        assertFalse(DigestSchedule.shouldPost(DigestMode.WEEKLY, week, now - almostAWeek + 1, now))
    }

    @Test
    fun `an empty week posts nothing`() {
        assertFalse(DigestSchedule.shouldPost(DigestMode.WEEKLY, DigestCounts(0, 0, 0, 0), null, now))
    }

    @Test
    fun `a clock that moved back does not stop the digest`() {
        assertTrue(DigestSchedule.shouldPost(DigestMode.WEEKLY, week, now + 100 * day, now))
    }

    @Test
    fun `the digest has a weekly period and leaves the shade after a day`() {
        assertEquals(7L, DigestSchedule.PERIOD_DAYS)
        assertEquals(7 * day, DigestSchedule.PERIOD_MS)
        assertEquals(day, DigestSchedule.TIMEOUT_MS)
    }

    @Test
    fun `there is no daily digest`() {
        assertEquals(listOf(DigestMode.OFF, DigestMode.WEEKLY), DigestMode.entries.toList())
    }
}
