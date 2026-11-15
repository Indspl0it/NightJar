package dev.nightjar.app.feature.rules

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nightjar.app.R
import dev.nightjar.app.core.security.NumberDisplay
import dev.nightjar.app.core.security.formatNumber
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.ui.components.NjCaption
import dev.nightjar.app.ui.components.NjCard
import dev.nightjar.app.ui.components.NjChoiceRow
import dev.nightjar.app.ui.components.NjDialog
import dev.nightjar.app.ui.components.NjEmptyState
import dev.nightjar.app.ui.components.NjGroup
import dev.nightjar.app.ui.components.NjOption
import dev.nightjar.app.ui.components.NjPrimaryButton
import dev.nightjar.app.ui.components.NjRowDivider
import dev.nightjar.app.ui.components.NjSegmented
import dev.nightjar.app.ui.components.NjSwitchRow
import dev.nightjar.app.ui.components.NjTextButton
import dev.nightjar.app.ui.components.NjTextField
import dev.nightjar.app.ui.components.NjTonalButton
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.nav.NavActions
import dev.nightjar.app.ui.nav.Route
import dev.nightjar.app.ui.text.LocalStrings
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme
import dev.nightjar.contract.ClassId
import dev.nightjar.rules.StringMatch

/**
 * Rule editor (-01,.06).
 *
 * Four stepped cards (When, Then, Name and status, Test) above a sticky Save bar. Problems are shown under the field they
 * belong to, and the Save bar says why it is disabled.
 */
@Composable
fun RuleEditorScreen(
    route: Route.RuleEditor,
    actions: NavActions,
) {
    val viewModel: RuleEditorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val maskNumbers by viewModel.maskNumbers.collectAsStateWithLifecycle()
    val display = NumberDisplay.forSetting(maskNumbers)
    val snackbar = remember { SnackbarHostState() }
    val tooLarge = stringResource(R.string.rule_edit_error_too_large)
    val saveFailed = stringResource(R.string.rule_edit_save_failed)
    val loadFailed = stringResource(R.string.rule_edit_load_failed)
    val currentActions by rememberUpdatedState(actions)

    LaunchedEffect(route) { viewModel.initialise(route.ruleId, route.messageId) }
    LaunchedEffect(state.closed) { if (state.closed) currentActions.back() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                RuleEditorEvent.Saved -> {
                    RuleSaveSignal.post()
                    currentActions.back()
                }

                RuleEditorEvent.TooLarge -> {
                    showLatestSnackbar(snackbar, tooLarge)
                }

                RuleEditorEvent.Failed -> {
                    showLatestSnackbar(snackbar, saveFailed)
                }

                RuleEditorEvent.LoadFailed -> {
                    showLatestSnackbar(snackbar, loadFailed)
                }
            }
        }
    }

    BackHandler(enabled = state.dirty && !state.closed) { viewModel.requestClose() }

    val editing = !state.loading && !state.notFound
    if (editing) {
        val suggestion = suggestedName(state.draft, display)
        LaunchedEffect(suggestion) { if (suggestion.isNotEmpty()) viewModel.applySuggestedName(suggestion) }
    }

    ToolScreen(
        title = stringResource(if (state.isNew) R.string.rule_edit_title_new else R.string.rule_edit_title_edit),
        onBack = viewModel::requestClose,
        snackbar = snackbar,
        bottomBar =
            if (editing) {
                { SaveBar(canSave = state.canSave, hasIssues = state.issues.isNotEmpty(), onSave = viewModel::save) }
            } else {
                null
            },
    ) {
        when {
            state.loading -> {
                toolLoading(4)
            }

            state.notFound -> {
                item(key = "not-found") {
                    NjEmptyState(
                        title = stringResource(R.string.screen_s08),
                        body = stringResource(R.string.rule_edit_not_found),
                        action = {
                            NjTonalButton(text = stringResource(R.string.action_back), onClick = { actions.back() })
                        },
                        modifier = Modifier.njEnter(1),
                    )
                }
            }

            else -> {
                ruleEditorItems(state = state, viewModel = viewModel, display = display)
            }
        }
    }

    if (state.showDiscardDialog) {
        NjDialog(
            title = stringResource(R.string.dlg_17_title),
            text = stringResource(R.string.dlg_17_body),
            confirmText = stringResource(R.string.dlg_17_confirm),
            dismissText = stringResource(R.string.dlg_17_cancel),
            onConfirm = viewModel::confirmDiscard,
            onDismiss = viewModel::dismissDiscard,
            destructive = true,
        )
    }
    if (state.showG05Dialog) {
        NjDialog(
            title = stringResource(R.string.dlg_07_title),
            text = stringResource(R.string.dlg_07_body),
            confirmText = stringResource(R.string.dlg_07_confirm),
            dismissText = null,
            onConfirm = viewModel::dismissG05,
            onDismiss = viewModel::dismissG05,
        )
    }
}

/** Sticky footer: the reason Save is off (when it is) above a full-width Save button. */
@Composable
private fun SaveBar(
    canSave: Boolean,
    hasIssues: Boolean,
    onSave: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(NjTheme.roles.s1)
                .padding(horizontal = NjSpacing.m, vertical = NjSpacing.s + 4.dp),
        verticalArrangement = Arrangement.spacedBy(NjSpacing.s),
    ) {
        if (hasIssues && !canSave) {
            Text(
                text = stringResource(R.string.rule_edit_save_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = NjTheme.roles.hi,
            )
        }
        NjPrimaryButton(
            text = stringResource(R.string.rule_edit_save),
            onClick = onSave,
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The form of the editor as list items: the When step here, the other steps in [ruleOutcomeItems]. */
@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.ruleEditorItems(
    state: RuleEditorUiState,
    viewModel: RuleEditorViewModel,
    display: NumberDisplay,
) {
    val draft = state.draft

    // ---- When
    item(key = "when-header") { ToolSection(stringResource(R.string.rule_edit_when), Modifier.njEnter(1)) }
    item(key = "when-type") {
        NjCard(Modifier.toolCard().njEnter(2)) {
            FieldLabel(stringResource(R.string.rule_edit_type), stringResource(R.string.rule_edit_type_hint))
            FlowRow(
                modifier = Modifier.padding(top = NjSpacing.s),
                horizontalArrangement = Arrangement.spacedBy(NjSpacing.s),
            ) {
                ruleEditorClasses.forEach { classId ->
                    FilterChip(
                        selected = classId in draft.classes,
                        onClick = { viewModel.toggleClass(classId) },
                        label = { Text(stringResource(classLabelRes(classId))) },
                        shape = NjShape.chip,
                    )
                }
            }
            Issues(state.issuesFor(DraftField.CLASSES))
        }
    }
    item(key = "when-from") {
        NjCard(Modifier.toolCard().njEnter(3), padding = 0.dp) {
            NjSwitchRow(
                title = stringResource(R.string.rule_edit_from),
                checked = draft.fromEnabled,
                onCheckedChange = viewModel::setFromEnabled,
            )
            if (draft.fromEnabled) {
                Column(
                    modifier = Modifier.padding(start = NjSpacing.m, end = NjSpacing.m, bottom = NjSpacing.m),
                    verticalArrangement = Arrangement.spacedBy(NjSpacing.s + 4.dp),
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
                        StringMatch.Op.entries.forEach { op ->
                            FilterChip(
                                selected = draft.fromOp == op,
                                onClick = { viewModel.setFromOp(op) },
                                label = { Text(stringResource(matchOpLabelRes(op))) },
                                shape = NjShape.chip,
                            )
                        }
                    }
                    NjTextField(
                        value = draft.fromValue,
                        onValueChange = viewModel::setFromValue,
                        label = stringResource(R.string.rule_edit_value),
                        isError = state.issuesFor(DraftField.FROM).isNotEmpty(),
                    )
                    Issues(state.issuesFor(DraftField.FROM))
                }
            }
        }
    }
    item(key = "when-sim") {
        NjCard(Modifier.toolCard().njEnter(4)) {
            FieldLabel(stringResource(R.string.rule_edit_sim))
            val options =
                listOf(
                    NjOption<Int?>(null, stringResource(R.string.rule_edit_sim_any)),
                    NjOption<Int?>(0, stringResource(R.string.rule_edit_sim_slot, 1)),
                    NjOption<Int?>(1, stringResource(R.string.rule_edit_sim_slot, 2)),
                )
            NjSegmented(
                options = options,
                selected = draft.slot,
                onSelect = viewModel::setSlot,
                modifier = Modifier.padding(top = NjSpacing.s),
            )
        }
    }
    draft.subtypes?.let { subtypes ->
        item(key = "when-subtypes") {
            NjCard(Modifier.toolCard().njEnter(5)) {
                FieldLabel(stringResource(R.string.rules_subtypes_title), stringResource(R.string.rules_subtypes_hint))
                FlowRow(
                    modifier = Modifier.padding(top = NjSpacing.s),
                    horizontalArrangement = Arrangement.spacedBy(NjSpacing.s),
                ) {
                    // Read-only. The chips show the condition; only a preset writes it.
                    subtypes.forEach { code ->
                        FilterChip(
                            selected = true,
                            onClick = {},
                            enabled = false,
                            label = { Text(subtypeLabel(draft.classes, code)) },
                            shape = NjShape.chip,
                        )
                    }
                }
            }
        }
    }
    item(key = "when-conditions") {
        NjCard(Modifier.toolCard().njEnter(5)) {
            FieldLabel(stringResource(R.string.rule_edit_conditions), stringResource(R.string.rule_edit_add_condition))
            val addable = addableConditions(draft)
            if (addable.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = NjSpacing.s),
                    horizontalArrangement = Arrangement.spacedBy(NjSpacing.s),
                ) {
                    addable.forEach { (kind, label) ->
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.addCondition(kind) },
                            label = { Text(stringResource(R.string.rule_edit_add_condition_chip, stringResource(label))) },
                            shape = NjShape.chip,
                        )
                    }
                }
            }
            ConditionEditors(state = state, viewModel = viewModel)
        }
    }

    ruleOutcomeItems(state, viewModel, display)
}

/** The Then, Name and status, and Test steps of the form. */
private fun LazyListScope.ruleOutcomeItems(
    state: RuleEditorUiState,
    viewModel: RuleEditorViewModel,
    display: NumberDisplay,
) {
    val draft = state.draft

    // ---- Then
    item(key = "then-header") { ToolSection(stringResource(R.string.rule_edit_then), Modifier.njEnter(6)) }
    item(key = "then-actions") {
        Column(Modifier.toolCard().njEnter(7), verticalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
            if (state.allowedActions.isEmpty()) {
                NjCard { NjCaption(stringResource(R.string.rule_edit_no_action)) }
            } else {
                NjGroup {
                    state.allowedActions.forEachIndexed { index, verdict ->
                        if (index > 0) NjRowDivider()
                        NjChoiceRow(
                            title = stringResource(verdictLabelRes(verdict)),
                            supporting = stringResource(verdictDescriptionRes(verdict)),
                            selected = draft.action == verdict,
                            onSelect = { viewModel.setAction(verdict) },
                        )
                    }
                }
            }
            Issues(state.issuesFor(DraftField.ACTION), onWhy = viewModel::explainG05)
            Issues(state.issuesFor(DraftField.DOCUMENT))
        }
    }

    // ---- Name and status
    item(key = "name-header") { ToolSection(stringResource(R.string.rule_edit_step_name), Modifier.njEnter(7)) }
    item(key = "name-card") {
        NjCard(Modifier.toolCard().njEnter(7), padding = 0.dp) {
            Column(Modifier.padding(NjSpacing.m)) {
                NjTextField(
                    value = draft.name,
                    onValueChange = viewModel::setName,
                    label = stringResource(R.string.rule_edit_name),
                )
            }
            NjRowDivider()
            NjSwitchRow(
                title = stringResource(R.string.rule_edit_enabled),
                checked = draft.enabled,
                onCheckedChange = viewModel::setEnabled,
            )
        }
    }

    // ---- Test
    item(key = "test-header") { ToolSection(stringResource(R.string.rule_edit_preview), Modifier.njEnter(7)) }
    item(key = "test-card") {
        NjCard(Modifier.toolCard().njEnter(7)) {
            state.history?.let { HistoryLine(it) }
            NjCaption(stringResource(R.string.rule_edit_preview_hint))
            Column(
                modifier = Modifier.padding(top = NjSpacing.s),
                verticalArrangement = Arrangement.spacedBy(NjSpacing.s),
            ) {
                NjTextField(
                    value = state.previewHex,
                    onValueChange = viewModel::setPreviewHex,
                    label = stringResource(R.string.rule_edit_preview_label),
                    singleLine = false,
                )
                PduPreviewResult(state.preview, display)
            }
        }
    }
}

/** The condition kinds that are not on the draft yet, with their chip labels. */
private fun addableConditions(draft: RuleDraft): List<Pair<ConditionKind, Int>> =
    buildList {
        if (draft.port() == null) add(ConditionKind.PORT to R.string.rule_edit_cond_port)
        if (draft.contentType() == null) add(ConditionKind.CONTENT_TYPE to R.string.rule_edit_cond_content_type)
        if (draft.smsc() == null) add(ConditionKind.SMSC to R.string.rule_edit_cond_smsc)
        if (draft.roaming() == null) add(ConditionKind.ROAMING to R.string.rule_edit_cond_roaming)
        if (draft.text() == null) add(ConditionKind.TEXT to R.string.rule_edit_cond_text)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConditionEditors(
    state: RuleEditorUiState,
    viewModel: RuleEditorViewModel,
) {
    val draft = state.draft

    draft.port()?.let { condition ->
        ConditionBox(
            title = stringResource(R.string.rule_edit_cond_port_range),
            onRemove = { viewModel.removeCondition(condition) },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
                NjTextField(
                    value = condition.from,
                    onValueChange = { viewModel.updateCondition(condition.copy(from = it)) },
                    label = stringResource(R.string.rule_edit_cond_port),
                    isError = state.issuesFor(DraftField.PORT).isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
                NjTextField(
                    value = condition.to,
                    onValueChange = { viewModel.updateCondition(condition.copy(to = it)) },
                    label = stringResource(R.string.rule_edit_cond_port_to),
                    isError = state.issuesFor(DraftField.PORT).isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }
            Issues(state.issuesFor(DraftField.PORT))
        }
    }

    draft.contentType()?.let { condition ->
        ConditionBox(
            title = stringResource(R.string.rule_edit_cond_content_type),
            onRemove = { viewModel.removeCondition(condition) },
        ) {
            OpRow(condition.op) { viewModel.updateCondition(condition.copy(op = it)) }
            NjTextField(
                value = condition.value,
                onValueChange = { viewModel.updateCondition(condition.copy(value = it)) },
                label = stringResource(R.string.rule_edit_value),
                isError = state.issuesFor(DraftField.CONTENT_TYPE).isNotEmpty(),
            )
            Issues(state.issuesFor(DraftField.CONTENT_TYPE))
        }
    }

    draft.smsc()?.let { condition ->
        ConditionBox(
            title = stringResource(R.string.rule_edit_cond_smsc),
            onRemove = { viewModel.removeCondition(condition) },
        ) {
            OpRow(condition.op) { viewModel.updateCondition(condition.copy(op = it)) }
            NjTextField(
                value = condition.value,
                onValueChange = { viewModel.updateCondition(condition.copy(value = it)) },
                label = stringResource(R.string.rule_edit_value),
                isError = state.issuesFor(DraftField.SMSC).isNotEmpty(),
            )
            Issues(state.issuesFor(DraftField.SMSC))
        }
    }

    draft.roaming()?.let { condition ->
        ConditionBox(
            title = stringResource(R.string.rule_edit_cond_roaming),
            onRemove = { viewModel.removeCondition(condition) },
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
                listOf(
                    null to R.string.rule_edit_roaming_any,
                    true to R.string.rule_edit_roaming_only,
                    false to R.string.rule_edit_roaming_not,
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = condition.roaming == value,
                        onClick = { viewModel.updateCondition(condition.copy(roaming = value)) },
                        label = { Text(stringResource(label)) },
                        shape = NjShape.chip,
                    )
                }
            }
        }
    }

    draft.text()?.let { condition ->
        ConditionBox(
            title = stringResource(R.string.rule_edit_cond_text),
            onRemove = { viewModel.removeCondition(condition) },
        ) {
            NjTextField(
                value = condition.value,
                onValueChange = { viewModel.updateCondition(condition.copy(value = it)) },
                label = stringResource(R.string.rule_edit_value),
                isError = state.issuesFor(DraftField.TEXT).isNotEmpty(),
            )
            Issues(state.issuesFor(DraftField.TEXT))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpRow(
    selected: StringMatch.Op,
    onChange: (StringMatch.Op) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
        // All four operators, as on the From row: a stored rule can carry any of them.
        StringMatch.Op.entries.forEach { op ->
            FilterChip(
                selected = selected == op,
                onClick = { onChange(op) },
                label = { Text(stringResource(matchOpLabelRes(op))) },
                shape = NjShape.chip,
            )
        }
    }
}

/** An inset box (`s2`) for one added condition: title, Delete and the condition's fields. */
@Composable
private fun ConditionBox(
    title: String,
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .padding(top = NjSpacing.m)
                .fillMaxWidth()
                .background(NjTheme.roles.s2, NjShape.field)
                .padding(start = NjSpacing.m, end = NjSpacing.xs, top = NjSpacing.xs, bottom = NjSpacing.m),
        verticalArrangement = Arrangement.spacedBy(NjSpacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = NjTheme.roles.tx,
                modifier = Modifier.weight(1f),
            )
            val removeDescription = stringResource(R.string.rule_edit_remove_condition, title)
            NjTextButton(
                text = stringResource(R.string.action_delete),
                onClick = onRemove,
                destructive = true,
                modifier = Modifier.semantics { contentDescription = removeDescription },
            )
        }
        Column(
            modifier = Modifier.padding(end = NjSpacing.m),
            verticalArrangement = Arrangement.spacedBy(NjSpacing.s),
        ) { content() }
    }
}

@Composable
private fun FieldLabel(
    title: String,
    hint: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = NjTheme.roles.tx)
        if (hint != null) NjCaption(hint)
    }
}

@Composable
private fun Issues(
    issues: List<DraftIssue>,
    onWhy: (() -> Unit)? = null,
) {
    issues.distinctBy { it.code to it.field }.forEach { issue ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = issueMessage(issue),
                style = MaterialTheme.typography.bodySmall,
                color = NjTheme.roles.hi,
                modifier = Modifier.weight(1f).padding(top = NjSpacing.xs),
            )
            if (issue.code == DraftIssueCode.BLOCKS_ALL_NORMAL && onWhy != null) {
                NjTextButton(text = stringResource(R.string.rule_edit_why), onClick = onWhy)
            }
        }
    }
}

@Composable
private fun issueMessage(issue: DraftIssue): String =
    when (issue.code) {
        // EMPTY_VALUE also covers a sender or SMSC that is empty after normalisation ("+", "00").
        DraftIssueCode.EMPTY_VALUE -> stringResource(R.string.rule_edit_error_empty)

        DraftIssueCode.REGEX_INVALID -> stringResource(R.string.rule_edit_error_regex, issue.detail)

        DraftIssueCode.REGEX_TOO_LONG -> stringResource(R.string.rule_edit_error_regex_long)

        DraftIssueCode.PORT_INVALID -> stringResource(R.string.rule_edit_error_port)

        DraftIssueCode.ACTION_NOT_ALLOWED -> stringResource(R.string.rule_edit_error_action)

        DraftIssueCode.BLOCKS_ALL_NORMAL -> stringResource(R.string.rule_edit_error_g05)

        DraftIssueCode.TOO_LARGE -> stringResource(R.string.rule_edit_error_too_large)

        DraftIssueCode.CLASSES_REQUIRED -> stringResource(R.string.rule_edit_error_classes)

        DraftIssueCode.INVALID_VALUE -> stringResource(R.string.rule_edit_error_invalid_value)
    }

/** "Would have matched n messages in the last 30 days", and how many of them a clamp changes. */
@Composable
private fun HistoryLine(history: PreviewResult) {
    Column(
        modifier = Modifier.padding(bottom = NjSpacing.s),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            pluralStringResource(R.plurals.rule_edit_history, history.count, history.count, history.windowDays),
            style = MaterialTheme.typography.bodyMedium,
            color = NjTheme.roles.tx,
        )
        if (history.clamped > 0) {
            NjCaption(pluralStringResource(R.plurals.rule_edit_history_clamped, history.clamped, history.clamped))
        }
    }
}

@Composable
private fun PduPreviewResult(
    preview: RulePreview,
    display: NumberDisplay,
) {
    val roles = NjTheme.roles
    when (preview) {
        RulePreview.Empty -> {
            Unit
        }

        is RulePreview.Invalid -> {
            Text(
                text = stringResource(R.string.rule_edit_preview_error, stringResource(invalidReasonRes(preview.reason))),
                style = MaterialTheme.typography.bodySmall,
                color = roles.hi,
            )
        }

        is RulePreview.Decoded -> {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(roles.s2, NjShape.field)
                        .padding(NjSpacing.m),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                preview.originator?.let {
                    Text(
                        stringResource(R.string.rule_edit_preview_sender, formatNumber(it, display).orEmpty()),
                        style = MaterialTheme.typography.bodySmall,
                        color = roles.tx,
                    )
                }
                preview.smsc?.let {
                    Text(
                        stringResource(R.string.rule_edit_preview_smsc, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = roles.tx,
                    )
                }
                preview.matchedClass?.let {
                    Text(
                        stringResource(R.string.rule_edit_preview_type, stringResource(classLabelRes(it))),
                        style = MaterialTheme.typography.bodySmall,
                        color = roles.tx,
                    )
                }
                preview.destinationPort?.let {
                    Text(
                        stringResource(R.string.rule_edit_preview_port, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = roles.tx,
                    )
                }
                Text(
                    text =
                        stringResource(
                            if (preview.matched) R.string.rule_edit_preview_match else R.string.rule_edit_preview_no_match,
                        ),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (preview.matched) roles.acc else roles.mu,
                    modifier = Modifier.padding(top = NjSpacing.xs),
                )
                preview.action?.let { action ->
                    Text(
                        stringResource(R.string.rule_edit_preview_outcome, stringResource(verdictLabelRes(action))),
                        style = MaterialTheme.typography.bodySmall,
                        color = roles.tx,
                    )
                }
                if (preview.clamped) {
                    NjCaption(stringResource(R.string.rule_edit_preview_clamped))
                }
            }
        }
    }
}

/** The name the editor offers while the user has not typed one. The sender follows the mask setting. */
@Composable
private fun suggestedName(
    draft: RuleDraft,
    display: NumberDisplay,
): String {
    val labels = draft.classes.sortedBy { it.ordinal }.map { stringResource(classLabelRes(it)) }
    if (labels.isEmpty()) return ""
    val from = formatNumber(draft.fromValue.trim(), display).orEmpty()
    if (from.isNotEmpty()) return stringResource(R.string.rule_edit_name_suggestion, labels.first(), from)
    return joinWith(labels, R.string.rule_edit_name_join)
}

/** The title of a subtype [code] of one of [classes]; the code itself when no row names it. */
@Composable
private fun subtypeLabel(
    classes: Set<ClassId>,
    code: String,
): String {
    val profile = classes.firstNotNullOfOrNull { ClassRegistry.subtype(it, code) } ?: return code
    return LocalStrings.current.get(profile.titleKey)
}

private fun invalidReasonRes(reason: RulePreview.InvalidReason): Int =
    when (reason) {
        RulePreview.InvalidReason.MALFORMED -> R.string.rule_edit_preview_malformed
        RulePreview.InvalidReason.NO_FIELDS -> R.string.rule_edit_preview_no_fields
    }

// ------------------------------------------------------------------ previews

/** The editor frame with the Type card, an issue and the Save bar. The full form needs the ViewModel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewRuleEditor() {
    ToolScreen(
        title = stringResource(R.string.rule_edit_title_new),
        onBack = {},
        bottomBar = { SaveBar(canSave = false, hasIssues = true, onSave = {}) },
    ) {
        item(key = "when-header") { ToolSection(stringResource(R.string.rule_edit_when)) }
        item(key = "when-type") {
            NjCard(Modifier.toolCard()) {
                FieldLabel(stringResource(R.string.rule_edit_type), stringResource(R.string.rule_edit_type_hint))
                FlowRow(
                    modifier = Modifier.padding(top = NjSpacing.s),
                    horizontalArrangement = Arrangement.spacedBy(NjSpacing.s),
                ) {
                    FilterChip(true, {}, label = { Text(stringResource(classLabelRes(ClassId.WAP_CP))) }, shape = NjShape.chip)
                    FilterChip(false, {}, label = { Text(stringResource(classLabelRes(ClassId.DATA_PORT))) }, shape = NjShape.chip)
                }
                Issues(listOf(DraftIssue(code = DraftIssueCode.CLASSES_REQUIRED, field = DraftField.CLASSES)))
            }
        }
        item(key = "test-card") {
            NjCard(Modifier.toolCard()) {
                PduPreviewResult(RulePreview.Invalid(RulePreview.InvalidReason.MALFORMED), NumberDisplay.PLAIN)
            }
        }
    }
}

@Preview(name = "Rule editor dark", widthDp = 400, heightDp = 700)
@Composable
private fun RuleEditorDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewRuleEditor() }
}

@Preview(name = "Rule editor light", widthDp = 400, heightDp = 700)
@Composable
private fun RuleEditorLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { PreviewRuleEditor() }
}
