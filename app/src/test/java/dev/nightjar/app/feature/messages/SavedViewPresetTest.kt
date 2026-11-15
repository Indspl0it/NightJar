package dev.nightjar.app.feature.messages

import dev.nightjar.app.data.model.FilterSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A saved view stores absolute bounds and, since APPDATA-R49, the preset name. The preset is re-resolved when the
 * view is applied; a view without the name is recognised by its span.
 */
class SavedViewPresetTest {
    private val zone = ZoneOffset.UTC
    private val savedOn = LocalDate.parse("2026-11-05")

    @Test
    fun `a last-7-days view is re-resolved against the day it is applied`() {
        val (from, to) = resolvePreset(DatePreset.LAST_7_DAYS, savedOn, zone)
        val saved = FilterSpec(fromWall = from, toWall = to)

        val applied = LocalDate.parse("2026-12-01")
        val (resolved, preset) = resolveSavedRange(saved, applied, zone)

        assertEquals(DatePreset.LAST_7_DAYS, preset)
        assertEquals(resolvePreset(DatePreset.LAST_7_DAYS, applied, zone), resolved.fromWall to resolved.toWall)
        assertNotEquals(saved.toWall, resolved.toWall, "the frozen window must move with the current day")
    }

    @Test
    fun `every preset becomes an absolute span of whole days`() {
        val expected = mapOf(DatePreset.TODAY to 1L, DatePreset.LAST_7_DAYS to 7L, DatePreset.LAST_30_DAYS to 30L)
        expected.forEach { (preset, days) ->
            val (from, to) = resolvePreset(preset, savedOn, zone)
            assertEquals(days * 86_400_000L, to!! - from!!, "span of $preset")
            assertEquals(preset, detectDatePreset(from, to))
        }
    }

    @Test
    fun `any-date views keep no bounds`() {
        assertEquals(null to null, resolvePreset(DatePreset.ANY, savedOn, zone))
        assertEquals(DatePreset.ANY, detectDatePreset(null, null))
    }

    @Test
    fun `the stored preset name decides before the span`() {
        // A 28-hour range that span detection reads as a custom range; the stored name says "Last 7 days".
        val spec = FilterSpec(fromWall = 1_700_000_000_000L, toWall = 1_700_100_800_000L, rangePreset = DatePreset.LAST_7_DAYS.name)
        val applied = LocalDate.parse("2026-12-01")
        val (resolved, preset) = resolveSavedRange(spec, applied, zone)
        assertEquals(DatePreset.LAST_7_DAYS, preset)
        assertEquals(resolvePreset(DatePreset.LAST_7_DAYS, applied, zone), resolved.fromWall to resolved.toWall)
        assertNull(resolved.rangePreset)
    }

    @Test
    fun `a custom range is not mistaken for a preset`() {
        val spec = FilterSpec(fromWall = 1_700_000_000_000L, toWall = 1_700_100_000_000L)
        val (resolved, preset) = resolveSavedRange(spec, savedOn, zone)
        assertEquals(DatePreset.ANY, preset)
        assertEquals(spec, resolved)
    }

    @Test
    fun `the sheet chip shows a preset only for its exact range on the current day`() {
        val (from, to) = resolvePreset(DatePreset.LAST_7_DAYS, savedOn, zone)
        assertEquals(DatePreset.LAST_7_DAYS, shownPreset(from, to, savedOn, zone))
        assertEquals(DatePreset.ANY, shownPreset(null, null, savedOn, zone))
        // A chart bar of an earlier day is one day long, but it is not "Today": no chip is selected.
        val (dayFrom, dayTo) = resolvePreset(DatePreset.TODAY, savedOn.minusDays(2), zone)
        assertNull(shownPreset(dayFrom, dayTo, savedOn, zone))
    }

    @Test
    fun `today stays a one-day window around the applied day`() {
        val (from, to) = resolvePreset(DatePreset.TODAY, savedOn, zone)
        assertEquals(savedOn.atStartOfDay(zone).toInstant().toEpochMilli(), from)
        assertEquals(
            savedOn
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli(),
            to,
        )
        assertTrue(to!! > from!!)
    }
}
