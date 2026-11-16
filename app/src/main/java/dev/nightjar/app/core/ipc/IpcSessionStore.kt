package dev.nightjar.app.core.ipc

import dev.nightjar.app.domain.protection.ProfileStatus
import dev.nightjar.app.feature.protection.SessionRegistry
import dev.nightjar.contract.HookState
import dev.nightjar.contract.Ipc
import dev.nightjar.contract.Tier
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** A live payload session: the id the payload echoes and the control binder it handed over (C.2). */
data class PayloadSession(
    val sessionId: String,
    val payloadVersion: String,
    val buildFingerprint: String?,
    val bootCount: Int?,
    val userUnlocked: Boolean,
    val control: android.os.IBinder?,
    val droppedCount: Long,
    val createdAtWallMs: Long,
    /** The IPC version the payload sent in `hello`. */
    val ipcVersion: Int = Ipc.VERSION,
) {
    val hasControl: Boolean get() = control?.isBinderAlive == true

    /** The payload speaks a newer IPC version than this app. */
    val payloadNewer: Boolean get() = IpcRules.isPayloadNewer(ipcVersion)
}

/**
 * Owns the handshake state (T03).
 *
 * The app is the server here: a `hello` creates a session, every other call must echo its id, and the control binder
 * is watched with `linkToDeath` so a payload crash immediately invalidates the session instead of leaving the UI
 * showing a stale "protected" state. All state is in one [AtomicReference] so a binder thread and a UI thread never
 * observe a half-updated session.
 */
@Singleton
class IpcSessionStore
    @Inject
    constructor(
        private val sessions: SessionRegistry,
        private val clock: dev.nightjar.app.data.repository.Clock,
    ) {
        private val current = AtomicReference<PayloadSession?>(null)

        /**
         * Firewall on in the payload. It starts true, the payload default on every build. The user's choice seeds
         * it at each `hello` when there is one and the app can read it; otherwise the last known value stays,
         * as the payload keeps its own. ENABLE_FIREWALL updates it ([setFirewallEnabled]).
         */
        private val firewallEnabled = AtomicReference(true)

        private val deathWatch = AtomicReference<android.os.IBinder.DeathRecipient?>(null)

        /** The last hook-health document the payload sent, so a push without one does not reset the profile. */
        private val lastHealth = AtomicReference<HookHealth?>(null)

        val session: PayloadSession? get() = current.get()

        val sessionId: String? get() = current.get()?.sessionId

        val isFirewallEnabled: Boolean get() = firewallEnabled.get()

        /** The last hook-health document the payload sent, parsed (rules revision and error). */
        val health: HookHealth? get() = lastHealth.get()

        /**
         * Starts a new session, replacing any previous one (the payload restarts on every phone-process restart).
         * Returns the session id for the reply.
         */
        fun begin(
            payloadVersion: String,
            buildFingerprint: String?,
            bootCount: Int?,
            userUnlocked: Boolean,
            control: android.os.IBinder?,
            droppedCount: Long,
            hookHealthJson: String?,
            ipcVersion: Int = Ipc.VERSION,
            firewallEnabled: Boolean? = null,
        ): PayloadSession {
            val previous = current.getAndSet(null)
            previous?.control?.let { binder -> unlink(binder) }
            val session =
                PayloadSession(
                    sessionId = UUID.randomUUID().toString(),
                    payloadVersion = payloadVersion,
                    buildFingerprint = buildFingerprint,
                    bootCount = bootCount,
                    userUnlocked = userUnlocked,
                    control = control,
                    droppedCount = droppedCount,
                    createdAtWallMs = clock.now(),
                    ipcVersion = ipcVersion,
                )
            current.set(session)
            if (firewallEnabled != null) this.firewallEnabled.set(firewallEnabled)
            val health = hookHealthJson?.let(HookHealth::parse)
            lastHealth.set(health)
            if (control != null) {
                val recipient = DeathWatch(session.sessionId)
                // A binder that died between the handshake and this call throws here: the session is dropped at once
                // instead of showing "protected" for a payload that is already gone.
                if (runCatching { control.linkToDeath(recipient, 0) }.isSuccess) {
                    deathWatch.set(recipient)
                } else {
                    clearIfCurrent(session.sessionId)
                    return session
                }
            }
            publish(health, session)
            return session
        }

        /** Updates what the protection engine needs after a handshake or a `hookHealth` push. */
        fun publishToProtection(
            hookHealthJson: String?,
            session: PayloadSession? = current.get(),
        ) {
            if (session == null) return
            // A push without a health document (a firewall toggle) keeps what the payload last reported; treating it as
            // "unknown" would drop a KNOWN profile and turn blocking off the moment it was enabled.
            val health =
                hookHealthJson?.let(HookHealth::parse)?.also { lastHealth.set(it) } ?: lastHealth.get()
            publish(health, session)
        }

        /** The lost-event count of a `hookHealth` push replaces the value from `hello`. */
        fun updateDroppedCount(droppedCount: Long) {
            if (droppedCount < 0) return
            while (true) {
                val live = current.get() ?: return
                if (current.compareAndSet(live, live.copy(droppedCount = droppedCount))) return
            }
        }

        private fun publish(
            health: HookHealth?,
            session: PayloadSession,
        ) {
            sessions.onHandshake(
                bootCount = session.bootCount,
                payloadVersion = session.payloadVersion,
                buildFingerprint = session.buildFingerprint,
                profile = health?.profile ?: ProfileStatus.UNKNOWN,
                captureTierLive = health?.captureTierLive ?: true,
                firewallTierLive = health?.firewallTierLive ?: true,
                firewallEnabled = firewallEnabled.get(),
                failedFirewallHook = health?.failedFirewallHook,
            )
        }

        fun setFirewallEnabled(enabled: Boolean) {
            firewallEnabled.set(enabled)
            current.get()?.let { publishToProtection(hookHealthJson = null, session = it) }
        }

        /** True when [candidate] is the live session (C.2: unknown session ⇒ `UNKNOWN_SESSION`). */
        fun matches(candidate: String?): Boolean {
            val live = current.get() ?: return false
            return candidate != null && candidate == live.sessionId
        }

        fun clear() {
            current.get()?.control?.let { binder -> unlink(binder) }
            current.set(null)
            sessions.onSessionLost()
        }

        /**
         * Clears only when [binder] is still the control binder of the live session: a call that failed on an old binder
         * must not end the session of a newer hello.
         */
        fun clearIfControl(binder: android.os.IBinder) {
            val live = current.get() ?: return
            if (live.control !== binder) return
            if (current.compareAndSet(live, null)) {
                unlink(binder)
                sessions.onSessionLost()
            }
        }

        /** Clears only when [sessionId] is still the live session: a late death callback must not kill its successor. */
        private fun clearIfCurrent(sessionId: String) {
            val live = current.get() ?: return
            if (live.sessionId != sessionId) return
            if (current.compareAndSet(live, null)) sessions.onSessionLost()
        }

        private fun unlink(binder: android.os.IBinder) {
            deathWatch.getAndSet(null)?.let { recipient -> runCatching { binder.unlinkToDeath(recipient, 0) } }
        }

        /** The payload process died: the session ends now, so the UI stops reporting protection. */
        private inner class DeathWatch(
            private val sessionId: String,
        ) : android.os.IBinder.DeathRecipient {
            override fun binderDied() = clearIfCurrent(sessionId)
        }
    }

/**
 * The parts of the C.8 hook-health JSON the protection state machine reads.
 *
 * The tier decision is the payload's. `firewallAvailable` is the payload's own firewall rule (HK-24 + HK-14 +
 * HK-15); `captureLive` is read when the payload sends it, else it is derived from the hook map exactly as the payload's
 * TierManager does (HK-06 and HK-04 and one of HK-01/HK-01b). The hook map only names the failed firewall-core hook.
 */
data class HookHealth(
    val profile: ProfileStatus,
    val captureTierLive: Boolean,
    val firewallTierLive: Boolean,
    val failedFirewallHook: String?,
    /** The rules revision the payload applied, when it reports one. */
    val rulesRevision: Long? = null,
    /** Why the payload could not apply the last rules snapshot (a class name only), when it reports one. */
    val rulesError: String? = null,
    /** The payload's lost-event count, when the document has one. */
    val droppedCount: Long? = null,
    /** How many rules of the applied snapshot the payload skipped (not supported there), when it reports it. */
    val rulesSkipped: Int? = null,
) {
    companion object {
        private val CAPTURE_HOOKS = listOf("HK-06", "HK-04")
        private val RADIO_HOOKS = listOf("HK-01", "HK-01b")

        /** Tolerant parse: a missing or broken map keeps the optimistic defaults (capture assumed live). */
        fun parse(json: String?): HookHealth? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val root = org.json.JSONObject(json)
                val profile =
                    when (root.optString("profile", "").uppercase()) {
                        "KNOWN" -> ProfileStatus.KNOWN
                        "MISMATCH" -> ProfileStatus.MISMATCH
                        else -> ProfileStatus.UNKNOWN
                    }
                val hooks = root.optJSONObject("hooks")
                val live = HookState.LIVE.wire()

                fun stateOf(id: String): String? = hooks?.optJSONObject(id)?.optString("state", "")
                var failed: String? = null
                if (hooks != null) {
                    val keys = hooks.keys()
                    while (keys.hasNext()) {
                        val id = keys.next()
                        val entry = hooks.optJSONObject(id) ?: continue
                        val core = entry.optString("tier", "") == Tier.FIREWALL_CORE.wire()
                        if (core && entry.optString("state", "") != live) {
                            failed = id
                            break
                        }
                    }
                }
                val captureFromHooks =
                    hooks == null ||
                        (CAPTURE_HOOKS.all { stateOf(it) == live } && RADIO_HOOKS.any { stateOf(it) == live })
                HookHealth(
                    profile = profile,
                    captureTierLive = if (root.has("captureLive")) root.optBoolean("captureLive") else captureFromHooks,
                    firewallTierLive =
                        if (root.has("firewallAvailable")) root.optBoolean("firewallAvailable") else failed == null,
                    failedFirewallHook = failed,
                    rulesRevision = if (root.has("rulesRevision")) root.optLong("rulesRevision") else null,
                    rulesError = root.optString("rulesError", "").ifEmpty { null },
                    droppedCount = if (root.has(Ipc.Key.DROPPED_COUNT)) root.optLong(Ipc.Key.DROPPED_COUNT) else null,
                    rulesSkipped = if (root.has("rulesSkipped")) root.optInt("rulesSkipped") else null,
                )
            }.getOrNull()
        }
    }
}
