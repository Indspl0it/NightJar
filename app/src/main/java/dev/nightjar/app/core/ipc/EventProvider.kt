package dev.nightjar.app.core.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.UserManager
import dagger.Lazy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.db.HookStatusEntity
import dev.nightjar.app.data.repository.CounterRepository
import dev.nightjar.app.data.repository.HookStatusRepository
import dev.nightjar.app.data.repository.RulesRepository
import dev.nightjar.app.data.staging.StagingSink
import dev.nightjar.app.domain.protection.ModuleSettingsFactory
import dev.nightjar.app.work.IngestSweep
import dev.nightjar.app.work.StagingDrain
import dev.nightjar.contract.Ipc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Payload → app IPC over `ContentResolver.call`.
 *
 * The provider is exported because the phone process calls it, and it rejects every caller whose uid is not 1001 with
 * `null` — the payload treats `null` as a failed call and backs off. Before the first unlock the
 * events go to the device-protected staging database verbatim; after it they are committed to the main
 * database, and the reply is only produced after that transaction.
 *
 * The provider is `directBootAware`, so [onCreate] runs before credential-protected storage exists. It therefore
 * resolves only device-protected objects — the session store, the logger and the staging service — and takes every
 * database-backed collaborator as a [Lazy] that [handle] touches only once [unlocked] is true. Whether the user is
 * unlocked is asked per call, never taken from the payload's handshake extra.
 */
class EventProvider : ContentProvider() {
    /**
     * Created by the framework before anything else in the app, so the entry point cannot be a constructor parameter.
     * A test may install a fake before `attachInfo` runs.
     */
    private var deps: EventProviderEntryPoint? = null

    /**
     * The caller identity for the UID check. A property so a unit test can stand in for a binder caller: a JVM test
     * has none, and the check itself is what has to be exercised. Nothing in production replaces the default.
     */
    internal var callingUid: () -> Int = { Binder.getCallingUid() }

    /** One call at a time. A second ingest waits for the first transaction instead of forking a second writer. */
    private val ingestMutex = Mutex()

    /** The unlock-only work is started once per process, from whichever entry point sees the unlock first. */
    private val unlockedWorkStarted = AtomicBoolean(false)

    private val sessions: IpcSessionStore get() = entry().sessions()

    private val log: ALog get() = entry().log()

    private val staging: StagingSink get() = entry().staging()

    override fun onCreate(): Boolean {
        // Hilt does not support @AndroidEntryPoint on a ContentProvider; an entry point is the supported pattern.
        // Nothing can be logged here: the log is itself behind the entry point, and a failure of the entry point is
        // exactly the case where there is none.
        if (deps == null) {
            deps =
                runCatching {
                    EntryPointAccessors.fromApplication(
                        requireNotNull(context).applicationContext,
                        EventProviderEntryPoint::class.java,
                    )
                }.getOrNull()
        }
        // The framework ignores the return value. Without its dependencies every call answers `null` (see [call]), which
        // the payload treats like an app that is not installed.
        return true
    }

    /** The provider's wiring. Replaced in tests, where Hilt's application component does not exist. */
    internal fun attach(dependencies: EventProviderEntryPoint) {
        deps = dependencies
    }

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        if (deps == null) return null
        val uid = callingUid()
        if (uid != PHONE_UID) {
            // No data, no explanation. A W line records the rejection; a uid is not secret.
            log.w(TAG, "rejected caller uid=$uid method=$method")
            return null
        }
        val reply =
            runCatching {
                runBlocking(Dispatchers.IO) {
                    // A cold SQLCipher open must not pin a binder thread of the phone process indefinitely; the
                    // payload retries on BUSY instead of blocking its own worker.
                    withTimeout(CALL_TIMEOUT_MS) {
                        ingestMutex.withLock { handle(method, extras ?: Bundle.EMPTY) }
                    }
                }
            }.getOrElse { error ->
                if (error is TimeoutCancellationException) {
                    log.w(TAG, "ipc call timed out method=$method")
                    IpcReply.Error(BUSY)
                } else {
                    // An unexpected failure answers with an error the payload retries; it never takes the phone
                    // process down or changes what telephony does.
                    log.w(TAG, "ipc call failed method=$method", error)
                    IpcReply.Error(INTERNAL_ERROR)
                }
            }
        return reply.toBundle()
    }

    private suspend fun handle(
        method: String,
        extras: Bundle,
    ): IpcReply {
        // A process that came up after `ACTION_USER_UNLOCKED` (so the broadcast was never delivered to it) still
        // has to drain the staging database and finish the interrupted pipeline runs.
        if (unlocked()) startUnlockedWork()
        return when (method) {
            Ipc.Method.HELLO -> hello(extras)
            Ipc.Method.EVENTS -> events(extras)
            Ipc.Method.COUNTERS -> counters(extras)
            Ipc.Method.HOOK_HEALTH -> hookHealth(extras)
            Ipc.Method.LOG -> payloadLog(extras)
            else -> IpcReply.Error(IpcRules.UNKNOWN_METHOD)
        }
    }

    /** The live answer, not the payload's frozen handshake flag. */
    private fun unlocked(): Boolean =
        runCatching { entry().userManager().isUserUnlocked }.getOrDefault(false)

    /**
     * The pieces that need credential-protected storage start here, once. [IpcSyncCoordinator] reads the
     * rules and settings tables, so it cannot start before the unlock; the drain replays what Direct Boot staged.
     */
    private fun startUnlockedWork() {
        if (!unlockedWorkStarted.compareAndSet(false, true)) return
        val ctx = context
        runCatching {
            entry().syncCoordinator().get().start()
            if (ctx != null) {
                StagingDrain.schedule(ctx)
                IngestSweep.schedule(ctx)
            }
        }.onFailure { log.w(TAG, "unlock work could not be scheduled", it) }
    }

    /** Creates the session and answers with the rules, settings and firewall state. */
    private suspend fun hello(extras: Bundle): IpcReply {
        val request =
            EventJson.parseHello(
                ipcVersion = extras.getInt(Ipc.Key.IPC_VERSION, Ipc.VERSION),
                payloadVersion = extras.getString(Ipc.Key.PAYLOAD_VERSION),
                buildFingerprint = extras.getString(Ipc.Key.BUILD_FINGERPRINT),
                bootCount = extras.getInt(Ipc.Key.BOOT_COUNT, -1),
                droppedCount = extras.getLong(Ipc.Key.DROPPED_COUNT, 0),
                // The payload's flag is diagnostic only; the live answer comes from [unlocked].
                userUnlocked = extras.getBoolean(Ipc.Key.USER_UNLOCKED, true),
            )
        val firewallEnabled = firewallChoice()
        val session =
            sessions.begin(
                payloadVersion = request.payloadVersion,
                buildFingerprint = request.buildFingerprint,
                bootCount = request.bootCount,
                userUnlocked = request.userUnlocked,
                control = extras.getBinder(Ipc.Key.CONTROL),
                droppedCount = request.droppedCount,
                hookHealthJson = extras.getString(Ipc.Key.HOOK_HEALTH),
                ipcVersion = request.ipcVersion,
                firewallEnabled = firewallEnabled,
            )
        persistHookStatus(extras.getString(Ipc.Key.HOOK_HEALTH))
        val rulesJson = rulesSnapshot()
        if (rulesJson != null && IpcRules.rulesTooLarge(rulesJson.toByteArray(Charsets.UTF_8).size)) {
            log.w(TAG, "rules snapshot exceeds ${Ipc.MAX_RULES_BYTES} bytes")
        }
        return IpcReply.Hello(
            sessionId = session.sessionId,
            rules = rulesJson,
            settings = settingsJson(),
            firewallEnabled = firewallEnabled,
        )
    }

    /**
     * The user's firewall choice (`FirewallChoiceStore`, credential-protected preferences, one value for
     * every build). Null when the user made no choice or the app cannot read it (before the first unlock). A null leaves
     * the `firewallEnabled` key out of the reply, so the payload keeps its default ON; an in-memory default here would
     * override the user's choice at every app restart.
     */
    private fun firewallChoice(): Boolean? {
        if (!unlocked()) return null
        return runCatching { entry().firewallChoice().get().choice() }
            .onFailure { log.w(TAG, "firewall choice not readable", it) }
            .getOrNull()
    }

    /**
     * One transaction, then the ack list.
     *
     * The decision is made per call from [unlocked]. The payload handshakes once per phone-process start — on a
     * fresh boot that is always before the first unlock — so a flag captured there would stage every event for the rest
     * of the boot, and the cap would then evict the real history.
     */
    private suspend fun events(extras: Bundle): IpcReply {
        if (!sessions.matches(extras.getString(Ipc.Key.SESSION_ID))) return IpcReply.UNKNOWN_SESSION
        val batch = extras.getString(Ipc.Key.BATCH) ?: return IpcReply.Error(IpcRules.EMPTY_BATCH)
        return if (unlocked()) ingest(batch) else stage(batch)
    }

    /** The unlocked path: parse, validate, commit, acknowledge. */
    private suspend fun ingest(batch: String): IpcReply {
        val ingest = entry().ingest().get()
        val parsed = ingest.parse(batch)
        IpcRules.eventsBatchError(parsed.size, batch.toByteArray(Charsets.UTF_8).size)?.let { return IpcReply.Error(it) }
        // A batch where no event parses still acknowledges every event that has an eventId; otherwise a batch
        // of two or more unreadable events would be sent again for ever.
        if (parsed.isEmpty()) return IpcReply.Acked(ackUnreadable(batch, parsed))
        // CB pages are folded into one row per broadcast before the transaction; every event of the batch is
        // still acknowledged, under the id the payload knows it by.
        ingest.commit(parsed)
        return IpcReply.Acked(ackAll(parsed) + ackUnreadable(batch, parsed))
    }

    /**
     * An event that the app cannot parse is still acknowledged when it has an `eventId`. The payload would
     * otherwise send the whole batch again and again until the bad event is alone. Nothing of it is stored.
     */
    private fun ackUnreadable(
        batch: String,
        parsed: List<dev.nightjar.app.data.repository.IncomingEvent>,
    ): List<String> {
        val array = runCatching { JSONArray(batch) }.getOrNull() ?: return emptyList()
        val known = parsed.mapTo(HashSet()) { it.eventId }
        val extra = ArrayList<String>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val eventId = o.optString(EVENT_ID, "").ifEmpty { continue }
            if (eventId in known) continue
            extra += ackKey(eventId, o.optInt(REVISION, 1).coerceAtLeast(1))
        }
        if (extra.isNotEmpty()) log.w(TAG, "unreadable events acknowledged count=${extra.size}")
        return extra
    }

    /**
     * Before the first unlock only the raw payload is kept, per event, in device-protected storage.
     *
     * An event is acknowledged only after its staging write succeeded. An acknowledged event is deleted from the
     * payload's spool, so acknowledging a failed write would destroy the only copy exactly when Direct Boot storage is
     * misbehaving.
     */
    private suspend fun stage(batch: String): IpcReply {
        val array = runCatching { JSONArray(batch) }.getOrNull() ?: return IpcReply.Error(IpcRules.MALFORMED_BATCH)
        IpcRules.eventsBatchError(array.length(), batch.toByteArray(Charsets.UTF_8).size)?.let {
            return IpcReply.Error(it)
        }
        val acked = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val eventId = o.optString(EVENT_ID, "").ifEmpty { continue }
            val revision = o.optInt(REVISION, 1).coerceAtLeast(1)
            if (staging.stage(eventId, o.toString(), o.optLong(RECEIVED_AT_WALL, 0))) {
                acked += ackKey(eventId, revision)
            }
        }
        if (acked.isEmpty() && array.length() > 0) return IpcReply.Error(STAGING_UNAVAILABLE)
        return IpcReply.Acked(acked)
    }

    /** Every event of [events] acknowledged under the id the payload sent, in payload order. */
    private fun ackAll(events: List<dev.nightjar.app.data.repository.IncomingEvent>): List<String> =
        events.map { ackKey(it.eventId, it.revision) }

    /** Counters for CL-NORMAL / CL-MO-VISIBLE. */
    private suspend fun counters(extras: Bundle): IpcReply {
        if (!sessions.matches(extras.getString(Ipc.Key.SESSION_ID))) return IpcReply.UNKNOWN_SESSION
        // Counters live in the encrypted database. There is no device-protected copy, so before the unlock the honest
        // answer is "not now" rather than a silent accept that would lose the deltas.
        if (!unlocked()) return IpcReply.Error(LOCKED)
        // One transaction: a failure in the middle followed by the payload's retry must not count the first rows twice.
        val rows =
            EventJson.parseCounters(extras.getString(Ipc.Key.COUNTERS)).map {
                dev.nightjar.app.data.db
                    .CounterEntity(it.day, it.subId, it.direction.wire(), it.classId.wire(), it.count.toLong())
            }
        entry().counters().get().addAll(rows)
        return IpcReply.Ok
    }

    /**
     * The Health screen reads `hook_status`, and nothing else ever writes it. The credential-protected
     * database is only touched once the user is unlocked; a later push after the unlock replaces the rows.
     */
    private suspend fun persistHookStatus(json: String?) {
        if (json.isNullOrBlank() || !unlocked()) return
        runCatching {
            val root = org.json.JSONObject(json)
            val fingerprint = root.optString("fingerprint", "").ifEmpty { return }
            val hooks = root.optJSONObject("hooks") ?: return
            val now = System.currentTimeMillis()
            val rows = ArrayList<HookStatusEntity>(hooks.length())
            val keys = hooks.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val h = hooks.optJSONObject(id) ?: continue
                rows +=
                    HookStatusEntity(
                        buildFingerprint = fingerprint,
                        hookId = id,
                        state = h.optString("state", ""),
                        errorCount = h.optInt("errorCount", 0),
                        lastError = h.optString("lastError", "").ifEmpty { null },
                        updatedAt = now,
                    )
            }
            if (rows.isNotEmpty()) entry().hookStatus().get().replaceForBuild(fingerprint, rows)
        }.onFailure { log.w(TAG, "hook status not stored", it) }
    }

    /** A health push updates the protection state immediately. */
    private suspend fun hookHealth(extras: Bundle): IpcReply {
        if (!sessions.matches(extras.getString(Ipc.Key.SESSION_ID))) return IpcReply.UNKNOWN_SESSION
        val json = extras.getString(Ipc.Key.HOOK_HEALTH)
        sessions.publishToProtection(json)
        // The push carries the current lost-event count (extra, or inside the document).
        val dropped = extras.getLong(Ipc.Key.DROPPED_COUNT, -1L).takeIf { it >= 0 } ?: sessions.health?.droppedCount
        if (dropped != null) sessions.updateDroppedCount(dropped)
        persistHookStatus(json)
        return IpcReply.Ok
    }

    /** L1 lines pushed by the payload. They are already masked by the payload. */
    private fun payloadLog(extras: Bundle): IpcReply {
        if (!sessions.matches(extras.getString(Ipc.Key.SESSION_ID))) return IpcReply.UNKNOWN_SESSION
        val lines = extras.getString(Ipc.Key.LINES)
        if (!lines.isNullOrBlank()) {
            val kept = context?.let { runCatching { PayloadLogStore.of(it).appendJson(lines) }.getOrDefault(0) } ?: 0
            log.i(TAG, "payload log lines=$kept")
        }
        return IpcReply.Ok
    }

    /** C.4 snapshot, or null before the unlock: an empty document would read as "no rules" and reset the payload. */
    private suspend fun rulesSnapshot(): String? =
        if (!unlocked()) {
            null
        } else {
            runCatching { entry().rules().get().serialize() }.getOrElse { error ->
                log.w(TAG, "rules snapshot failed", error)
                null
            }
        }

    /**
     * C.5 body: the one factory that also carries `appPackage`, the app's signing certificate and the blocked
     * sender packages. Null before the unlock for the same reason as the rules snapshot.
     */
    private suspend fun settingsJson(): String? =
        if (!unlocked()) {
            null
        } else {
            runCatching {
                // `current()` reads the store; `settings.value` is the default object until DataStore has answered, which
                // is the case when this call is the one that started the process. The payload would then run on defaults
                // (safe mode off, outgoing on) until the next settings change.
                entry().moduleSettings().get().build(
                    entry()
                        .settings()
                        .get()
                        .current(),
                )
            }.getOrElse { error ->
                log.w(TAG, "settings snapshot failed", error)
                null
            }
        }

    private fun entry(): EventProviderEntryPoint = checkNotNull(deps) { "EventProvider used before onCreate" }

    // No URI surface: the provider exists only for `call()`.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        /** `com.android.phone`'s uid. */
        const val PHONE_UID = 1001

        /** Longer than a cold SQLCipher key derivation plus one batch, shorter than the payload's backoff. */
        const val CALL_TIMEOUT_MS = 15_000L

        /** The payload retries these; the reply is a property of the app side, not a protocol error. */
        const val BUSY = IpcRules.BUSY

        /** Nothing could be staged, so nothing is acknowledged; the payload keeps the event and retries. */
        const val STAGING_UNAVAILABLE = IpcRules.STAGING_UNAVAILABLE

        /** Credential-protected storage is not available yet (before the first unlock); retry later. */
        const val LOCKED = IpcRules.LOCKED

        const val INTERNAL_ERROR = IpcRules.INTERNAL

        /** C.3 event keys that the staging path reads without a full parse. */
        private const val EVENT_ID = "eventId"
        private const val REVISION = "revision"
        private const val RECEIVED_AT_WALL = "receivedAtWall"

        private const val TAG = "ipc"
    }
}

/**
 * The provider's dependencies. ContentProviders are created before the Hilt component is reachable through
 * `@AndroidEntryPoint`, so they are pulled from the application component in [EventProvider.onCreate].
 *
 * Everything that needs the credential-protected database is a [Lazy]: the provider is created in Direct Boot, where
 * resolving it throws, and the values are only used once [EventProvider] has seen an unlocked user.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface EventProviderEntryPoint {
    /** Device-protected: the staging database is the pre-unlock ingest target. */
    fun staging(): StagingSink

    /** In-memory handshake state. */
    fun sessions(): IpcSessionStore

    /** Not available before `onCreate` returns; kept non-lazy because the caller rejects and logs before any unlock. */
    fun log(): ALog

    /** Asks whether the CE storage of the current user is readable. */
    fun userManager(): UserManager

    fun counters(): Lazy<CounterRepository>

    fun rules(): Lazy<RulesRepository>

    fun settings(): Lazy<SettingsStore>

    fun syncCoordinator(): Lazy<IpcSyncCoordinator>

    /** Parse + CB-fold + commit + post-processing, shared with the staging drain. */
    fun ingest(): Lazy<EventIngest>

    /** The per-hook rows the Health screen reads. */
    fun hookStatus(): Lazy<HookStatusRepository>

    /** The one `settings.json` body. */
    fun moduleSettings(): Lazy<ModuleSettingsFactory>

    /** Credential-protected preferences: the user's firewall choice. */
    fun firewallChoice(): Lazy<FirewallChoiceStore>
}
