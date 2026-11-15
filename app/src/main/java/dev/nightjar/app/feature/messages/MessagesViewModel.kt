package dev.nightjar.app.feature.messages

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.model.FeedChip
import dev.nightjar.app.data.model.FilterSpec
import dev.nightjar.app.data.model.MessageListItem
import dev.nightjar.app.data.model.MuteScope
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.data.repository.MessageRepository
import dev.nightjar.app.data.repository.MuteRepository
import dev.nightjar.app.feature.classify.QuarantineReleaser
import dev.nightjar.app.feature.classify.ReleaseOutcome
import dev.nightjar.app.feature.export.ExportRequestStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** (01, 10). */
data class MessagesUiState(
    val spec: FilterSpec = FilterSpec.DEFAULT,
    val total: Int? = null,
    val quarantineCount: Int = 0,
    val query: String = "",
    val selection: Set<Long> = emptySet(),
    val deleteConfirm: List<Long>? = null,
    /** The rows of [deleteConfirm] include a held message, so the question adds the hold warning. */
    val deleteIncludesHeld: Boolean = false,
    /** The message open in the detail pane of the two-pane layout. */
    val selectedRowId: Long? = null,
    /** `privacy.maskNumbers`, applied when a row is drawn, never when it is stored. */
    val maskNumbers: Boolean = false,
    /** Narrowing conditions of [spec], shown as the badge on the filter button (05). */
    val activeFilterCount: Int = 0,
)

/** One-off messages, localised by the screen so the ViewModel stays free of Android resources. */
enum class MessagesMessage {
    REVIEWED,
    UNREVIEWED,
    SWIPE_DISABLED,
    MUTED,

    /** A database or settings write failed. The screen shows a fixed text. */
    FAILED,

    /** The Release button of a held row worked. */
    RELEASED,

    /** No releaser is bound, for example the module is not running. */
    RELEASE_UNAVAILABLE,

    /** The Block button of a held row blocked it. */
    HELD_BLOCKED,
}

/** One-off messages; navigation requests travel through the screen, not the ViewModel. */
sealed interface MessagesEvent {
    data class Snack(
        val message: MessagesMessage,
        val undo: (() -> Unit)? = null,
    ) : MessagesEvent

    data class Deleted(
        val count: Int,
    ) : MessagesEvent

    data class OpenRule(
        val messageId: Long,
    ) : MessagesEvent

    /** The release did not work. [code] is a technical `RELEASE_*` code without message content. */
    data class ReleaseFailed(
        val code: String,
    ) : MessagesEvent
}

/** User intents of Messages. */
sealed interface MessagesAction {
    data class SelectChip(
        val chip: FeedChip,
    ) : MessagesAction

    /** Shows or hides delivery reports (the chip "Delivery reports"). */
    data object ToggleReports : MessagesAction

    /** Removes the active chip "Blocked" (`fates`). */
    data object ClearFates : MessagesAction

    /** Removes the active chip "Needs attention" (`attentionOnly`). */
    data object ClearAttention : MessagesAction

    data class Search(
        val query: String,
    ) : MessagesAction

    /** Opens [messageId] in the detail pane of the two-pane layout. */
    data class OpenInPane(
        val messageId: Long,
    ) : MessagesAction

    /** Closes the detail pane (Back in the pane). */
    data object ClosePane : MessagesAction

    data object ClearSelection : MessagesAction

    /** Selects every row of the current list (filter and search), not only the loaded pages. */
    data object SelectAll : MessagesAction

    /** Selects the rows of the current list that are not selected, and deselects the others. */
    data object InvertSelection : MessagesAction

    /** The right swipe "Mark as reviewed". It always marks the row reviewed; it never removes the mark. */
    data class SwipeReviewed(
        val row: MessageListItem,
    ) : MessagesAction

    data class SwipeLeft(
        val row: MessageListItem,
    ) : MessagesAction

    data class MarkSelectionReviewed(
        val reviewed: Boolean,
    ) : MessagesAction

    data class StarSelection(
        val starred: Boolean,
    ) : MessagesAction

    data object AskDeleteSelection : MessagesAction

    data object ConfirmDelete : MessagesAction

    data object CancelDelete : MessagesAction

    data object ClearFilters : MessagesAction

    data class Mute(
        val row: MessageListItem,
    ) : MessagesAction

    /** The Release button of a held row. Same call as Home and Detail. */
    data class Release(
        val row: MessageListItem,
    ) : MessagesAction

    /** The Block button of a held row. Same call as the Block button of the notification. */
    data class BlockHeld(
        val row: MessageListItem,
    ) : MessagesAction
}

/**
 *
 * The filter lives in [FeedFilterController] so the filter sheet, Home drill-downs and a notification deep link all
 * feed the same flow; this ViewModel only observes it. Paging comes from [MessageRepository.feed], so 10k+ rows scroll
 * without holding the list in memory (01).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class MessagesViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val messages: MessageRepository,
        private val mutes: MuteRepository,
        private val feedFilter: FeedFilterController,
        private val settings: SettingsStore,
        private val clock: Clock,
        private val releasers: Set<@JvmSuppressWildcards QuarantineReleaser>,
    ) : ViewModel() {
        private val state = MutableStateFlow(MessagesUiState())
        val uiState: StateFlow<MessagesUiState> = state.asStateFlow()

        /** One-shot events. A channel keeps an event sent while the screen does not collect (configuration change). */
        private val events = Channel<MessagesEvent>(Channel.BUFFERED)
        val eventFlow: Flow<MessagesEvent> = events.receiveAsFlow()

        /** The review and hold state of each selected row when it was selected (bulk Undo, delete warning). */
        private val selectedFacts = SelectionFacts()

        /** The running "Select all" or "Invert selection" query. A new one replaces it. */
        private var selectAllJob: Job? = null

        val feed: Flow<PagingData<MessageListItem>> =
            feedFilter.spec
                .flatMapLatest { spec -> messages.feed(spec) }
                .cachedIn(viewModelScope)

        /**
         * Every held message, newest first, for "Waiting for you". The heading counts them with
         * [MessagesUiState.quarantineCount], the source of the tab badge.
         */
        val held: Flow<PagingData<MessageListItem>> =
            messages.feed(FilterSpec(chip = FeedChip.QUARANTINED)).cachedIn(viewModelScope)

        private val queryFlow = MutableStateFlow("")

        /**
         * The last `spec.search` this ViewModel wrote itself ([applySearch]). The spec echo for that value must not
         * overwrite what is being typed.
         */
        private var writtenSearch: String? = null

        init {
            // The route argument is a chip wire name (notification deep link), `family:<name>` or `day:<epochDay>`
            // (Home tiles and chart bars). See [parseEntryArgument]. It is applied once: a ViewModel created
            // again (process death) must not replace a filter the user set later.
            val wire = savedState.get<String>("filter")
            if (!wire.isNullOrBlank() && savedState.get<Boolean>(KEY_FILTER_APPLIED) != true) {
                savedState[KEY_FILTER_APPLIED] = true
                val entry = parseEntryArgument(wire)
                if (entry != null) {
                    feedFilter.openFiltered(entry)
                } else {
                    feedFilter.selectChip(FeedChip.from(wire.uppercase()))
                }
            }
            viewModelScope.launch {
                feedFilter.spec.collect { spec ->
                    val echo = spec.search.orEmpty()
                    state.update { current ->
                        // The search field is always on screen: the typed text wins over the echo.
                        current.copy(
                            spec = spec,
                            query =
                                queryAfterSpecChange(
                                    current = current.query,
                                    echo = echo,
                                    writtenHere = echo == writtenSearch,
                                ),
                            activeFilterCount = activeFilterCount(spec),
                        )
                    }
                }
            }
            viewModelScope.launch {
                // Follows the database as well as the filter: a message arriving or being deleted changes the total.
                feedFilter.spec
                    .flatMapLatest { spec -> messages.observeCount(spec).map<Int, Int?> { it }.catch { emit(null) } }
                    .collect { count -> state.update { it.copy(total = count) } }
            }
            viewModelScope.launch {
                messages.observeQuarantinedCount().collect { count -> state.update { it.copy(quarantineCount = count) } }
            }
            viewModelScope.launch {
                settings.settings
                    .map { it.maskNumbers }
                    .distinctUntilChanged()
                    .collect { mask -> state.update { it.copy(maskNumbers = mask) } }
            }
            viewModelScope.launch {
                queryFlow
                    .debounce(300)
                    .distinctUntilChanged()
                    .collect { query -> applySearch(query) }
            }
        }

        fun onAction(action: MessagesAction) {
            when (action) {
                is MessagesAction.SelectChip -> {
                    feedFilter.selectChip(action.chip)
                }

                MessagesAction.ToggleReports -> {
                    val spec = feedFilter.spec.value
                    val show = !spec.showDeliveryReports
                    feedFilter.apply(spec.copy(showDeliveryReports = show))
                    // The choice is kept in the settings, so the feed opens the same way next time.
                    launchWrite { settings.setShowDeliveryReports(show) }
                }

                MessagesAction.ClearFates -> {
                    feedFilter.apply(feedFilter.spec.value.copy(fates = emptySet()))
                }

                MessagesAction.ClearAttention -> {
                    feedFilter.apply(feedFilter.spec.value.copy(attentionOnly = false))
                }

                is MessagesAction.Search -> {
                    state.update { it.copy(query = action.query) }
                    queryFlow.value = action.query
                }

                is MessagesAction.OpenInPane -> {
                    state.update { it.copy(selectedRowId = action.messageId) }
                }

                MessagesAction.ClosePane -> {
                    state.update { it.copy(selectedRowId = null) }
                }

                MessagesAction.ClearSelection -> {
                    clearSelection()
                }

                MessagesAction.SelectAll -> {
                    selectFromList(invert = false)
                }

                MessagesAction.InvertSelection -> {
                    selectFromList(invert = true)
                }

                is MessagesAction.SwipeReviewed -> {
                    val row = action.row
                    // Undo removes the mark only when this swipe set it.
                    setReviewed(listOf(row.id), true, undoIds = if (row.isReviewed) emptyList() else listOf(row.id))
                }

                is MessagesAction.SwipeLeft -> {
                    swipeLeft(action.row)
                }

                is MessagesAction.MarkSelectionReviewed -> {
                    val ids = state.value.selection.toList()
                    setReviewed(ids, action.reviewed, undoIds = ids.filterNot(selectedFacts::wasReviewed))
                }

                is MessagesAction.StarSelection -> {
                    star(state.value.selection.toList(), action.starred)
                }

                MessagesAction.AskDeleteSelection -> {
                    val ids = state.value.selection.toList()
                    if (ids.isNotEmpty()) {
                        state.update { it.copy(deleteConfirm = ids, deleteIncludesHeld = selectedFacts.anyHeld(ids)) }
                    }
                }

                MessagesAction.ConfirmDelete -> {
                    confirmDelete()
                }

                MessagesAction.CancelDelete -> {
                    state.update { it.copy(deleteConfirm = null, deleteIncludesHeld = false) }
                }

                MessagesAction.ClearFilters -> {
                    feedFilter.reset()
                    queryFlow.value = ""
                }

                is MessagesAction.Mute -> {
                    mute(action.row)
                }

                is MessagesAction.Release -> {
                    release(action.row.id)
                }

                is MessagesAction.BlockHeld -> {
                    blockHeld(action.row.id)
                }
            }
        }

        /** Long-press starts multi-select with the pressed row (07). */
        fun toggleSelection(row: MessageListItem) {
            val id = row.id
            if (id in state.value.selection) {
                selectedFacts.forget(id)
            } else {
                selectedFacts.note(id, row.isReviewed, isHeld(row))
            }
            state.update { it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id) }
        }

        /** Ends multi-select and forgets the facts noted at selection. */
        private fun clearSelection() {
            selectAllJob?.cancel()
            selectedFacts.clear()
            state.update { it.copy(selection = emptySet()) }
        }

        /**
         * "Select all" ([invert] false) or "Invert selection" ([invert] true) over the full current list.
         * The list is paged, so the ids come from [MessageRepository.feedIds] (a Room query off the main thread), not
         * from the loaded pages. The held rows of "Waiting for you" are in the list too. An empty result ends the
         * selection mode.
         */
        private fun selectFromList(invert: Boolean) {
            val spec = feedFilter.spec.value
            selectAllJob?.cancel()
            selectAllJob =
                viewModelScope.launch {
                    val rows = catching { messages.feedIds(spec) }
                    if (rows == null) {
                        events.send(MessagesEvent.Snack(MessagesMessage.FAILED))
                        return@launch
                    }
                    val ids = rows.map { it.id }
                    val next = if (invert) invertedSelection(ids, state.value.selection) else ids.toCollection(LinkedHashSet())
                    selectedFacts.replace(rows, next)
                    state.update { it.copy(selection = next) }
                }
        }

        /**
         * Deposits the multi-selection for Export and clears the filter slot, so the next export never inherits a
         * stale request (AC3).
         */
        fun exportSelection() {
            val request = exportRequestFor(state.value.selection, feedFilter.spec.value)
            ExportRequestStore.setSelection(request.selection)
            ExportRequestStore.setFilter(request.filter)
        }

        /**
         * Deposits the active filter for Export and clears the selection slot. A default spec leaves the filter slot
         * empty, so Export preselects Everything instead of a disabled Filter scope.
         */
        fun exportFilter() {
            val request = exportRequestFor(emptyList(), feedFilter.spec.value)
            ExportRequestStore.setSelection(request.selection)
            ExportRequestStore.setFilter(request.filter)
        }

        private fun applySearch(query: String) {
            val trimmed = query.trim().ifEmpty { null }
            writtenSearch = trimmed.orEmpty()
            val spec = feedFilter.spec.value
            if (spec.search == trimmed) return
            feedFilter.apply(spec.copy(search = trimmed))
        }

        /** Undo (only after a mark was set) removes the mark again from [undoIds], the rows that were not reviewed before. */
        private fun setReviewed(
            ids: List<Long>,
            reviewed: Boolean,
            undoIds: List<Long>,
        ) {
            if (ids.isEmpty()) return
            launchWrite {
                val changed = messages.markReviewed(ids, reviewed)
                val undo: (() -> Unit)? =
                    if (reviewed && undoIds.isNotEmpty()) {
                        { launchWrite { messages.markReviewed(undoIds, false) } }
                    } else {
                        null
                    }
                events.send(MessagesEvent.Snack(if (reviewed) MessagesMessage.REVIEWED else MessagesMessage.UNREVIEWED, undo))
                if (changed > 0) clearSelection()
            }
        }

        private fun swipeLeft(row: MessageListItem) {
            when {
                supportsRuleSwipe(row) -> {
                    viewModelScope.launch { events.send(MessagesEvent.OpenRule(row.id)) }
                }

                supportsMuteSwipe(row) -> {
                    mute(row)
                }

                else -> {
                    viewModelScope.launch { events.send(MessagesEvent.Snack(MessagesMessage.SWIPE_DISABLED)) }
                }
            }
        }

        private fun star(
            ids: List<Long>,
            starred: Boolean,
        ) {
            if (ids.isEmpty()) return
            launchWrite {
                messages.setStarred(ids, starred)
                clearSelection()
            }
        }

        private fun confirmDelete() {
            val ids = state.value.deleteConfirm ?: return
            state.update { it.copy(deleteConfirm = null, deleteIncludesHeld = false) }
            launchWrite {
                val deleted = messages.delete(ids)
                selectedFacts.clear()
                state.update {
                    it.copy(
                        selection = emptySet(),
                        // A deleted message must not stay open in the detail pane.
                        selectedRowId = it.selectedRowId?.takeUnless { id -> id in ids },
                    )
                }
                events.send(MessagesEvent.Deleted(deleted))
            }
        }

        private fun mute(row: MessageListItem) {
            val originator = row.originator
            if (originator.isNullOrBlank()) {
                // A row without a sender cannot be muted.
                viewModelScope.launch { events.send(MessagesEvent.Snack(MessagesMessage.SWIPE_DISABLED)) }
                return
            }
            launchWrite {
                mutes.mute(MuteScope.ORIGINATOR, originator, clock.now() + MUTE_1H)
                events.send(MessagesEvent.Snack(MessagesMessage.MUTED))
            }
        }

        /**
         * Re-injection through the payload, the same call as Home and Detail (05). A released message
         * is marked released at once, so it leaves "Waiting for you" without waiting for the payload event. A release
         * cannot be undone, so the snackbar has no Undo.
         */
        private fun release(messageId: Long) {
            val releaser = releasers.firstOrNull()
            viewModelScope.launch {
                val outcome = if (releaser == null) null else catching { releaser.release(messageId) }
                if (outcome is ReleaseOutcome.Released) catching { messages.setQuarantineState(messageId, quarantined = false) }
                events.send(
                    when (outcome) {
                        is ReleaseOutcome.Released -> MessagesEvent.Snack(MessagesMessage.RELEASED)
                        is ReleaseOutcome.Failed -> MessagesEvent.ReleaseFailed(outcome.reason)
                        null -> MessagesEvent.Snack(MessagesMessage.RELEASE_UNAVAILABLE)
                    },
                )
            }
        }

        /** Runs [block] and gives null when it fails. Cancellation is rethrown, so a cancelled job stops. */
        private inline fun <T> catching(block: () -> T): T? =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

        /**
         * Blocks a held message for good ([MessageRepository.blockHeld]): fate Blocked, one BLOCK_HELD
         * audit record, marked as reviewed. There is no Undo: the notification Block button has none either.
         */
        private fun blockHeld(messageId: Long) {
            launchWrite {
                val blocked = messages.blockHeld(messageId)
                events.send(MessagesEvent.Snack(if (blocked) MessagesMessage.HELD_BLOCKED else MessagesMessage.FAILED))
            }
        }

        /** Runs a database or settings write. A failed write shows [MessagesMessage.FAILED] instead of a crash. */
        private fun launchWrite(block: suspend () -> Unit) {
            viewModelScope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    events.send(MessagesEvent.Snack(MessagesMessage.FAILED))
                }
            }
        }

        private companion object {
            const val MUTE_1H = 60L * 60 * 1000

            /** Set when the route argument was applied, so it is applied only once. */
            const val KEY_FILTER_APPLIED = "filterApplied"
        }
    }
