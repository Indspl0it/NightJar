package dev.nightjar.app.feature.messages

import dev.nightjar.app.core.security.NumberDisplay
import dev.nightjar.app.core.security.formatNumber
import dev.nightjar.app.data.model.FeedChip
import dev.nightjar.app.data.model.FilterSpec
import dev.nightjar.app.data.model.MessageListItem
import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.text.Strings
import dev.nightjar.app.domain.text.displayTitle
import dev.nightjar.app.domain.text.simDisplay
import dev.nightjar.app.feature.export.ExportRequest
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The filter chips of the Messages list, in display order. */
val FEED_CHIPS: List<FeedChip> =
    listOf(
        FeedChip.ALL,
        FeedChip.SUSPICIOUS,
        FeedChip.QUARANTINED,
        FeedChip.OUTGOING,
        FeedChip.BROADCASTS,
        FeedChip.SIM1,
        FeedChip.SIM2,
    )

/** Local day of a message, for the day headers (01). */
fun feedDay(
    wallMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): LocalDate = Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate()

/**
 * The header to draw above [current] when [previous] is null or on a different day (01). Returns null when the
 * two rows share a day.
 */
fun dayHeader(
    previous: MessageListItem?,
    current: MessageListItem,
    zone: ZoneId = ZoneId.systemDefault(),
): LocalDate? {
    val day = feedDay(current.receivedAtWall, zone)
    val prior = previous?.let { feedDay(it.receivedAtWall, zone) }
    return if (prior == day) null else day
}

/**
 * The second line of a feed row: who sent it, or, when there is no sender, what is missing.
 *
 * The number is masked here, when the row is drawn, from `privacy.maskNumbers` through [NumberDisplay.forSetting],
 * never when it is stored, so flipping the setting also covers earlier messages. Direction is not
 * repeated: the avatar, title and outgoing chip already carry it.
 */
fun senderLine(
    row: MessageListItem,
    display: NumberDisplay,
    noSender: String,
): String {
    val sender = row.originator ?: row.consumerPackageLabel ?: row.consumerPackage ?: ""
    return formatNumber(sender, display).orEmpty().ifBlank { noSender }
}

/**
 * The number after "To" on a sent row or a delivery report, masked like [senderLine]; null when it is not
 * known, so the row leaves "To …" out. A sent row has the number in [MessageListItem.destination]. A delivery report
 * has the recipient as its originator.
 */
fun sentToNumber(
    row: MessageListItem,
    display: NumberDisplay,
): String? {
    val raw = if (row.direction == Direction.OUT) row.destination ?: row.originator else row.originator
    return raw?.takeIf { it.isNotBlank() }?.let { formatNumber(it, display) }?.takeIf { it.isNotBlank() }
}

/** Line 2 of a sent row: the "To" part and the SIM part, each only when known, joined with " · ". */
fun sentLine(
    toPart: String?,
    sim: String,
): String = listOfNotNull(toPart?.takeIf { it.isNotBlank() }, sim.takeIf { it.isNotBlank() }).joinToString(" · ")

/** True for a data message that an app on this phone sent. Its row names the app on the third line. */
fun isSentDataMessage(row: MessageListItem): Boolean = row.direction == Direction.OUT && row.classId == ClassId.MO_DATA

/** The name of the app that sent a message, the label first, then the package name; null when not known. */
fun sentDataApp(row: MessageListItem): String? =
    row.callingPackageLabel?.takeIf { it.isNotBlank() } ?: row.callingPackage?.takeIf { it.isNotBlank() }

/** The headline of a feed row: the reworded title plus "×N" when repeats were merged into it. */
fun rowTitle(
    row: MessageListItem,
    strings: Strings,
): String {
    val base = displayTitle(row.classId, row.title, strings)
    return if (row.repeatCount > 1) "$base ×${row.repeatCount}" else base
}

/** The small footer of a feed row: operator and SIM slot, only when known. The slot name is a resource (sim_slot_name). */
fun simLine(
    row: MessageListItem,
    strings: Strings,
): String = strings.simDisplay(row.slot, row.carrierName)

/**
 * The Export request for exporting [selection] under [spec] (AC3).
 *
 * A default spec carries no condition, so the filter slot stays empty and Export preselects Everything; an empty
 * selection leaves Selection unavailable instead of exporting zero rows.
 */
fun exportRequestFor(
    selection: Collection<Long>,
    spec: FilterSpec,
): ExportRequest =
    ExportRequest(
        selection = selection.toList(),
        filter = spec.takeIf { !it.isDefault },
    )

/**
 * The search box text after a spec emission.
 *
 * `applySearch` writes the *trimmed* query into the spec after a 300 ms debounce and the spec echoes it back. Writing
 * that echo into the field dropped keystrokes typed during the pause and removed a trailing space, so "two words"
 * could not be typed with a pause. While the field is open the typed text wins for writes made here; a change from a
 * saved view, a Home drill-down or a deep link still replaces it.
 */
fun queryAfterSpecChange(
    current: String,
    echo: String,
    writtenHere: Boolean,
): String = if (writtenHere) current else echo

/** True until every part of a multipart message has arrived (08). */
fun needsPartsLabel(row: MessageListItem): Boolean = row.partsTotal > 1 && row.partsHave < row.partsTotal

/** True when the class offers the "Rule for messages like this" swipe (06). */
fun supportsRuleSwipe(row: MessageListItem): Boolean =
    ClassRegistry.of(row.classId).primary == dev.nightjar.app.domain.PrimaryAction.BLOCK_LIKE_THIS ||
        ClassRegistry.of(row.classId).secondary == dev.nightjar.app.domain.PrimaryAction.BLOCK_LIKE_THIS

/** True when the class offers the "Mute" swipe (silent pings only, -06). */
fun supportsMuteSwipe(row: MessageListItem): Boolean =
    ClassRegistry.of(row.classId).family == dev.nightjar.app.domain.ClassFamily.SILENT

/** The number of narrowing conditions, shown as the filter button badge (05). */
fun activeFilterCount(spec: FilterSpec): Int {
    var count = 0
    if (spec.classIds.isNotEmpty()) count++
    if (spec.severities.isNotEmpty()) count++
    if (spec.direction != null) count++
    if (spec.fromWall != null || spec.toWall != null) count++
    if (spec.subId != null) count++
    if (spec.unreviewedOnly) count++
    if (spec.starredOnly) count++
    if (!spec.search.isNullOrBlank()) count++
    if (!spec.originatorQuery.isNullOrBlank()) count++
    if (spec.port != null) count++
    if (!spec.contentType.isNullOrBlank()) count++
    if (!spec.packageQuery.isNullOrBlank()) count++
    if (!spec.showDeliveryReports) count++
    // Home drill-downs set these; the sheet has no control for them, so the badge is the only sign.
    if (spec.fates.isNotEmpty()) count++
    if (spec.attentionOnly) count++
    return count
}

/** Range of [query] inside [text], ignoring case; null when absent or blank (04 highlight). */
fun highlightRange(
    text: String,
    query: String?,
): IntRange? {
    if (query.isNullOrBlank()) return null
    val index = text.indexOf(query, ignoreCase = true)
    return if (index < 0) null else index until (index + query.length)
}

/** Classes offered in the filter sheet, in registry order. */
fun filterableClasses(): List<ClassId> = ClassRegistry.all().filter { it.feedVisible }.map { it.classId }

// ------------------------------------------------------------------ grouping and entry filters

/** Result of a delivery report as the feed shows it. */
enum class ReportStatus { DELIVERED, PENDING, FAILED }

/** True for a delivery report (an SMS-STATUS-REPORT, `CL-STATUS`). */
fun isDeliveryReport(row: MessageListItem): Boolean = row.classId == ClassId.STATUS || row.deliveryStatus != null

/** A row can be swiped only outside selection mode: a sent message or a delivery report has no Block and no review. */
fun canSwipe(
    row: MessageListItem,
    selecting: Boolean,
): Boolean = !selecting && row.direction == Direction.IN && !isDeliveryReport(row)

/** The result of a delivery report; null for any other row or a status that is not known. */
fun reportStatus(row: MessageListItem): ReportStatus? =
    when (row.deliveryStatus) {
        "DELIVERED" -> ReportStatus.DELIVERED
        "PENDING" -> ReportStatus.PENDING
        "FAILED" -> ReportStatus.FAILED
        else -> null
    }

/** One line of the feed list: a day header, or a message with the delivery reports that belong to it. */
sealed interface FeedEntry {
    /** Stable key for the list. A day key never collides with a row id key. */
    val key: String

    data class Day(
        val day: LocalDate,
    ) : FeedEntry {
        override val key: String get() = "day-$day"
    }

    /**
     * A message row. [reports] are its linked delivery reports, oldest first. [sourceIndex] is the index of [row] in
     * the list that was grouped, so the screen can tell the pager which index it is showing.
     */
    data class Message(
        val row: MessageListItem,
        val reports: List<MessageListItem>,
        val sourceIndex: Int,
    ) : FeedEntry {
        override val key: String get() = "msg-${row.id}"
    }
}

/**
 * Turns the loaded feed rows (newest first) into list entries.
 *
 * - A delivery report whose sent message is in [rows] moves under that message, as a sub-row. It does not get its
 *   own entry.
 * - A delivery report with no link, or whose sent message is not loaded, stays its own row.
 * - A day header goes before the first entry of each local day. The day of a message with reports is the day of the
 *   message, not of its reports.
 * - A row for which [skip] is true gets no entry, but the other rows keep their index in [rows] (the held
 *   rows show in "Waiting for you" instead).
 */
fun buildFeedEntries(
    rows: List<MessageListItem>,
    zone: ZoneId = ZoneId.systemDefault(),
    skip: (MessageListItem) -> Boolean = { false },
): List<FeedEntry> {
    val loaded = rows.mapTo(HashSet()) { it.id }
    val reportsOf =
        rows
            .filter { isDeliveryReport(it) && it.relatedMessageId != null && it.relatedMessageId in loaded }
            .groupBy { it.relatedMessageId!! }
            .mapValues { (_, list) -> list.sortedBy { it.receivedAtWall } }
    val grouped = reportsOf.values.flatMapTo(HashSet()) { list -> list.map { it.id } }
    val entries = ArrayList<FeedEntry>(rows.size + 8)
    var lastDay: LocalDate? = null
    rows.forEachIndexed { index, row ->
        if (row.id in grouped || skip(row)) return@forEachIndexed
        val day = feedDay(row.receivedAtWall, zone)
        if (day != lastDay) {
            entries += FeedEntry.Day(day)
            lastDay = day
        }
        entries += FeedEntry.Message(row, reportsOf[row.id].orEmpty(), index)
    }
    return entries
}

/** Where an entry sits in its day group; the screen rounds the outer corners of the group (7.2: 22 dp). */
enum class GroupPosition { ONLY, FIRST, MIDDLE, LAST }

/** The position of the entry at [index] among the message entries of one day. A day header ends a group. */
fun groupPosition(
    entries: List<FeedEntry>,
    index: Int,
): GroupPosition {
    val first = index == 0 || entries[index - 1] is FeedEntry.Day
    val last = index == entries.lastIndex || entries[index + 1] is FeedEntry.Day
    return when {
        first && last -> GroupPosition.ONLY
        first -> GroupPosition.FIRST
        last -> GroupPosition.LAST
        else -> GroupPosition.MIDDLE
    }
}

/** The four counters of Home. A tap on a tile opens Messages with the matching family. */
enum class EntryFamily(
    val wire: String,
) {
    HIDDEN("hidden"),
    SUSPICIOUS("suspicious"),
    ON_HOLD("on_hold"),
    BLOCKED("blocked"),
    ;

    companion object {
        fun from(wire: String?): EntryFamily? = entries.firstOrNull { it.wire == wire?.lowercase() }
    }
}

/** The whole local day [epochDay] (days since 1970-01-01) as a time range, for the filter. */
fun dayRange(
    epochDay: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): LongRange {
    val day = LocalDate.ofEpochDay(epochDay)
    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val end =
        day
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    return start until end
}

/**
 * The filter for an entry from Home (step 6). [family] picks the counter, [epochDay] limits to one local day;
 * both can be null. Hidden is the default feed (the feed already leaves out visible messages and counters).
 *
 * Blocked is `fates = setOf(Fate.BLOCKED)`.
 */
fun entryFilter(
    family: EntryFamily?,
    epochDay: Long?,
    zone: ZoneId = ZoneId.systemDefault(),
): FilterSpec {
    var spec = FilterSpec()
    when (family) {
        EntryFamily.SUSPICIOUS -> spec = spec.copy(chip = FeedChip.SUSPICIOUS)
        EntryFamily.ON_HOLD -> spec = spec.copy(chip = FeedChip.QUARANTINED)
        EntryFamily.BLOCKED -> spec = spec.copy(fates = setOf(dev.nightjar.contract.Fate.BLOCKED))
        EntryFamily.HIDDEN, null -> Unit
    }
    if (epochDay != null) {
        val range = dayRange(epochDay, zone)
        spec = spec.copy(fromWall = range.first, toWall = range.last + 1)
    }
    // Delivery reports are never part of the Home counters.
    if (family != null || epochDay != null) spec = spec.copy(showDeliveryReports = false)
    return spec
}

/**
 * Reads the entry form of the `filter` argument of the Messages route: a family (`family:blocked`), a day
 * (`day:20400`, epoch day), or both (`family:blocked,day:20400`). Anything else gives null; the ViewModel reads a chip
 * wire name (`quarantined`) itself.
 */
fun parseEntryArgument(
    argument: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): FilterSpec? {
    if (argument.isNullOrBlank()) return null
    var family: EntryFamily? = null
    var day: Long? = null
    for (part in argument.split(',')) {
        val key = part.substringBefore(':', "")
        val value = part.substringAfter(':', "")
        when (key.lowercase()) {
            "family" -> family = EntryFamily.from(value)
            "day" -> day = value.toLongOrNull()
        }
    }
    return if (family == null && day == null) null else entryFilter(family, day, zone)
}
