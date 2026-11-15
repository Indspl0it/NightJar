package dev.nightjar.app.core.ipc

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's firewall choice, one value for every build.
 *
 * The firewall is on by default on every build, known or unknown. Only an explicit choice on the Firewall screen turns it
 * off, so the value has three states: null (no choice, the payload default ON stays), true, false. There is no per-build
 * grant and no self-test grant any more (is removed).
 *
 * Credential-protected preferences: opened on first use, never before the first unlock. Before the unlock
 * [choice] returns null, so the hello reply leaves the `firewallEnabled` key out instead of sending a wrong value.
 */
@Singleton
class FirewallChoiceStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val prefs: SharedPreferences by lazy {
            context.getSharedPreferences("nightjar-firewall", Context.MODE_PRIVATE)
        }

        /** The stored choice, or null when there is none or the preferences cannot be read (before the unlock). */
        fun choice(): Boolean? =
            runCatching { prefs.takeIf { it.contains(KEY) }?.getBoolean(KEY, true) }.getOrNull()

        /** The same value as a flow. It emits null and stays open when the preferences cannot be read. */
        fun observe(): Flow<Boolean?> =
            callbackFlow {
                val store = runCatching { prefs }.getOrNull()
                if (store == null) {
                    trySend(null)
                    awaitClose()
                    return@callbackFlow
                }
                val listener =
                    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                        if (key == KEY) trySend(choice())
                    }
                store.registerOnSharedPreferenceChangeListener(listener)
                trySend(choice())
                awaitClose { store.unregisterOnSharedPreferenceChangeListener(listener) }
            }.distinctUntilChanged()

        /** Stores the user's choice. Call it off the main thread: it writes to disk before it returns. */
        fun set(on: Boolean) {
            // Synchronous on purpose, the choice is on disk before the payload is told (a crash cannot lose it).
            prefs.edit(commit = true) { putBoolean(KEY, on) }
        }

        private companion object {
            // A new key: the old per-build set ("enabledBuilds") is not read, so a stale grant cannot turn blocking off.
            const val KEY = "userChoice"
        }
    }

/**
 * The switch of the Firewall screen.
 *
 * [state] is the user's intent: the stored choice, or true when there is no choice (the payload default). What the
 * payload applies right now is in the protection report (`ProtectionReport.firewallEnabled`); the two differ only until
 * the payload connects.
 *
 * [set] stores the choice, writes the audit record and sends ENABLE_FIREWALL. When the payload is not connected, the
 * result is [ControlResult.NoSession] or [ControlResult.Dead] and the next hello reply carries the choice.
 */
@Singleton
class FirewallSwitch
    @Inject
    constructor(
        private val store: FirewallChoiceStore,
        private val connection: PayloadConnection,
        private val audit: AuditLog,
        private val log: ALog,
    ) {
        val state: Flow<Boolean> = store.observe().map { it ?: true }

        suspend fun set(on: Boolean): ControlResult<Unit> {
            val before =
                withContext(Dispatchers.IO) {
                    val previous = store.choice() ?: true
                    store.set(on)
                    previous
                }
            val action = if (on) AuditAction.FIREWALL_ENABLE else AuditAction.FIREWALL_DISABLE
            audit.recordValue(action, AuditActor.USER, TARGET, before, on)
            return send(on)
        }

        /**
         * Sends the stored choice to a connected payload; does nothing when there is no choice. Called once after the
         * unlock ([IpcSyncCoordinator]): a hello before the unlock cannot read the choice, so the payload runs with its
         * default ON until then.
         */
        suspend fun pushStoredChoice() {
            val choice = withContext(Dispatchers.IO) { store.choice() } ?: return
            send(choice)
        }

        private suspend fun send(on: Boolean): ControlResult<Unit> {
            val result = withContext(Dispatchers.IO) { connection.enableFirewall(on) }
            if (result is ControlResult.Failed) log.w(TAG, "enableFirewall failed: ${result.message}")
            return result
        }

        private companion object {
            const val TAG = "firewallSwitch"
            const val TARGET = "firewall"
        }
    }
