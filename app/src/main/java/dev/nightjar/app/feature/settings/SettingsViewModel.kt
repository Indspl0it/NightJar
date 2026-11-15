package dev.nightjar.app.feature.settings

import android.content.Context
import android.hardware.biometrics.BiometricManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.R
import dev.nightjar.app.core.security.AppLockController
import dev.nightjar.app.core.settings.DigestMode
import dev.nightjar.app.core.settings.LockTimeout
import dev.nightjar.app.core.settings.Retention
import dev.nightjar.app.core.settings.Settings
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.core.settings.UiMode
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.maintenance.DataWiper
import dev.nightjar.app.data.maintenance.Housekeeping
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.domain.protection.ModuleFailure
import dev.nightjar.app.domain.protection.ModuleOpResult
import dev.nightjar.app.feature.protection.PhoneWatchdog
import dev.nightjar.app.feature.protection.RestartBlock
import dev.nightjar.app.ui.theme.NjAccent
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Confirmation dialogs Settings can show. */
sealed interface SettingsDialog {
    data object DeleteAll : SettingsDialog

    data class RetentionChange(
        val target: Retention,
        val count: Int,
    ) : SettingsDialog

    data object AppLockNoCredential : SettingsDialog

    data object RemoveModule : SettingsDialog

    data object RestartPhone : SettingsDialog

    data object ResearcherIntro : SettingsDialog

    data class Severity(
        val classId: ClassId,
    ) : SettingsDialog
}

/** One snackbar each. A failure never carries exception text: the screen shows a fixed string per action. */
sealed interface SettingsEvent {
    data object DataDeleted : SettingsEvent

    data object DeleteAllFailed : SettingsEvent

    data class RetentionDeleted(
        val count: Int,
    ) : SettingsEvent

    data object PhoneRestarted : SettingsEvent

    data class RestartFailed(
        val reason: ModuleFailure,
    ) : SettingsEvent

    /** A call or safe mode does not allow the phone service restart now. */
    data class RestartBlocked(
        val block: RestartBlock,
    ) : SettingsEvent

    /** [undoable] is false for the write that an Undo made, so Undo is not offered again. */
    data class SafeModeChanged(
        val enabled: Boolean,
        val undoable: Boolean,
    ) : SettingsEvent

    data object SaveFailed : SettingsEvent
}

data class SettingsUiState(
    val settings: Settings = Settings(),
    val dialog: SettingsDialog? = null,
    val busy: Boolean = false,
    val deleteAllError: Boolean = false,
)

/**
 * state.
 *
 * Every write goes through [SettingsStore], which clamps, audits `SETTINGS_CHANGE` and mirrors the payload keys; the
 * ViewModel adds only the cross-screen steps: the retention dialog before shortening retention, the safe-mode audit,
 * the app-lock credential check and the phone service restart through root. "Turn off NightJar" only tells the user
 * the Vector or LSPosed steps: the app has nothing to remove.
 */
@HiltViewModel
// One action per row of Settings; each delegates to the store, so the width is the screen's, not a second responsibility.
@Suppress("TooManyFunctions")
class SettingsViewModel
    @Inject
    constructor(
        private val store: SettingsStore,
        private val housekeeping: Housekeeping,
        private val wiper: DataWiper,
        private val watchdog: PhoneWatchdog,
        private val audit: AuditLog,
        private val appLock: AppLockController,
        private val clock: Clock,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val dialog = MutableStateFlow<SettingsDialog?>(null)
        private val busy = MutableStateFlow(false)
        private val deleteAllError = MutableStateFlow(false)
        private val channel = Channel<SettingsEvent>(Channel.BUFFERED)

        val events = channel.receiveAsFlow()

        val state: StateFlow<SettingsUiState> =
            combine(store.settings, dialog, busy, deleteAllError) { settings, open, isBusy, error ->
                SettingsUiState(settings, open, isBusy, error)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

        // ------------------------------------------------------------------ mode and appearance

        fun requestMode(mode: UiMode) {
            viewModelScope.launch {
                // The stored value, not the screen state: the screen state is the default until the first emission.
                val current = store.current().mode
                if (mode == UiMode.RESEARCHER && current != UiMode.RESEARCHER) {
                    // Explains what appears. It shows on each switch to Researcher: a short reminder each time.
                    dialog.value = SettingsDialog.ResearcherIntro
                } else {
                    write { store.setMode(mode) }
                }
            }
        }

        fun setTheme(theme: ThemeMode) = write { store.setTheme(theme) }

        fun setDynamicColor(enabled: Boolean) = write { store.setDynamicColor(enabled) }

        fun setAutoReview(enabled: Boolean) = write { store.setAutoReview(enabled) }

        fun setAccent(accent: NjAccent) = write { store.setAccent(accent) }

        /** Off also clears an old [ThemeMode.BLACK], so the switch and the theme agree. */
        fun setTrueBlack(enabled: Boolean) =
            write {
                store.edit {
                    it.copy(trueBlack = enabled, theme = if (!enabled && it.theme == ThemeMode.BLACK) ThemeMode.DARK else it.theme)
                }
            }

        fun setFloatingBar(enabled: Boolean) = write { store.setFloatingBar(enabled) }

        fun setListAnimations(enabled: Boolean) = write { store.setListAnimations(enabled) }

        fun setListBounce(enabled: Boolean) = write { store.setListBounce(enabled) }

        fun setReduceMotion(enabled: Boolean) = write { store.setReduceMotion(enabled) }

        fun setHaptics(enabled: Boolean) = write { store.setHaptics(enabled) }

        // ------------------------------------------------------------------ notifications

        fun setNotifySuspicious(enabled: Boolean) = write { store.setNotifySuspicious(enabled) }

        fun setNotifyNotable(enabled: Boolean) = write { store.setNotifyNotable(enabled) }

        fun setNotifyCb(enabled: Boolean) = write { store.setNotifyCb(enabled) }

        fun setDigest(mode: DigestMode) = write { store.setNotifyDigest(mode) }

        fun setRateLimit(minutes: Int) = write { store.setNotifyRateLimit(minutes) }

        // ------------------------------------------------------------------ detectors

        fun setSilentCount(count: Int) = write { store.setDetectSilentCount(count) }

        fun setSilentWindow(minutes: Int) = write { store.setDetectSilentWindow(minutes) }

        fun setSilentNewSender(enabled: Boolean) = write { store.setDetectSilentNewSender(enabled) }

        fun setSilentRoaming(enabled: Boolean) = write { store.setDetectSilentRoaming(enabled) }

        fun setSuplGrace(minutes: Int) = write { store.setDetectSuplGrace(minutes) }

        fun requestSeverity(classId: ClassId) {
            dialog.value = SettingsDialog.Severity(classId)
        }

        fun setSeverity(
            classId: ClassId,
            severity: Severity?,
        ) {
            dialog.value = null
            write { store.setSeverityOverride(classId, severity) }
        }

        // ------------------------------------------------------------------ outbound and broadcasts

        fun setOutgoingEnabled(enabled: Boolean) = write { store.setOutgoingEnabled(enabled) }

        fun setOutgoingLogVisible(enabled: Boolean) = write { store.setOutgoingLogVisible(enabled) }

        fun setCbLog(enabled: Boolean) = write { store.setCbLog(enabled) }

        fun setCbAllChannels(enabled: Boolean) = write { store.setCbAllChannels(enabled) }

        // ------------------------------------------------------------------ privacy and data

        fun setMaskNumbers(enabled: Boolean) = write { store.setMaskNumbers(enabled) }

        fun setHideInNotifications(enabled: Boolean) = write { store.setHideInNotifications(enabled) }

        fun setBlockScreenshots(enabled: Boolean) = write { store.setBlockScreenshots(enabled) }

        fun setLockTimeout(timeout: LockTimeout) = write { store.setLockTimeout(timeout) }

        fun setAppLock(enabled: Boolean) {
            if (!enabled) {
                write { store.setAppLock(false) }
                return
            }
            if (!credentialAvailable()) {
                dialog.value = SettingsDialog.AppLockNoCredential
                return
            }
            write {
                store.setAppLock(true)
                // The lock covers the app as soon as it is switched on, not at the next cold start.
                appLock.lockNow()
            }
        }

        /**
         * Shortening retention deletes now, so the count is shown first. Choosing "Delete" applies the
         * new value and runs housekeeping; Cancel leaves the previous value untouched.
         */
        fun requestRetention(target: Retention) {
            viewModelScope.launch {
                val current = store.current().retention
                if (target == current) return@launch
                val count =
                    try {
                        housekeeping.countForRetention(target, clock.now())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        0
                    }
                val shortens = current.days == null || (target.days != null && target.days < current.days)
                if (count > 0 && shortens) {
                    dialog.value = SettingsDialog.RetentionChange(target, count)
                } else {
                    applyRetention(target)
                }
            }
        }

        private fun applyRetention(target: Retention) {
            dialog.value = null
            busy.value = true
            viewModelScope.launch {
                try {
                    store.setRetention(target)
                    val report = housekeeping.run(clock.now())
                    channel.send(SettingsEvent.RetentionDeleted(report.messagesDeleted))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    channel.send(SettingsEvent.SaveFailed)
                } finally {
                    busy.value = false
                }
            }
        }

        fun confirmDialog() {
            when (val open = dialog.value) {
                null -> {
                    Unit
                }

                // Requires the typed word; only the dialog's own confirm button calls [deleteAll].
                SettingsDialog.DeleteAll -> {
                    Unit
                }

                is SettingsDialog.RetentionChange -> {
                    applyRetention(open.target)
                }

                SettingsDialog.AppLockNoCredential -> {
                    dialog.value = null
                }

                // Is information only: OK closes it.
                SettingsDialog.RemoveModule -> {
                    dialog.value = null
                }

                SettingsDialog.RestartPhone -> {
                    restartPhoneService()
                }

                SettingsDialog.ResearcherIntro -> {
                    dialog.value = null
                    write { store.setMode(UiMode.RESEARCHER) }
                }

                is SettingsDialog.Severity -> {
                    dialog.value = null
                }
            }
        }

        fun dismissDialog() {
            if (!busy.value) dialog.value = null
        }

        fun requestDeleteAll() {
            deleteAllError.value = false
            dialog.value = SettingsDialog.DeleteAll
        }

        /**
         * The confirmation word is required; anything else shows the error and keeps the dialog open. The word is
         * the hint resource, so a translation of the hint and the check stay the same word.
         */
        fun deleteAll(typed: String) {
            if (busy.value) return
            if (typed.trim() != context.getString(R.string.settings_delete_all_hint)) {
                deleteAllError.value = true
                return
            }
            dialog.value = null
            deleteAllError.value = false
            busy.value = true
            viewModelScope.launch {
                try {
                    wiper.deleteAll()
                    channel.send(SettingsEvent.DataDeleted)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    channel.send(SettingsEvent.DeleteAllFailed)
                } finally {
                    busy.value = false
                }
            }
        }

        fun requestRemoveModule() {
            dialog.value = SettingsDialog.RemoveModule
        }

        /** Opens only when no call is active and safe mode is off ([PhoneWatchdog.restartBlock]). */
        fun requestRestartPhone() {
            viewModelScope.launch {
                when (val block = withContext(Dispatchers.IO) { watchdog.restartBlock() }) {
                    null -> {
                        dialog.value = SettingsDialog.RestartPhone
                    }

                    else -> {
                        channel.send(SettingsEvent.RestartBlocked(block))
                    }
                }
            }
        }

        /** [PhoneWatchdog.restartPhone] checks again, because a call can start while the restart dialog is open. */
        private fun restartPhoneService() {
            dialog.value = null
            busy.value = true
            viewModelScope.launch {
                try {
                    val block = withContext(Dispatchers.IO) { watchdog.restartBlock() }
                    if (block != null) {
                        channel.send(SettingsEvent.RestartBlocked(block))
                        return@launch
                    }
                    when (val result = withContext(Dispatchers.IO) { watchdog.restartPhone() }) {
                        is ModuleOpResult.Success -> channel.send(SettingsEvent.PhoneRestarted)
                        is ModuleOpResult.Failure -> channel.send(SettingsEvent.RestartFailed(result.reason))
                    }
                } finally {
                    busy.value = false
                }
            }
        }

        // ------------------------------------------------------------------ advanced

        fun setDebugLogging(enabled: Boolean) = write { store.setDebugLogging(enabled) }

        /** [undoable] is false when the snackbar's Undo calls this, so the undo snackbar has no Undo of its own. */
        fun setSafeMode(
            enabled: Boolean,
            undoable: Boolean = true,
        ) {
            write {
                store.setSafeMode(enabled)
                audit.record(
                    action = if (enabled) AuditAction.SAFE_MODE_ON else AuditAction.SAFE_MODE_OFF,
                    actor = AuditActor.USER,
                    target = "advanced.safeMode",
                )
                channel.send(SettingsEvent.SafeModeChanged(enabled, undoable))
            }
        }

        private fun credentialAvailable(): Boolean {
            val manager = context.getSystemService(BiometricManager::class.java) ?: return false
            return manager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            ) == BiometricManager.BIOMETRIC_SUCCESS
        }

        private fun write(block: suspend () -> Unit) {
            viewModelScope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    channel.send(SettingsEvent.SaveFailed)
                }
            }
        }
    }
