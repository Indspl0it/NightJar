package dev.nightjar.app.feature.messages

import dev.nightjar.app.data.model.FilterSpec
import dev.nightjar.app.data.model.MessageListItem
import dev.nightjar.app.domain.text.ListTitle
import dev.nightjar.app.domain.text.MessageReason
import dev.nightjar.app.domain.text.Strings
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.app.domain.text.isUnreadablePushTitle
import dev.nightjar.app.domain.text.simDisplay
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity

// The pure logic of a Messages list row. Out of MessagesLogic.kt to keep each
// file under the detekt function limit.

/**
 * The title of a Messages list row: the short type name ([ListTitle], max 24 characters) plus "×N" when
 * repeats were merged into it. Home and Detail keep the full title ([rowTitle]).
 */
fun listRowTitle(
    row: MessageListItem,
    strings: Strings,
): String {
    val base = ListTitle.title(row.classId, row.title, strings)
    return if (row.repeatCount > 1) "$base ×${row.repeatCount}" else base
}

/** The "why is this flagged" line of a feed row or Home card; null for normal rows. */
fun rowReason(
    row: MessageListItem,
    strings: Strings,
): String? = rowReasonKey(row, strings)?.let { strings.get(it) }

private fun rowReasonKey(
    row: MessageListItem,
    strings: Strings,
): TextKey? =
    MessageReason.key(
        row.classId,
        row.severity,
        row.direction,
        row.fate,
        isUnreadablePushTitle(row.classId, row.title, strings),
        titleKey = if (row.classId == ClassId.SMART) MessageReason.smartTitleKey(row.title, strings) else null,
    )

/**
 * The description of a Messages list row (max 2 lines): the detail that the short title drops, the app that
 * sent or added it, then the reason line. A delivery report keeps its reason line only: its title already has the
 * result. Null when there is nothing to say.
 */
fun rowDescription(
    row: MessageListItem,
    strings: Strings,
): String? =
    if (isDeliveryReport(row)) {
        rowReason(row, strings)
    } else {
        ListTitle.description(row.classId, row.title, rowReasonKey(row, strings), strings)
    }

/** The SIM part of line 2 of a list row: the slot only ("SIM 2"). Detail keeps the operator name. */
fun simSlotLine(
    row: MessageListItem,
    strings: Strings,
): String = strings.simDisplay(row.slot, carrierName = null)

/**
 * The count line under the chips: "[messages] messages · [filters] filters on". The filter part and the
 * Clear button show only when [filters] is above 0.
 */
data class CountLine(
    val messages: Int,
    val filters: Int,
) {
    val canClear: Boolean get() = filters > 0
}

/** The count line for [total] rows under [spec]; null while the total is not known. */
fun countLine(
    total: Int?,
    spec: FilterSpec,
): CountLine? = total?.let { CountLine(it, activeFilterCount(spec)) }

/** A message that NightJar holds until the user releases or blocks it. A blocked message is not held. */
fun isHeld(row: MessageListItem): Boolean = row.isQuarantined && row.fate != Fate.BLOCKED

/**
 * "Waiting for you" shows above the list in the unfiltered view when messages are on hold. [heldCount] is the
 * number of the tab badge. In a filtered view the held rows stay in their day group, with the same buttons.
 */
fun showsWaitingSection(
    spec: FilterSpec,
    heldCount: Int,
): Boolean = spec.isDefault && heldCount > 0

/** The fate word of the status line ("On hold", "Blocked", "Delivered"). */
enum class RowFate { ON_HOLD, BLOCKED, RELEASED, DELIVERED, NOT_DELIVERED, WAITING, SENT, NOT_SENT }

/** The fate word of a row; null when NightJar does not know what happened to it, and for a delivery report. */
fun rowFate(row: MessageListItem): RowFate? =
    when {
        isDeliveryReport(row) -> null
        isHeld(row) -> RowFate.ON_HOLD
        else -> FATE_WORDS[row.fate]
    }

private val FATE_WORDS: Map<Fate, RowFate> =
    buildMap {
        listOf(Fate.BLOCKED, Fate.BLOCKLIST).forEach { put(it, RowFate.BLOCKED) }
        put(Fate.QUARANTINED, RowFate.ON_HOLD)
        put(Fate.RELEASED, RowFate.RELEASED)
        listOf(
            Fate.SHOWN,
            Fate.APP,
            Fate.VVM,
            Fate.CARRIER,
            Fate.MISSED_CALL,
            Fate.SYSTEM,
            Fate.SIM,
            Fate.OEM_REDIRECTED,
            Fate.ACCEPTED,
        ).forEach { put(it, RowFate.DELIVERED) }
        listOf(
            Fate.NO_RECEIVER,
            Fate.OEM_DROPPED,
            Fate.NACK_STORAGE,
            Fate.REJECTED,
            Fate.NOT_DISPATCHED,
            Fate.PHONE_STOPPED,
            Fate.UNLOCK_EXPIRED,
        ).forEach { put(it, RowFate.NOT_DELIVERED) }
        listOf(Fate.INCOMPLETE, Fate.PENDING_UNLOCK).forEach { put(it, RowFate.WAITING) }
        listOf(Fate.SENT, Fate.SEND_UNCONFIRMED).forEach { put(it, RowFate.SENT) }
        listOf(Fate.SEND_FAILED, Fate.NOT_SENT).forEach { put(it, RowFate.NOT_SENT) }
    }

/**
 * The parts of the status line, always in this order: fate word, severity (none for Normal), "Test" for a
 * row with the legacy debug flag. Example: "Blocked · High · Test".
 */
data class RowStatus(
    val fate: RowFate?,
    val severity: Severity?,
    val test: Boolean,
) {
    val isEmpty: Boolean get() = fate == null && severity == null && !test
}

fun rowStatus(row: MessageListItem): RowStatus =
    RowStatus(
        fate = rowFate(row),
        severity = row.severity.takeIf { it != Severity.INFO },
        test = row.debugInjected,
    )

/**
 * True when the row is not reviewed. Every incoming row has a review state, a blocked row too. A row that is
 * not reviewed has a bold title and an accent dot. A reviewed row is dimmed.
 *
 * A sent message and a delivery report show as reviewed from the start. The user
 * made the send, so the dot only adds noise.
 */
fun needsReview(row: MessageListItem): Boolean =
    !row.isReviewed && row.direction != Direction.OUT && !isDeliveryReport(row)

/** The type icon is red only for a High row. Every other icon is muted. */
fun isHighRisk(row: MessageListItem): Boolean = row.severity == Severity.HIGH

/** The position of row [index] in a card of [size] rows, for its round corners and its divider. */
fun positionInGroup(
    index: Int,
    size: Int,
): GroupPosition =
    when {
        size <= 1 -> GroupPosition.ONLY
        index == 0 -> GroupPosition.FIRST
        index == size - 1 -> GroupPosition.LAST
        else -> GroupPosition.MIDDLE
    }
