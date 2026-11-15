package dev.nightjar.app.feature.messages

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import dev.nightjar.app.R
import dev.nightjar.app.core.security.NumberDisplay
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.data.model.FeedChip
import dev.nightjar.app.data.model.MessageListItem
import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.feature.detail.DetailIcons
import dev.nightjar.app.feature.detail.releaseFailureRes
import dev.nightjar.app.feature.rules.showLatestSnackbar
import dev.nightjar.app.ui.components.LocalNjBottomInset
import dev.nightjar.app.ui.components.NjCaption
import dev.nightjar.app.ui.components.NjCollapsedTitle
import dev.nightjar.app.ui.components.NjDialog
import dev.nightjar.app.ui.components.NjEmptyState
import dev.nightjar.app.ui.components.NjFilterChips
import dev.nightjar.app.ui.components.NjIcons
import dev.nightjar.app.ui.components.NjLargeTitle
import dev.nightjar.app.ui.components.NjLineIcons
import dev.nightjar.app.ui.components.NjOption
import dev.nightjar.app.ui.components.NjSearchField
import dev.nightjar.app.ui.components.NjShimmerCard
import dev.nightjar.app.ui.components.NjSwipeRow
import dev.nightjar.app.ui.components.NjTonalButton
import dev.nightjar.app.ui.components.NjTopBarHeight
import dev.nightjar.app.ui.components.currentNjMotion
import dev.nightjar.app.ui.components.rememberNjTitleCollapsed
import dev.nightjar.app.ui.components.typeIconFor
import dev.nightjar.app.ui.text.LocalStrings
import dev.nightjar.app.ui.theme.LocalNjMotionPrefs
import dev.nightjar.app.ui.theme.NjMotion
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme
import dev.nightjar.app.ui.theme.SeverityTokens
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Verdict
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The held messages of "Waiting for you", newest first, and the actions of their buttons. */
internal class HeldRows(
    val rows: List<MessageListItem> = emptyList(),
    /** Tells the held pager which index is on screen, so it loads the next page in time. */
    val onShowIndex: (Int) -> Unit = {},
    val onRelease: (MessageListItem) -> Unit = {},
    val onBlock: (MessageListItem) -> Unit = {},
)

/**
 * The actions of a feed row, for the day groups and for "Waiting for you". In selection mode a tap and a long press
 * select the row.
 */
internal class RowCallbacks(
    val onClickRow: (MessageListItem) -> Unit,
    val onSelectRow: (MessageListItem) -> Unit,
    val onLongClickRow: (MessageListItem) -> Unit,
    val onToggleReviewed: (MessageListItem) -> Unit,
    val onSwipeLeft: (MessageListItem) -> Unit,
) {
    fun click(
        row: MessageListItem,
        selecting: Boolean,
    ) = if (selecting) onSelectRow(row) else onClickRow(row)

    fun longClick(
        row: MessageListItem,
        selecting: Boolean,
    ) = if (selecting) onSelectRow(row) else onLongClickRow(row)

    /** A delivery report opens its message, but not in selection mode. */
    fun openReport(
        report: MessageListItem,
        selecting: Boolean,
    ) {
        if (!selecting) onClickRow(report)
    }
}

/** The two buttons of a held row. [onRelease] is null when the type of the message cannot be released. */
internal class HeldActions(
    val onRelease: (() -> Unit)?,
    val onBlock: () -> Unit,
)

/** The buttons for [row]: only a held row has them, and not in selection mode. */
internal fun heldActions(
    row: MessageListItem,
    selecting: Boolean,
    held: HeldRows,
): HeldActions? =
    if (selecting || !isHeld(row)) {
        null
    } else {
        HeldActions(
            onRelease = if (ClassRegistry.isReleasable(row.classId)) ({ held.onRelease(row) }) else null,
            onBlock = { held.onBlock(row) },
        )
    }

/**
 * The amber heading "Waiting for you · N" and one card with the held rows. [count] is the number of the tab
 * badge (`state_quarantine_badge`), so the heading and the badge always agree.
 */
internal fun LazyListScope.waitingItems(
    count: Int,
    held: HeldRows,
    state: MessagesUiState,
    display: NumberDisplay,
    haptics: Boolean,
    clickLabels: RowClickLabels,
    callbacks: RowCallbacks,
) {
    val selecting = state.selection.isNotEmpty()
    item(key = "waiting", contentType = "day") { WaitingHeader(count) }
    held.rows.forEachIndexed { index, row ->
        item(key = "held-${row.id}", contentType = "message") {
            held.onShowIndex(index)
            FeedRow(
                entry = FeedEntry.Message(row, emptyList(), index),
                position = positionInGroup(index, held.rows.size),
                query = state.spec.search,
                selecting = selecting,
                selected = row.id in state.selection,
                current = row.id == state.selectedRowId,
                display = display,
                haptics = haptics,
                swipeEnabled = canSwipe(row, selecting),
                clickLabels = clickLabels,
                onOpenReport = {},
                onClick = { callbacks.click(row, selecting) },
                onLongClick = { callbacks.longClick(row, selecting) },
                onToggleReviewed = { callbacks.onToggleReviewed(row) },
                onSwipeLeft = { callbacks.onSwipeLeft(row) },
                heldActions = heldActions(row, selecting, held),
            )
        }
    }
}

/** The heading of "Waiting for you": a pause icon and the text in amber, the colour of "waits for you". */
@Composable
private fun WaitingHeader(count: Int) {
    val roles = NjTheme.roles
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(roles.bg)
                .padding(start = NjSpacing.m + NjSpacing.xs, end = NjSpacing.m, top = NjSpacing.m, bottom = NjSpacing.s)
                .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(NjLineIcons.OnHold, contentDescription = null, tint = roles.amb, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(NjSpacing.xs + 2.dp))
        Text(
            text = stringResource(R.string.msgs_waiting_heading, count).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = roles.amb,
        )
    }
}

/** The shape of a row inside its day group: only the outer corners of the group are round. */
private fun groupShape(position: GroupPosition): Shape =
    when (position) {
        GroupPosition.ONLY -> RoundedCornerShape(GroupRadius)
        GroupPosition.FIRST -> RoundedCornerShape(topStart = GroupRadius, topEnd = GroupRadius)
        GroupPosition.LAST -> RoundedCornerShape(bottomStart = GroupRadius, bottomEnd = GroupRadius)
        GroupPosition.MIDDLE -> RoundedCornerShape(0.dp)
    }

/** The accessibility names of the tap and the long press of a row, and its state in selection mode. */
internal data class RowClickLabels(
    val open: String,
    val select: String,
    val selectedState: String,
    val notSelectedState: String,
)

/**
 * One message row with its swipe actions: right = "Mark as reviewed", left = "Block" (NjSwipeRow). With
 * [swipeEnabled] false the row has no swipe. [heldActions] adds Release and Block buttons to a held row.
 * With [selecting] true the selection circle replaces the type icon, and a [selected] row has an accent tint.
 */
@Composable
internal fun FeedRow(
    entry: FeedEntry.Message,
    position: GroupPosition,
    query: String?,
    selecting: Boolean,
    selected: Boolean,
    current: Boolean,
    display: NumberDisplay,
    haptics: Boolean,
    swipeEnabled: Boolean,
    clickLabels: RowClickLabels,
    onOpenReport: (MessageListItem) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleReviewed: () -> Unit,
    onSwipeLeft: () -> Unit,
    modifier: Modifier = Modifier,
    heldActions: HeldActions? = null,
) {
    val haptic = LocalHapticFeedback.current
    // A short tick when a swipe action runs, only when "Haptic feedback" is on.
    val tick = { if (haptics) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    if (!swipeEnabled) {
        Row(modifier.padding(horizontal = NjSpacing.m)) {
            FeedRowBody(
                entry = entry,
                position = position,
                query = query,
                selecting = selecting,
                selected = selected,
                current = current,
                display = display,
                clickLabels = clickLabels,
                onOpenReport = onOpenReport,
                onClick = onClick,
                onLongClick = onLongClick,
                heldActions = heldActions,
                modifier = Modifier.weight(1f),
            )
        }
        return
    }
    NjSwipeRow(
        onReview = {
            tick()
            onToggleReviewed()
        },
        // "Block" is the existing left swipe: a rule for similar messages, or a mute for silent pings.
        onBlock = {
            tick()
            onSwipeLeft()
        },
        modifier = modifier.padding(horizontal = NjSpacing.m),
    ) {
        FeedRowBody(
            entry = entry,
            position = position,
            query = query,
            selecting = selecting,
            selected = selected,
            current = current,
            display = display,
            clickLabels = clickLabels,
            onOpenReport = onOpenReport,
            onClick = onClick,
            onLongClick = onLongClick,
            heldActions = heldActions,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The body of a list row: type icon in a round neutral container, title
 * (max 2 lines), time and the "not reviewed" dot; line 2 = sender and SIM; line 3 = description (max 2 lines); then the
 * status line "<fate> · <severity> · Test" in plain text, and the Release and Block buttons of a held row.
 *
 * Colour rule: amber = waits for you ("Waiting for you"), accent dot = not reviewed, red = high risk (the icon and the
 * word "High"). Everything else is muted. A reviewed row has a normal-weight muted title and dimmer lines, and no dot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedRowBody(
    entry: FeedEntry.Message,
    position: GroupPosition,
    query: String?,
    selecting: Boolean,
    selected: Boolean,
    current: Boolean,
    display: NumberDisplay,
    clickLabels: RowClickLabels,
    onOpenReport: (MessageListItem) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    heldActions: HeldActions? = null,
) {
    val row = entry.row
    val strings = LocalStrings.current
    val roles = NjTheme.roles
    val report = isDeliveryReport(row)
    val unreviewed = needsReview(row)
    val sim = simSlotLine(row, strings)
    val address = senderLine(row, display, stringResource(R.string.msgs_no_sender))
    // "To <number> · SIM", each part only when known. A sent row never shows "No sender address".
    val toPart = sentToNumber(row, display)?.let { stringResource(R.string.row_sent_to, it) }
    val titleText =
        if (report) {
            stringResource(R.string.msg_delivery_report, stringResource(reportLabel(reportStatus(row))))
        } else {
            remember(row.classId, row.title, row.repeatCount, strings) { listRowTitle(row, strings) }
        }
    val title = highlighted(titleText, if (report) null else query, roles.amb.copy(alpha = HIGHLIGHT_ALPHA))
    val subLine =
        if (report || row.direction == Direction.OUT) {
            sentLine(toPart, sim)
        } else {
            val parts =
                if (needsPartsLabel(row)) {
                    pluralStringResource(R.plurals.msgs_parts, row.partsTotal, row.partsHave, row.partsTotal)
                } else {
                    ""
                }
            listOf(address, sim, parts).filter { it.isNotEmpty() }.joinToString(" · ")
        }
    // A sent data message uses the description line to say which app sent it.
    val description =
        remember(row.id, row.title, row.severity, row.fate, strings) { rowDescription(row, strings) }
            ?: if (isSentDataMessage(row)) {
                sentDataApp(row)?.let { stringResource(R.string.row_sent_data_by_app, it) }
                    ?: stringResource(R.string.row_sent_data_no_app)
            } else {
                null
            }
    val tone = rowTone(unreviewed)
    Column(
        modifier =
            modifier
                .clip(groupShape(position))
                .background(rowBackground(roles, selected))
                .combinedClickable(
                    // In selection mode a tap selects or deselects the row.
                    onClickLabel = if (selecting) clickLabels.select else clickLabels.open,
                    onLongClickLabel = clickLabels.select,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ).semantics(mergeDescendants = true) { rowSelectionState(selecting, selected, current, clickLabels) },
    ) {
        Row(Modifier.padding(NjSpacing.m), verticalAlignment = Alignment.Top) {
            RowLeading(row, selecting, selected)
            Spacer(Modifier.width(NjSpacing.m - 4.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = tone.title,
                        fontWeight = tone.weight,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        // TalkBack reads the review state after the title.
                        modifier = Modifier.weight(1f).semantics { contentDescription = "$titleText, ${tone.reviewState}" },
                    )
                    Spacer(Modifier.width(NjSpacing.s))
                    Text(
                        timeOf(row.receivedAtWall),
                        style = MaterialTheme.typography.labelMedium,
                        color = roles.mu,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (unreviewed) {
                        Spacer(Modifier.width(NjSpacing.xs + 2.dp))
                        Box(
                            Modifier
                                .padding(top = 7.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(roles.acc),
                        )
                    }
                }
                if (subLine.isNotEmpty()) {
                    Text(
                        text = subLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tone.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                description?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tone.description,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusLine(rowStatus(row), tone.secondary)
                heldActions?.let { HeldButtons(it) }
                if (entry.reports.isNotEmpty()) {
                    val reportsText = pluralStringResource(R.plurals.msg_x_reports_count, entry.reports.size, entry.reports.size)
                    Column(Modifier.semantics { contentDescription = reportsText }) {
                        entry.reports.forEach { report -> ReportSubRow(report, onOpen = { onOpenReport(report) }) }
                    }
                }
            }
        }
        if (position == GroupPosition.FIRST || position == GroupPosition.MIDDLE) {
            Box(
                Modifier
                    .padding(start = 68.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(roles.line),
            )
        }
    }
}

/** The text colours of a row and its review state for TalkBack. */
private class RowTone(
    val title: Color,
    val weight: FontWeight,
    val secondary: Color,
    val description: Color,
    val reviewState: String,
)

/** Not reviewed: bold title in `tx`, lines in `mu`. Reviewed: normal-weight muted title, dimmer lines. */
@Composable
private fun rowTone(unreviewed: Boolean): RowTone {
    val roles = NjTheme.roles
    return if (unreviewed) {
        RowTone(
            title = roles.tx,
            weight = FontWeight.SemiBold,
            secondary = roles.mu,
            description = roles.tx.copy(alpha = DESCRIPTION_ALPHA),
            reviewState = stringResource(R.string.msgs_row_not_reviewed),
        )
    } else {
        val dim = roles.mu.copy(alpha = REVIEWED_ALPHA)
        RowTone(
            title = roles.mu,
            weight = FontWeight.Normal,
            secondary = dim,
            description = dim,
            reviewState = stringResource(R.string.msgs_row_reviewed),
        )
    }
}

/** The type icon of a row in a round neutral container. The icon is red only for a High row. */
@Composable
internal fun RowTypeIcon(row: MessageListItem) {
    val roles = NjTheme.roles
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(roles.s2),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = typeIconFor(ClassRegistry.of(row.classId).family),
            contentDescription = null,
            tint = if (isHighRisk(row)) roles.hi else roles.mu,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * "Blocked · High · Test" in plain text, one line, in [color]. Only the word "High" uses the `hi` colour.
 * The words are short, so the line is not cut at normal widths.
 */
@Composable
private fun StatusLine(
    status: RowStatus,
    color: Color,
) {
    if (status.isEmpty) return
    val roles = NjTheme.roles
    val fate = status.fate?.let { stringResource(fateLabel(it)) }
    val severity = status.severity?.let { SeverityTokens.label(it) }
    val test = if (status.test) stringResource(R.string.msgs_status_test) else null
    val text =
        buildAnnotatedString {
            listOfNotNull(fate, severity, test).forEachIndexed { index, word ->
                if (index > 0) append(" · ")
                if (word == severity && status.severity == Severity.HIGH) {
                    pushStyle(SpanStyle(color = roles.hi))
                    append(word)
                    pop()
                } else {
                    append(word)
                }
            }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** Release (tonal, neutral) and Block (text, muted) under a held row. */
@Composable
private fun HeldButtons(actions: HeldActions) {
    Row(
        modifier = Modifier.padding(top = NjSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(NjSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.onRelease?.let { release ->
            NjTonalButton(text = stringResource(R.string.msgs_held_release), onClick = release)
        }
        TextButton(onClick = actions.onBlock, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.msgs_held_block), color = NjTheme.roles.mu)
        }
    }
}

/** The word of a [RowFate] on the status line. */
private fun fateLabel(fate: RowFate): Int =
    when (fate) {
        RowFate.ON_HOLD -> R.string.msgs_chip_quarantined
        RowFate.BLOCKED -> R.string.msg_badge_blocked
        RowFate.RELEASED -> R.string.msgs_fate_released
        RowFate.DELIVERED -> R.string.msgs_fate_delivered
        RowFate.NOT_DELIVERED -> R.string.msgs_fate_not_delivered
        RowFate.WAITING -> R.string.msgs_fate_waiting
        RowFate.SENT -> R.string.msgs_fate_sent
        RowFate.NOT_SENT -> R.string.msgs_fate_not_sent
    }

/** The alpha of the lines of a reviewed row: dimmer than `mu`, still readable. */
private const val REVIEWED_ALPHA = 0.7f

/** The alpha of the description of a row that is not reviewed (`tx` at 82 %). */
private const val DESCRIPTION_ALPHA = 0.82f

private fun reportLabel(status: ReportStatus?): Int =
    when (status) {
        ReportStatus.DELIVERED -> R.string.msg_delivery_delivered
        ReportStatus.FAILED -> R.string.msg_delivery_failed
        ReportStatus.PENDING, null -> R.string.msg_delivery_pending
    }

/** A delivery report under its sent message: a status mark, "Delivery report: delivered" and the time. A tap opens it. */
@Composable
private fun ReportSubRow(
    report: MessageListItem,
    onOpen: () -> Unit,
) {
    val roles = NjTheme.roles
    val status = reportStatus(report)
    val color =
        when (status) {
            ReportStatus.DELIVERED -> roles.ok
            ReportStatus.FAILED -> roles.hi
            ReportStatus.PENDING, null -> roles.amb
        }
    val icon: ImageVector =
        when (status) {
            ReportStatus.DELIVERED -> NjIcons.Check
            ReportStatus.FAILED -> NjIcons.Close
            ReportStatus.PENDING, null -> NjIcons.Info
        }
    Row(
        modifier =
            Modifier
                .padding(top = NjSpacing.s)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(roles.s2)
                .clickable(role = Role.Button, onClick = onOpen)
                .heightIn(min = 48.dp)
                .padding(horizontal = NjSpacing.m - 4.dp, vertical = NjSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = roles.bg, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(NjSpacing.s))
        Text(
            text = stringResource(R.string.msg_delivery_report, stringResource(reportLabel(status))),
            style = MaterialTheme.typography.titleSmall,
            color = roles.tx,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(NjSpacing.s))
        Text(timeOf(report.receivedAtWall), style = MaterialTheme.typography.labelMedium, color = roles.mu)
    }
}

private fun timeOf(wallMs: Long): String =
    java.time.Instant
        .ofEpochMilli(wallMs)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** [text] with the search match on a [background] of the theme (the `amb` role, see [HIGHLIGHT_ALPHA]). */
private fun highlighted(
    text: String,
    query: String?,
    background: Color,
): AnnotatedString {
    val range = highlightRange(text, query) ?: return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(SpanStyle(background = background), range.first, range.last + 1)
    }
}

/** The alpha of the search match background: light enough to keep the title readable in both themes. */
private const val HIGHLIGHT_ALPHA = 0.4f
