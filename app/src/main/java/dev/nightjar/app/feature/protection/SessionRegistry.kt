package dev.nightjar.app.feature.protection

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.domain.protection.HandshakeSnapshot
import dev.nightjar.app.domain.protection.ProfileStatus
import dev.nightjar.contract.Ipc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The current payload session, as far as protection state is concerned.
 *
 * The app-side IPC provider calls [onHandshake] on `hello` and [onSessionLost] when the control binder dies or
 * `ping` times out; everything else reads [handshake]. Keeping the state here rather than inside the provider means
 * the state machine and the UI do not depend on the IPC implementation.
 */
@Singleton
class SessionRegistry
    @Inject
    constructor(
        private val clock: Clock,
        private val store: HandshakeStore = HandshakeStore.NONE,
    ) {
        private val state = MutableStateFlow<HandshakeSnapshot?>(null)

        /** Set once the persisted handshake was looked at, or a live event made it irrelevant. */
        @Volatile private var restored = false

        val handshake: StateFlow<HandshakeSnapshot?> = state.asStateFlow()

        /**
         * Brings back the handshake of this boot after the app process was killed.
         *
         * The payload says `hello` once per phone-process start, and the app process that received it is an ordinary
         * low-priority process: when it dies, the in-memory session goes with it and nothing re-sends the `hello` until
         * the payload next has an event. Without this, the module row and the protection state read "not active" on a
         * device whose payload is running fine. A handshake from an earlier boot is never restored.
         *
         * Suspends on disk; call it before reading [handshake] from a screen or the protection monitor.
         */
        suspend fun restore() {
            if (restored) return
            val saved = withContext(Dispatchers.IO) { store.load() }
            restored = true
            if (saved != null) state.compareAndSet(null, saved)
        }

        fun onHandshake(
            bootCount: Int?,
            payloadVersion: String,
            buildFingerprint: String?,
            profile: ProfileStatus,
            captureTierLive: Boolean,
            firewallTierLive: Boolean,
            firewallEnabled: Boolean,
            failedFirewallHook: String? = null,
        ) {
            restored = true
            val snapshot =
                HandshakeSnapshot(
                    bootCount = bootCount,
                    payloadVersion = payloadVersion,
                    buildFingerprint = buildFingerprint,
                    profile = profile,
                    captureTierLive = captureTierLive,
                    firewallTierLive = firewallTierLive,
                    firewallEnabled = firewallEnabled,
                    receivedAtWallMs = clock.now(),
                    failedFirewallHook = failedFirewallHook,
                )
            state.value = snapshot
            store.save(snapshot)
        }

        /** Dead binder, failed `ping` or a new boot: the session no longer counts. */
        fun onSessionLost() {
            restored = true
            state.value = null
            store.clear()
        }
    }

/**
 * Persistence for the last handshake, so a new app process can tell that the payload handshook during this boot (see
 * [SessionRegistry.restore]). Only the facts the protection state machine reads are kept; there is no session id and no
 * binder, so nothing here can authorise a call.
 */
interface HandshakeStore {
    /** The saved handshake if it is from the current boot, otherwise null. Blocking. */
    fun load(): HandshakeSnapshot?

    fun save(snapshot: HandshakeSnapshot)

    fun clear()

    companion object {
        val NONE: HandshakeStore =
            object : HandshakeStore {
                override fun load(): HandshakeSnapshot? = null

                override fun save(snapshot: HandshakeSnapshot) = Unit

                override fun clear() = Unit
            }

        /**
         * A saved handshake counts only when it names the boot it came from and that is the boot running now. A snapshot
         * without a boot count cannot be dated, so it is dropped rather than trusted across a reboot.
         */
        fun restorable(
            saved: HandshakeSnapshot?,
            currentBootCount: Int?,
        ): HandshakeSnapshot? =
            saved?.takeIf { it.bootCount != null && currentBootCount != null && it.bootCount == currentBootCount }
    }
}

/** [HandshakeStore] over a small SharedPreferences file in app-private storage. */
@Singleton
class PrefsHandshakeStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val bootCounter: BootCounter,
    ) : HandshakeStore {
        private val prefs: SharedPreferences by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

        override fun load(): HandshakeSnapshot? =
            runCatching {
                if (!prefs.contains(K_BOOT)) return null
                val profile = prefs.getString(K_PROFILE, null)
                val saved =
                    HandshakeSnapshot(
                        bootCount = prefs.getInt(K_BOOT, -1).takeIf { it >= 0 },
                        payloadVersion = prefs.getString(K_VERSION, null) ?: return null,
                        buildFingerprint = prefs.getString(K_FINGERPRINT, null),
                        profile = ProfileStatus.entries.firstOrNull { it.name == profile } ?: ProfileStatus.UNKNOWN,
                        captureTierLive = prefs.getBoolean(K_CAPTURE, true),
                        firewallTierLive = prefs.getBoolean(K_FIREWALL_TIER, true),
                        // Blocking is on by default, the same default as the payload and IpcSessionStore.
                        firewallEnabled = prefs.getBoolean(K_FIREWALL_ON, true),
                        receivedAtWallMs = prefs.getLong(K_AT, 0),
                        failedFirewallHook = prefs.getString(K_FAILED_HOOK, null),
                    )
                HandshakeStore.restorable(saved, bootCounter.current())
            }.getOrNull()

        override fun save(snapshot: HandshakeSnapshot) {
            runCatching {
                prefs.edit {
                    putInt(K_BOOT, snapshot.bootCount ?: -1)
                    putString(K_VERSION, snapshot.payloadVersion)
                    putString(K_FINGERPRINT, snapshot.buildFingerprint)
                    putString(K_PROFILE, snapshot.profile.name)
                    putBoolean(K_CAPTURE, snapshot.captureTierLive)
                    putBoolean(K_FIREWALL_TIER, snapshot.firewallTierLive)
                    putBoolean(K_FIREWALL_ON, snapshot.firewallEnabled)
                    putLong(K_AT, snapshot.receivedAtWallMs)
                    putString(K_FAILED_HOOK, snapshot.failedFirewallHook)
                }
            }
        }

        override fun clear() {
            runCatching { prefs.edit { clear() } }
        }

        private companion object {
            const val FILE = "nightjar-handshake"
            const val K_BOOT = Ipc.Key.BOOT_COUNT
            const val K_VERSION = Ipc.Key.PAYLOAD_VERSION
            const val K_FINGERPRINT = Ipc.Key.BUILD_FINGERPRINT
            const val K_PROFILE = "profile"
            const val K_CAPTURE = "captureTierLive"
            const val K_FIREWALL_TIER = "firewallTierLive"
            const val K_FIREWALL_ON = Ipc.Key.FIREWALL_ENABLED
            const val K_AT = "receivedAtWallMs"
            const val K_FAILED_HOOK = "failedFirewallHook"
        }
    }

/** Reads `Settings.Global.BOOT_COUNT`, the only reliable way to tell "this boot" from an earlier one. */
@Singleton
class BootCounter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** The current boot count, or null when the setting is unavailable (the state machine then trusts the session). */
        fun current(): Int? {
            val value = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            return value.takeIf { it >= 0 }
        }
    }
