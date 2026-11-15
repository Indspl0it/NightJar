package dev.nightjar.app.feature.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.domain.selftest.CanaryClass
import dev.nightjar.app.domain.selftest.ClassResult
import dev.nightjar.app.domain.selftest.SelfTestOutcome
import dev.nightjar.app.domain.selftest.SelfTestStatus
import dev.nightjar.app.ui.components.NjBadge
import dev.nightjar.app.ui.components.NjBottomSheet
import dev.nightjar.app.ui.components.NjCaption
import dev.nightjar.app.ui.components.NjCard
import dev.nightjar.app.ui.components.NjDialog
import dev.nightjar.app.ui.components.NjGroup
import dev.nightjar.app.ui.components.NjIcons
import dev.nightjar.app.ui.components.NjListRow
import dev.nightjar.app.ui.components.NjPrimaryButton
import dev.nightjar.app.ui.components.NjRowDivider
import dev.nightjar.app.ui.components.NjTextButton
import dev.nightjar.app.ui.components.NjTonalButton
import dev.nightjar.app.ui.theme.NjRoles
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme

/**
 * The Health self-test block (03). It is optional diagnostics: protection works without ever running it and
 * nothing on this screen blocks on its result.
 *
 * Rendered as one item of the Health list (the title and the card in one column); the picker/progress/report sheet is
 * [SelfTestSheet].
 */
@Composable
fun SelfTestSection(
    state: SelfTestUiState,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(NjSpacing.s + 2.dp)) {
        StatusSectionTitle(stringResource(R.string.selftest_optional_title))
        NjCard {
            Column(verticalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
                Text(
                    stringResource(R.string.selftest_optional_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NjTheme.roles.mu,
                )
                val last = state.lastRun
                if (last != null) {
                    NjCaption(stringResource(R.string.selftest_last_run, formatMediumDate(last.startedAt)))
                    Badges(last)
                } else {
                    NjCaption(stringResource(R.string.health_selftest_none))
                }

                if (state.phase == SelfTestPhase.RUNNING) {
                    Progress(state)
                    NjTextButton(text = stringResource(R.string.dlg_28_cancel), onClick = onCancel)
                } else {
                    NjTonalButton(
                        text = stringResource(R.string.action_run_self_test),
                        onClick = onOpen,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // The self-test covers Android-side detection only.
                NjCaption(stringResource(R.string.msg_tst_note))
            }
        }
    }
}

/**
 * Picker, live progress and the result report.
 *
 * One sheet across the whole run so the state machine's phases read as one surface; the blocking-rules warning is a
 * dialog on top of it.
 */
@Composable
fun SelfTestSheet(
    state: SelfTestUiState,
    onDismiss: () -> Unit,
    onToggle: (CanaryClass) -> Unit,
    onSetAll: (Boolean) -> Unit,
    onStart: () -> Unit,
    onConfirm: () -> Unit,
    onDismissConfirm: () -> Unit,
    onCancel: () -> Unit,
    onRunAgain: () -> Unit,
    onCloseReport: () -> Unit,
    onDismissError: () -> Unit,
) {
    if (state.phase == SelfTestPhase.IDLE) return

    // A swipe, a scrim tap or Back cannot hide the sheet while canaries are in flight: close() refuses then, and a
    // hidden sheet would leave an invisible modal over the screen.
    NjBottomSheet(onDismiss = onDismiss, dismissible = state.phase != SelfTestPhase.RUNNING) {
        SelfTestSheetBody(
            state = state,
            onDismiss = onDismiss,
            onToggle = onToggle,
            onSetAll = onSetAll,
            onStart = onStart,
            onCancel = onCancel,
            onRunAgain = onRunAgain,
            onCloseReport = onCloseReport,
        )
    }

    if (state.phase == SelfTestPhase.CONFIRM) {
        NjDialog(
            title = stringResource(R.string.dlg_28_title),
            text = stringResource(R.string.dlg_28_body),
            confirmText = stringResource(R.string.dlg_28_confirm),
            dismissText = stringResource(R.string.dlg_28_cancel),
            onDismiss = onDismissConfirm,
            onConfirm = onConfirm,
        )
    }

    state.error?.let { error ->
        NjDialog(
            title = stringResource(R.string.selftest_error_title),
            text = stringResource(errorText(error)),
            confirmText = stringResource(R.string.action_ok),
            dismissText = null,
            onDismiss = onDismissError,
            onConfirm = onDismissError,
        )
    }
}

/** The content of the sheet for the current phase. Separate from the modal sheet so the previews can show it. */
@Composable
private fun SelfTestSheetBody(
    state: SelfTestUiState,
    onDismiss: () -> Unit,
    onToggle: (CanaryClass) -> Unit,
    onSetAll: (Boolean) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRunAgain: () -> Unit,
    onCloseReport: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NjSpacing.m),
        verticalArrangement = Arrangement.spacedBy(NjSpacing.s + 2.dp),
    ) {
        when (state.phase) {
            SelfTestPhase.PICKER -> PickerContent(state, onToggle, onSetAll, onStart, onDismiss)
            SelfTestPhase.CONFIRM -> PickerContent(state, onToggle, onSetAll, onStart, onDismiss)
            SelfTestPhase.RUNNING -> RunningContent(state, onCancel)
            SelfTestPhase.REPORT -> ReportContent(state, onRunAgain, onCloseReport)
            SelfTestPhase.IDLE -> Unit
        }
        CoverageBlock(state)
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        color = NjTheme.roles.tx,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun PickerContent(
    state: SelfTestUiState,
    onToggle: (CanaryClass) -> Unit,
    onSetAll: (Boolean) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetTitle(stringResource(R.string.bs_07_title))
    Text(stringResource(R.string.selftest_picker_hint), style = MaterialTheme.typography.bodyMedium, color = NjTheme.roles.mu)
    NjTextButton(
        text = stringResource(if (state.allSelected) R.string.selftest_clear_all else R.string.selftest_select_all),
        onClick = { onSetAll(!state.allSelected) },
    )
    NjGroup {
        CanaryClass.order.forEachIndexed { index, canary ->
            if (index > 0) NjRowDivider()
            NjListRow(
                title = stringResource(canaryLabel(canary)),
                leading = { Checkbox(checked = canary in state.selected, onCheckedChange = null) },
                onClick = { onToggle(canary) },
                showChevron = false,
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = NjSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(NjSpacing.s + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NjTonalButton(text = stringResource(R.string.dlg_28_cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
        NjPrimaryButton(
            text = stringResource(R.string.bs_07_start),
            onClick = onStart,
            enabled = state.canStart,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Progress bar, "n of m" and the type under test. */
@Composable
private fun Progress(state: SelfTestUiState) {
    LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth(),
        progress = { if (state.total <= 0) 0f else state.completed.toFloat() / state.total },
        color = NjTheme.roles.acc,
        trackColor = NjTheme.roles.s3,
    )
    Text(
        stringResource(R.string.selftest_progress, state.completed, state.total),
        style = MaterialTheme.typography.labelMedium,
        color = NjTheme.roles.tx,
    )
    state.current?.let { canary ->
        NjCaption(stringResource(R.string.selftest_running, stringResource(canaryLabel(canary))))
    }
}

@Composable
private fun RunningContent(
    state: SelfTestUiState,
    onCancel: () -> Unit,
) {
    SheetTitle(stringResource(R.string.selftest_title))
    NjCard {
        Column(verticalArrangement = Arrangement.spacedBy(NjSpacing.s)) { Progress(state) }
    }
    if (state.results.isNotEmpty()) {
        NjGroup {
            state.results.forEachIndexed { index, result ->
                if (index > 0) NjRowDivider()
                ResultRow(result.canary, result)
            }
        }
    }
    NjTextButton(text = stringResource(R.string.dlg_28_cancel), onClick = onCancel)
}

@Composable
private fun ReportContent(
    state: SelfTestUiState,
    onRunAgain: () -> Unit,
    onClose: () -> Unit,
) {
    SheetTitle(stringResource(R.string.health_selftest_results))
    state.lastRun?.let { run ->
        NjCaption(stringResource(R.string.selftest_last_run, formatMediumDate(run.startedAt)))
    }
    NjGroup {
        CanaryClass.order.forEachIndexed { index, canary ->
            if (index > 0) NjRowDivider()
            ResultRow(canary, state.results.firstOrNull { it.canary == canary })
        }
    }
    NjCaption(stringResource(R.string.msg_tst_note))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NjSpacing.s + 2.dp),
    ) {
        NjTonalButton(
            text = stringResource(R.string.selftest_run_again),
            onClick = onRunAgain,
            modifier = Modifier.weight(1f),
        )
        NjPrimaryButton(
            text = stringResource(R.string.selftest_close),
            onClick = onClose,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One class with its outcome and reason; the status is a word, never colour alone. */
@Composable
private fun ResultRow(
    canary: CanaryClass,
    result: ClassResult?,
) {
    NjListRow(
        title = stringResource(canaryLabel(canary)),
        supporting = result?.let { reasonInWords(it) },
        trailing = { StatusBadge(result?.status) },
    )
}

/** The reason code of a result in plain words; the raw code stays in the stored report. */
@Composable
private fun reasonInWords(result: ClassResult): String? {
    val reason = result.reason
    return when {
        reason == null && result.status == SelfTestStatus.TIMEOUT -> {
            stringResource(R.string.selftest_why_timeout)
        }

        reason == null -> {
            null
        }

        reason.startsWith("SKIPPED:") -> {
            stringResource(R.string.selftest_why_skipped, reason.removePrefix("SKIPPED:"))
        }

        reason.startsWith("CLASS:") -> {
            stringResource(R.string.selftest_why_class, plainCode(reason.removePrefix("CLASS:"), "CL-"))
        }

        reason.startsWith("STAGE:") -> {
            stringResource(R.string.selftest_why_stage, reason.removePrefix("STAGE:"))
        }

        reason.startsWith("FATE:") -> {
            stringResource(R.string.selftest_why_fate, plainCode(reason.removePrefix("FATE:"), "FT-"))
        }

        else -> {
            stringResource(R.string.selftest_reason, reason)
        }
    }
}

private fun plainCode(
    code: String,
    prefix: String,
): String =
    code
        .removePrefix(prefix)
        .lowercase()
        .replace('-', ' ')
        .replace('_', ' ')

/**
 * Neutral by design: a failed canary is information, not an alarm, so it uses the amber tone and never red. The icon
 * marks pass and fail, so colour is not the only cue.
 */
private fun statusColor(
    roles: NjRoles,
    status: SelfTestStatus?,
): Color =
    when (status) {
        SelfTestStatus.PASS -> roles.ok
        SelfTestStatus.FAIL, SelfTestStatus.TIMEOUT -> roles.amb
        else -> roles.mu
    }

private fun statusIcon(status: SelfTestStatus?): ImageVector? =
    when (status) {
        SelfTestStatus.PASS -> NjIcons.Check
        SelfTestStatus.FAIL, SelfTestStatus.TIMEOUT -> NjIcons.Diamond
        else -> null
    }

@Composable
private fun StatusBadge(status: SelfTestStatus?) {
    NjBadge(
        text = stringResource(statusLabel(status)),
        color = statusColor(NjTheme.roles, status),
        icon = statusIcon(status),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Badges(run: SelfTestOutcome) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(NjSpacing.s),
        verticalArrangement = Arrangement.spacedBy(NjSpacing.s),
    ) {
        CanaryClass.order.forEach { canary ->
            val status = run.resultFor(canary)?.status
            NjBadge(
                text = stringResource(canaryLabel(canary)) + ": " + stringResource(statusLabel(status)),
                color = statusColor(NjTheme.roles, status),
                icon = statusIcon(status),
            )
        }
    }
}

/** Coverage: last time each self-test class was seen from the network, or "never". */
@Composable
private fun CoverageBlock(state: SelfTestUiState) {
    val classes = CanaryClass.order.map { it.classId }
    val byClass = orderedCoverage(state.coverage, classes).associateBy { it.classId }
    StatusSectionTitle(stringResource(R.string.health_coverage))
    NjGroup {
        CanaryClass.order.forEachIndexed { index, canary ->
            if (index > 0) NjRowDivider()
            val row = byClass[canary.classId]
            NjListRow(
                title = stringResource(canaryLabel(canary)),
                trailingValue = row?.lastSeenFromNetwork?.let(::formatMediumDate) ?: stringResource(R.string.health_never),
            )
        }
    }
}

private fun canaryLabel(canary: CanaryClass): Int =
    when (canary) {
        CanaryClass.FLASH -> R.string.selftest_canary_flash
        CanaryClass.DATA_PORT -> R.string.selftest_canary_data_port
        CanaryClass.WAP_SI -> R.string.selftest_canary_wap_si
        CanaryClass.WAP_CP -> R.string.selftest_canary_wap_cp
        CanaryClass.MWI -> R.string.selftest_canary_mwi
        CanaryClass.SILENT -> R.string.selftest_canary_silent
        CanaryClass.SIM_DD -> R.string.selftest_canary_sim_dd
        CanaryClass.REPLACE -> R.string.selftest_canary_replace
    }

private fun statusLabel(status: SelfTestStatus?): Int =
    when (status) {
        SelfTestStatus.PASS -> R.string.selftest_status_pass
        SelfTestStatus.FAIL -> R.string.selftest_status_fail
        SelfTestStatus.SKIPPED -> R.string.selftest_status_skipped
        SelfTestStatus.TIMEOUT -> R.string.selftest_status_timeout
        SelfTestStatus.CANCELLED -> R.string.selftest_status_cancelled
        null -> R.string.selftest_status_not_run
    }

private fun errorText(error: SelfTestError): Int =
    when (error) {
        SelfTestError.LOCKED -> R.string.selftest_error_locked
        SelfTestError.BUSY -> R.string.selftest_error_busy
        SelfTestError.INVALID -> R.string.selftest_error_invalid
        SelfTestError.UNAVAILABLE -> R.string.selftest_error_unavailable
    }

// ------------------------------------------------------------------ previews

private fun previewSelfTestReport(): SelfTestUiState {
    val results =
        listOf(
            ClassResult(CanaryClass.FLASH, SelfTestStatus.PASS),
            ClassResult(CanaryClass.DATA_PORT, SelfTestStatus.PASS),
            ClassResult(CanaryClass.WAP_SI, SelfTestStatus.TIMEOUT),
            ClassResult(CanaryClass.SILENT, SelfTestStatus.SKIPPED, "SKIPPED:HK-04"),
        )
    return SelfTestUiState(
        phase = SelfTestPhase.REPORT,
        results = results,
        lastRun = SelfTestOutcome("preview", 0, 1, cancelled = false, results = results),
    )
}

@Composable
private fun PreviewSheet(state: SelfTestUiState) {
    Column(Modifier.background(NjTheme.roles.bg).padding(vertical = NjSpacing.m)) {
        SelfTestSheetBody(state, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(name = "Self-test report dark", heightDp = 1200)
@Composable
private fun SelfTestReportDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewSheet(previewSelfTestReport()) }
}

@Preview(name = "Self-test report light", heightDp = 1200)
@Composable
private fun SelfTestReportLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { PreviewSheet(previewSelfTestReport()) }
}

@Preview(name = "Self-test picker dark", heightDp = 1200)
@Composable
private fun SelfTestPickerDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewSheet(SelfTestUiState(phase = SelfTestPhase.PICKER)) }
}

@Preview(name = "Self-test section light")
@Composable
private fun SelfTestSectionLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) {
        Column(Modifier.background(NjTheme.roles.bg).padding(NjSpacing.m)) {
            SelfTestSection(state = previewSelfTestReport().copy(phase = SelfTestPhase.IDLE), onOpen = {}, onCancel = {})
        }
    }
}
