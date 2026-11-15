package dev.nightjar.app.feature.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.domain.rules.RulePrefill
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.EnforcementMatrix
import dev.nightjar.rules.RulesFormatException
import dev.nightjar.rules.StringMatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RuleEditorUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val draft: RuleDraft = RuleDraft(),
    val issues: List<DraftIssue> = emptyList(),
    val allowedActions: List<Verdict> = emptyList(),
    val previewHex: String = "",
    val preview: RulePreview = RulePreview.Empty,
    /** What the draft would have matched in the stored messages; null until the first run. */
    val history: PreviewResult? = null,
    val saving: Boolean = false,
    val showDiscardDialog: Boolean = false,
    val showG05Dialog: Boolean = false,
    val closed: Boolean = false,
    /** The draft as loaded, so [dirty] survives every edit. */
    val initial: RuleDraft = RuleDraft(),
) {
    val isNew: Boolean get() = draft.id == null

    /**
     * True once the user changed anything that would be lost by closing.
     *
     * The editor fills an untouched name from the selected classes ([RuleEditorViewModel.applySuggestedName]); that
     * suggestion is not a user edit, so the name is ignored while it was never typed.
     */
    val dirty: Boolean
        get() = comparable(draft) != comparable(initial)

    private fun comparable(value: RuleDraft): RuleDraft =
        if (value.nameTouched) value else value.copy(name = "")

    val canSave: Boolean
        get() = !loading && !saving && !notFound && issues.isEmpty() && draft.classes.isNotEmpty()

    fun issuesFor(field: DraftField): List<DraftIssue> = issues.filter { it.field == field }
}

sealed interface RuleEditorEvent {
    data object Saved : RuleEditorEvent

    data object TooLarge : RuleEditorEvent

    /** The write failed. The screen shows a fixed text: an exception message is technical and not translated. */
    data object Failed : RuleEditorEvent

    /** The rule or the message could not be read. Nothing was saved. */
    data object LoadFailed : RuleEditorEvent
}

/**
 * state.
 *
 * Everything the editor offers comes from the shared engine: [allowedRuleActions] for the Then list,
 * [validateDraft] (the shared `RuleValidator`) for the inline errors, [previewRule] for the pasted-PDU preview and the repository's
 * size check for the 64 KB limit. The ViewModel never writes a rule that has an issue.
 */
@HiltViewModel
class RuleEditorViewModel
    @Inject
    constructor(
        private val port: RuleEditorPort,
        private val matcher: PreviewMatcher,
        settings: SettingsStore,
    ) : ViewModel() {
        private val mutable = MutableStateFlow(RuleEditorUiState())
        val state: StateFlow<RuleEditorUiState> = mutable.asStateFlow()

        /** `privacy.maskNumbers`, for the preview sender and the suggested name. */
        val maskNumbers: StateFlow<Boolean> =
            settings.settings
                .map { it.maskNumbers }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), settings.settings.value.maskNumbers)

        private val channel = Channel<RuleEditorEvent>(Channel.BUFFERED)
        val events: Flow<RuleEditorEvent> = channel.receiveAsFlow()

        private var started = false
        private var previewJob: Job? = null
        private var historyJob: Job? = null

        /** Loads the route's rule or message prefill once. Called from the screen's first composition. */
        fun initialise(
            ruleId: Long?,
            messageId: Long?,
        ) {
            if (started) return
            started = true
            viewModelScope.launch {
                try {
                    val draft =
                        when {
                            ruleId != null -> {
                                val stored = port.rule(ruleId)
                                if (stored == null) {
                                    mutable.update { it.copy(loading = false, notFound = true) }
                                    return@launch
                                }
                                draftOf(stored)
                            }

                            messageId != null -> {
                                val message = port.message(messageId)
                                if (message == null) {
                                    RuleDraft()
                                } else {
                                    prefill(message.classId, message.originator)
                                }
                            }

                            else -> {
                                RuleDraft()
                            }
                        }
                    mutable.update {
                        it.copy(
                            loading = false,
                            draft = draft,
                            initial = draft,
                            issues = validateDraft(draft),
                            allowedActions = allowedRuleActions(draft),
                        )
                    }
                    scheduleHistory()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    mutable.update { it.copy(loading = false, notFound = true) }
                    channel.send(RuleEditorEvent.LoadFailed)
                }
            }
        }

        private fun prefill(
            classId: ClassId,
            originator: String?,
        ): RuleDraft {
            val allowed = EnforcementMatrix.allowedRuleActions(classId, originator != null)
            val prefill = RulePrefill.forMessage(classId, originator, allowed)
            val conditions = prefill?.conditions
            return RuleDraft(
                classes = conditions?.classes()?.toSet() ?: setOf(classId),
                fromEnabled = conditions?.originator() != null,
                fromOp = conditions?.originator()?.op() ?: StringMatch.Op.IS,
                fromValue = conditions?.originator()?.value() ?: "",
                action = prefill?.action ?: Verdict.NOTIFY,
            )
        }

        fun toggleClass(classId: ClassId) {
            updateDraft { draft ->
                val next = draft.classes.toMutableSet()
                if (!next.add(classId)) next.remove(classId)
                draft.copy(classes = next)
            }
        }

        fun setFromEnabled(enabled: Boolean) {
            updateDraft { it.copy(fromEnabled = enabled) }
        }

        fun setFromOp(op: StringMatch.Op) {
            updateDraft { it.copy(fromOp = op) }
        }

        fun setFromValue(value: String) {
            updateDraft { it.copy(fromValue = value) }
        }

        fun setSlot(slot: Int?) {
            updateDraft { it.copy(slot = slot) }
        }

        fun setAction(action: Verdict) {
            updateDraft { it.copy(action = action) }
        }

        fun setEnabled(enabled: Boolean) {
            updateDraft { it.copy(enabled = enabled) }
        }

        fun setName(name: String) {
            updateDraft { it.copy(name = name, nameTouched = true) }
        }

        /** UI-suggested name is only applied while the user has not typed one. */
        fun applySuggestedName(name: String) {
            if (state.value.draft.nameTouched) return
            updateDraft { it.copy(name = name) }
        }

        fun addCondition(kind: ConditionKind) {
            val condition =
                when (kind) {
                    ConditionKind.PORT -> DraftCondition.Port()
                    ConditionKind.CONTENT_TYPE -> DraftCondition.ContentType()
                    ConditionKind.SMSC -> DraftCondition.Smsc()
                    ConditionKind.ROAMING -> DraftCondition.Roaming()
                    ConditionKind.TEXT -> DraftCondition.Text()
                }
            updateDraft { it.withCondition(condition) }
        }

        fun updateCondition(condition: DraftCondition) {
            updateDraft { it.withCondition(condition) }
        }

        fun removeCondition(condition: DraftCondition) {
            updateDraft { it.without(condition) }
        }

        fun setPreviewHex(hex: String) {
            mutable.update { it.copy(previewHex = hex) }
            schedulePreview()
        }

        fun requestClose() {
            val current = state.value
            if (current.dirty) {
                mutable.update { it.copy(showDiscardDialog = true) }
            } else {
                mutable.update { it.copy(closed = true) }
            }
        }

        fun confirmDiscard() {
            mutable.update { it.copy(showDiscardDialog = false, closed = true) }
        }

        fun dismissDiscard() {
            mutable.update { it.copy(showDiscardDialog = false) }
        }

        fun dismissG05() {
            mutable.update { it.copy(showG05Dialog = false) }
        }

        /** When the user asks why a blocking action is unavailable. */
        fun explainG05() {
            mutable.update { it.copy(showG05Dialog = true) }
        }

        fun save() {
            val current = state.value
            if (current.saving || current.loading) return
            val issues = validateDraft(current.draft)
            if (issues.isNotEmpty()) {
                val g05 = issues.any { it.code == DraftIssueCode.BLOCKS_ALL_NORMAL }
                mutable.update { it.copy(issues = issues, showG05Dialog = g05) }
                return
            }
            val rule = current.draft.toRule()
            mutable.update { it.copy(saving = true, issues = emptyList()) }
            viewModelScope.launch {
                try {
                    if (!port.fitsBudget(rule)) {
                        channel.send(RuleEditorEvent.TooLarge)
                        return@launch
                    }
                    val id = current.draft.id
                    if (id == null) {
                        port.create(
                            name = rule.name(),
                            action = rule.action(),
                            conditions = rule.`when`(),
                            enabled = rule.enabled(),
                        )
                    } else {
                        port.update(
                            id = id,
                            name = rule.name(),
                            action = rule.action(),
                            conditions = rule.`when`(),
                            enabled = rule.enabled(),
                        )
                    }
                    // The budget was checked before the write. Once the write returns the rule is stored, so
                    // the editor closes: keeping it open would let a second Save create a duplicate (.3).
                    channel.send(RuleEditorEvent.Saved)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: RulesFormatException) {
                    channel.send(RuleEditorEvent.TooLarge)
                } catch (_: Exception) {
                    channel.send(RuleEditorEvent.Failed)
                } finally {
                    mutable.update { it.copy(saving = false) }
                }
            }
        }

        private fun updateDraft(transform: (RuleDraft) -> RuleDraft) {
            val next = transform(state.value.draft)
            val issues = validateDraft(next)
            mutable.update {
                it.copy(
                    draft = next,
                    issues = issues,
                    allowedActions = allowedRuleActions(next),
                )
            }
            schedulePreview()
            scheduleHistory()
        }

        /**
         * Counts the stored messages of the last 30 days that the draft would have matched (04).
         *
         * Debounced like the PDU preview; every edit cancels the run before it, and [PreviewMatcher] stops at once.
         * A draft without a type or with an issue shows no count: the rule cannot be saved like that. A read error
         * leaves the count out; it is a hint, not a gate.
         */
        private fun scheduleHistory() {
            historyJob?.cancel()
            historyJob =
                viewModelScope.launch {
                    delay(PREVIEW_DEBOUNCE_MS)
                    val current = state.value
                    val result =
                        if (current.draft.classes.isEmpty() || current.issues.isNotEmpty()) {
                            null
                        } else {
                            try {
                                matcher.preview(current.draft)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                        }
                    mutable.update { it.copy(history = result) }
                }
        }

        /** 300 ms debounce (04); decoding and evaluation are cheap but typing is not. */
        private fun schedulePreview() {
            previewJob?.cancel()
            previewJob =
                viewModelScope.launch {
                    delay(PREVIEW_DEBOUNCE_MS)
                    val current = state.value
                    val hex = current.previewHex.trim()
                    mutable.update {
                        it.copy(
                            preview =
                                if (hex.isEmpty()) {
                                    RulePreview.Empty
                                } else {
                                    previewRule(current.draft, hex)
                                },
                        )
                    }
                }
        }

        private companion object {
            const val PREVIEW_DEBOUNCE_MS = 300L
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }

/** The optional conditions offered by "+ Add condition" (02). */
enum class ConditionKind {
    PORT,
    CONTENT_TYPE,
    SMSC,
    ROAMING,
    TEXT,
}
