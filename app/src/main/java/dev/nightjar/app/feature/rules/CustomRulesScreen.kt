package dev.nightjar.app.feature.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nightjar.app.R
import dev.nightjar.app.core.security.NumberDisplay
import dev.nightjar.app.core.security.formatNumber
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.feature.detail.DetailIcons
import dev.nightjar.app.ui.components.LocalNjBottomInset
import dev.nightjar.app.ui.components.NjBadge
import dev.nightjar.app.ui.components.NjCaption
import dev.nightjar.app.ui.components.NjCard
import dev.nightjar.app.ui.components.NjEmptyState
import dev.nightjar.app.ui.components.NjGroup
import dev.nightjar.app.ui.components.NjIcons
import dev.nightjar.app.ui.components.NjPrimaryButton
import dev.nightjar.app.ui.components.NjRowDivider
import dev.nightjar.app.ui.components.NjTonalButton
import dev.nightjar.app.ui.components.badgeContainer
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.nav.NavActions
import dev.nightjar.app.ui.nav.Route
import dev.nightjar.app.ui.theme.LocalNjMotionPrefs
import dev.nightjar.app.ui.theme.NjRoles
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.Conditions
import dev.nightjar.rules.RuleOrigin
import dev.nightjar.rules.StringMatch

/** Custom rules (-01,.04). */
@Composable
fun CustomRulesScreen(actions: NavActions) {
    val viewModel: CustomRulesViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val deleted = stringResource(R.string.rules_deleted)
    val restored = stringResource(R.string.rules_restored)
    val undo = stringResource(R.string.action_undo)
    val savedText = stringResource(R.string.snk_02)
    val failed = stringResource(R.string.change_failed)

    LaunchedEffect(Unit) {
        if (RuleSaveSignal.consume()) showLatestSnackbar(snackbar, savedText)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CustomRulesEvent.Deleted -> {
                    showLatestSnackbar(snackbar, deleted, actionLabel = undo) { viewModel.undoDelete(event.token) }
                }

                CustomRulesEvent.Restored -> {
                    showLatestSnackbar(snackbar, restored)
                }

                CustomRulesEvent.Failed -> {
                    showLatestSnackbar(snackbar, failed)
                }
            }
        }
    }

    CustomRulesContent(
        state = state,
        snackbar = snackbar,
        onBack = { actions.back() },
        onEnabled = viewModel::setEnabled,
        onDelete = viewModel::delete,
        onMove = viewModel::move,
        onEdit = { id ->
            RuleSaveSignal.expect()
            actions.open(Route.RuleEditor(ruleId = id))
        },
        onNewRule = {
            RuleSaveSignal.expect()
            actions.open(Route.RuleEditor())
        },
    )
}

/** The space after the last rule, so the floating "New rule" button does not cover it. */
private val NewRuleClearance = 88.dp

/** The stagger index of the first user rule: after the title, the preset group and the section titles. */
private const val FIRST_RULE_INDEX = 4

@Composable
private fun CustomRulesContent(
    state: CustomRulesUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onEnabled: (Long, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
    onMove: (Long, Int) -> Unit,
    onEdit: (Long) -> Unit,
    onNewRule: () -> Unit,
) {
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val display = NumberDisplay.forSetting(state.maskNumbers)
    val haptics = LocalNjMotionPrefs.current.haptics
    val haptic = LocalHapticFeedback.current

    ToolScreen(
        title = stringResource(R.string.screen_s07),
        onBack = onBack,
        snackbar = snackbar,
        listEndGap = NewRuleClearance,
        floating = {
            // The button hides while a snackbar shows, so it never covers the Undo action of the snackbar.
            if (snackbar.currentSnackbarData == null) {
                NjPrimaryButton(
                    text = stringResource(R.string.firewall_new_rule),
                    onClick = onNewRule,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = NjSpacing.m, bottom = LocalNjBottomInset.current + NjSpacing.m),
                )
            }
        },
    ) {
        when {
            state.loading -> {
                toolLoading(3)
            }

            state.isEmpty -> {
                item(key = "empty") {
                    NjEmptyState(
                        title = stringResource(R.string.rules_empty_title),
                        body = stringResource(R.string.rules_empty_body),
                        icon = NjIcons.Tune,
                        action = { NjTonalButton(text = stringResource(R.string.firewall_new_rule), onClick = onNewRule) },
                        modifier = Modifier.njEnter(1),
                    )
                }
            }

            else -> {
                if (state.presetRules.isNotEmpty()) {
                    item(key = "preset-header") {
                        ToolSection(stringResource(R.string.rules_from_preset), Modifier.njEnter(1))
                    }
                    item(key = "preset-rules") {
                        NjGroup(Modifier.toolCard().njEnter(2)) {
                            state.presetRules.forEachIndexed { index, row ->
                                if (index > 0) NjRowDivider()
                                RuleRow(
                                    row = row,
                                    editable = false,
                                    display = display,
                                    onEnabled = {},
                                    onEdit = {},
                                )
                            }
                        }
                    }
                    item(key = "preset-note") {
                        NjCaption(
                            stringResource(R.string.rules_preset_locked),
                            Modifier.padding(start = NjSpacing.m + NjSpacing.xs, end = NjSpacing.m, top = NjSpacing.s),
                        )
                    }
                }

                item(key = "user-header") {
                    ToolSection(
                        stringResource(R.string.firewall_custom_rules, state.userRules.size),
                        Modifier.njEnter(3),
                    )
                }

                itemsIndexed(state.userRules, key = { _, row -> row.id }) { position, row ->
                    val moveUp = stringResource(R.string.rules_move_up)
                    val moveDown = stringResource(R.string.rules_move_down)
                    val handleDescription = stringResource(R.string.cd_drag_handle, row.name)
                    SwipeToDelete(
                        onDelete = {
                            // A short tick when the swipe deletes, only when "Haptic feedback" is on.
                            if (haptics) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onDelete(row.id)
                        },
                        modifier = Modifier.toolCard().njEnter(FIRST_RULE_INDEX + position),
                    ) {
                        NjCard(padding = 0.dp) {
                            RuleRow(
                                row = row,
                                editable = true,
                                display = display,
                                onEnabled = { enabled -> onEnabled(row.id, enabled) },
                                onEdit = { onEdit(row.id) },
                                dragModifier =
                                    Modifier
                                        // Keyed on the rule only: a key on the list restarted the detector at each
                                        // reorder, so one drag moved the rule one step. move() reads the current order.
                                        .pointerInput(row.id) {
                                            detectVerticalDragGestures(
                                                onDragStart = { dragDistance = 0f },
                                                onVerticalDrag = { change, dy ->
                                                    change.consume()
                                                    dragDistance += dy
                                                    if (dragDistance > threshold) {
                                                        onMove(row.id, 1)
                                                        dragDistance = 0f
                                                    } else if (dragDistance < -threshold) {
                                                        onMove(row.id, -1)
                                                        dragDistance = 0f
                                                    }
                                                },
                                            )
                                        }.semantics {
                                            contentDescription = handleDescription
                                            customActions =
                                                listOf(
                                                    CustomAccessibilityAction(moveUp) {
                                                        onMove(row.id, -1)
                                                        true
                                                    },
                                                    CustomAccessibilityAction(moveDown) {
                                                        onMove(row.id, 1)
                                                        true
                                                    },
                                                )
                                        },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A user rule card that the user can swipe to the left to delete. [dev.nightjar.app.ui.components.NjSwipeRow] has the
 * fixed actions "Mark as reviewed" and "Block", so this row keeps its own swipe, in the `hi` colour of a destructive
 * action. The swipe asks for the delete and springs back: the row goes away when the stored list drops it, and stays
 * usable when the delete fails.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
                false
            },
        )
    val roles = NjTheme.roles
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                Modifier
                    .fillMaxSize()
                    .clip(NjShape.card)
                    .background(badgeContainer(roles, roles.hi))
                    .padding(horizontal = NjSpacing.l),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(NjIcons.Close, contentDescription = null, tint = roles.hi, modifier = Modifier.size(20.dp))
                Text(
                    text = stringResource(R.string.action_delete),
                    style = MaterialTheme.typography.titleSmall,
                    color = roles.hi,
                    modifier = Modifier.padding(start = NjSpacing.s),
                )
            }
        },
    ) {
        content()
    }
}

@Composable
private fun RuleRow(
    row: RuleRowUi,
    editable: Boolean,
    display: NumberDisplay,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    /** The drag handle's gestures; [Modifier] for a row that cannot move. */
    dragModifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(enabled = editable, onClick = onEdit)
                .padding(start = NjSpacing.s, end = NjSpacing.m, top = NjSpacing.s, bottom = NjSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = dragModifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            // The box carries the handle description and the move actions; the glyph itself is decoration.
            if (editable) Icon(DetailIcons.DragHandle, contentDescription = null, tint = roles.mu)
        }
        Column(
            modifier = Modifier.weight(1f).padding(vertical = NjSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                row.name.ifBlank { stringResource(R.string.rules_any_class) },
                style = MaterialTheme.typography.titleSmall,
                color = roles.tx,
            )
            Text(
                text = conditionSummary(row.conditions, display),
                style = MaterialTheme.typography.bodyMedium,
                color = roles.mu,
            )
            val lastMatch = row.lastMatchAt
            NjCaption(
                text =
                    if (row.matchCount > 0L && lastMatch != null) {
                        pluralStringResource(
                            R.plurals.rules_matched,
                            row.matchCount.toInt(),
                            row.matchCount,
                            dev.nightjar.app.feature.settings.UiFormat
                                .date(lastMatch),
                        )
                    } else {
                        stringResource(R.string.rules_never_matched)
                    },
            )
        }
        Spacer(Modifier.width(NjSpacing.s))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(NjSpacing.xs)) {
            NjBadge(text = stringResource(verdictLabelRes(row.action)), color = verdictColor(roles, row.action))
            // The name only: the switch itself tells TalkBack "on" or "off". "Turn on <name>" was wrong for a rule that is on.
            val switchDescription = row.name.ifBlank { stringResource(R.string.rules_any_class) }
            Switch(
                checked = row.enabled,
                onCheckedChange = onEnabled,
                enabled = editable,
                modifier = Modifier.semantics { contentDescription = switchDescription },
            )
        }
    }
}

/** The colour of a rule action: Allow = `ok`, Notify = `acc`, Hold = `amb`, Block = `hi` (redesign 7.1). */
internal fun verdictColor(
    roles: NjRoles,
    verdict: Verdict,
): Color =
    when (verdict) {
        Verdict.ALLOW -> roles.ok
        Verdict.NOTIFY -> roles.acc
        Verdict.QUARANTINE -> roles.amb
        Verdict.BLOCK -> roles.hi
    }

/**
 * The conditions of a rule in one line. Each part is a format string, so a translation can order its words; the sender
 * value follows `privacy.maskNumbers`. A regex is shown as written: masking it would hide what it matches.
 */
@Composable
private fun conditionSummary(
    conditions: Conditions,
    display: NumberDisplay,
): String {
    val parts = ArrayList<String>(4)
    val classLabels = conditions.classes()?.map { stringResource(classLabelRes(it)) }.orEmpty()
    if (classLabels.isNotEmpty()) parts += joinWith(classLabels, R.string.rule_edit_name_join)
    conditions.originator()?.let { match ->
        val value = if (match.op() == StringMatch.Op.REGEX) match.value() else formatNumber(match.value(), display).orEmpty()
        parts += stringResource(R.string.rules_sum_from, stringResource(matchOpLabelRes(match.op())), value)
    }
    conditions.port()?.let { port ->
        parts +=
            if (port.from() == port.to()) {
                stringResource(R.string.rule_edit_preview_port, port.from())
            } else {
                stringResource(R.string.rules_sum_port_range, port.from(), port.to())
            }
    }
    conditions.contentType()?.let {
        parts += stringResource(R.string.rules_sum_content_type, it.value())
    }
    conditions.smsc()?.let {
        parts += stringResource(R.string.rules_sum_smsc, it.value())
    }
    conditions.roaming()?.let {
        parts +=
            when (it) {
                true -> stringResource(R.string.rule_edit_roaming_only)
                false -> stringResource(R.string.rule_edit_roaming_not)
            }
    }
    conditions.textContains()?.let {
        parts += stringResource(R.string.rules_sum_text, it)
    }
    conditions.subtypes()?.let {
        parts += stringResource(R.string.rules_sum_subtypes, joinWith(it.toList(), R.string.list_join))
    }
    if (parts.isEmpty()) return stringResource(R.string.rules_any_class)
    return joinWith(parts, R.string.sum_join)
}

/** Joins [items] pair by pair with the two-argument format string [joinRes]. */
@Composable
internal fun joinWith(
    items: List<String>,
    joinRes: Int,
): String {
    if (items.isEmpty()) return ""
    var text = items.first()
    for (item in items.drop(1)) text = stringResource(joinRes, text, item)
    return text
}

// ------------------------------------------------------------------ previews

private val PreviewRulesState =
    CustomRulesUiState(
        loading = false,
        userRules =
            listOf(
                RuleRowUi(
                    id = 5,
                    name = "Unknown settings push",
                    enabled = true,
                    action = Verdict.QUARANTINE,
                    origin = RuleOrigin.USER,
                    conditions = Conditions.ANY,
                    matchCount = 3,
                    lastMatchAt = 1_760_000_000_000,
                ),
                RuleRowUi(
                    id = 6,
                    name = "Data port 2948",
                    enabled = false,
                    action = Verdict.BLOCK,
                    origin = RuleOrigin.USER,
                    conditions = Conditions.ANY,
                    matchCount = 0,
                    lastMatchAt = null,
                ),
            ),
        presetRules =
            listOf(
                RuleRowUi(
                    id = 1,
                    name = "Block auto-open link pushes",
                    enabled = true,
                    action = Verdict.BLOCK,
                    origin = RuleOrigin.PRESET,
                    conditions =
                        Conditions
                            .builder()
                            .classes(ClassId.WAP_SISL)
                            .subtypes("SL")
                            .build(),
                    matchCount = 0,
                    lastMatchAt = null,
                ),
            ),
    )

@Composable
private fun PreviewCustomRules(state: CustomRulesUiState) {
    CustomRulesContent(
        state = state,
        snackbar = remember { SnackbarHostState() },
        onBack = {},
        onEnabled = { _, _ -> },
        onDelete = {},
        onMove = { _, _ -> },
        onEdit = {},
        onNewRule = {},
    )
}

@Preview(name = "Custom rules dark", widthDp = 400, heightDp = 800)
@Composable
private fun CustomRulesDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewCustomRules(PreviewRulesState) }
}

@Preview(name = "Custom rules light", widthDp = 400, heightDp = 800)
@Composable
private fun CustomRulesLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { PreviewCustomRules(PreviewRulesState) }
}

@Preview(name = "Custom rules empty dark", widthDp = 400, heightDp = 640)
@Composable
private fun CustomRulesEmptyDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewCustomRules(CustomRulesUiState(loading = false)) }
}

@Preview(name = "Custom rules loading light", widthDp = 400, heightDp = 640)
@Composable
private fun CustomRulesLoadingLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { PreviewCustomRules(CustomRulesUiState(loading = true)) }
}
