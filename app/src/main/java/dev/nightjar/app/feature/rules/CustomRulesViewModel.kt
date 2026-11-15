package dev.nightjar.app.feature.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.Conditions
import dev.nightjar.rules.RuleOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import dev.nightjar.app.data.model.Rule as StoredRule
import dev.nightjar.rules.Rule as EngineRule

/** One row of a user rule or a locked preset rule. */
data class RuleRowUi(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val action: Verdict,
    val origin: RuleOrigin,
    val conditions: Conditions,
    val matchCount: Long,
    val lastMatchAt: Long?,
) {
    val fromPreset: Boolean get() = origin == RuleOrigin.PRESET
}

data class CustomRulesUiState(
    val loading: Boolean = true,
    val userRules: List<RuleRowUi> = emptyList(),
    val presetRules: List<RuleRowUi> = emptyList(),
    /** `privacy.maskNumbers`: a sender condition shows masked in the row summary. */
    val maskNumbers: Boolean = false,
) {
    val isEmpty: Boolean get() = !loading && userRules.isEmpty() && presetRules.isEmpty()
}

sealed interface CustomRulesEvent {
    /** A rule was deleted. [token] goes back to [CustomRulesViewModel.undoDelete], so Undo restores this rule only. */
    data class Deleted(
        val token: Long,
    ) : CustomRulesEvent

    data object Restored : CustomRulesEvent

    /** A write failed. The screen shows a fixed text: an exception message is technical and not translated. */
    data object Failed : CustomRulesEvent
}

/**
 * state.
 *
 * Priority is the list order: dragging or the TalkBack "move" actions call [reorder], which goes through
 * `RulesRepository.reorder` (audit `RULE_REORDER`) and therefore syncs like any other change. Preset rules are shown
 * but never reordered or edited here.
 */
@HiltViewModel
class CustomRulesViewModel
    @Inject
    constructor(
        private val port: CustomRulesPort,
    ) : ViewModel() {
        private val mutations = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        private val channel = Channel<CustomRulesEvent>(Channel.BUFFERED)

        val events: Flow<CustomRulesEvent> = channel.receiveAsFlow()

        /**
         * Restore points by token, newest last. A second delete while the first Undo snackbar shows adds a second
         * entry instead of replacing the first, so each Undo restores its own rule.
         */
        private val deleted = LinkedHashMap<Long, DeletedRule>()
        private var nextToken = 0L

        /**
         * The order the user last asked for, while a reorder write is still in flight. Drag moves are applied to this
         * list so two moves issued one after the other cannot both be computed from the pre-write order (.2).
         */
        private var orderOverride: List<Long>? = null

        init {
            viewModelScope.launch {
                for (task in mutations) {
                    try {
                        task()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A failed write never reaches the stored order, so an override waiting for it would be
                        // compared against forever and every later move would start from an order that was never saved.
                        orderOverride = null
                        channel.send(CustomRulesEvent.Failed)
                    }
                }
            }
        }

        /**
         * Eagerly shared: [delete] and [currentOrder] read [state] outside a collector, and a `WhileSubscribed` value
         * can be the empty initial state there. The ViewModel lives only while Custom rules is on the back stack.
         */
        val state: StateFlow<CustomRulesUiState> =
            combine(port.config, port.storedRules, port.maskNumbers) { config, stored, mask ->
                val matches = stored.associateBy { it.id }
                val rows = config.rules.map { rule -> rule.toUi(matches[rule.id()]) }
                CustomRulesUiState(
                    loading = false,
                    userRules = rows.filter { !it.fromPreset },
                    presetRules = rows.filter { it.fromPreset },
                    maskNumbers = mask,
                )
            }.onEach { emitted ->
                // The stored order caught up with the last write, so the override has done its job (.2).
                if (orderOverride == emitted.userRules.map { it.id }) orderOverride = null
            }.stateIn(viewModelScope, SharingStarted.Eagerly, CustomRulesUiState())

        fun setEnabled(
            id: Long,
            enabled: Boolean,
        ) {
            enqueue { port.setEnabled(id, enabled) }
        }

        /** Drag end: [orderedIds] is the user-rule order the user dropped. */
        fun reorder(orderedIds: List<Long>) {
            if (orderedIds.isEmpty() || orderedIds == currentOrder()) return
            orderOverride = orderedIds
            enqueue { port.reorder(orderedIds) }
        }

        /** TalkBack custom actions and drag: move one rule by [delta] without a drag. */
        fun move(
            id: Long,
            delta: Int,
        ) {
            val ids = currentOrder().toMutableList()
            val from = ids.indexOf(id)
            if (from < 0) return
            val to = (from + delta).coerceIn(0, ids.lastIndex)
            if (to == from) return
            ids.removeAt(from)
            ids.add(to, id)
            reorder(ids)
        }

        fun delete(id: Long) {
            val row = state.value.userRules.firstOrNull { it.id == id } ?: return
            enqueue {
                val order = currentOrder()
                val index = order.indexOf(id)
                if (index < 0) return@enqueue
                port.delete(id)
                // Only an applied delete can be undone; a queued one that fails leaves no stale restore point.
                val token = nextToken++
                deleted[token] = DeletedRule(row, index)
                while (deleted.size > MAX_RESTORE_POINTS) deleted.remove(deleted.keys.first())
                orderOverride = order.filterNot { it == id }
                channel.send(CustomRulesEvent.Deleted(token))
            }
        }

        /**
         * Undo: re-creates the rule and puts it back at the index it had.
         *
         * The id changes (the repository assigns the next one); the rule's behaviour, name, action and place in the
         * priority list are restored. The repository has no create-at-position call, so the position is written by the
         * follow-up reorder, which the queue keeps immediately behind the create.
         */
        fun undoDelete(token: Long) {
            val restore = deleted.remove(token) ?: return
            enqueue {
                val createdId =
                    port.create(
                        name = restore.row.name,
                        action = restore.row.action,
                        conditions = restore.row.conditions,
                        enabled = restore.row.enabled,
                    )
                // The stored list can already show the new rule (its flow emits while this task waits), so it is
                // taken out first: otherwise the reorder would carry its id two times.
                val ids =
                    currentOrder()
                        .filterNot { it == createdId }
                        .toMutableList()
                        .apply { add(restore.index.coerceIn(0, size), createdId) }
                orderOverride = ids
                port.reorder(ids)
                channel.send(CustomRulesEvent.Restored)
            }
        }

        /** The order the last drop or move asked for, or the order currently rendered. */
        private fun currentOrder(): List<Long> = orderOverride ?: state.value.userRules.map { it.id }

        /**
         * Every write runs in order on one worker, so a mutation issued while another is in flight is not lost and
         * input can stay enabled.
         */
        private fun enqueue(block: suspend () -> Unit) {
            mutations.trySend(block)
        }

        private data class DeletedRule(
            val row: RuleRowUi,
            val index: Int,
        )

        private companion object {
            /** Undo lives as long as its snackbar; a few restore points cover rapid deletes. */
            const val MAX_RESTORE_POINTS = 8
        }
    }

private fun EngineRule.toUi(stored: StoredRule?): RuleRowUi =
    RuleRowUi(
        id = id(),
        name = name(),
        enabled = enabled(),
        action = action(),
        origin = origin(),
        conditions = `when`(),
        matchCount = stored?.matchCount ?: 0,
        lastMatchAt = stored?.lastMatchAt,
    )
