package dev.nightjar.app.feature.protection

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.R
import dev.nightjar.app.core.ipc.IpcSessionStore
import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.core.root.RootOps
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.domain.notify.AlertKind
import dev.nightjar.app.domain.notify.AlertPlan
import dev.nightjar.app.domain.notify.ChannelId
import dev.nightjar.app.domain.notify.DeepLinks
import dev.nightjar.app.domain.notify.NotificationAction
import dev.nightjar.app.domain.protection.ModuleFailure
import dev.nightjar.app.domain.protection.ModuleManager
import dev.nightjar.app.domain.protection.ModuleOpResult
import dev.nightjar.app.domain.text.Explainer
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.app.notifications.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** A `com.android.phone` process that runs without the payload. Health shows it with a restart button. */
data class PhoneProcessProblem(
    /** Pid of the phone process that has no payload. */
    val pid: Int,
    /** `SystemClock.elapsedRealtime()` when the watchdog first saw this process without a payload. */
    val sinceElapsedMs: Long,
)

/** Why Health must not offer the phone service restart now. */
enum class RestartBlock {
    /** A call is active or the phone rings: a restart drops the call. */
    ACTIVE_CALL,

    /** The user turned on safe mode. */
    SAFE_MODE,
}

/**
 * Finds a phone process that came back without the module (: it only detects, it never restarts by itself).
 *
 * A crash of `com.android.phone` (a malformed WAP push, for one) respawns the process, and it does not always come back
 * with the module loaded. While the app process runs, this class reads the pid of the phone process through root every
 * [POLL_MS]. When the pid changes after a payload session and no new payload says hello within [GRACE_MS], it publishes
 * [problem] and posts one notification that opens Health. Only the user starts a restart, with the Health button, and
 * [restartPhone] refuses it during a call and in safe mode.
 *
 * A boot with no payload session is the NOT_ACTIVE state of [ProtectionMonitor], which has its own notification, so the
 * watchdog says nothing about it. If the app process starts after the crash, the watchdog has no old pid to compare and
 * cannot see the problem; the next phone process change is found as usual.
 */
@Singleton
class PhoneWatchdog
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val root: RootOps,
        private val sessions: IpcSessionStore,
        private val settings: SettingsStore,
        private val moduleManager: ModuleManager,
        private val notifier: Notifier,
        private val explainer: Explainer,
        private val clock: Clock,
        private val log: ALog,
    ) {
        // A process-wide singleton: the scope ends with the app process, which is also where polling must stop.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val started = AtomicBoolean(false)
        private val state = MutableStateFlow<PhoneProcessProblem?>(null)

        /** The current problem, or null when the phone process has its payload or the watchdog cannot tell. */
        val problem: StateFlow<PhoneProcessProblem?> = state.asStateFlow()

        fun start() {
            if (!started.compareAndSet(false, true)) return
            scope.launch {
                settings.settings.first { it.onboardingDone }
                loop()
            }
        }

        /** Why a restart is not allowed now, or null when Health can offer it. Health reads it again before each tap. */
        fun restartBlock(): RestartBlock? =
            when {
                inCall() -> RestartBlock.ACTIVE_CALL
                settings.settings.value.safeMode -> RestartBlock.SAFE_MODE
                else -> null
            }

        /**
         * The Health button: restarts `com.android.phone` one time (audited by [ModuleManager]). It does the
         * checks of [restartBlock] again, because a call can start after the screen showed the button.
         */
        suspend fun restartPhone(): ModuleOpResult {
            val block = restartBlock()
            if (block != null) {
                log.i(TAG, "restart refused", listOf("block" to block.name))
                return ModuleOpResult.Failure(ModuleFailure.CLI_FAILED)
            }
            return try {
                moduleManager.restartPhoneService()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "phone service restart failed", e)
                ModuleOpResult.Failure(ModuleFailure.CLI_FAILED)
            }
        }

        private suspend fun loop() {
            var lastPid: Int? = null
            // The session stamp is wall time, so "last seen alive" is wall time too. The grace period uses elapsed time,
            // so a change of the clock cannot shorten or extend it.
            var lastSeenOldWallMs = 0L
            var lastPollWallMs = 0L
            var graceFromMs = SystemClock.elapsedRealtime()
            var sawPayload = false
            while (true) {
                delay(POLL_MS)
                val pid = readPid() ?: continue
                val nowMs = SystemClock.elapsedRealtime()
                val nowWallMs = clock.now()
                val session = sessions.session
                if (pid != lastPid) {
                    // A payload session that the earlier process had: this new process must say hello again.
                    lastSeenOldWallMs = if (lastPid != null && sawPayload) lastPollWallMs else 0L
                    lastPid = pid
                    graceFromMs = nowMs
                }
                lastPollWallMs = nowWallMs
                val hooked = session != null && session.createdAtWallMs > lastSeenOldWallMs
                if (session != null) sawPayload = true
                val orphaned = lastSeenOldWallMs > 0L && !hooked && nowMs - graceFromMs >= GRACE_MS
                publish(if (orphaned) state.value ?: PhoneProcessProblem(pid, nowMs) else null)
            }
        }

        private suspend fun readPid(): Int? =
            try {
                root.phonePid()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

        private fun publish(next: PhoneProcessProblem?) {
            val previous = state.value
            if ((previous == null) == (next == null)) return
            state.value = next
            if (next != null) {
                log.w(TAG, "phone process ${next.pid} has no payload after the grace period. Health offers a restart.")
                runCatching { notifier.post(plan()) }
            } else {
                runCatching { notifier.cancel(NOTIFICATION_ID) }
            }
        }

        private fun inCall(): Boolean {
            // AudioManager.getMode needs no permission (the app has no READ_PHONE_STATE). Any mode other than
            // NORMAL (ringtone, call, call screening, communication) counts as a call.
            val audio = context.getSystemService(AudioManager::class.java) ?: return true
            return audio.mode != AudioManager.MODE_NORMAL
        }

        private fun plan(): AlertPlan {
            val publicText = explainer.render(TextKey.NOTIF_PUBLIC_ACTIVITY)
            return AlertPlan(
                kind = AlertKind.PROTECTION_STATUS,
                channel = ChannelId.STATUS,
                notificationId = NOTIFICATION_ID,
                title = context.getString(R.string.watchdog_title),
                text = context.getString(R.string.watchdog_text),
                bigText = null,
                publicTitle = publicText,
                publicText = publicText,
                groupKey = "nightjar.group",
                alerting = true,
                onlyAlertOnce = true,
                actions = listOf(NotificationAction.OPEN),
                deepLink = DeepLinks.HEALTH,
            )
        }

        private companion object {
            const val TAG = "phone-watchdog"

            /** One root call every 30 s, and only while the app process runs. */
            const val POLL_MS = 30_000L
            const val GRACE_MS = 20_000L

            /** After the status notification ids (0x0F00_0000 + StatusKind.ordinal), so it never replaces one. */
            const val NOTIFICATION_ID = 0x0F00_0010
        }
    }
