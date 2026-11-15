package dev.nightjar.app.feature.home

import dev.nightjar.app.data.model.FeedChip
import dev.nightjar.app.data.model.MessageListItem
import dev.nightjar.app.data.repository.WeekCounts
import dev.nightjar.app.data.repository.WeekDay
import dev.nightjar.app.domain.protection.ProtectionReason
import dev.nightjar.app.domain.protection.ProtectionReport
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.ProtectionState
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** What a tap on Home opens, the status card state and the quick actions. */
class HomeTapMappingTest {
    private val zone = ZoneOffset.UTC

    private val days =
        listOf("2026-11-07", "2026-11-08", "2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12", "2026-11-13")
            .mapIndexed { index, date ->
                WeekDay(LocalDate.parse(date), WeekCounts(hidden = index, suspicious = 1, onHold = 2, blocked = 3))
            }.let { weekDayBars(it, zone) }

    private fun report(
        state: ProtectionState,
        paused: Boolean = false,
        available: Boolean = true,
        enabled: Boolean = true,
    ) = ProtectionReport(
        state = state,
        reason = ProtectionReason.NONE,
        blockingPaused = paused,
        firewallAvailable = available,
        firewallEnabled = enabled,
    )

    private fun row(
        quarantined: Boolean = false,
        fate: Fate = Fate.SYSTEM,
        direction: Direction = Direction.IN,
        classId: ClassId = ClassId.SILENT,
    ) = MessageListItem(
        id = 1,
        eventId = "e1",
        receivedAtWall = 1,
        classId = classId,
        severity = Severity.SUSPICIOUS,
        title = "t",
        originator = "+10000004821",
        textPreview = "",
        fate = fate,
        verdict = Verdict.ALLOW,
        isQuarantined = quarantined,
        isReviewed = false,
        isStarred = false,
        direction = direction,
        subId = 1,
        slot = 0,
        partsHave = 1,
        partsTotal = 1,
        consumerPackage = null,
        consumerPackageLabel = null,
        isReplay = false,
    )

    @Test
    fun `the week window runs from the first day start to the last day end`() {
        val window = weekWindow(days)!!
        assertEquals(days.first().filter.fromWall, window.first)
        assertEquals(days.last().filter.toWall, window.second)
        assertNull(weekWindow(emptyList()))
    }

    @Test
    fun `hidden tile opens the whole week without delivery reports`() {
        val spec = tileFilter(WeekTile.HIDDEN, days)
        assertEquals(FeedChip.ALL, spec.chip)
        assertEquals(weekWindow(days)!!.first, spec.fromWall)
        assertEquals(weekWindow(days)!!.second, spec.toWall)
        assertFalse(spec.showDeliveryReports)
    }

    @Test
    fun `suspicious tile opens the suspicious chip for the week`() {
        val spec = tileFilter(WeekTile.SUSPICIOUS, days)
        assertEquals(FeedChip.SUSPICIOUS, spec.chip)
        assertEquals(weekWindow(days)!!.first, spec.fromWall)
    }

    @Test
    fun `on hold tile opens the on hold chip for the week`() {
        val spec = tileFilter(WeekTile.ON_HOLD, days)
        assertEquals(FeedChip.QUARANTINED, spec.chip)
        assertEquals(weekWindow(days)!!.second, spec.toWall)
    }

    @Test
    fun `blocked tile opens the blocked fate for the week`() {
        val spec = tileFilter(WeekTile.BLOCKED, days)
        assertEquals(setOf(Fate.BLOCKED), spec.fates)
        assertEquals(FeedChip.ALL, spec.chip)
        assertEquals(weekWindow(days)!!.first, spec.fromWall)
        assertEquals(weekWindow(days)!!.second, spec.toWall)
        assertFalse(spec.showDeliveryReports)
        assertTrue(tileFilter(WeekTile.HIDDEN, days).fates.isEmpty())
    }

    @Test
    fun `view all opens the held or suspicious unreviewed filter`() {
        assertTrue(ATTENTION_FILTER.attentionOnly)
        assertEquals(FeedChip.ALL, ATTENTION_FILTER.chip)
        assertTrue(ATTENTION_FILTER.fates.isEmpty())
    }

    @Test
    fun `tiles without days open with no date limit`() {
        val spec = tileFilter(WeekTile.SUSPICIOUS, emptyList())
        assertNull(spec.fromWall)
        assertNull(spec.toWall)
    }

    @Test
    fun `a chart bar opens its own local day`() {
        val spec = barFilter(days, 5)!!
        assertEquals(1_794_441_600_000L, spec.fromWall)
        assertEquals(1_794_528_000_000L, spec.toWall)
        assertEquals(days[5].filter, spec)
        assertNull(barFilter(days, 7))
        assertNull(barFilter(days, -1))
    }

    @Test
    fun `tile counts come from the week counters`() {
        val week = WeekCounts(hidden = 47, suspicious = 3, onHold = 2, blocked = 5)
        assertEquals(listOf(47, 3, 2, 5), WeekTile.entries.map { tileCount(it, week) })
    }

    @Test
    fun `status card state follows the protection report`() {
        assertEquals(HomeStatus.PROTECTED, homeStatus(report(ProtectionState.PROTECTED)))
        assertEquals(HomeStatus.BLOCKING_PAUSED, homeStatus(report(ProtectionState.PROTECTED, paused = true)))
        assertEquals(HomeStatus.MONITOR_ONLY, homeStatus(report(ProtectionState.MONITOR_ONLY)))
        assertEquals(HomeStatus.NOT_ACTIVE, homeStatus(report(ProtectionState.NOT_ACTIVE)))
        assertEquals(HomeStatus.SAFE_MODE, homeStatus(report(ProtectionState.SAFE_MODE)))
        assertEquals(HomeStatus.UNSUPPORTED, homeStatus(report(ProtectionState.UNSUPPORTED)))
    }

    @Test
    fun `the status card shows blocking off when the user turned off the Blocking switch`() {
        // Regression: the card showed "Protected" with the switch off.
        assertEquals(HomeStatus.MONITOR_ONLY, homeStatus(report(ProtectionState.PROTECTED, enabled = false)))
        assertNull(statusCardAction(HomeStatus.MONITOR_ONLY))
        // Safe mode has its own text and button.
        assertEquals(
            HomeStatus.BLOCKING_PAUSED,
            homeStatus(report(ProtectionState.PROTECTED, paused = true, enabled = false)),
        )
        // A build where blocking cannot work is not a user choice: the card stays Protected (Health shows the detail).
        assertEquals(
            HomeStatus.PROTECTED,
            homeStatus(report(ProtectionState.PROTECTED, available = false, enabled = false)),
        )
    }

    @Test
    fun `the status card shows one fix button only when the user can fix the state from Home`() {
        assertEquals(HomeCardAction.OPEN_FRAMEWORK, statusCardAction(HomeStatus.NOT_ACTIVE))
        assertEquals(HomeCardAction.TURN_OFF_SAFE_MODE, statusCardAction(HomeStatus.BLOCKING_PAUSED))
        assertNull(statusCardAction(HomeStatus.PROTECTED))
        assertNull(statusCardAction(HomeStatus.UNSUPPORTED))
        assertNull(statusCardAction(HomeStatus.MONITOR_ONLY))
        assertNull(statusCardAction(HomeStatus.SAFE_MODE))
    }

    @Test
    fun `a held message has release and block similar`() {
        val actions = attentionActions(row(quarantined = true, fate = Fate.QUARANTINED, classId = ClassId.WAP_CP))
        assertTrue(actions.release)
        assertTrue(actions.blockSimilar)
    }

    @Test
    fun `a blocked message has no quick action`() {
        val actions = attentionActions(row(fate = Fate.BLOCKED))
        assertFalse(actions.release)
        assertFalse(actions.blockSimilar)
        assertFalse(actions.any)
    }

    @Test
    fun `an outgoing message has no block similar`() {
        assertFalse(attentionActions(row(direction = Direction.OUT)).blockSimilar)
    }

    @Test
    fun `a type that no hook can stop has no block similar`() {
        listOf(ClassId.VVM, ClassId.MISSED_CALL, ClassId.APP_TOKEN, ClassId.CARRIER, ClassId.RCS_PROV, ClassId.OEM)
            .forEach { assertFalse(attentionActions(row(classId = it)).blockSimilar, "$it") }
        assertTrue(attentionActions(row(classId = ClassId.DATA_PORT)).blockSimilar)
    }

    @Test
    fun `day label is the short day name and the week range uses the first and last day`() {
        assertEquals("Fri", dayShortLabel("2026-11-13", Locale.ENGLISH))
        assertEquals("bad", dayShortLabel("bad", Locale.ENGLISH))
        assertEquals(LocalDate.parse("2026-11-07") to LocalDate.parse("2026-11-13"), weekRange(days))
        assertNull(weekRange(emptyList()))
    }

    @Test
    fun `on day compares the date in the zone and type bar fraction stays in range`() {
        val day = Instant.ofEpochMilli(1_794_441_600_000L).atZone(zone).toLocalDate()
        assertTrue(isOnDay(1_794_441_600_000L + 3_600_000L, day, zone))
        assertFalse(isOnDay(1_794_528_000_000L, day, zone))
        assertEquals(0.5f, typeBarFraction(6, 12))
        assertEquals(0f, typeBarFraction(3, 0))
        assertEquals(1f, typeBarFraction(20, 12))
    }
}
