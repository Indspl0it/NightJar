package dev.nightjar.app.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.db.MessageDao
import dev.nightjar.app.data.db.SendingAppRow
import dev.nightjar.contract.Direction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The packages the user blocked from sending SMS. The settings document carries them as
 * `outgoing.blocked`, and the payload `OutboundGate` refuses a send from a package in the list.
 *
 * Credential-protected preferences, like the firewall choice: opened on first use, never before the first unlock. The
 * settings document is only built after the unlock, so [blocked] never sends an empty list in place of the real one.
 */
@Singleton
class OutgoingBlockStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val prefs: SharedPreferences by lazy {
            context.getSharedPreferences("nightjar-outgoing", Context.MODE_PRIVATE)
        }

        /** The blocked package names; empty when there are none or the preferences cannot be read. */
        fun blocked(): Set<String> = runCatching { prefs.getStringSet(KEY, emptySet()).orEmpty().toSet() }.getOrDefault(emptySet())

        /** The same value as a flow, so a change rewrites the settings document. Emits an empty set when unreadable. */
        fun observe(): Flow<Set<String>> =
            callbackFlow {
                val store = runCatching { prefs }.getOrNull()
                if (store == null) {
                    trySend(emptySet())
                    awaitClose()
                    return@callbackFlow
                }
                val listener =
                    SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                        if (key == KEY) trySend(blocked())
                    }
                store.registerOnSharedPreferenceChangeListener(listener)
                trySend(blocked())
                awaitClose { store.unregisterOnSharedPreferenceChangeListener(listener) }
            }.distinctUntilChanged()

        /** Adds [packageName]. Call it off the main thread: it writes to disk before it returns. */
        fun block(packageName: String) = write { it + packageName }

        /** Removes [packageName]. Call it off the main thread: it writes to disk before it returns. */
        fun unblock(packageName: String) = write { it - packageName }

        private fun write(change: (Set<String>) -> Set<String>) {
            synchronized(this) {
                // A new set each time: SharedPreferences must not get back the instance it returned. Synchronous on
                // purpose: the settings push that follows reads the stored set.
                prefs.edit(commit = true) { putStringSet(KEY, HashSet(change(blocked()))) }
            }
        }

        private companion object {
            const val KEY = "blockedPackages"
        }
    }

/** One row of the Settings list "Apps that send SMS". */
data class SendingApp(
    val packageName: String,
    /** Wall time of the newest sent SMS of this package; null when no stored row is left (only blocked). */
    val lastSentWall: Long?,
    /** Stored sent SMS of this package (self-test, replay, released and debug rows not counted). */
    val count: Int,
    val blocked: Boolean,
)

/**
 * The Settings screen API for "Block sending".
 *
 * [apps] lists every package that sent SMS (from the stored outgoing rows), newest first, and after them every blocked
 * package that has no stored row any more, so the user can always unblock it. [setSendBlocked] stores the change and
 * writes the audit record; the store change makes `SettingsSync` push the settings document with the new
 * `outgoing.blocked`. When the payload is not connected, the next hello carries the document.
 */
@Singleton
class OutgoingBlocking
    @Inject
    constructor(
        private val store: OutgoingBlockStore,
        private val dao: MessageDao,
        private val audit: AuditLog,
    ) {
        val apps: Flow<List<SendingApp>> =
            combine(dao.observeSendingApps(Direction.OUT.wire()), store.observe()) { rows, blocked -> merge(rows, blocked) }

        suspend fun setSendBlocked(
            packageName: String,
            blocked: Boolean,
        ) {
            if (packageName.isBlank()) return
            val changed =
                withContext(Dispatchers.IO) {
                    val was = packageName in store.blocked()
                    if (blocked) store.block(packageName) else store.unblock(packageName)
                    was != blocked
                }
            if (!changed) return
            val action = if (blocked) AuditAction.OUTGOING_BLOCK else AuditAction.OUTGOING_UNBLOCK
            audit.record(action, AuditActor.USER, target = packageName)
        }

        internal companion object {
            fun merge(
                rows: List<SendingAppRow>,
                blocked: Set<String>,
            ): List<SendingApp> {
                val seen = rows.map { it.packageName }.toSet()
                val sent = rows.map { SendingApp(it.packageName, it.lastSentWall, it.count, it.packageName in blocked) }
                val onlyBlocked = (blocked - seen).sorted().map { SendingApp(it, lastSentWall = null, count = 0, blocked = true) }
                return sent + onlyBlocked
            }
        }
    }
