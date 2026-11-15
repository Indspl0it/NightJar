package dev.nightjar.app.feature.health

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.BuildConfig
import dev.nightjar.app.core.ipc.IpcSessionStore
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.core.telephony.SimSlots
import dev.nightjar.app.data.db.NightjarDatabase
import dev.nightjar.app.data.db.databaseSizeBytes
import dev.nightjar.app.data.model.Coverage
import dev.nightjar.app.data.model.HookStatus
import dev.nightjar.app.data.repository.CoverageRepository
import dev.nightjar.app.data.repository.HookStatusRepository
import dev.nightjar.app.data.repository.RulesRepository
import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.protection.HandshakeSnapshot
import dev.nightjar.app.domain.protection.ModuleFailure
import dev.nightjar.app.domain.protection.ModuleInstallState
import dev.nightjar.app.domain.protection.ModuleOpResult
import dev.nightjar.app.domain.protection.ModuleStatus
import dev.nightjar.app.domain.protection.ProtectionReport
import dev.nightjar.app.domain.protection.RootSolution
import dev.nightjar.app.domain.text.Strings
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.app.domain.text.genericTitle
import dev.nightjar.app.domain.text.simNames
import dev.nightjar.app.feature.home.heroSeverity
import dev.nightjar.app.feature.protection.ModuleInstaller
import dev.nightjar.app.feature.protection.PhoneWatchdog
import dev.nightjar.app.feature.protection.ProtectionMonitor
import dev.nightjar.app.feature.protection.RestartBlock
import dev.nightjar.app.feature.protection.SessionRegistry
import dev.nightjar.app.feature.shell.heroText
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/** One "Seen from the network" row (04). */
data class CoverageRow(
    val classId: ClassId,
    val title: String,
    val lastSeen: String?,
)

/** (01, 06). */
data class HealthUiState(
    val loading: Boolean = true,
    val report: ProtectionReport? = null,
    val heroTitle: String = "",
    val heroBody: String = "",
    val heroSeverity: Severity = Severity.INFO,
    val safeMode: Boolean = false,
    val moduleState: ModuleInstallState = ModuleInstallState.NOT_INSTALLED,
    val rootManager: String = "",
    /** Root as onboarding decides it ([RootSolution.isRooted]). Null when root detection did not return a result. */
    val rooted: Boolean? = null,
    val rootVersion: String? = null,
    /** True when an Xposed framework (Vector or LSPosed) is present. */
    val framework: Boolean = false,
    val buildFull: String = "",
    val appVersion: String = "",
    val payloadVersion: String? = null,
    val hooksLive: Int = 0,
    val hooksTotal: Int = 0,
    val coverage: List<CoverageRow> = emptyList(),
    val lostEvents: Int = 0,
    val databaseBytes: Long = 0,
    /** The rules state the payload reports. Null without a payload session or a health document. */
    val rules: RulesHealth? = null,
    /** The payload speaks a newer IPC version than the app. Null without a session. */
    val payloadNewer: Boolean? = null,
    /** The phone process runs without the payload ([PhoneWatchdog.problem]). Health shows the restart card. */
    val phoneProblem: Boolean = false,
    /** Why the restart button is off now, or null when the user can restart ([PhoneWatchdog.restartBlock]). */
    val restartBlock: RestartBlock? = null,
    /** Is open. */
    val confirmRestart: Boolean = false,
    val restarting: Boolean = false,
) {
    val showDatabaseRow: Boolean get() = shouldShowDatabaseRow(databaseBytes)
}

/** One snackbar each for the phone service restart. */
sealed interface HealthEvent {
    data object PhoneRestarted : HealthEvent

    data class RestartFailed(
        val reason: ModuleFailure,
    ) : HealthEvent
}

/**
 *
 * Everything is read from the same sources the rest of the app uses: [ModuleInstaller] for root/module, the handshake
 * for payload and profile, the hook table for per-build hook state and the coverage table. Reads that touch a file, a
 * binder or a system setting run on [Dispatchers.IO].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
// Reads thirteen independent sources; a dependency bag would only move the same list one level down.
@Suppress("LongParameterList")
class HealthViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val installer: ModuleInstaller,
        private val sessions: SessionRegistry,
        private val ipc: IpcSessionStore,
        private val protection: ProtectionMonitor,
        private val hooks: HookStatusRepository,
        private val coverage: CoverageRepository,
        private val settings: SettingsStore,
        private val strings: Strings,
        private val simSlots: SimSlots,
        private val watchdog: PhoneWatchdog,
        private val rulesRepository: RulesRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(HealthUiState())
        val uiState: StateFlow<HealthUiState> = state.asStateFlow()

        private val channel = Channel<HealthEvent>(Channel.BUFFERED)
        val events = channel.receiveAsFlow()

        private val dateFormat =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

        private val hookFingerprint = MutableStateFlow(Build.FINGERPRINT)

        /** "SIM 1", "SIM 1 and SIM 2" or "your SIMs" for the hero copy. */
        private var simNames: String = strings.get(TextKey.MSG_UNKNOWN_SUBS)

        /** The running [refresh]; a new refresh cancels it, so two refreshes never write the state out of order. */
        private var refreshJob: Job? = null

        init {
            viewModelScope.launch {
                protection.report.collect { report ->
                    if (report == null) return@collect
                    // A handshake changes the hero without a manual refresh.
                    state.update { current -> applyHero(current, report) }
                }
            }
            viewModelScope.launch {
                settings.settings.collect { snapshot -> state.update { it.copy(safeMode = snapshot.safeMode) } }
            }
            viewModelScope.launch {
                // A change of safe mode changes the block, so the settings flow is part of the input.
                combine(watchdog.problem, settings.settings) { problem, _ -> problem != null }
                    .collect { problem ->
                        val block = if (problem) readRestartBlock() else null
                        state.update { it.copy(phoneProblem = problem, restartBlock = block) }
                    }
            }
            viewModelScope.launch {
                coverage.observeAll().collect { rows ->
                    // Every class gets a row, including the ones never seen ("never").
                    state.update { it.copy(coverage = orderedCoverage(rows).map(::coverageRow)) }
                }
            }
            viewModelScope.launch {
                hookFingerprint
                    .flatMapLatest { fingerprint -> hooks.observeForBuild(fingerprint) }
                    .collect { rows -> applyHooks(rows) }
            }
            viewModelScope.launch {
                // A handshake arriving while the screen is open must turn the module row and payload version green
                // without a manual refresh.
                sessions.handshake.drop(1).collect { refresh() }
            }
            // The only first read: HealthScreen does not call refresh() again when it opens.
            refresh()
        }

        /** Re-reads module/root state and the live session counters. */
        fun refresh() {
            refreshJob?.cancel()
            refreshJob =
                viewModelScope.launch {
                    runCatching { protection.refresh() }
                    runCatching { sessions.restore() }
                    // A call can end while the screen is open: Refresh reads the block again.
                    if (state.value.phoneProblem) {
                        val block = readRestartBlock()
                        state.update { it.copy(restartBlock = block) }
                    }
                    val handshake = sessions.handshake.value
                    val active = handshake != null
                    hookFingerprint.value = handshake?.buildFingerprint ?: Build.FINGERPRINT
                    val root: RootSolution? = runCatching { installer.detectRoot() }.getOrNull()
                    val rootLabel: String? = root?.let { solution -> strings.get(solution.manager.labelKey) }
                    val module: ModuleStatus? = runCatching { installer.status(activeThisBoot = active) }.getOrNull()
                    val reads =
                        withContext(Dispatchers.IO) {
                            DeviceReads(
                                databaseBytes = runCatching { databaseSizeBytes(context, NightjarDatabase.NAME) }.getOrDefault(0),
                                simNames = strings.simNames(simSlots.readySlots()),
                                rulesRevision = runCatching { rulesRepository.observe().first().revision() }.getOrNull(),
                            )
                        }
                    simNames = reads.simNames
                    applyRefresh(handshake, active, root, rootLabel, module, reads)
                }
        }

        /** The restart button: the restart dialog opens only when [PhoneWatchdog.restartBlock] is null at the tap. */
        fun requestRestart() {
            if (state.value.restarting) return
            viewModelScope.launch {
                val block = readRestartBlock()
                state.update { it.copy(restartBlock = block, confirmRestart = block == null) }
            }
        }

        fun dismissRestart() {
            state.update { it.copy(confirmRestart = false) }
        }

        /** "Restart". [PhoneWatchdog.restartPhone] does the checks again, because a call can start meanwhile. */
        fun confirmRestart() {
            if (state.value.restarting) return
            state.update { it.copy(confirmRestart = false, restarting = true) }
            viewModelScope.launch {
                val result =
                    try {
                        withContext(Dispatchers.IO) { watchdog.restartPhone() }
                    } finally {
                        state.update { it.copy(restarting = false) }
                    }
                val block = readRestartBlock()
                state.update { it.copy(restartBlock = block) }
                when (result) {
                    is ModuleOpResult.Success -> channel.send(HealthEvent.PhoneRestarted)
                    is ModuleOpResult.Failure -> channel.send(HealthEvent.RestartFailed(result.reason))
                }
            }
        }

        /** The audio mode is a binder read, so it runs on [Dispatchers.IO]. */
        private suspend fun readRestartBlock(): RestartBlock? = withContext(Dispatchers.IO) { watchdog.restartBlock() }

        /** The values [refresh] reads on [Dispatchers.IO]. */
        private class DeviceReads(
            val databaseBytes: Long,
            val simNames: String,
            /** The revision of the current rules of the app, the one RulesSync pushes. */
            val rulesRevision: Long?,
        )

        private fun applyRefresh(
            handshake: HandshakeSnapshot?,
            active: Boolean,
            root: RootSolution?,
            rootLabel: String?,
            module: ModuleStatus?,
            reads: DeviceReads,
        ) {
            state.update {
                val base =
                    it.copy(
                        loading = false,
                        // "installed" is "an Xposed framework is present"; the payload handshake is the only proof
                        // the module is loaded, so a framework without one reads as not loaded yet.
                        moduleState =
                            when {
                                module?.installed == null -> ModuleInstallState.NOT_INSTALLED
                                active -> ModuleInstallState.INSTALLED
                                else -> ModuleInstallState.PENDING_REBOOT
                            },
                        rootManager = rootLabel ?: "",
                        rooted = root?.isRooted,
                        rootVersion = root?.version,
                        framework = module?.installed != null,
                        buildFull = hookFingerprint.value,
                        appVersion = BuildConfig.VERSION_NAME,
                        payloadVersion = handshake?.payloadVersion,
                        lostEvents = (ipc.session?.droppedCount ?: 0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        databaseBytes = reads.databaseBytes,
                        // Both need a live payload session. The last health document stays in the store
                        // after the session ends, so it is read only while a session exists.
                        rules = ipc.session?.let { rulesHealth(ipc.health, reads.rulesRevision) },
                        payloadNewer = ipc.session?.payloadNewer,
                    )
                protection.report.value?.let { report -> applyHero(base, report) } ?: base
            }
        }

        private fun applyHooks(rows: List<HookStatus>) {
            val counts = hookCounts(rows)
            state.update { it.copy(hooksLive = counts.live, hooksTotal = counts.total) }
        }

        private fun coverageRow(row: Coverage): CoverageRow =
            CoverageRow(
                classId = row.classId,
                title = strings.genericTitle(ClassRegistry.of(row.classId).titleKey),
                lastSeen = row.lastSeenFromNetwork?.let { at -> dateFormat.format(Instant.ofEpochMilli(at)) },
            )

        /**
         * The hero is derived from a report, so a new handshake (the collector) and a manual [refresh]
         * render it through this one path.
         */
        private fun applyHero(
            current: HealthUiState,
            report: ProtectionReport,
        ): HealthUiState {
            val (title, body) = strings.heroText(report, simNames)
            return current.copy(
                report = report,
                heroSeverity = heroSeverity(report),
                heroTitle = title,
                heroBody = body,
            )
        }
    }
