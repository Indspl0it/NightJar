package dev.nightjar.app.data.staging

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.data.db.DbKeyStore
import dev.nightjar.app.data.db.DeviceProtectedFiles
import dev.nightjar.app.data.db.StagedEventEntity
import dev.nightjar.app.data.db.StagingDatabase
import dev.nightjar.app.data.db.StagingDatabaseFactory
import dev.nightjar.app.data.db.StagingStore
import dev.nightjar.app.data.db.sqlCipherFactory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two operations the IPC boundary and the unlock drain need.
 *
 * An interface rather than the concrete class: the provider is a `ContentProvider`, so its collaborators have to be
 * replaceable without Hilt (are tested against a staging store that fails).
 */
interface StagingSink {
    /** Stages one event, trimming the table to its cap. False when the staging database is unavailable. */
    suspend fun stage(
        eventId: String,
        json: String,
        receivedAt: Long,
    ): Boolean

    /** Replays every staged event through [consume] and deletes the accepted ones; returns the number drained. */
    suspend fun drain(consume: suspend (StagedEventEntity) -> Boolean): Int

    /** Deletes every staged event ("delete all data"). Returns the number deleted. */
    suspend fun clear(): Int = 0
}

/**
 * Device-protected staging for events captured before the first unlock.
 *
 * The staging database and its key live in device-protected storage, so the IPC provider can write events while the
 * user is locked; the main database (credential-protected) is opened only after unlock. Only the raw event JSON
 * is stored — PDUs and metadata, never decoded text — and the table is capped at [StagingDatabase.MAX_EVENTS].
 *
 * [drain] runs on `ACTION_USER_UNLOCKED` (or the first app start after unlock): it hands each staged event to the
 * ingest pipeline and deletes it only after the consumer reports success, so a crash mid-drain replays the same events
 * and the idempotent upsert makes that a no-op.
 */
@Singleton
class StagingService
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val log: ALog,
    ) : StagingSink {
        private val lock = Any()

        @Volatile private var store: StagingStore? = null

        /** The staging store, opening the encrypted database on first use. */
        fun store(): StagingStore? =
            store ?: synchronized(lock) {
                store ?: open()?.also { store = it }
            }

        private fun open(): StagingStore? =
            runCatching {
                val de = DeviceProtectedFiles.context(context)
                val keys = DbKeyStore(DeviceProtectedFiles.keyDirectory(context))
                val key = keys.loadOrCreate(DbKeyStore.STAGE_KEY)
                val db = StagingDatabaseFactory.open(de, sqlCipherFactory(key))
                key.fill(0)
                StagingStore(db.stagedEventDao())
            }.onFailure { log.w(TAG, "staging database unavailable", it) }.getOrNull()

        /** Stages one event, trimming the table to its cap. Returns false when staging is unavailable. */
        override suspend fun stage(
            eventId: String,
            json: String,
            receivedAt: Long,
        ): Boolean {
            val s = store() ?: return false
            return runCatching {
                s.stage(eventId, json, receivedAt)
                true
            }.getOrElse {
                log.w(TAG, "staging write failed", it)
                false
            }
        }

        /**
         * Replays every staged event through [consume] and deletes the ones it accepted.
         * Returns the number of events drained.
         *
         * The consumer accepts a row that can never be ingested (unreadable JSON) so that it is deleted;
         * false or an exception means "retry at the next drain". Cancellation stops the drain and keeps the rows.
         */
        override suspend fun drain(consume: suspend (StagedEventEntity) -> Boolean): Int {
            val s = store() ?: return 0
            var drained = 0
            while (true) {
                val batch = s.peek(DRAIN_BATCH)
                if (batch.isEmpty()) break
                val accepted = ArrayList<String>(batch.size)
                for (row in batch) {
                    val ok =
                        try {
                            consume(row)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.w(TAG, "staged event not ingested, kept for the next drain", e)
                            false
                        }
                    if (ok) accepted += row.eventId
                }
                if (accepted.isNotEmpty()) s.remove(accepted)
                drained += accepted.size
                // Nothing was accepted: stop rather than spin on a permanent failure; the next unlock retries.
                if (accepted.isEmpty()) break
            }
            if (drained > 0) log.i(TAG, "staged events drained", listOf("count" to drained))
            return drained
        }

        /** Deletes every staged event (test support and "delete all data"). */
        override suspend fun clear(): Int = store()?.clear() ?: 0

        /** True when the staging key file exists (the database has been initialised). */
        fun keyExists(): Boolean = File(DeviceProtectedFiles.keyDirectory(context), DbKeyStore.STAGE_KEY).isFile

        private companion object {
            const val TAG = "staging"
            const val DRAIN_BATCH = 100
        }
    }
