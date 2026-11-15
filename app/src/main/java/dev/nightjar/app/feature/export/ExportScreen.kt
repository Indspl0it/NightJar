package dev.nightjar.app.feature.export

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.feature.rules.ToolScreen
import dev.nightjar.app.feature.rules.ToolSection
import dev.nightjar.app.feature.rules.toolCard
import dev.nightjar.app.feature.rules.toolLoading
import dev.nightjar.app.ui.components.NjCaption
import dev.nightjar.app.ui.components.NjCard
import dev.nightjar.app.ui.components.NjChoiceRow
import dev.nightjar.app.ui.components.NjDialog
import dev.nightjar.app.ui.components.NjGroup
import dev.nightjar.app.ui.components.NjPrimaryButton
import dev.nightjar.app.ui.components.NjRowDivider
import dev.nightjar.app.ui.components.NjSwitchRow
import dev.nightjar.app.ui.components.NjTextButton
import dev.nightjar.app.ui.components.NjTonalButton
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.nav.NavActions
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme

/** What to do after the export dialog has been answered. */
private enum class PendingExport { SAVE, SHARE }

/** Export (-01, 03). */
@Composable
fun ExportScreen(actions: NavActions) {
    val viewModel: ExportViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current

    var pending by remember { mutableStateOf<PendingExport?>(null) }
    // One launcher per format, so the picker and the target app get the real type of the file.
    val onPicked: (Uri?) -> Unit = { uri -> if (uri != null) viewModel.exportTo(uri) }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.JSON.mime), onPicked)
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.CSV.mime), onPicked)
    val textLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.PDU_HEX.mime), onPicked)
    val startExport: () -> Unit = {
        val launcher =
            when (state.format) {
                ExportFormat.JSON -> jsonLauncher
                ExportFormat.CSV -> csvLauncher
                ExportFormat.PDU_HEX -> textLauncher
            }
        launcher.launch(exportFileName(state.format, System.currentTimeMillis()))
    }
    val startShare: () -> Unit = { viewModel.share() }

    val failed = stringResource(R.string.export_failed)
    val cancelled = stringResource(R.string.export_cancelled)
    val open = stringResource(R.string.export_open)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ExportEvent.Done -> {
                    val done =
                        resources.getQuantityString(R.plurals.export_done, event.count, event.count)
                    val result = snackbar.showSnackbar(done, open, duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) {
                        event.destination?.let { destination ->
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(destination.toUri(), state.format.mime)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                )
                            }
                        }
                    }
                }

                ExportEvent.Failed -> {
                    snackbar.showSnackbar(failed)
                }

                ExportEvent.Cancelled -> {
                    snackbar.showSnackbar(cancelled)
                }

                is ExportEvent.ShareReady -> {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", event.file)
                    val send =
                        Intent(Intent.ACTION_SEND)
                            .setType(state.format.mime)
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .apply { clipData = ClipData.newUri(context.contentResolver, "export", uri) }
                    context.startActivity(Intent.createChooser(send, resources.getString(R.string.export_share_title)))
                }
            }
        }
    }

    LaunchedEffect(state.busy) {
        if (state.busy) pending = null
    }

    ExportContent(
        state = state,
        snackbar = snackbar,
        onBack = { actions.back() },
        onScope = viewModel::setScope,
        onFormat = viewModel::setFormat,
        onIncludeText = viewModel::setIncludeText,
        onIncludeTree = viewModel::setIncludeTree,
        onMaskNumbers = viewModel::setMaskNumbers,
        // Warns about message text, so it shows only when the export includes the text.
        onExport = { if (state.options.includeText) pending = PendingExport.SAVE else startExport() },
        onShare = { if (state.options.includeText) pending = PendingExport.SHARE else startShare() },
        onCancel = viewModel::cancel,
    )

    pending?.let { action ->
        NjDialog(
            title = stringResource(R.string.dlg_11_title),
            text = stringResource(R.string.dlg_11_body),
            confirmText = stringResource(R.string.dlg_11_confirm),
            dismissText = stringResource(R.string.dlg_11_cancel),
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                when (action) {
                    PendingExport.SAVE -> startExport()
                    PendingExport.SHARE -> startShare()
                }
            },
        )
    }
}

/** The side margin of a caption under a group: it lines up with the text in the rows. */
private val CaptionModifier = Modifier.padding(start = NjSpacing.m + NjSpacing.xs, end = NjSpacing.m, top = NjSpacing.s)

@Composable
private fun ExportContent(
    state: ExportUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onScope: (ExportScope) -> Unit,
    onFormat: (ExportFormat) -> Unit,
    onIncludeText: (Boolean) -> Unit,
    onIncludeTree: (Boolean) -> Unit,
    onMaskNumbers: (Boolean) -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
) {
    ToolScreen(
        title = stringResource(R.string.screen_s11),
        onBack = onBack,
        snackbar = snackbar,
    ) {
        if (state.loading) {
            toolLoading(3)
            return@ToolScreen
        }

        item(key = "scope-header") { ToolSection(stringResource(R.string.export_scope), Modifier.njEnter(1)) }
        item(key = "scope") {
            NjGroup(Modifier.toolCard().njEnter(2)) {
                ScopeOption(ExportScope.SELECTION, state, onScope)
                NjRowDivider()
                ScopeOption(ExportScope.FILTER, state, onScope)
                NjRowDivider()
                ScopeOption(ExportScope.ALL, state, onScope)
            }
        }
        if (!state.hasSelection) {
            item(key = "selection-hint") { NjCaption(stringResource(R.string.export_selection_hint), CaptionModifier) }
        }
        if (!state.hasFilter) {
            item(key = "filter-hint") { NjCaption(stringResource(R.string.export_filter_hint), CaptionModifier) }
        }

        item(key = "format-header") { ToolSection(stringResource(R.string.export_format), Modifier.njEnter(3)) }
        item(key = "format") {
            NjGroup(Modifier.toolCard().njEnter(4)) {
                ExportFormat.entries.forEachIndexed { index, format ->
                    if (index > 0) NjRowDivider()
                    RadioRow(
                        label = formatLabel(format),
                        selected = state.format == format,
                        enabled = !state.busy,
                        onClick = { onFormat(format) },
                    )
                }
            }
        }

        item(key = "options-header") { ToolSection(stringResource(R.string.export_options), Modifier.njEnter(5)) }
        item(key = "options-hint") { NjCaption(stringResource(R.string.export_include_text_hint), CaptionModifier) }
        if (!state.options.showsRawPdu) {
            item(key = "hex-hint") { NjCaption(stringResource(R.string.export_hex_hidden), CaptionModifier) }
        }
        item(key = "options") {
            NjGroup(Modifier.toolCard().njEnter(6)) {
                NjSwitchRow(
                    title = stringResource(R.string.export_include_text),
                    checked = state.options.includeText,
                    onCheckedChange = onIncludeText,
                    enabled = !state.busy,
                )
                NjRowDivider()
                NjSwitchRow(
                    title = stringResource(R.string.export_mask_numbers),
                    supporting = stringResource(R.string.export_mask_numbers_hint),
                    checked = state.options.maskNumbers,
                    onCheckedChange = onMaskNumbers,
                    enabled = !state.busy,
                )
                NjRowDivider()
                NjSwitchRow(
                    title = stringResource(R.string.export_include_tree),
                    supporting =
                        if (state.format != ExportFormat.JSON || !state.options.showsRawPdu) {
                            stringResource(R.string.export_tree_unavailable)
                        } else {
                            null
                        },
                    checked = state.options.includeTree,
                    onCheckedChange = onIncludeTree,
                    enabled = !state.busy && state.format == ExportFormat.JSON && state.options.showsRawPdu,
                )
            }
        }

        item(key = "run") {
            if (state.busy) {
                ProgressCard(state.progress, onCancel, Modifier.toolCard().padding(top = NjSpacing.s))
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NjSpacing.s),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .toolCard()
                            .padding(top = NjSpacing.s)
                            .njEnter(7),
                ) {
                    NjPrimaryButton(
                        text = stringResource(R.string.export_export),
                        onClick = onExport,
                        enabled = state.scopeCount > 0,
                        modifier = Modifier.weight(1f),
                    )
                    NjTonalButton(
                        text = stringResource(R.string.export_share),
                        onClick = onShare,
                        enabled = state.scopeCount > 0,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** The progress of a running export, with Cancel. */
@Composable
private fun ProgressCard(
    progress: ExportProgress?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    NjCard(modifier) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            progress = {
                if (progress == null || progress.total <= 0) 0f else progress.done.toFloat() / progress.total
            },
            color = roles.acc,
            trackColor = roles.s3,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text =
                    stringResource(
                        R.string.export_progress,
                        progress?.done ?: 0,
                        progress?.total ?: 0,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = roles.tx,
                modifier = Modifier.weight(1f),
            )
            NjTextButton(text = stringResource(R.string.export_cancel), onClick = onCancel)
        }
    }
}

@Composable
private fun ScopeOption(
    scope: ExportScope,
    state: ExportUiState,
    onScope: (ExportScope) -> Unit,
) {
    val enabled =
        when (scope) {
            ExportScope.SELECTION -> state.hasSelection
            ExportScope.FILTER -> state.hasFilter
            ExportScope.ALL -> true
        }
    val label =
        stringResource(
            when (scope) {
                ExportScope.SELECTION -> R.string.export_scope_selection
                ExportScope.FILTER -> R.string.export_scope_filter
                ExportScope.ALL -> R.string.export_scope_all
            },
            state.counts.of(scope),
        )
    RadioRow(label, state.scope == scope, enabled && !state.busy) { onScope(scope) }
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    NjChoiceRow(
        title = label,
        selected = selected,
        onSelect = onClick,
        enabled = enabled,
    )
}

@Composable
private fun formatLabel(format: ExportFormat): String =
    stringResource(
        when (format) {
            ExportFormat.JSON -> R.string.export_format_json
            ExportFormat.PDU_HEX -> R.string.export_format_pdu
            ExportFormat.CSV -> R.string.export_format_csv
        },
    )

// ------------------------------------------------------------------ previews

private val PreviewExportState =
    ExportUiState(
        loading = false,
        counts = ExportCounts(selection = 3, filter = 12, all = 240),
        hasSelection = true,
        hasFilter = true,
    )

@Composable
private fun PreviewExport(state: ExportUiState) {
    ExportContent(
        state = state,
        snackbar = remember { SnackbarHostState() },
        onBack = {},
        onScope = {},
        onFormat = {},
        onIncludeText = {},
        onIncludeTree = {},
        onMaskNumbers = {},
        onExport = {},
        onShare = {},
        onCancel = {},
    )
}

@Preview(name = "Export dark", widthDp = 400, heightDp = 900)
@Composable
private fun ExportDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewExport(PreviewExportState) }
}

@Preview(name = "Export light", widthDp = 400, heightDp = 900)
@Composable
private fun ExportLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { PreviewExport(PreviewExportState) }
}

@Preview(name = "Export running dark", widthDp = 400, heightDp = 900)
@Composable
private fun ExportRunningDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        PreviewExport(PreviewExportState.copy(progress = ExportProgress(done = 40, total = 240)))
    }
}
