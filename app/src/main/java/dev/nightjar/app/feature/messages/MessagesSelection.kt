package dev.nightjar.app.feature.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.data.model.MessageListItem
import dev.nightjar.app.ui.components.NjLineIcons
import dev.nightjar.app.ui.components.NjMenuDivider
import dev.nightjar.app.ui.components.NjMenuItem
import dev.nightjar.app.ui.components.NjOverflowMenu
import dev.nightjar.app.ui.components.NjTopBarHeight
import dev.nightjar.app.ui.components.NjTopBarIconButton
import dev.nightjar.app.ui.theme.NjRoles
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme

/**
 * The top-bar actions while the user selects messages: "Mark as reviewed" as an icon, then the menu with text items.
 * Delete is the only red item, and it asks first.
 */
@Composable
internal fun SelectionActions(callbacks: SelectionCallbacks) {
    NjTopBarIconButton(
        icon = NjLineIcons.Reviewed,
        contentDescription = stringResource(R.string.msgs_mark_reviewed),
        onClick = callbacks.onMarkReviewed,
    )
    NjOverflowMenu(contentDescription = stringResource(R.string.action_more)) { close ->
        SelectionMenuItem(R.string.msgs_mark_unreviewed, NjLineIcons.Unreviewed, callbacks.onMarkUnreviewed, close)
        SelectionMenuItem(R.string.msgs_star, NjLineIcons.Star, callbacks.onStar, close)
        SelectionMenuItem(R.string.msgs_export, NjLineIcons.Export, callbacks.onExport, close)
        NjMenuDivider()
        SelectionMenuItem(R.string.trusted_select_all, NjLineIcons.SelectAll, callbacks.onSelectAll, close)
        SelectionMenuItem(R.string.trusted_invert, NjLineIcons.Invert, callbacks.onInvert, close)
        SelectionMenuItem(R.string.trusted_clear, NjLineIcons.ClearAll, callbacks.onClear, close)
        NjMenuDivider()
        SelectionMenuItem(R.string.msgs_delete, NjLineIcons.Delete, callbacks.onDelete, close, destructive = true)
    }
}

@Composable
private fun SelectionMenuItem(
    label: Int,
    icon: ImageVector,
    onClick: () -> Unit,
    close: () -> Unit,
    destructive: Boolean = false,
) {
    NjMenuItem(
        text = stringResource(label),
        icon = icon,
        destructive = destructive,
        onClick = {
            close()
            onClick()
        },
    )
}

/**
 * The line that replaces the large title, the search field, the chips and the count line while the user selects: a
 * checkbox with "Select all N" (none, some or all selected), then "Invert". [total] is the number of rows of the current
 * list. A space of the bar height comes first, because the selection bar lies over the top of the list.
 */
internal fun LazyListScope.selectAllItem(
    selected: Int,
    total: Int?,
    callbacks: SelectionCallbacks,
) {
    item(key = "select-bar-space", contentType = "space") { Spacer(Modifier.height(NjTopBarHeight)) }
    item(key = "select-all", contentType = "select-all") { SelectAllRow(selected, total, callbacks) }
}

@Composable
private fun SelectAllRow(
    selected: Int,
    total: Int?,
    callbacks: SelectionCallbacks,
) {
    val roles = NjTheme.roles
    val all = selectAllState(selected, total)
    val checkbox = toggleableStateOf(all)
    // A tap selects all rows. When all rows are selected, a tap clears the selection.
    val onToggle = if (all == SelectAllState.ALL) callbacks.onClear else callbacks.onSelectAll
    val label =
        if (total != null) {
            pluralStringResource(R.plurals.select_select_all_count, total, total)
        } else {
            stringResource(R.string.trusted_select_all)
        }
    val invertDescription = stringResource(R.string.trusted_invert)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NjSpacing.m, vertical = NjSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .clip(NjShape.pill)
                    .triStateToggleable(state = checkbox, role = Role.Checkbox, onClick = onToggle)
                    .heightIn(min = 48.dp)
                    .padding(end = NjSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TriStateCheckbox(
                state = checkbox,
                onClick = null,
                colors = CheckboxDefaults.colors(checkedColor = roles.acc, uncheckedColor = roles.mu, checkmarkColor = roles.bg),
            )
            Text(label, style = MaterialTheme.typography.bodyMedium, color = roles.tx)
        }
        Text("·", style = MaterialTheme.typography.bodyMedium, color = roles.mu, modifier = Modifier.clearAndSetSemantics {})
        TextButton(
            onClick = callbacks.onInvert,
            modifier = Modifier.semantics { contentDescription = invertDescription },
        ) {
            Text(stringResource(R.string.select_invert), color = roles.acc)
        }
    }
}

private fun toggleableStateOf(state: SelectAllState): ToggleableState =
    when (state) {
        SelectAllState.NONE -> ToggleableState.Off
        SelectAllState.SOME -> ToggleableState.Indeterminate
        SelectAllState.ALL -> ToggleableState.On
    }

/** The leading part of a list row: the type icon, or in selection mode the selection circle. */
@Composable
internal fun RowLeading(
    row: MessageListItem,
    selecting: Boolean,
    selected: Boolean,
) {
    if (selecting) SelectCircle(selected) else RowTypeIcon(row)
}

/**
 * The selection circle in place of the type icon: filled with the accent and a tick when [selected], an empty ring when
 * not. A tap on it is a tap on the row, so it toggles the row.
 */
@Composable
private fun SelectCircle(selected: Boolean) {
    val roles = NjTheme.roles
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (selected) {
            Box(Modifier.size(SELECT_CIRCLE).clip(CircleShape).background(roles.acc), contentAlignment = Alignment.Center) {
                Icon(NjLineIcons.Check, contentDescription = null, tint = roles.bg, modifier = Modifier.size(20.dp))
            }
        } else {
            Box(Modifier.size(SELECT_CIRCLE).border(2.dp, roles.mu, CircleShape))
        }
    }
}

/** The background of a list row: the card colour, with an accent tint while the row is selected. */
internal fun rowBackground(
    roles: NjRoles,
    selected: Boolean,
): Color = if (selected) roles.acc.copy(alpha = SELECTED_TINT_ALPHA).compositeOver(roles.s1) else roles.s1

/**
 * The selection state of a row for TalkBack. In selection mode every row states "Selected" or "Not selected". Out of
 * it, only the row open in the detail pane ([current]) is selected.
 */
internal fun SemanticsPropertyReceiver.rowSelectionState(
    selecting: Boolean,
    selected: Boolean,
    current: Boolean,
    labels: RowClickLabels,
) {
    if (selecting) {
        this.selected = selected
        stateDescription = if (selected) labels.selectedState else labels.notSelectedState
    } else if (current) {
        this.selected = true
    }
}

/** The diameter of the selection circle; the 40 dp slot of the type icon holds it. */
private val SELECT_CIRCLE = 32.dp

/** The accent tint of a selected row over the card colour (accent at 12 %). */
private const val SELECTED_TINT_ALPHA = 0.12f
