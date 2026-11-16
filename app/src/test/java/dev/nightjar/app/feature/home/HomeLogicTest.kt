package dev.nightjar.app.feature.home

import dev.nightjar.app.data.model.ClassCount
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneOffset

class HomeLogicTest {
    private fun report(
        state: ProtectionState,
        paused: Boolean = false,
    ) = ProtectionReport(
        state = state,
        reason = ProtectionReason.NONE,
        blockingPaused = paused,
        firewallAvailable = state == ProtectionState.PROTECTED,
        firewallEnabled = state == ProtectionState.PROTECTED,
    )

    private fun row(
        classId: ClassId,
        id: Long = 1,
        quarantined: Boolean = false,
        severity: Severity = Severity.SUSPICIOUS,
    ) = MessageListItem(
        id = id,
        eventId = "e$id",
        receivedAtWall = id,
        classId = classId,
        severity = severity,
        title = "t",
        originator = "+10000004821",
        textPreview = "",
        fate = Fate.SYSTEM,
        verdict = Verdict.ALLOW,
        isQuarantined = quarantined,
        isReviewed = false,
        isStarred = false,
        direction = Direction.IN,
        subId = 1,
        slot = 0,
        partsHave = 1,
        partsTotal = 1,
        consumerPackage = null,
        consumerPackageLabel = null,
        isReplay = false,
    )

    @Test
    fun `hero severity follows the protection state`() {
        assertEquals(Severity.INFO, heroSeverity(report(ProtectionState.PROTECTED)))
        assertEquals(Severity.NOTABLE, heroSeverity(report(ProtectionState.MONITOR_ONLY)))
        assertEquals(Severity.SUSPICIOUS, heroSeverity(report(ProtectionState.SAFE_MODE)))
        assertEquals(Severity.SUSPICIOUS, heroSeverity(report(ProtectionState.NOT_ACTIVE)))
        assertEquals(Severity.SUSPICIOUS, heroSeverity(report(ProtectionState.UNSUPPORTED)))
    }

    @Test
    fun `Suspicious and High rows show the Suspicious tone`() {
        assertTrue(isSuspiciousRow(row(ClassId.SILENT, severity = Severity.SUSPICIOUS)))
        assertTrue(isSuspiciousRow(row(ClassId.SILENT, severity = Severity.HIGH)))
        assertFalse(isSuspiciousRow(row(ClassId.SILENT, severity = Severity.NOTABLE)))
    }

    @Test
    fun `attention tag keeps High as High, not Suspicious`() {
        assertEquals(Severity.HIGH, attentionTagSeverity(row(ClassId.SILENT, severity = Severity.HIGH)))
        assertEquals(Severity.SUSPICIOUS, attentionTagSeverity(row(ClassId.SILENT, severity = Severity.SUSPICIOUS)))
        assertNull(attentionTagSeverity(row(ClassId.SILENT, quarantined = true, severity = Severity.NOTABLE)))
    }

    @Test
    fun `today of the window is the last day`() {
        val days =
            listOf(
                WeekDay(LocalDate.parse("2026-11-04"), WeekCounts()),
                WeekDay(LocalDate.parse("2026-11-05"), WeekCounts()),
            )
        assertEquals(LocalDate.parse("2026-11-05"), windowToday(weekDayBars(days, ZoneOffset.UTC)))
        assertNull(windowToday(emptyList()))
    }

    @Test
    fun `by type keeps only positive counts sorted descending`() {
        val rows = byTypeRows(listOf(ClassCount(ClassId.SILENT, 2), ClassCount(ClassId.MWI, 9), ClassCount(ClassId.CB, 0)))
        assertEquals(listOf(ClassId.MWI, ClassId.SILENT), rows.map { it.classId })
    }

    @Test
    fun `day filter covers the whole local day`() {
        val spec = dayFilter(LocalDate.parse("2026-11-05"), ZoneOffset.UTC)
        assertEquals(1_793_836_800_000L, spec.fromWall)
        assertEquals(1_793_923_200_000L, spec.toWall)
        assertNotNull(spec.fromWall)
    }

    @Test
    fun `week day bars keep the order, the counts and the day filter`() {
        val days =
            listOf(
                WeekDay(LocalDate.parse("2026-11-04"), WeekCounts(hidden = 4, suspicious = 1, onHold = 2, blocked = 3)),
                WeekDay(LocalDate.parse("2026-11-05"), WeekCounts()),
            )
        val bars = weekDayBars(days, ZoneOffset.UTC)
        assertEquals(listOf("2026-11-04", "2026-11-05"), bars.map { it.label })
        assertEquals(4, bars[0].count)
        assertEquals(0, bars[1].count)
        assertEquals(1_793_836_800_000L, bars[1].filter.fromWall)
        assertEquals(1_793_923_200_000L, bars[1].filter.toWall)
    }
}
