package dev.nightjar.app.feature.logs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nightjar.app.data.audit.AuditAction

/** The audit list state and its filter callback, for Audit history and for the Audit tab of Logs (01). */
internal class AuditBinding(
    val state: AuditUiState,
    val onSelect: (AuditAction?) -> Unit,
)

/** Reads the audit list from [viewModel]. Call it only while the list is on the screen, so the list loads on demand. */
@Composable
internal fun auditBinding(viewModel: AuditViewModel = hiltViewModel()): AuditBinding {
    val state by viewModel.state.collectAsStateWithLifecycle()
    return AuditBinding(state, viewModel::select)
}
