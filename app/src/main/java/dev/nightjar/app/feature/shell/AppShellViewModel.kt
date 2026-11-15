package dev.nightjar.app.feature.shell

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.core.telephony.SimSlots
import dev.nightjar.app.data.repository.MessageRepository
import dev.nightjar.app.domain.protection.ProtectionReport
import dev.nightjar.app.domain.text.Strings
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.app.domain.text.simNames
import dev.nightjar.app.feature.protection.OtaMonitor
import dev.nightjar.app.feature.protection.ProtectionMonitor
import dev.nightjar.app.notifications.NotificationChannels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Banner content for the shell slot.
 *
 * The protection banner carries its text in [title] and [body] (from the hero copy). The notification and the OTA
 * banners carry string resource ids in [titleRes] and [bodyRes] instead, so the UI reads them in the current language.
 */
data class ShellBanner(
    val title: String = "",
    val body: String = "",
    val isNotification: Boolean,
    /** The build changed since the last self-test; the action opens Health, where the self-test runs. */
    val isOta: Boolean = false,
    @param:StringRes val titleRes: Int = 0,
    @param:StringRes val bodyRes: Int = 0,
)

/** Shell state shared by every destination. */
data class AppShellUiState(
    /**
     * Null until the first settings read; the graph start destination waits for it (no onboarding flash). In one process
     * it only goes from true to false: a finished onboarding is not shown again.
     */
    val startOnboarding: Boolean? = null,
    val report: ProtectionReport? = null,
    val banner: ShellBanner? = null,
    val heroTitle: String = "",
    val heroBody: String = "",
    val quarantineBadge: Int = 0,
    val notificationsAllowed: Boolean = true,
    /** The build fingerprint changed since it was last acknowledged. */
    val otaPrompt: Boolean = false,
)

/**
 * The shell's data: the global banner, the quarantine badge and the start destination.
 *
 * Banner priority is the protection banner first (a protection failure must never be hidden), then the
 * notifications-off banner, then the OTA prompt.
 */
@HiltViewModel
class AppShellViewModel
    @Inject
    constructor(
        private val settings: SettingsStore,
        private val protection: ProtectionMonitor,
        private val messages: MessageRepository,
        private val channels: NotificationChannels,
        private val strings: Strings,
        private val ota: OtaMonitor,
        private val simSlots: SimSlots,
    ) : ViewModel() {
        private val state = MutableStateFlow(AppShellUiState())
        val uiState: StateFlow<AppShellUiState> = state.asStateFlow()

        /** "SIM 1", "SIM 1 and SIM 2" or "your SIMs" for the hero copy. Read off the main thread (binder). */
        private val simNames = MutableStateFlow(strings.get(TextKey.MSG_UNKNOWN_SUBS))

        init {
            viewModelScope.launch { readSimNames() }
            viewModelScope.launch {
                // A store that cannot be read shows the first-run flow: skipping it would leave a new user with no
                // root, no module and no explanation.
                val snapshot = runCatching { settings.current() }.getOrNull()
                state.update { it.copy(startOnboarding = snapshot?.onboardingDone?.not() ?: true) }
            }
            viewModelScope.launch {
                // One-way latch: a finished onboarding switches the graph to the shell. The default snapshot emitted
                // before DataStore's first read is `onboardingDone = false`, so only `true` is acted on.
                settings.settings.collect { snapshot ->
                    if (snapshot.onboardingDone) state.update { it.copy(startOnboarding = false) }
                }
            }
            viewModelScope.launch {
                messages.observeQuarantinedCount().collect { count ->
                    state.update { it.copy(quarantineBadge = count) }
                }
            }
            viewModelScope.launch {
                combine(protection.report, simNames) { report, sims -> report to sims }
                    .collect { (report, sims) ->
                        if (report == null) return@collect
                        val (title, body) = strings.heroText(report, sims)
                        state.update { current ->
                            current.copy(
                                report = report,
                                heroTitle = title,
                                heroBody = body,
                                banner = banner(report, current.notificationsAllowed, title, body, current.otaPrompt),
                            )
                        }
                    }
            }
            viewModelScope.launch {
                // Not during onboarding: the report and its status notification start only after onboarding.
                // Afterwards every handshake and lost session updates the report.
                settings.settings.first { it.onboardingDone }
                protection.watchSessions()
            }
            // Detection is a read, never a prompt: on first run the shell composes before onboarding asks for root,
            // and the root prompt must wait for it. Screens that need a fresh report (Home, Health) call
            // [ProtectionMonitor.refresh] themselves.
            viewModelScope.launch {
                val allowed = runCatching { channels.notificationsAllowed() }.getOrDefault(true)
                state.update {
                    it.copy(notificationsAllowed = allowed, banner = banner(it.report, allowed, it.heroTitle, it.heroBody, it.otaPrompt))
                }
            }
            viewModelScope.launch { checkOta() }
        }

        /**
         * Compares the running build with the last acknowledged one. A device that has no baseline yet (an upgrade
         * from a build that never stored one) gets it now, so the *next* update is detected.
         */
        private suspend fun checkOta() {
            val done = settings.settings.first { it.onboardingDone }
            if (done.lastBuildFingerprint == null) {
                runCatching { ota.acknowledge() }
                return
            }
            updateOtaPrompt()
        }

        /** Reads the OTA prompt again. A self-test acknowledges the build, so the banner goes on the next resume. */
        private suspend fun updateOtaPrompt() {
            val prompt = runCatching { ota.shouldPrompt() }.getOrDefault(false)
            state.update {
                it.copy(otaPrompt = prompt, banner = banner(it.report, it.notificationsAllowed, it.heroTitle, it.heroBody, prompt))
            }
        }

        private suspend fun readSimNames() {
            val slots = withContext(Dispatchers.IO) { simSlots.readySlots() }
            simNames.value = strings.simNames(slots)
        }

        /** Recomputes protection, notification permission, the SIM names, the OTA prompt and the banner (on each resume). */
        fun refresh() {
            viewModelScope.launch {
                runCatching { protection.refresh() }
                readSimNames()
                val allowed = runCatching { channels.notificationsAllowed() }.getOrDefault(true)
                state.update {
                    it.copy(
                        notificationsAllowed = allowed,
                        banner = banner(it.report, allowed, it.heroTitle, it.heroBody, it.otaPrompt),
                    )
                }
                if (state.value.startOnboarding == false) updateOtaPrompt()
            }
        }

        private fun banner(
            report: ProtectionReport?,
            notificationsAllowed: Boolean,
            title: String,
            body: String,
            otaPrompt: Boolean,
        ): ShellBanner? =
            when {
                report != null && report.needsBanner -> {
                    ShellBanner(title = title, body = body, isNotification = false)
                }

                !notificationsAllowed -> {
                    ShellBanner(isNotification = true, titleRes = R.string.ban_03_title, bodyRes = R.string.ban_03_body)
                }

                otaPrompt -> {
                    ShellBanner(isNotification = false, isOta = true, titleRes = R.string.tc_02_title, bodyRes = R.string.tc_02_body)
                }

                else -> {
                    null
                }
            }
    }

/**
 * Needs a re-read every time the app comes back from system settings: the notification permission and the
 * protection report can both change while NightJar is in the background, and `refresh()` has no other caller.
 *
 * Call once in [dev.nightjar.app.ui.nav.NightjarAppRoot], next to `val shell: AppShellViewModel = hiltViewModel()`.
 */
@Composable
fun AppShellResumeEffect(viewModel: AppShellViewModel) {
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
}
