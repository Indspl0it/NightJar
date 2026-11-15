package dev.nightjar.app.feature.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.model.FeedChip
import dev.nightjar.app.data.model.FilterSpec
import dev.nightjar.app.data.model.SavedView
import dev.nightjar.app.data.repository.SavedViewRepository
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Severity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToLong

/** Date presets of the filter sheet. */
enum class DatePreset { ANY, TODAY, LAST_7_DAYS, LAST_30_DAYS }

data class FilterUiState(
    val draft: FilterSpec = FilterSpec.DEFAULT,
    /** The selected date chip, or null for a range that no preset covers (a chart day, for example). */
    val datePreset: DatePreset? = DatePreset.ANY,
    val savedViews: List<SavedView> = emptyList(),
    val saveDialogOpen: Boolean = false,
    val saveName: String = "",
    /** A saved view whose JSON could not be read; the sheet shows a note instead of crashing. */
    val savedViewUnreadable: Boolean = false,
    /** The last write (Apply, save or delete of a view) failed; the sheet shows a fixed text. */
    val writeFailed: Boolean = false,
) {
    val activeCount: Int get() = activeFilterCount(draft)
}

sealed interface FilterEvent {
    data object Applied : FilterEvent
}

sealed interface FilterAction {
    /** The sheet opens: the draft starts again from the filter of the feed. */
    data object Open : FilterAction

    /** Removes the "Blocked" condition (`fates`) that Home sets. */
    data object ClearFates : FilterAction

    /** Removes the "Needs attention" condition (`attentionOnly`) that Home sets. */
    data object ClearAttention : FilterAction

    data class ToggleClass(
        val classId: ClassId,
    ) : FilterAction

    data class ToggleSeverity(
        val severity: Severity,
    ) : FilterAction

    data class SetDirection(
        val direction: Direction?,
    ) : FilterAction

    /**
     * SIM chip of the filter sheet. [slot] is the card slot (0 or 1), not the subscription id: `subId` is assigned by
     * the device and is commonly 1 and 2 or higher with an eSIM, so filtering on it named the wrong card. The slot is
     * carried by [FeedChip.SIM1]/[FeedChip.SIM2], which the feed already maps onto `message.slot`.
     */
    data class SetSim(
        val slot: Int?,
    ) : FilterAction

    data class SetDatePreset(
        val preset: DatePreset,
    ) : FilterAction

    data class SetUnreviewed(
        val enabled: Boolean,
    ) : FilterAction

    data class SetStarred(
        val enabled: Boolean,
    ) : FilterAction

    /** Digits of the destination port; anything else is dropped. */
    data class SetPort(
        val digits: String,
    ) : FilterAction

    data class SetOriginator(
        val query: String,
    ) : FilterAction

    data class SetContentType(
        val query: String,
    ) : FilterAction

    data class SetPackage(
        val query: String,
    ) : FilterAction

    data class SetDeliveryReports(
        val enabled: Boolean,
    ) : FilterAction

    data object Reset : FilterAction

    data object Apply : FilterAction

    data object OpenSaveDialog : FilterAction

    data object CloseSaveDialog : FilterAction

    data class SaveName(
        val name: String,
    ) : FilterAction

    data object SaveView : FilterAction

    data class ApplySavedView(
        val view: SavedView,
    ) : FilterAction

    data class DeleteSavedView(
        val view: SavedView,
    ) : FilterAction
}

/**
 * Filter sheet (05).
 *
 * The draft is local until Apply, so closing the sheet leaves the feed untouched. Saved views store the same
 * [FilterSpec] JSON the repository persists, so a view created here can be reopened from Messages directly.
 */
@HiltViewModel
class FilterViewModel
    @Inject
    constructor(
        private val feedFilter: FeedFilterController,
        private val savedViews: SavedViewRepository,
        private val settings: SettingsStore,
    ) : ViewModel() {
        private val state = MutableStateFlow(stateFor(feedFilter.spec.value))
        val uiState: StateFlow<FilterUiState> = state.asStateFlow()

        /** One-shot events. A channel keeps an event sent while the sheet does not collect. */
        private val events = Channel<FilterEvent>(Channel.BUFFERED)
        val eventFlow: Flow<FilterEvent> = events.receiveAsFlow()

        init {
            viewModelScope.launch {
                savedViews.observeAll().collect { views -> state.update { it.copy(savedViews = views) } }
            }
        }

        fun onAction(action: FilterAction) {
            // Two families: actions that edit the draft being assembled, and actions on the saved-view list. One
            // `when` over both grew past the complexity limit and made it easy to add a draft edit in the wrong half.
            if (onDraftAction(action)) return
            onSavedViewAction(action)
        }

        /** Draft edits. Returns false when [action] belongs to the saved-view family. */
        private fun onDraftAction(action: FilterAction): Boolean =
            when (action) {
                FilterAction.Open -> {
                    // The ViewModel lives on the host back-stack entry, so it outlives one opening of the sheet.
                    state.update { stateFor(feedFilter.spec.value).copy(savedViews = it.savedViews) }
                    true
                }

                FilterAction.ClearFates -> {
                    updateDraft { it.copy(fates = emptySet()) }
                    true
                }

                FilterAction.ClearAttention -> {
                    updateDraft { it.copy(attentionOnly = false) }
                    true
                }

                is FilterAction.ToggleClass -> {
                    updateDraft { spec -> spec.copy(classIds = spec.classIds.toggle(action.classId)) }
                    true
                }

                is FilterAction.ToggleSeverity -> {
                    updateDraft { spec -> spec.copy(severities = spec.severities.toggle(action.severity)) }
                    true
                }

                is FilterAction.SetDirection -> {
                    updateDraft { it.copy(direction = action.direction) }
                    true
                }

                is FilterAction.SetSim -> {
                    updateDraft { spec -> spec.copy(chip = chipFor(action.slot, spec.chip)) }
                    true
                }

                is FilterAction.SetDatePreset -> {
                    state.update { it.copy(datePreset = action.preset, draft = dateRange(action.preset, it.draft)) }
                    true
                }

                is FilterAction.SetUnreviewed -> {
                    updateDraft { it.copy(unreviewedOnly = action.enabled) }
                    true
                }

                is FilterAction.SetStarred -> {
                    updateDraft { it.copy(starredOnly = action.enabled) }
                    true
                }

                is FilterAction.SetPort -> {
                    updateDraft { it.copy(port = action.digits.filter(Char::isDigit).toIntOrNull()) }
                    true
                }

                is FilterAction.SetOriginator -> {
                    updateDraft { it.copy(originatorQuery = action.query.ifBlank { null }) }
                    true
                }

                is FilterAction.SetContentType -> {
                    updateDraft { it.copy(contentType = action.query.ifBlank { null }) }
                    true
                }

                is FilterAction.SetPackage -> {
                    updateDraft { it.copy(packageQuery = action.query.ifBlank { null }) }
                    true
                }

                is FilterAction.SetDeliveryReports -> {
                    updateDraft { it.copy(showDeliveryReports = action.enabled) }
                    true
                }

                FilterAction.Reset -> {
                    // "Delivery reports" is a saved setting, not a condition: Reset keeps its value.
                    state.update {
                        it.copy(
                            draft = FilterSpec.DEFAULT.copy(showDeliveryReports = it.draft.showDeliveryReports),
                            datePreset = DatePreset.ANY,
                        )
                    }
                    true
                }

                FilterAction.Apply -> {
                    val draft = state.value.draft
                    feedFilter.apply(draft)
                    launchWrite {
                        // "Delivery reports" is a setting, so the next start shows the same list.
                        settings.setShowDeliveryReports(draft.showDeliveryReports)
                        events.send(FilterEvent.Applied)
                    }
                    true
                }

                else -> {
                    false
                }
            }

        /** A SIM slot toggle: tapping the selected slot returns to ALL, slot 0 is SIM1 and any other slot is SIM2. */
        private fun chipFor(
            slot: Int?,
            current: FeedChip,
        ): FeedChip =
            when (slot) {
                null -> if (current == FeedChip.SIM1 || current == FeedChip.SIM2) FeedChip.ALL else current
                0 -> FeedChip.SIM1
                else -> FeedChip.SIM2
            }

        /** Save/apply/delete of stored views. */
        private fun onSavedViewAction(action: FilterAction) {
            when (action) {
                FilterAction.OpenSaveDialog -> {
                    state.update { it.copy(saveDialogOpen = true) }
                }

                FilterAction.CloseSaveDialog -> {
                    state.update { it.copy(saveDialogOpen = false, saveName = "") }
                }

                is FilterAction.SaveName -> {
                    state.update { it.copy(saveName = action.name) }
                }

                FilterAction.SaveView -> {
                    val name = state.value.saveName.trim()
                    if (name.isEmpty()) return
                    // APPDATA-R49: the view keeps the name of a relative preset, so it is applied as "the last 7 days
                    // from now" and not as the frozen bounds. A custom range stores no preset.
                    val current = state.value
                    val draft = current.draft.copy(rangePreset = current.datePreset?.takeIf { it != DatePreset.ANY }?.name)
                    launchWrite {
                        savedViews.save(name, draft)
                        state.update { it.copy(saveDialogOpen = false, saveName = "") }
                    }
                }

                is FilterAction.ApplySavedView -> {
                    // A saved row is user data that may predate a schema change or be corrupted; a decode failure
                    // must leave the draft untouched instead of crashing the action handler.
                    val saved = runCatching { FilterSpec.fromJson(action.view.filterJson) }.getOrNull()
                    if (saved == null) {
                        state.update { it.copy(savedViewUnreadable = true) }
                        return
                    }
                    val now = ZonedDateTime.now(ZoneId.systemDefault())
                    val (resolved, _) = resolveSavedRange(saved, now.toLocalDate(), now.zone)
                    val preset = shownPreset(resolved.fromWall, resolved.toWall, now.toLocalDate(), now.zone)
                    state.update { it.copy(draft = resolved, datePreset = preset, savedViewUnreadable = false) }
                }

                is FilterAction.DeleteSavedView -> {
                    launchWrite { savedViews.delete(action.view.id) }
                }

                else -> {
                    Unit
                }
            }
        }

        private fun updateDraft(transform: (FilterSpec) -> FilterSpec) {
            state.update { it.copy(draft = transform(it.draft)) }
        }

        /** The sheet state for the feed filter [spec]: the date chip shows the preset that [spec] covers today. */
        private fun stateFor(spec: FilterSpec): FilterUiState {
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            return FilterUiState(draft = spec, datePreset = shownPreset(spec.fromWall, spec.toWall, now.toLocalDate(), now.zone))
        }

        /** Runs a settings or database write. A failure shows a fixed text in the sheet instead of crashing the app. */
        private fun launchWrite(block: suspend () -> Unit) {
            state.update { it.copy(writeFailed = false) }
            viewModelScope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    state.update { it.copy(writeFailed = true) }
                }
            }
        }

        private fun dateRange(
            preset: DatePreset,
            spec: FilterSpec,
        ): FilterSpec {
            val zone = ZoneId.systemDefault()
            val today = ZonedDateTime.now(zone).toLocalDate()
            val (from, to) = resolvePreset(preset, today, zone)
            return spec.copy(fromWall = from, toWall = to)
        }

        private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
    }

/**
 * Absolute bounds of a relative [preset] for [today] in [zone].
 *
 * Pure so the sheet and the saved-view round trip are testable off-device; [DatePreset.ANY] clears both bounds.
 */
internal fun resolvePreset(
    preset: DatePreset,
    today: LocalDate,
    zone: ZoneId,
): Pair<Long?, Long?> {
    fun start(day: LocalDate): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()
    return when (preset) {
        DatePreset.ANY -> null to null
        DatePreset.TODAY -> start(today) to start(today.plusDays(1))
        DatePreset.LAST_7_DAYS -> start(today.minusDays(6)) to start(today.plusDays(1))
        DatePreset.LAST_30_DAYS -> start(today.minusDays(29)) to start(today.plusDays(1))
    }
}

/**
 * The date chip to show for the bounds [fromWall] and [toWall] on [today]: [DatePreset.ANY] without bounds, the preset
 * whose range on [today] is exactly these bounds, or null for any other range (no chip is selected).
 */
internal fun shownPreset(
    fromWall: Long?,
    toWall: Long?,
    today: LocalDate,
    zone: ZoneId,
): DatePreset? {
    if (fromWall == null && toWall == null) return DatePreset.ANY
    return DatePreset.entries.firstOrNull { it != DatePreset.ANY && resolvePreset(it, today, zone) == (fromWall to toWall) }
}

/**
 * The preset a saved view's absolute bounds came from, for a view saved before `rangePreset` existed.
 *
 * Such a view has only its bounds, so a relative preset is recognised by the whole-day span it covers: 1, 7 or 30
 * days. A daylight-saving transition inside the window shifts the span by an hour, hence [DST_SLOP]; anything further
 * from a whole number of days is a custom range and returns [DatePreset.ANY]. Rounding alone is not enough: a 28-hour
 * custom range rounds to one day and would silently become "Today" when the view is applied.
 */
internal fun detectDatePreset(
    fromWall: Long?,
    toWall: Long?,
): DatePreset {
    if (fromWall == null || toWall == null) return DatePreset.ANY
    val span = toWall - fromWall
    if (span <= 0) return DatePreset.ANY
    val days = (span / MILLIS_PER_DAY).roundToLong()
    if (abs(span - (days * MILLIS_PER_DAY).toLong()) > DST_SLOP) return DatePreset.ANY
    return when (days) {
        1L -> DatePreset.TODAY
        7L -> DatePreset.LAST_7_DAYS
        30L -> DatePreset.LAST_30_DAYS
        else -> DatePreset.ANY
    }
}

/**
 * Re-resolves the relative dates of a saved view against [today].
 *
 * A view saved on 5 Nov as "Last 7 days" stores 30 Oct–5 Nov; applying it later must mean the last seven days from
 * now, not that frozen window. The stored preset name (`rangePreset`, APPDATA-R49) decides first; a view saved before
 * that field existed falls back to [detectDatePreset]. Bounds that match no preset are left alone. The returned spec has
 * no preset name: it is a saved-view field and narrows nothing.
 */
internal fun resolveSavedRange(
    spec: FilterSpec,
    today: LocalDate,
    zone: ZoneId,
): Pair<FilterSpec, DatePreset> {
    val stored = DatePreset.entries.firstOrNull { it.name == spec.rangePreset }
    val preset = stored ?: detectDatePreset(spec.fromWall, spec.toWall)
    val plain = spec.copy(rangePreset = null)
    if (preset == DatePreset.ANY) return plain to DatePreset.ANY
    val (from, to) = resolvePreset(preset, today, zone)
    return plain.copy(fromWall = from, toWall = to) to preset
}

/** One hour of slack for a daylight-saving transition, well below the error a real custom range produces. */
private const val DST_SLOP = 3_600_000L

private const val MILLIS_PER_DAY = 86_400_000.0
