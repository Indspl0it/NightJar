package dev.nightjar.app.feature.diagnostics

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.ui.components.NjBannerKind
import dev.nightjar.app.ui.components.NjDialog
import dev.nightjar.app.ui.components.NjInfoBanner
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme

/**
 * Build the diagnostics ZIP and hand it to the share sheet (03).
 *
 * The checkbox is off by default and says what it adds. The dialog stays open while the bundle is built, then opens the
 * chooser; a failure is reported in the dialog instead of a silent no-op, because an unsupported-build user has no other
 * way to produce the profile data they were asked for.
 */
@Composable
fun SendDiagnosticsDialog(
    onDismiss: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val close = {
        viewModel.reset()
        onDismiss()
    }

    LaunchedEffect(state.file) {
        val file = state.file ?: return@LaunchedEffect
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType(MIME)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newUri(context.contentResolver, "diagnostics", uri) }
        runCatching {
            context.startActivity(
                Intent
                    .createChooser(send, resources.getString(R.string.action_send_diagnostics))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        viewModel.consumeFile()
        close()
    }

    DiagnosticsDialogContent(
        state = state,
        onDismiss = close,
        onCreate = viewModel::create,
        onIncludeMessages = viewModel::onIncludeMessages,
    )
}

/** The dialog itself, without the ViewModel and the share sheet, so a preview can show it. */
@Composable
private fun DiagnosticsDialogContent(
    state: DiagnosticsUiState,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    onIncludeMessages: (Boolean) -> Unit,
) {
    NjDialog(
        title = stringResource(R.string.dlg_19_title),
        text = stringResource(R.string.dlg_19_body),
        confirmText = stringResource(R.string.dlg_19_confirm),
        dismissText = stringResource(R.string.dlg_19_cancel),
        confirmEnabled = !state.busy,
        onDismiss = { if (!state.busy) onDismiss() },
        onConfirm = onCreate,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = state.includeMessages,
                        enabled = !state.busy,
                        role = Role.Checkbox,
                        onValueChange = onIncludeMessages,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.includeMessages, onCheckedChange = null, enabled = !state.busy)
            Text(
                stringResource(R.string.dlg_19_include_messages),
                style = MaterialTheme.typography.bodyMedium,
                color = NjTheme.roles.tx,
            )
        }
        if (state.failed) {
            NjInfoBanner(text = stringResource(R.string.diagnostics_failed), kind = NjBannerKind.ERROR)
        }
    }
}

private const val MIME = "application/zip"

// ------------------------------------------------------------------ previews

@Preview(name = "Diagnostics dark")
@Composable
private fun DiagnosticsDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        DiagnosticsDialogContent(state = DiagnosticsUiState(), onDismiss = {}, onCreate = {}, onIncludeMessages = {})
    }
}

@Preview(name = "Diagnostics light, failed")
@Composable
private fun DiagnosticsLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) {
        DiagnosticsDialogContent(
            state = DiagnosticsUiState(includeMessages = true, failed = true),
            onDismiss = {},
            onCreate = {},
            onIncludeMessages = {},
        )
    }
}
