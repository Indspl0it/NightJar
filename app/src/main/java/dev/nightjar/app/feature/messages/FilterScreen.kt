package dev.nightjar.app.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.data.model.FeedChip
import dev.nightjar.app.data.model.FilterSpec
import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.text.Strings
import dev.nightjar.app.domain.text.genericTitle
import dev.nightjar.app.ui.components.NjBottomSheet
import dev.nightjar.app.ui.components.NjDialog
import dev.nightjar.app.ui.components.NjGroup
import dev.nightjar.app.ui.components.NjListRow
import dev.nightjar.app.ui.components.NjPrimaryButton
import dev.nightjar.app.ui.components.NjRowDivider
import dev.nightjar.app.ui.components.NjSectionHeader
import dev.nightjar.app.ui.components.NjSwitchRow
import dev.nightjar.app.ui.components.NjTextButton
import dev.nightjar.app.ui.components.NjTextField
import dev.nightjar.app.ui.components.NjTonalButton
import dev.nightjar.app.ui.text.LocalStrings
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Severity

/**
 * Presented over Messages as an [NjBottomSheet]. Home also opens it: [onApplied] runs after
 * "Apply", so Home can open Messages. [onDismiss] runs when the user closes the sheet.
 */
@Composable
fun FilterScreen(
    onDismiss: () -> Unit,
    onApplied: () -> Unit = onDismiss,
    viewModel: FilterViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val applied by rememberUpdatedState(onApplied)
    // The sheet leaves the composition when it closes, so this flag is new for each opening and kept on rotation.
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!opened) {
            viewModel.onAction(FilterAction.Open)
            opened = true
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                FilterEvent.Applied -> applied()
            }
        }
    }
    NjBottomSheet(onDismiss = onDismiss) {
        FilterContent(
            state = state,
            onAction = viewModel::onAction,
        )
    }
    if (state.saveDialogOpen) {
        NjDialog(
            title = stringResource(R.string.filter_save_view),
            confirmText = stringResource(R.string.action_save),
            onConfirm = { viewModel.onAction(FilterAction.SaveView) },
            onDismiss = { viewModel.onAction(FilterAction.CloseSaveDialog) },
        ) {
            NjTextField(
                value = state.saveName,
                onValueChange = { viewModel.onAction(FilterAction.SaveName(it)) },
                label = stringResource(R.string.filter_view_name),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterContent(
    state: FilterUiState,
    onAction: (FilterAction) -> Unit,
) {
    val strings = LocalStrings.current
    var moreOpen by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NjSpacing.m),
        verticalArrangement = Arrangement.spacedBy(NjSpacing.s),
    ) {
        Text(
            stringResource(R.string.bs_01_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )

        // Home sets these two conditions and the sheet has no control for them: a chip shows each, a tap removes it.
        if (state.draft.fates.isNotEmpty() || state.draft.attentionOnly) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
                if (state.draft.fates.isNotEmpty()) {
                    RemovableChip(stringResource(R.string.home_stat_blocked)) { onAction(FilterAction.ClearFates) }
                }
                if (state.draft.attentionOnly) {
                    RemovableChip(stringResource(R.string.home_needs_attention)) { onAction(FilterAction.ClearAttention) }
                }
            }
        }

        NjSectionHeader(stringResource(R.string.filter_date))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
            DatePreset.entries.forEach { preset ->
                ChoiceChip(
                    label =
                        stringResource(
                            when (preset) {
                                DatePreset.ANY -> R.string.filter_date_any
                                DatePreset.TODAY -> R.string.filter_date_today
                                DatePreset.LAST_7_DAYS -> R.string.filter_date_7d
                                DatePreset.LAST_30_DAYS -> R.string.filter_date_30d
                            },
                        ),
                    selected = state.datePreset == preset,
                    onClick = { onAction(FilterAction.SetDatePreset(preset)) },
                )
            }
        }

        NjSectionHeader(stringResource(R.string.filter_classes))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
            ChoiceChip(
                label = stringResource(R.string.nj_data_chip_delivery_reports),
                selected = state.draft.showDeliveryReports,
                onClick = { onAction(FilterAction.SetDeliveryReports(!state.draft.showDeliveryReports)) },
            )
            filterableClasses().forEach { classId ->
                ChoiceChip(
                    label = strings.genericTitle(ClassRegistry.of(classId).titleKey),
                    selected = classId in state.draft.classIds,
                    onClick = { onAction(FilterAction.ToggleClass(classId)) },
                )
            }
        }

        NjSectionHeader(stringResource(R.string.filter_severity))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
            Severity.entries.forEach { severity ->
                ChoiceChip(
                    label = severityLabel(severity, strings),
                    selected = severity in state.draft.severities,
                    onClick = { onAction(FilterAction.ToggleSeverity(severity)) },
                )
            }
        }

        NjSectionHeader(stringResource(R.string.filter_direction))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
            ChoiceChip(
                label = stringResource(R.string.filter_any),
                selected = state.draft.direction == null,
                onClick = { onAction(FilterAction.SetDirection(null)) },
            )
            ChoiceChip(
                label = stringResource(R.string.filter_inbound),
                selected = state.draft.direction == Direction.IN,
                onClick = { onAction(FilterAction.SetDirection(Direction.IN)) },
            )
            ChoiceChip(
                label = stringResource(R.string.filter_outbound),
                selected = state.draft.direction == Direction.OUT,
                onClick = { onAction(FilterAction.SetDirection(Direction.OUT)) },
            )
        }

        NjSectionHeader(stringResource(R.string.filter_sim))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
            ChoiceChip(
                label = stringResource(R.string.filter_sim_any),
                selected = state.draft.chip != FeedChip.SIM1 && state.draft.chip != FeedChip.SIM2,
                onClick = { onAction(FilterAction.SetSim(null)) },
            )
            ChoiceChip(
                label = stringResource(R.string.msgs_chip_sim1),
                selected = state.draft.chip == FeedChip.SIM1,
                onClick = { onAction(FilterAction.SetSim(0)) },
            )
            ChoiceChip(
                label = stringResource(R.string.msgs_chip_sim2),
                selected = state.draft.chip == FeedChip.SIM2,
                onClick = { onAction(FilterAction.SetSim(1)) },
            )
        }

        NjGroup(Modifier.padding(top = NjSpacing.s)) {
            NjSwitchRow(
                title = stringResource(R.string.filter_unreviewed),
                checked = state.draft.unreviewedOnly,
                onCheckedChange = { onAction(FilterAction.SetUnreviewed(it)) },
            )
            NjRowDivider()
            NjSwitchRow(
                title = stringResource(R.string.filter_starred),
                checked = state.draft.starredOnly,
                onCheckedChange = { onAction(FilterAction.SetStarred(it)) },
            )
        }

        // Lists these three fields and the star switch; they existed in FilterSpec with no control.
        NjGroup(Modifier.padding(top = NjSpacing.s)) {
            NjListRow(
                title = stringResource(R.string.filter_advanced),
                onClick = { moreOpen = !moreOpen },
                trailingValue = null,
            )
            if (moreOpen) {
                Column(
                    modifier = Modifier.padding(start = NjSpacing.m, end = NjSpacing.m, bottom = NjSpacing.m),
                    verticalArrangement = Arrangement.spacedBy(NjSpacing.s),
                ) {
                    NjTextField(
                        label = stringResource(R.string.filter_originator),
                        value = state.draft.originatorQuery.orEmpty(),
                        onValueChange = { onAction(FilterAction.SetOriginator(it)) },
                    )
                    NjTextField(
                        label = stringResource(R.string.filter_port),
                        value =
                            state.draft.port
                                ?.toString()
                                .orEmpty(),
                        onValueChange = { onAction(FilterAction.SetPort(it)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    NjTextField(
                        label = stringResource(R.string.filter_content_type),
                        value = state.draft.contentType.orEmpty(),
                        onValueChange = { onAction(FilterAction.SetContentType(it)) },
                    )
                    NjTextField(
                        label = stringResource(R.string.filter_package),
                        value = state.draft.packageQuery.orEmpty(),
                        onValueChange = { onAction(FilterAction.SetPackage(it)) },
                    )
                }
            }
        }

        NjSectionHeader(stringResource(R.string.filter_saved_views))
        if (state.savedViewUnreadable) {
            Text(
                stringResource(R.string.filter_view_unreadable),
                style = MaterialTheme.typography.bodySmall,
                color = NjTheme.roles.hi,
            )
        }
        NjGroup {
            if (state.savedViews.isEmpty()) {
                NjListRow(title = stringResource(R.string.filter_no_saved))
            } else {
                state.savedViews.forEachIndexed { index, view ->
                    if (index > 0) NjRowDivider()
                    NjListRow(
                        title = view.name,
                        onClick = { onAction(FilterAction.ApplySavedView(view)) },
                        showChevron = false,
                        trailing = {
                            NjTextButton(
                                text = stringResource(R.string.action_delete),
                                onClick = { onAction(FilterAction.DeleteSavedView(view)) },
                                destructive = true,
                            )
                        },
                    )
                }
            }
        }
        NjTextButton(text = stringResource(R.string.filter_save_view), onClick = { onAction(FilterAction.OpenSaveDialog) })

        if (state.writeFailed) {
            Text(
                stringResource(R.string.change_failed),
                style = MaterialTheme.typography.bodySmall,
                color = NjTheme.roles.hi,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = NjSpacing.s),
            horizontalArrangement = Arrangement.spacedBy(NjSpacing.s),
        ) {
            NjTonalButton(
                text = stringResource(R.string.bs_01_reset),
                onClick = { onAction(FilterAction.Reset) },
                modifier = Modifier.weight(1f),
            )
            NjPrimaryButton(
                text = stringResource(R.string.bs_01_apply),
                onClick = { onAction(FilterAction.Apply) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        shape = NjShape.chip,
    )
}

@Composable
private fun severityLabel(
    severity: Severity,
    strings: Strings,
): String =
    strings.get(
        when (severity) {
            Severity.INFO -> dev.nightjar.app.domain.text.TextKey.SEVERITY_INFO
            Severity.NOTABLE -> dev.nightjar.app.domain.text.TextKey.SEVERITY_NOTABLE
            Severity.SUSPICIOUS -> dev.nightjar.app.domain.text.TextKey.SEVERITY_SUSPICIOUS
            Severity.HIGH -> dev.nightjar.app.domain.text.TextKey.SEVERITY_HIGH
        },
    )

@Preview(name = "filter light")
@Composable
private fun FilterLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) {
        FilterContent(
            state =
                FilterUiState(
                    draft =
                        FilterSpec(
                            classIds = setOf(ClassId.WAP_CP, ClassId.SILENT),
                            severities = setOf(Severity.SUSPICIOUS),
                            unreviewedOnly = true,
                        ),
                ),
            onAction = {},
        )
    }
}

@Preview(name = "filter dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FilterDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        FilterContent(state = FilterUiState(), onAction = {})
    }
}
