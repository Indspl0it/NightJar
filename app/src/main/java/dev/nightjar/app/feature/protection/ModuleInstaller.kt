package dev.nightjar.app.feature.protection

import dev.nightjar.app.domain.protection.ModuleManager
import dev.nightjar.app.domain.protection.ModuleOpResult
import dev.nightjar.app.domain.protection.ModuleStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Progress of the "enable the Xposed module" step, for the onboarding dialogs. */
sealed interface InstallUiState {
    data object Idle : InstallUiState

    /** The user has been told to enable NightJar in Vector/LSPosed; a reboot is offered after this. */
    data class Success(
        val needsReboot: Boolean,
    ) : InstallUiState
}

/**
 * The app's protection lifecycle for the Xposed model.
 *
 * NightJar is itself the Xposed module: nothing is flashed or written under `/data/adb/modules`. "Install" means the
 * user enables NightJar in Vector/LSPosed with scope Phone Services and reboots; this class only detects the framework,
 * records that the reboot prompt comes next, restarts the phone process and reboots. Rules and settings reach the
 * payload over the EventProvider handshake (: the payload pins this app's signing certificate). It uses the one
 * [ModuleManager] of [ProtectionBindings], so the audit mapping exists one time only.
 */
@Singleton
class ModuleInstaller
    @Inject
    constructor(
        private val manager: ModuleManager,
    ) {
        private val state = MutableStateFlow<InstallUiState>(InstallUiState.Idle)
        val installState: StateFlow<InstallUiState> = state.asStateFlow()

        /** Root solution and version (step 2). */
        suspend fun detectRoot() = manager.detectRoot()

        /** Xposed framework presence and whether the payload handshook this boot. */
        suspend fun status(activeThisBoot: Boolean): ModuleStatus = manager.status(activeThisBoot = activeThisBoot)

        /**
         * The "Xposed module" step: the user has enabled NightJar in Vector/LSPosed (scope Phone Services). Nothing is
         * installed here: this only marks the reboot as pending, so the flow moves on to the reboot prompt.
         */
        fun install() {
            state.value = InstallUiState.Success(needsReboot = true)
        }

        /** The action of the restart dialog. */
        suspend fun restartPhoneService(): ModuleOpResult = manager.restartPhoneService()

        /** "Reboot now". */
        suspend fun reboot(): ModuleOpResult = manager.reboot()
    }
