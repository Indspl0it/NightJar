package dev.nightjar.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nightjar.app.core.ipc.PayloadConnection
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.core.telephony.SimSlots
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.model.FilterSpec
import dev.nightjar.app.data.model.MessageListItem
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.data.repository.MessageRepository
import dev.nightjar.app.data.repository.WeekCounts
import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.protection.ProtectionCopy
import dev.nightjar.app.domain.text.Strings
import dev.nightjar.app.domain.text.Templates
import dev.nightjar.app.domain.text.genericTitle
import dev.nightjar.app.domain.text.simNames
import dev.nightjar.app.feature.classify.QuarantineReleaser
import dev.nightjar.app.feature.classify.ReleaseOutcome
import dev.nightjar.app.feature.messages.FeedFilterController
import dev.nightjar.app.feature.protection.ProtectionMonitor
import dev.nightjar.contract.ClassId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** One "By type" row (05). */
data class TypeRow(
    val classId: ClassId,
    val title: String,
    val count: Int,
)

/** Only what [HomeScreen] shows. */
data class HomeUiState(
    val loading: Boolean = true,
    val heroTitle: String = "",
    val heroBody: String = "",
    val status: HomeStatus = HomeStatus.PROTECTED,
    /** The four totals of the last 7 days. */
    val week: WeekCounts = WeekCounts.ZERO,
    val days: List<DayBar> = emptyList(),
    val byType: List<TypeRow> = emptyList(),
    val refreshing: Boolean = false,
    val safeModeOn: Boolean = false,
    /** `privacy.maskNumbers`, applied when the attention rows are drawn. */
    val maskNumbers: Boolean = false,
    /** The 0-based SIM slots with a ready SIM card, for "NightJar monitors SIM 1 and SIM 2". */
    val simSlots: List<Int> = emptyList(),
)

/** What Home tells the user about an action it completed. */
sealed interface HomeEvent {
    /**
     * Safe mode changed. [undoable] is false for the write an Undo performs, so one Undo never offers another.
     */
    data class SafeModeChanged(
        val enabled: Boolean,
        val undoable: Boolean,
    ) : HomeEvent

    /** A message went back to the phone. */
    data object Released : HomeEvent

    /**
     * The release did not work. [code] is a technical `RELEASE_*` code without message content; the
     * screen shows the matching text, never the code.
     */
    data class ReleaseFailed(
        val code: String,
    ) : HomeEvent

    /** No releaser is bound, for example the module is not running. */
    data object ReleaseUnavailable : HomeEvent
}

/** User intents of Home. */
sealed interface HomeAction {
    data object Refresh : HomeAction

    /** Sends the held message [messageId] to the messaging app. */
    data class Release(
        val messageId: Long,
    ) : HomeAction

    data class SetSafeMode(
        val enabled: Boolean,
    ) : HomeAction

    /** Undo: writes [enabled] back without offering a second Undo. */
    data class UndoSafeMode(
        val enabled: Boolean,
    ) : HomeAction
}

/**
 * Everything Home reads from, grouped for its constructor.
 *
 * Home is a dashboard, so it legitimately touches most repositories; listing eleven constructor parameters tripped the
 * detekt limit and made the dependency set hard to read. Grouping is mechanical — no behaviour lives here.
 */
data class HomeDependencies
    @Inject
    constructor(
        val messages: MessageRepository,
        val protection: ProtectionMonitor,
        val settings: SettingsStore,
        val strings: Strings,
        val feedFilter: FeedFilterController,
        val payload: PayloadConnection,
        val clock: Clock,
        val audit: AuditLog,
        val releasers: Set<@JvmSuppressWildcards QuarantineReleaser>,
        val sims: SimSlots,
    )

/**
 *
 * Hero copy comes from [ProtectionCopy] so Home and the global banner cannot drift; every count is a repository
 * aggregate, never a client-side sum.
 */
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val deps: HomeDependencies,
    ) : ViewModel() {
        private val state = MutableStateFlow(HomeUiState())
        val uiState: StateFlow<HomeUiState> = state.asStateFlow()

        /** One-shot events. A channel keeps an event sent while the screen does not collect (configuration change). */
        private val events = Channel<HomeEvent>(Channel.BUFFERED)
        val eventFlow: Flow<HomeEvent> = events.receiveAsFlow()

        /** The newest held or Suspicious messages that are not reviewed; the screen shows at most [ATTENTION_LIMIT]. */
        val attention: Flow<PagingData<MessageListItem>> =
            deps.messages.feed(ATTENTION_FILTER).cachedIn(viewModelScope)

        private val zone: ZoneId = ZoneId.systemDefault()

        /** Wakes the day-boundary check immediately when Home becomes visible again. */
        private val windowTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /**
         * "The last 7 days" is relative to the wall deps.clock. Home is the permanent bottom of the stack, so the
         * boundaries are recomputed every minute and on resume instead of once in `init`: after midnight the
         * counts must roll over without a process restart.
         */
        private val timeWindows: Flow<LocalDate> =
            merge(
                flow {
                    while (true) {
                        emit(Unit)
                        delay(WINDOW_TICK_MS)
                    }
                },
                windowTicks,
            ).map { today() }.distinctUntilChanged()

        init {
            viewModelScope.launch {
                // The new day restarts the query; the value itself is only the trigger.
                timeWindows.collectLatest { _ ->
                    deps.messages
                        .weekStats(deps.clock.now(), zone)
                        .collect { week ->
                            state.update {
                                it.copy(
                                    loading = false,
                                    week = week.totals,
                                    days = weekDayBars(week.days, zone),
                                    byType =
                                        byTypeRows(week.byClass).map { row ->
                                            TypeRow(
                                                classId = row.classId,
                                                title = deps.strings.genericTitle(ClassRegistry.of(row.classId).titleKey),
                                                count = row.count,
                                            )
                                        },
                                )
                            }
                        }
                }
            }
            viewModelScope.launch {
                // The hero names the ready SIMs ("SIM 1 and SIM 2", or "your SIMs" while none is known). The
                // templates have no root manager any more, so Home does not detect it.
                val slots = state.map { it.simSlots }.distinctUntilChanged()
                combine(deps.protection.report, slots) { report, ready -> report to ready }
                    .collect { (report, ready) ->
                        if (report == null) return@collect
                        val hero = ProtectionCopy.hero(report = report, subNames = deps.strings.simNames(ready))
                        val title =
                            Templates.format(deps.strings.get(hero.titleKey), hero.titleArgs)
                        val body =
                            Templates.format(
                                deps.strings.get(hero.subtitleKey),
                                hero.subtitleArgs + mapOf("hook" to (report.failedHook ?: "")),
                            )
                        state.update {
                            it.copy(
                                loading = false,
                                heroTitle = title,
                                heroBody = body,
                                status = homeStatus(report),
                            )
                        }
                    }
            }
            viewModelScope.launch {
                deps.settings.settings.collect { snapshot ->
                    state.update { it.copy(safeModeOn = snapshot.safeMode, maskNumbers = snapshot.maskNumbers) }
                }
            }
            refresh()
        }

        fun onAction(action: HomeAction) {
            when (action) {
                HomeAction.Refresh -> refresh()
                is HomeAction.Release -> release(action.messageId)
                is HomeAction.SetSafeMode -> setSafeMode(action.enabled, undoable = true)
                is HomeAction.UndoSafeMode -> setSafeMode(action.enabled, undoable = false)
            }
        }

        /** Home recomputes "today" as soon as it is visible again, not on the next minute tick. */
        fun onResume() {
            windowTicks.tryEmit(Unit)
            // A SIM card can go in or out while NightJar is in the background.
            readSims()
        }

        /** The ready SIM slots for the status card. Binder calls, so never on the main dispatcher. */
        private fun readSims() {
            viewModelScope.launch {
                val slots = catching { withContext(Dispatchers.IO) { deps.sims.readySlots() } } ?: return@launch
                state.update { it.copy(simSlots = slots) }
            }
        }

        /** Pull to refresh: ping the payload, then recompute protection and hook health. */
        fun refresh() {
            if (state.value.refreshing) return
            state.update { it.copy(refreshing = true) }
            readSims()
            viewModelScope.launch {
                // A binder transaction into the phone process: blocking, so never on the main dispatcher.
                catching { withContext(Dispatchers.IO) { deps.payload.ping() } }
                catching { deps.protection.refresh() }
                state.update { it.copy(refreshing = false) }
            }
        }

        /** Filters the feed to the "Needs attention" slice. The screen then opens Messages. */
        fun openAttention() {
            deps.feedFilter.openFiltered(ATTENTION_FILTER)
        }

        fun openClass(classId: ClassId) {
            deps.feedFilter.openFiltered(classFilter(classId))
        }

        /** Opens the feed with [filter]. Used by the strip columns, the chart bars and the other drill-downs of Home. */
        fun openFilter(filter: FilterSpec) {
            deps.feedFilter.openFiltered(filter)
        }

        /**
         * Writes safe mode and audits it exactly as Protection/Settings do. [undoable] is false for the Undo
         * write itself, so the snackbar after an Undo never offers another Undo.
         */
        private fun setSafeMode(
            enabled: Boolean,
            undoable: Boolean,
        ) {
            viewModelScope.launch {
                deps.settings.setSafeMode(enabled)
                deps.audit.record(
                    action = if (enabled) AuditAction.SAFE_MODE_ON else AuditAction.SAFE_MODE_OFF,
                    actor = AuditActor.USER,
                    target = "advanced.safeMode",
                )
                catching { deps.protection.refresh() }
                events.send(HomeEvent.SafeModeChanged(enabled = enabled, undoable = undoable))
            }
        }

        private fun today(): LocalDate = Instant.ofEpochMilli(deps.clock.now()).atZone(zone).toLocalDate()

        /**
         * The Release quick action of a held message. Same call as the detail screen. A released message is
         * marked released at once, so it leaves "Needs attention" without waiting for the payload event (APPDATA-X03).
         */
        private fun release(messageId: Long) {
            val releaser = deps.releasers.firstOrNull()
            viewModelScope.launch {
                val outcome = if (releaser == null) null else catching { releaser.release(messageId) }
                if (outcome is ReleaseOutcome.Released) catching { deps.messages.setQuarantineState(messageId, quarantined = false) }
                events.send(
                    when (outcome) {
                        is ReleaseOutcome.Released -> HomeEvent.Released
                        is ReleaseOutcome.Failed -> HomeEvent.ReleaseFailed(outcome.reason)
                        null -> HomeEvent.ReleaseUnavailable
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

        private companion object {
            /** The day boundary is checked every minute, so midnight costs at most one minute of staleness. */
            const val WINDOW_TICK_MS = 60_000L
        }
    }
