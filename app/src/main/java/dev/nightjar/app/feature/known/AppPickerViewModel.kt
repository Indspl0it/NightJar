package dev.nightjar.app.feature.known

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nightjar.app.data.repository.TrustedPackageRepository
import dev.nightjar.app.domain.known.AppFilter
import dev.nightjar.app.domain.known.AppPicker
import dev.nightjar.app.domain.known.InstalledApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The trust picker.
 *
 * @param rows the rows on the screen: installed apps after the filter and the search, sorted by label
 * @param selected the packages with a check. At the start these are the trusted packages
 * @param changes how many trusts a save adds or removes
 */
data class AppPickerUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val query: String = "",
    val filter: AppFilter = AppFilter.USER,
    val rows: List<InstalledApp> = emptyList(),
    val selected: Set<String> = emptySet(),
    val changes: Int = 0,
    val saving: Boolean = false,
)

sealed interface AppPickerEvent {
    /** The save is written. The screen closes the picker. */
    data class Saved(
        val added: Int,
        val removed: Int,
    ) : AppPickerEvent

    /** A write failed. Some changes can be written; the list shows the stored state. */
    data object Failed : AppPickerEvent
}

/**
 * State of the trust picker. [start] reads the installed apps (off the main thread, in [InstalledAppSource]) and
 * the trusted packages; the trusted ones start selected. A save adds the new checks and removes the cleared ones.
 */
@HiltViewModel
class AppPickerViewModel
    @Inject
    constructor(
        private val source: InstalledAppSource,
        private val trusted: TrustedPackageRepository,
    ) : ViewModel() {
        private var apps: List<InstalledApp> = emptyList()
        private var trustedAtStart: Set<String> = emptySet()

        private val ui = MutableStateFlow(AppPickerUiState())
        val state: StateFlow<AppPickerUiState> = ui.asStateFlow()

        /**
         * The picker session: [start] begins a new one. This ViewModel lives longer than one opening of the picker, so a
         * save result belongs to the session that started the save. A result of an older session is dropped: it must not
         * close a later opening, or show a message in it (review M1).
         */
        private var session = 0

        private class SessionEvent(
            val session: Int,
            val event: AppPickerEvent,
        )

        private val channel = Channel<SessionEvent>(Channel.BUFFERED)

        /** The save results of the current session only. */
        val events: Flow<AppPickerEvent> =
            channel
                .receiveAsFlow()
                .filter { it.session == session }
                .map { it.event }

        /** Starts a new pick: reads the apps and the trusted packages again, and resets the search and the checks. */
        fun start() {
            session++
            ui.value = AppPickerUiState(loading = true)
            viewModelScope.launch {
                try {
                    val loaded = AppPicker.sorted(source.load())
                    val current = trusted.all().mapTo(LinkedHashSet()) { it.packageName }
                    apps = loaded
                    trustedAtStart = current
                    ui.update { it.copy(loading = false, failed = false, selected = current).recomputed() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ui.update { it.copy(loading = false, failed = true) }
                }
            }
        }

        fun setQuery(query: String) {
            ui.update { it.copy(query = query).recomputed() }
        }

        fun setFilter(filter: AppFilter) {
            ui.update { it.copy(filter = filter).recomputed() }
        }

        fun toggle(packageName: String) {
            select { AppPicker.toggle(it.selected, packageName) }
        }

        fun selectAll() {
            select { AppPicker.selectAll(it.selected, it.rows) }
        }

        fun invert() {
            select { AppPicker.invert(it.selected, it.rows) }
        }

        fun clear() {
            select { AppPicker.clear(it.selected, it.rows) }
        }

        /**
         * Changes the checks. While a save runs, the checks do not change: the save writes the checks of its start, and
         * its end closes the picker, so a later change would be lost without a message (review L5).
         */
        private fun select(change: (AppPickerUiState) -> Set<String>) {
            ui.update { if (it.saving) it else it.copy(selected = change(it)).recomputed() }
        }

        /** Writes the changes. With no change it reports a save of nothing, so the screen closes. */
        fun save() {
            val current = ui.value
            if (current.loading || current.failed || current.saving) return
            val changes = AppPicker.changes(trustedAtStart, current.selected)
            val owner = session
            if (changes.isEmpty) {
                channel.trySend(SessionEvent(owner, AppPickerEvent.Saved(0, 0)))
                return
            }
            ui.update { it.copy(saving = true) }
            val labels = apps.associate { it.packageName to it.label }
            viewModelScope.launch {
                try {
                    // Removals first: a failed add then never leaves an app trusted that the user cleared.
                    changes.remove.sorted().forEach { trusted.remove(it) }
                    changes.add.sorted().forEach { trusted.add(it, labels[it]) }
                    if (owner == session) trustedAtStart = current.selected
                    sendFor(owner, AppPickerEvent.Saved(changes.add.size, changes.remove.size))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Some writes can be done: compare the next save with what is stored now.
                    val stored = runCatching { trusted.all() }.getOrNull()
                    if (stored != null && owner == session) trustedAtStart = stored.mapTo(LinkedHashSet()) { it.packageName }
                    sendFor(owner, AppPickerEvent.Failed)
                } finally {
                    // A later session has its own state: the old save does not change it.
                    if (owner == session) ui.update { it.copy(saving = false).recomputed() }
                }
            }
        }

        /** Sends [event] only while [owner] is still the current session. */
        private suspend fun sendFor(
            owner: Int,
            event: AppPickerEvent,
        ) {
            if (owner == session) channel.send(SessionEvent(owner, event))
        }

        private fun AppPickerUiState.recomputed(): AppPickerUiState =
            copy(
                rows = AppPicker.visible(apps, query, filter),
                changes = AppPicker.changes(trustedAtStart, selected).count,
            )
    }
