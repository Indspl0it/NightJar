package dev.nightjar.app.feature.onboarding

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.core.settings.UiMode
import dev.nightjar.app.core.telephony.SimSlots
import dev.nightjar.app.data.repository.RulesRepository
import dev.nightjar.app.domain.protection.InstallCheck
import dev.nightjar.app.domain.protection.InstallChecklist
import dev.nightjar.app.domain.protection.ModuleFailure
import dev.nightjar.app.domain.protection.ModuleOpResult
import dev.nightjar.app.domain.protection.ProfileStatus
import dev.nightjar.app.domain.protection.RootManager
import dev.nightjar.app.domain.protection.RootSolution
import dev.nightjar.app.domain.text.Strings
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.app.domain.text.simNames
import dev.nightjar.app.feature.protection.DeviceChecks
import dev.nightjar.app.feature.protection.InstallUiState
import dev.nightjar.app.feature.protection.ModuleInstaller
import dev.nightjar.app.feature.protection.SessionRegistry
import dev.nightjar.app.notifications.NotificationChannels
import dev.nightjar.rules.Preset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Everything the three onboarding screens render. */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val checks: List<InstallCheck> = emptyList(),
    val checking: Boolean = false,
    val checksRan: Boolean = false,
    /** Root manager and version for the root row, for example "Magisk 30700". Null when there is nothing to show. */
    val rootLabel: String? = null,
    val install: InstallUiState = InstallUiState.Idle,
    val mode: UiMode = UiMode.SIMPLE,
    val notificationsAllowed: Boolean = false,
    /** "SIM 1", "SIM 1 and SIM 2" or "your SIMs" for the Ready screen. */
    val simNames: String = "",
    /** True when the Recommended preset is on after onboarding ends. Only then the Ready screen says so. */
    val recommendedPreset: Boolean = false,
    val showRebootDialog: Boolean = false,
    /** True while "Reboot now" waits for the root shell. */
    val rebooting: Boolean = false,
    /** Why the last "Reboot now" failed; the reboot dialog then shows it and stays open. */
    val rebootFailure: ModuleFailure? = null,
    val error: Boolean = false,
    val finished: Boolean = false,
) {
    /** True after the user opened Vector or LSPosed in this session: the module loads after the next reboot. */
    val rebootPending: Boolean
        get() = install is InstallUiState.Success

    /** The main action of the Setup screen. */
    val action: SetupAction
        get() = OnboardingLogic.setupAction(checks, checksRan, rebootPending, error)

    /** The second way out of the current Setup state. */
    val secondary: SetupSecondary
        get() = OnboardingLogic.secondary(action, OnboardingLogic.canContinue(checks, checksRan))

    /** The four Setup rows. */
    val items: List<SetupItem>
        get() = OnboardingLogic.setupItems(checks, checksRan, rebootPending, error)

    /** The row that opens as a card, or null. */
    val currentRow: SetupRow?
        get() = OnboardingLogic.currentRow(items)

    /** The button in the card of the current row, or null. */
    val cardAction: SetupAction?
        get() = OnboardingLogic.cardAction(action)

    /** The buttons below the dots. */
    val footer: List<SetupFooterButton>
        get() = OnboardingLogic.footerButtons(action, secondary)
}

/** User intents; the screen never mutates state itself. */
sealed interface OnboardingEvent {
    data object Start : OnboardingEvent

    data object Next : OnboardingEvent

    data object Back : OnboardingEvent

    /**
     * "Grant root" in the root card: runs the first check, which asks the root manager. The card tells the user why
     * NightJar needs root before the user selects it.
     */
    data object GrantRoot : OnboardingEvent

    /** "Check again". */
    data object Retry : OnboardingEvent

    /**
     * "Open LSPosed": the screen opens the manager app, and the reboot step comes next. NightJar cannot see the module
     * switch in Vector or LSPosed. The module row shows the real state only after the reboot.
     */
    data object OpenFramework : OnboardingEvent

    /** "Reboot phone": shows the reboot confirmation. */
    data object Reboot : OnboardingEvent

    data object DismissReboot : OnboardingEvent

    data object RebootNow : OnboardingEvent

    /** "Later" below "Reboot phone": onboarding ends, and the user reboots the phone at a later time. */
    data object RebootLater : OnboardingEvent

    data class SelectMode(
        val mode: UiMode,
    ) : OnboardingEvent

    /** Reads the notification permission again, before the permission prompt. */
    data object RequestNotifications : OnboardingEvent

    data class NotificationsResult(
        val granted: Boolean,
    ) : OnboardingEvent

    /**
     * The screen is in the foreground again, for example after Vector, LSPosed, the root manager or system settings. The
     * checks run again only when the last run had root ([OnboardingLogic.recheckOnResume]).
     */
    data object Resume : OnboardingEvent
}

/**
 * Welcome, Setup and Ready.
 *
 * The root prompt is a side effect of [OnboardingEvent.GrantRoot], which the screen sends from the "Grant root" button
 * of the root card. The card tells the user why NightJar needs root. All checklist rows come from
 * [InstallChecklist] over the real [ModuleInstaller] results.
 *
 * The flow is shown only while onboarding is not done. If it shows again (after the reboot from the Setup screen, a
 * failed reboot, or the process died in the flow), it starts at Setup and runs the checks by itself, because the phone
 * state can have changed. The user selected "Grant root" before, so this does not prompt for root without a reason.
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settings: SettingsStore,
        private val installer: ModuleInstaller,
        private val sessions: SessionRegistry,
        private val device: DeviceChecks,
        private val channels: NotificationChannels,
        private val simSlots: SimSlots,
        private val strings: Strings,
        private val rules: RulesRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(OnboardingUiState(simNames = strings.get(TextKey.MSG_UNKNOWN_SUBS)))
        val uiState: StateFlow<OnboardingUiState> = state.asStateFlow()

        init {
            // Creating the channels needs no permission. With the channels in place, "Open channel settings" in
            // Settings works also when the user does not allow notifications here.
            viewModelScope.launch(Dispatchers.IO) { runCatching { channels.ensureCreated() } }
            viewModelScope.launch {
                val current = settings.current()
                val allowed = channels.notificationsAllowed()
                val resume =
                    when {
                        current.onboardingDone -> OnboardingStep.CHECKS

                        // After the reboot from the Setup screen: the fingerprint is written when the user opens Vector
                        // or LSPosed, and again before the reboot. Without a persisted step this is the only safe hint.
                        current.lastBuildFingerprint != null -> OnboardingStep.CHECKS

                        else -> OnboardingStep.WELCOME
                    }
                state.update {
                    it.copy(
                        step = resume,
                        mode = current.mode,
                        notificationsAllowed = allowed,
                    )
                }
                if (resume == OnboardingStep.CHECKS) runChecks()
            }
            viewModelScope.launch {
                // The payload handshakes once the phone process restarts with the module; re-evaluate so the module row
                // changes without the user pressing "Check again".
                sessions.handshake.drop(1).collect {
                    val current = state.value
                    if (current.step == OnboardingStep.CHECKS && current.checksRan && !current.checking) runChecks()
                }
            }
            viewModelScope.launch {
                installer.installState.collect { install ->
                    // Baseline for OTA detection and the hint that lets a reboot resume at Setup.
                    if (install is InstallUiState.Success) {
                        runCatching { settings.setLastBuildFingerprint(Build.FINGERPRINT) }
                    }
                    state.update { it.copy(install = install) }
                }
            }
        }

        fun onEvent(event: OnboardingEvent) {
            when (event) {
                OnboardingEvent.Start -> {
                    goTo(OnboardingStep.CHECKS)
                }

                OnboardingEvent.Next -> {
                    next()
                }

                OnboardingEvent.Back -> {
                    // On Welcome the screen closes the activity; onboarding is not done, so it must not finish here.
                    val current = state.value
                    if (current.step != OnboardingStep.WELCOME) state.update { it.copy(step = current.step.previous()) }
                }

                OnboardingEvent.GrantRoot -> {
                    runChecks()
                }

                OnboardingEvent.Retry -> {
                    runChecks()
                }

                OnboardingEvent.OpenFramework -> {
                    installer.install()
                }

                OnboardingEvent.Reboot -> {
                    state.update { it.copy(showRebootDialog = true, rebootFailure = null) }
                }

                OnboardingEvent.DismissReboot -> {
                    if (!state.value.rebooting) state.update { it.copy(showRebootDialog = false, rebootFailure = null) }
                }

                OnboardingEvent.RebootNow -> {
                    rebootNow()
                }

                OnboardingEvent.RebootLater -> {
                    finish()
                }

                is OnboardingEvent.SelectMode -> {
                    state.update { it.copy(mode = event.mode) }
                    viewModelScope.launch { settings.setMode(event.mode) }
                }

                OnboardingEvent.RequestNotifications -> {
                    refreshNotifications()
                }

                is OnboardingEvent.NotificationsResult -> {
                    state.update { it.copy(notificationsAllowed = event.granted) }
                }

                OnboardingEvent.Resume -> {
                    refreshNotifications()
                    val current = state.value
                    // Back from Vector, LSPosed or another app: the checks run again by themselves, but only when the
                    // last run had root. Without root, a new check would ask the root manager again (H1).
                    val recheck =
                        OnboardingLogic.recheckOnResume(
                            step = current.step,
                            checks = current.checks,
                            checksRan = current.checksRan,
                            checking = current.checking,
                            error = current.error,
                        )
                    if (recheck) runChecks()
                }
            }
        }

        private fun refreshNotifications() {
            val allowed = channels.notificationsAllowed()
            state.update { it.copy(notificationsAllowed = allowed) }
        }

        private fun goTo(step: OnboardingStep) {
            state.update { it.copy(step = step) }
        }

        private fun next() {
            val current = state.value
            when (current.step) {
                OnboardingStep.WELCOME -> {
                    goTo(OnboardingStep.CHECKS)
                }

                OnboardingStep.CHECKS -> {
                    if (!OnboardingLogic.canLeaveSetup(current.action, current.secondary)) return
                    goTo(OnboardingStep.DONE)
                    viewModelScope.launch { readSimNames() }
                    viewModelScope.launch { readPreset() }
                }

                OnboardingStep.DONE -> {
                    finish()
                }
            }
        }

        private suspend fun readSimNames() {
            val names =
                runCatching {
                    val slots = withContext(Dispatchers.IO) { simSlots.readySlots() }
                    strings.simNames(slots)
                }.getOrNull() ?: return
            state.update { it.copy(simNames = names) }
        }

        /** A failed read shows no preset line: the Ready screen must not say that Recommended is on when it is not sure. */
        private suspend fun readPreset() {
            val preset = runCatching { rules.presetAfterSetup() }.getOrNull() ?: return
            state.update { it.copy(recommendedPreset = preset == Preset.RECOMMENDED) }
        }

        /** Reads root, module state and profile, then evaluates the real checklist. */
        private fun runChecks() {
            if (state.value.checking) return
            state.update { it.copy(checking = true, error = false) }
            viewModelScope.launch {
                try {
                    val root = installer.detectRoot()
                    sessions.restore()
                    val handshake = sessions.handshake.value
                    val active = handshake != null
                    val module = installer.status(activeThisBoot = active)
                    val profile = handshake?.profile ?: ProfileStatus.UNKNOWN
                    val checks =
                        InstallChecklist.evaluate(
                            root = root,
                            sdkInt = device.sdkInt(),
                            profile = profile,
                            module = module,
                            activeThisBoot = active,
                        )
                    state.update {
                        it.copy(
                            checking = false,
                            checksRan = true,
                            checks = checks,
                            rootLabel = rootLabel(root),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    state.update { it.copy(checking = false, checksRan = true, error = true) }
                }
            }
        }

        /** "Magisk 30700"; only the version when the manager is not known; null when there is neither. */
        private fun rootLabel(root: RootSolution): String? {
            if (!root.isRooted) return null
            val manager = root.manager.takeIf { it != RootManager.UNKNOWN }?.let { strings.get(it.labelKey) }
            return listOfNotNull(manager, root.version?.takeIf { it.isNotBlank() }).joinToString(" ").ifEmpty { null }
        }

        private fun rebootNow() {
            if (state.value.rebooting) return
            state.update { it.copy(rebooting = true, rebootFailure = null) }
            viewModelScope.launch {
                val result =
                    try {
                        // Persist the resume hint first: a successful reboot kills this process before anything after it
                        // runs. After the reboot the flow opens at Setup, runs the checks, and continues to Ready.
                        settings.setLastBuildFingerprint(Build.FINGERPRINT)
                        installer.reboot()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        ModuleOpResult.Failure(ModuleFailure.CLI_FAILED)
                    }
                when (result) {
                    is ModuleOpResult.Success -> {
                        state.update { it.copy(rebooting = false) }
                    }

                    // Stays open with the reason: the user can try again, or cancel and select Later.
                    is ModuleOpResult.Failure -> {
                        state.update { it.copy(rebooting = false, rebootFailure = result.reason) }
                    }
                }
            }
        }

        private fun finish() {
            if (state.value.finished) return
            viewModelScope.launch {
                settings.setOnboardingDone(true)
                // A new install starts with Recommended. An install with a stored preset keeps it.
                runCatching { rules.applyDefaultPresetOnce() }
                if (settings.current().lastBuildFingerprint == null) {
                    runCatching { settings.setLastBuildFingerprint(Build.FINGERPRINT) }
                }
                state.update { it.copy(finished = true) }
            }
        }
    }
