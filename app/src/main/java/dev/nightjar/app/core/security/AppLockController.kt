package dev.nightjar.app.core.security

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import dev.nightjar.app.core.settings.LockTimeout
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.repository.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Why [authenticateForAppLock] did or did not open the gate. */
enum class AppLockResult {
    /** The user authenticated. */
    AUTHENTICATED,

    /** The user cancelled or the attempt failed; the gate stays closed. */
    CANCELLED,

    /** The device has no enrolled credential at all, so there is nothing to authenticate against. */
    NO_CREDENTIAL,
}

/**
 * Whether the app must show the lock surface.
 *
 * A null [backgroundedAt] means "the app did not record leaving the foreground", which is the cold-start case: the lock
 * is shown even though nothing was backgrounded. Pure, so both the cold-start and the timeout cases are unit-tested
 * without a clock or a device.
 */
fun shouldLock(
    appLock: Boolean,
    timeout: LockTimeout,
    backgroundedAt: Long?,
    now: Long,
): Boolean =
    when {
        !appLock -> false

        backgroundedAt == null -> true

        timeout == LockTimeout.IMMEDIATELY -> true

        // A wall clock that moved back cannot prove that the grace period is still running: lock.
        now < backgroundedAt -> true

        else -> now - backgroundedAt >= timeout.millis
    }

/**
 * The state after a foreground decision. A surface that is already up stays up: leaving it in the background and
 * returning inside the grace period only proves the user did not leave *an unlocked* app, it does not authenticate
 * anyone, so it must never open the gate.
 */
fun lockAfterForeground(
    current: Boolean?,
    decision: Boolean,
): Boolean = current == true || decision

/**
 * App lock.
 *
 * The lock is a *gate*, not an encryption boundary: the database is encrypted with a file key, so the
 * lock only decides whether the app's UI is visible. It re-locks on cold start and after the configured idle time in
 * the background ([LockTimeout]).
 *
 * [locked] is `null` until the first decision after process start: the UI must stay opaque until then, otherwise the
 * first frame would show content built from the default settings snapshot.
 *
 * Authentication uses the platform [BiometricPrompt] with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, which is exactly
 * "strong biometric or device credential" and needs no extra library.
 */
@Singleton
class AppLockController
    @Inject
    constructor(
        private val settings: SettingsStore,
        private val clock: Clock,
        private val audit: AuditLog,
    ) {
        private val _locked = MutableStateFlow<Boolean?>(null)

        /** True while the UI must be covered by the lock screen; null until the first decision. */
        val locked: StateFlow<Boolean?> = _locked.asStateFlow()

        private val _ready = MutableStateFlow(false)

        /** True once the first lock decision has been made; the activity shows nothing before that. */
        val ready: StateFlow<Boolean> = _ready.asStateFlow()

        private var backgroundedAt: Long? = null

        /**
         * Called from `onStart`: locks when the app was backgrounded longer than the timeout, or on cold start.
         *
         * No recorded background time after the first decision means the stop was not a real "left the
         * app" (the credential prompt covered the activity, or the lock was off then); the state stays as it is. With
         * [LockTimeout.IMMEDIATELY] the prompt would otherwise lock the app again right after a successful unlock.
         */
        suspend fun onForeground() {
            // Reading before DataStore has answered would see the default `appLock = false` and never lock.
            settings.loaded.first { it }
            val config = settings.settings.value
            if (!config.appLock) {
                _locked.value = false
                backgroundedAt = null
                _ready.value = true
                return
            }
            val since = backgroundedAt
            val current = _locked.value
            val decision =
                if (since == null && current != null) false else shouldLock(config.appLock, config.lockTimeout, since, clock.now())
            _locked.value = lockAfterForeground(current, decision)
            backgroundedAt = null
            _ready.value = true
        }

        /** Called from `onStop`: remembers when the app left the foreground. A stop caused by the prompt is ignored. */
        fun onBackground() {
            if (AppLockPrompt.active) return
            if (settings.settings.value.appLock) backgroundedAt = clock.now()
        }

        /** Marks the UI unlocked after a successful authentication. */
        fun unlock() {
            _locked.value = false
        }

        /**
         * Opens the gate without a prompt and records why.
         *
         * A device whose credential was removed has nothing left to authenticate against, so the lock must fail open —
         * but the bypass has to be visible in the audit log.
         */
        suspend fun unlockWithoutCredential() {
            _locked.value = false
            // JSON values like every other settings audit row.
            runCatching {
                audit.recordValue(
                    action = AuditAction.SETTINGS_CHANGE,
                    actor = AuditActor.USER,
                    target = "privacy.appLock",
                    before = "locked",
                    after = "unlocked without prompt: no credential enrolled",
                )
            }
        }

        /**
         * Locks immediately.
         *
         * Deliberately unconditional: the only caller is the switch that has just written `privacy.appLock = true`, and
         * re-reading the snapshot there races DataStore's emission, which would leave the app unlocked right after the
         * user turned the lock on.
         */
        fun lockNow() {
            _locked.value = true
        }
    }

/**
 * True while the credential prompt is on screen. The prompt is another activity, so the app gets
 * `onStop`/`onStart` for it; [AppLockController.onBackground] must not count that as leaving the app.
 */
internal object AppLockPrompt {
    @Volatile var active: Boolean = false
}

/**
 * Shows the platform biometric/credential prompt.
 *
 * The prompt allows `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, so a negative button must be omitted: the platform throws
 * for a negative button combined with the device-credential fallback. The user still cancels with Back.
 *
 * Only "no credential enrolled" (the user removed the PIN, or the device never had one) and "no hardware" count as *no
 * lock available* ([AppLockResult.NO_CREDENTIAL]); every other status still attempts the prompt and fails closed, so a
 * transient error leaves the gate closed and the retry action useful.
 */
suspend fun authenticateForAppLock(
    activity: Activity,
    title: String,
    subtitle: String,
): AppLockResult =
    suspendCancellableCoroutine { cont ->
        val manager = activity.getSystemService(BiometricManager::class.java)
        val status =
            manager?.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            ) ?: BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
        if (status == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED || status == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) {
            if (cont.isActive) cont.resume(AppLockResult.NO_CREDENTIAL)
            return@suspendCancellableCoroutine
        }
        val signal = CancellationSignal()
        // A platform that cannot build or show the prompt must not leave the surface waiting forever.
        val prompt =
            runCatching {
                BiometricPrompt
                    .Builder(activity)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    ).build()
            }.getOrNull()
        if (prompt == null) {
            if (cont.isActive) cont.resume(AppLockResult.CANCELLED)
            return@suspendCancellableCoroutine
        }
        val callback =
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    AppLockPrompt.active = false
                    if (cont.isActive) cont.resume(AppLockResult.AUTHENTICATED)
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    AppLockPrompt.active = false
                    if (cont.isActive) cont.resume(AppLockResult.CANCELLED)
                }
            }
        cont.invokeOnCancellation {
            AppLockPrompt.active = false
            signal.cancel()
        }
        AppLockPrompt.active = true
        runCatching { prompt.authenticate(signal, activity.mainExecutor, callback) }
            .onFailure {
                AppLockPrompt.active = false
                if (cont.isActive) cont.resume(AppLockResult.CANCELLED)
            }
    }
