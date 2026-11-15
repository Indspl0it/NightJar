package dev.nightjar.app.feature.protection

import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.domain.notify.NotificationIds
import dev.nightjar.app.domain.protection.ProtectionInputs
import dev.nightjar.app.domain.protection.ProtectionReport
import dev.nightjar.app.domain.protection.ProtectionStateEngine
import dev.nightjar.app.notifications.StatusNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the app's view of the protection state.
 *
 * Inputs come from three places: the payload session ([SessionRegistry]), `Settings.Global.BOOT_COUNT` ([BootCounter])
 * and the user's own safe-mode switch. There is no root-written safe-mode flag in the Xposed model. The engine is a pure
 * function, so this class only gathers and publishes.
 */
@Singleton
class ProtectionMonitor
    @Inject
    constructor(
        private val sessions: SessionRegistry,
        private val bootCounter: BootCounter,
        private val settings: SettingsStore,
        private val statusNotifier: StatusNotifier,
    ) {
        private val state = MutableStateFlow<ProtectionReport?>(null)

        /** The last computed report; null until [refresh] has run once. */
        val report: StateFlow<ProtectionReport?> = state.asStateFlow()

        /**
         * Set once per boot so the "not active" status alert is not repeated. The boot worker, the session
         * watcher and the screens change it from different threads, so it is a concurrent set.
         */
        private val postedThisBoot: MutableSet<NotificationIds.StatusKind> = ConcurrentHashMap.newKeySet()

        /**
         * Recomputes the state and syncs the status notifications. Called at start, on every handshake, after a ping,
         * after a safe-mode change and from the boot check. A failure goes to the caller, which keeps the previous
         * report (fail open).
         */
        suspend fun refresh(): ProtectionReport {
            // `settings.settings.value` is the default snapshot until DataStore has answered; reading it first would
            // drop a user safe mode from the first report after process start.
            settings.loaded.first { it }
            // A new process has no handshake yet; the payload only says hello once per phone-process start.
            sessions.restore()
            val inputs =
                ProtectionInputs(
                    handshake = sessions.handshake.value,
                    currentBootCount = bootCounter.current(),
                    userSafeMode = settings.settings.value.safeMode,
                )
            val report = ProtectionStateEngine.evaluate(inputs)
            state.value = report
            // A copy: the notifier must not read a set that another thread changes during the sync.
            statusNotifier.sync(report, moduleNewerThanApp = false, oncePerBootAlreadyPosted = postedThisBoot.toSet())
            return report
        }

        /**
         * Keeps [report] current across handshakes: a `hello` (or a lost session) changes the state without anyone
         * calling [refresh], so screens that only read [report] would show "not active" until the user left and
         * re-entered them. Runs until the caller's scope is cancelled.
         */
        suspend fun watchSessions() {
            sessions.handshake.collect {
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A root or notification failure keeps the previous report (fail open); the next handshake retries.
                }
            }
        }

        /** Records that a condition was already alerted this boot, so [refresh] does not repeat it. */
        fun markPosted(kind: NotificationIds.StatusKind) {
            postedThisBoot += kind
        }
    }
