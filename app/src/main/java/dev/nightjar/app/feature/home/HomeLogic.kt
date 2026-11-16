package dev.nightjar.app.feature.home

import dev.nightjar.app.data.model.ClassCount
import dev.nightjar.app.data.model.FeedChip
import dev.nightjar.app.data.model.FilterSpec
import dev.nightjar.app.data.model.MessageListItem
import dev.nightjar.app.data.repository.WeekCounts
import dev.nightjar.app.data.repository.WeekDay
import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.protection.ProtectionReport
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.ProtectionState
import dev.nightjar.contract.Severity
import dev.nightjar.rules.EnforcementMatrix
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** The colour/icon token the hero uses for a protection state (01). */
fun heroSeverity(report: ProtectionReport): Severity =
    when (report.state) {
        ProtectionState.PROTECTED -> Severity.INFO
        ProtectionState.MONITOR_ONLY -> Severity.NOTABLE
        ProtectionState.SAFE_MODE, ProtectionState.NOT_ACTIVE, ProtectionState.UNSUPPORTED -> Severity.SUSPICIOUS
    }

/** "Needs attention" shows at most three rows (03). */
const val ATTENTION_LIMIT: Int = 3

/** True when an attention row shows a severity tone and tag: Suspicious and High both count. */
fun isSuspiciousRow(row: MessageListItem): Boolean = row.severity >= Severity.SUSPICIOUS

/**
 * The severity that an attention row shows in its tag and tone: Suspicious or High, as stored. A High row says "High",
 * not "Suspicious", the same as the Messages list. Null for a lower severity (the row then shows only "On hold").
 */
fun attentionTagSeverity(row: MessageListItem): Severity? = row.severity.takeIf { isSuspiciousRow(row) }

/** Filter behind the "Needs attention" rows and "View all": held or Suspicious messages that are not reviewed. */
val ATTENTION_FILTER: FilterSpec = FilterSpec(attentionOnly = true)

/** Filter behind a "By type" row (05). */
fun classFilter(classId: ClassId): FilterSpec = FilterSpec(classIds = setOf(classId))

/** Filter behind one chart bar (04): the whole local day of [day]. */
fun dayFilter(
    day: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): FilterSpec {
    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val end =
        day
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    // The day counts leave out delivery reports, so the list they open leaves them out too.
    return FilterSpec(fromWall = start, toWall = end, showDeliveryReports = false)
}

/** A chart bar: the `yyyy-MM-dd` day, the hidden count of the day and the filter it opens. */
data class DayBar(
    val label: String,
    val count: Int,
    val filter: FilterSpec,
)

/** Builds one bar per day of the week window, oldest first. Each bar opens the whole local day. */
fun weekDayBars(
    days: List<WeekDay>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<DayBar> =
    days.map { day ->
        DayBar(
            label = day.date.toString(),
            count = day.counts.hidden,
            filter = dayFilter(day.date, zone),
        )
    }

/** By-type rows sorted by count, only classes with a count (05). */
fun byTypeRows(classes: List<ClassCount>): List<ClassCount> =
    classes.filter { it.count > 0 }.sortedByDescending { it.count }

/** The state the status card shows. [BLOCKING_PAUSED] is `PROTECTED` while the user keeps safe mode on. */
enum class HomeStatus { PROTECTED, BLOCKING_PAUSED, MONITOR_ONLY, NOT_ACTIVE, SAFE_MODE, UNSUPPORTED }

fun homeStatus(report: ProtectionReport): HomeStatus =
    when (report.state) {
        ProtectionState.PROTECTED -> {
            when {
                report.blockingPaused -> HomeStatus.BLOCKING_PAUSED

                // The Blocking switch is off. Amber card, no button: a tap opens Protection status.
                report.blockingOff -> HomeStatus.MONITOR_ONLY

                else -> HomeStatus.PROTECTED
            }
        }

        ProtectionState.MONITOR_ONLY -> {
            HomeStatus.MONITOR_ONLY
        }

        ProtectionState.SAFE_MODE -> {
            HomeStatus.SAFE_MODE
        }

        ProtectionState.NOT_ACTIVE -> {
            HomeStatus.NOT_ACTIVE
        }

        ProtectionState.UNSUPPORTED -> {
            HomeStatus.UNSUPPORTED
        }
    }

/**
 * The one button of the status card (design 1). The card itself opens Protection status, so a button shows
 * only for a state where the user can do the fix from Home.
 */
enum class HomeCardAction {
    /** The module is not active: open the Vector or LSPosed manager app, as the setup screen does. */
    OPEN_FRAMEWORK,

    /** The user paused blocking with safe mode: turn safe mode off. */
    TURN_OFF_SAFE_MODE,
}

/**
 * The button of the status card for [status], or null for no button. UNSUPPORTED has no fix in the app, and the engine
 * never gives SAFE_MODE. MONITOR_ONLY (the Blocking switch is off) has no button: the card opens the
 * Protection tab.
 */
fun statusCardAction(status: HomeStatus): HomeCardAction? =
    when (status) {
        HomeStatus.NOT_ACTIVE -> HomeCardAction.OPEN_FRAMEWORK
        HomeStatus.BLOCKING_PAUSED -> HomeCardAction.TURN_OFF_SAFE_MODE
        HomeStatus.PROTECTED, HomeStatus.MONITOR_ONLY, HomeStatus.SAFE_MODE, HomeStatus.UNSUPPORTED -> null
    }

/** The four columns of the "This week" strip, in display order. */
enum class WeekTile { HIDDEN, SUSPICIOUS, ON_HOLD, BLOCKED }

/** The start (inclusive) and the end (exclusive) of the week the [days] cover, or null when there are no days. */
fun weekWindow(days: List<DayBar>): Pair<Long, Long>? {
    val from = days.firstOrNull()?.filter?.fromWall ?: return null
    val to = days.lastOrNull()?.filter?.toWall ?: return null
    return from to to
}

/**
 * The filter that a tap on a strip column opens in Messages. Every filter keeps the week of the chart, so the list has
 * the same rows as the number. The chip is `SUSPICIOUS` or `QUARANTINED` for those two columns.
 * The Blocked column uses the fate filter `FT-BLOCKED`, as the counter does.
 */
fun tileFilter(
    tile: WeekTile,
    days: List<DayBar>,
): FilterSpec {
    val window = weekWindow(days)
    val week = FilterSpec(fromWall = window?.first, toWall = window?.second, showDeliveryReports = false)
    return when (tile) {
        WeekTile.HIDDEN -> week
        WeekTile.BLOCKED -> week.copy(fates = setOf(Fate.BLOCKED))
        WeekTile.SUSPICIOUS -> week.copy(chip = FeedChip.SUSPICIOUS)
        WeekTile.ON_HOLD -> week.copy(chip = FeedChip.QUARANTINED)
    }
}

/** The filter that a tap on chart bar [index] opens, or null when the bar does not exist. */
fun barFilter(
    days: List<DayBar>,
    index: Int,
): FilterSpec? = days.getOrNull(index)?.filter

/** The number that a strip column shows. */
fun tileCount(
    tile: WeekTile,
    week: WeekCounts,
): Int =
    when (tile) {
        WeekTile.HIDDEN -> week.hidden
        WeekTile.SUSPICIOUS -> week.suspicious
        WeekTile.ON_HOLD -> week.onHold
        WeekTile.BLOCKED -> week.blocked
    }

/** The quick actions of a "Needs attention" row. A row with no action is a plain row that opens the message. */
data class AttentionActions(
    val release: Boolean,
    val blockSimilar: Boolean,
) {
    val any: Boolean get() = release || blockSimilar
}

/**
 * Release needs a held message that its type lets the user release. Block similar needs an incoming message that is not
 * blocked yet, of a type that a rule can block.
 */
fun attentionActions(row: MessageListItem): AttentionActions =
    AttentionActions(
        release = row.isQuarantined && ClassRegistry.of(row.classId).releasable,
        blockSimilar =
            row.direction == Direction.IN &&
                row.fate != Fate.BLOCKED &&
                !EnforcementMatrix.noOwnEnforcementPoint(row.classId),
    )

/** The short day name under a chart bar. [iso] is the `yyyy-MM-dd` label of the [DayBar]. */
fun dayShortLabel(
    iso: String,
    locale: Locale,
): String = runCatching { LocalDate.parse(iso).dayOfWeek.getDisplayName(TextStyle.SHORT, locale) }.getOrDefault(iso)

/** The first and the last day of the week, for the header text of the strip. */
fun weekRange(days: List<DayBar>): Pair<LocalDate, LocalDate>? {
    val first = days.firstOrNull()?.label?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val last = days.lastOrNull()?.label?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    return first to last
}

/**
 * The last day of the week window, which is "today" for the ViewModel. The screen keys date texts on it, so they move
 * to the new day at midnight together with the counts. Null while there are no days.
 */
fun windowToday(days: List<DayBar>): LocalDate? = weekRange(days)?.second

/** True when the instant [wallMs] falls on the local [day] in [zone]. */
fun isOnDay(
    wallMs: Long,
    day: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean = Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate() == day

/** The width of the bar of a "By type" row, from 0 to 1, relative to the biggest count. */
fun typeBarFraction(
    count: Int,
    max: Int,
): Float = if (max <= 0) 0f else (count.coerceAtLeast(0).toFloat() / max).coerceIn(0f, 1f)
