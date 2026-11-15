package dev.nightjar.app.feature.messages

import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.model.FeedChip
import dev.nightjar.app.data.model.FilterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The filter the Messages feed is showing (03, 05).
 *
 * And the filter sheet are separate destinations, and Home deep-links into a filtered feed, so the selection
 * has to outlive one screen's ViewModel. A singleton holder is enough: the filter is UI state, never persisted unless
 * the user saves it as a view ([dev.nightjar.app.data.repository.SavedViewRepository]).
 */
@Singleton
class FeedFilterController
    @Inject
    constructor(
        private val settings: SettingsStore,
        scope: CoroutineScope,
    ) {
        private val state = MutableStateFlow(FilterSpec.DEFAULT)
        val spec: StateFlow<FilterSpec> = state.asStateFlow()

        init {
            // "Delivery reports" is a saved setting. Once it is read from disk, an untouched filter follows it.
            scope.launch {
                settings.loaded.first { it }
                val saved = settings.current().showDeliveryReports
                state.update { if (it == FilterSpec.DEFAULT) it.copy(showDeliveryReports = saved) else it }
            }
        }

        /** Replaces the filter (filter sheet "Apply", a saved view or a Home drill-down). */
        fun apply(spec: FilterSpec) {
            state.value = spec
        }

        /**
         * Clears every condition, including the chip. "Delivery reports" is a saved setting, not a filter condition
         * , so it keeps the saved value.
         */
        fun reset() {
            state.value = FilterSpec.DEFAULT.copy(showDeliveryReports = settings.settings.value.showDeliveryReports)
        }

        /** Selects one chip, keeping the advanced conditions (03: "All" resets the chip only). */
        fun selectChip(chip: FeedChip) {
            state.value = state.value.copy(chip = chip)
        }

        /** Opens the feed from Home or a notification with a pre-selected chip. */
        fun openFiltered(spec: FilterSpec) {
            state.value = spec
        }

        /**
         * Opens the feed from a Home tile or a chart bar. [family] is the tile, [epochDay] is a local day
         * (days since 1970-01-01). Both can be null.
         */
        fun openEntry(
            family: EntryFamily? = null,
            epochDay: Long? = null,
        ) {
            state.value = entryFilter(family, epochDay)
        }
    }
