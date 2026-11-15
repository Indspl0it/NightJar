package dev.nightjar.app.feature.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.model.AuditEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class AuditUiState(
    val loading: Boolean = true,
    val entries: List<AuditEntry> = emptyList(),
    /** Action types present in the loaded window, in enum order. */
    val actions: List<AuditAction> = emptyList(),
    val selected: AuditAction? = null,
) {
    val filtered: List<AuditEntry>
        get() = if (selected == null) entries else entries.filter { it.action == selected.name }
}

/** State (-01): the audit trail, filterable by action type. */
@HiltViewModel
class AuditViewModel
    @Inject
    constructor(
        audit: AuditLog,
    ) : ViewModel() {
        private val selected = MutableStateFlow<AuditAction?>(null)

        val state: StateFlow<AuditUiState> =
            combine(audit.observe(LIMIT), selected) { entries, filter ->
                val present =
                    AuditAction.entries.filter { action -> entries.any { it.action == action.name } }
                AuditUiState(
                    loading = false,
                    entries = entries,
                    actions = present,
                    selected = filter,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuditUiState())

        fun select(action: AuditAction?) {
            selected.value = action
        }

        private companion object {
            const val LIMIT = 500
        }
    }
