package dev.nightjar.app.data.maintenance

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.core.ipc.PayloadLogStore
import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.core.settings.Retention
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.db.CounterDao
import dev.nightjar.app.data.db.DetectionDao
import dev.nightjar.app.data.db.HookStatusDao
import dev.nightjar.app.data.db.MessageDao
import dev.nightjar.app.data.db.MuteDao
import dev.nightjar.app.data.db.NightjarDatabase
import dev.nightjar.app.data.db.SelfTestRunDao
import dev.nightjar.app.data.db.databaseSizeBytes
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.data.staging.StagingSink
import dev.nightjar.contract.AckResult
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.FateDetail
import javax.inject.Inject
import javax.inject.Singleton

/** What one housekeeping run did. Logged at I as counts only. */
data class RetentionReport(
    val messagesDeleted: Int,
    val countersDeleted: Int,
    val hookFingerprintsDeleted: Int,
    val selfTestRunsDeleted: Int,
    val auditRowsDeleted: Int,
    val detectionsDeleted: Int,
    val mutesDeleted: Int,
    val databaseBytes: Long,
) {
    /** Over 200 MB the Health row suggests a shorter retention. */
    val overSizeLimit: Boolean get() = databaseBytes > SIZE_LIMIT_BYTES

    companion object {
        const val SIZE_LIMIT_BYTES = 200L * 1024 * 1024
    }
}

/**
 * Retention and housekeeping.
 *
 * Runs daily from [RetentionWorker] and immediately after the user shortens retention. Rules and settings
 * are never touched here: retention only deletes captured data.
 */
interface Housekeeping {
    suspend fun run(nowWallMs: Long): RetentionReport

    /** Count: how many messages a new retention would delete right now. */
    suspend fun countForRetention(
        retention: Retention,
        nowWallMs: Long,
    ): Int
}

/**
 * Rows that an older version stored as FT-UNKNOWN get the true fate they show (no message shows "unknown").
 * The list and the detail already read such a row with [dev.nightjar.app.domain.ingest.TrueFate]; this makes the stored
 * value, the filters and the counts agree with it.
 */
internal suspend fun repairUnknownFates(messages: MessageDao): Int =
    messages.repairUnknownFates(
        unknown = Fate.UNKNOWN.wire(),
        outbound = Direction.OUT.wire(),
        noDispatchLike = "%\"${FateDetail.NO_DISPATCH}\":true%",
        notSent = Fate.NOT_SENT.wire(),
        sendUnconfirmed = Fate.SEND_UNCONFIRMED.wire(),
        accepted = Fate.ACCEPTED.wire(),
        notDispatched = Fate.NOT_DISPATCHED.wire(),
        nackStorage = Fate.NACK_STORAGE.wire(),
        rejected = Fate.REJECTED.wire(),
        outOfMemory = AckResult.OUT_OF_MEMORY,
        ok = AckResult.OK,
        handled = AckResult.HANDLED,
    )

/** L2 log field name; not an IPC key. */
private const val LOG_FIELD_COUNTERS = "counters" // contract-ok: log field name, not an IPC Bundle key

// One injected DAO per table the daily run touches; a wrapper type per DAO would only move the list elsewhere.
@Suppress("LongParameterList")
@Singleton
class RoomHousekeeping
    @Inject
    constructor(
        private val messages: MessageDao,
        private val counters: CounterDao,
        private val hooks: HookStatusDao,
        private val selfTests: SelfTestRunDao,
        private val auditDao: dev.nightjar.app.data.db.AuditDao,
        private val detections: DetectionDao,
        private val mutes: MuteDao,
        private val settings: SettingsStore,
        private val log: ALog,
        @ApplicationContext private val context: Context,
    ) : Housekeeping {
        override suspend fun run(nowWallMs: Long): RetentionReport {
            // A failed title repair must not stop the retention run; cancellation still stops it.
            try {
                messages.repairPlaceholderTitles()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "placeholder title repair failed", e)
            }
            try {
                repairUnknownFates(messages)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "fate repair failed", e)
            }
            val retention = settings.current().retention
            val cutoff = retention.cutoff(nowWallMs)
            val deletedMessages = if (cutoff == null) 0 else messages.deletePurgeable(cutoff)
            // Counters are kept two years regardless of the message retention.
            val deletedCounters = counters.deleteBefore(dayOf(nowWallMs - COUNTER_KEEP_MS))
            val deletedFingerprints = hooks.keepOnlyNewestBuilds(KEEP_FINGERPRINTS)
            val deletedSelfTests = selfTests.keepNewest(KEEP_SELF_TEST_RUNS)
            val deletedAudit = auditDao.deleteBefore(nowWallMs - AUDIT_KEEP_MS)
            val deletedDetections =
                detections.deleteReviewedBefore(cutoff ?: (nowWallMs - DETECTION_KEEP_MS)) +
                    detections.deleteMemberless(nowWallMs - MEMBERLESS_GRACE_MS)
            val deletedMutes = mutes.deleteExpired(nowWallMs)
            val bytes = databaseSizeBytes(context, NightjarDatabase.NAME)
            val report =
                RetentionReport(
                    messagesDeleted = deletedMessages,
                    countersDeleted = deletedCounters,
                    hookFingerprintsDeleted = deletedFingerprints,
                    selfTestRunsDeleted = deletedSelfTests,
                    auditRowsDeleted = deletedAudit,
                    detectionsDeleted = deletedDetections,
                    mutesDeleted = deletedMutes,
                    databaseBytes = bytes,
                )
            log.i(
                TAG,
                "retention run",
                listOf(
                    "deleted" to deletedMessages,
                    LOG_FIELD_COUNTERS to deletedCounters,
                    "audit" to deletedAudit,
                    "dbBytes" to bytes,
                ),
            )
            return report
        }

        override suspend fun countForRetention(
            retention: Retention,
            nowWallMs: Long,
        ): Int {
            val cutoff = retention.cutoff(nowWallMs) ?: return 0
            return messages.countPurgeable(cutoff)
        }

        private fun dayOf(wallMs: Long): Int =
            java.time.Instant
                .ofEpochMilli(wallMs)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
                .let { it.year * 10_000 + it.monthValue * 100 + it.dayOfMonth }

        private companion object {
            const val TAG = "housekeeping"
            const val KEEP_FINGERPRINTS = 5
            const val KEEP_SELF_TEST_RUNS = 20
            const val COUNTER_KEEP_MS = 2L * 365 * 24 * 60 * 60 * 1000
            const val AUDIT_KEEP_MS = 365L * 24 * 60 * 60 * 1000
            const val DETECTION_KEEP_MS = 365L * 24 * 60 * 60 * 1000

            /** A memberless detection younger than this may still be in the middle of a write. */
            const val MEMBERLESS_GRACE_MS = 24L * 60 * 60 * 1000
        }
    }

/** Wall-clock cutoff for a retention setting; null means "forever" (nothing is purged). */
fun Retention.cutoff(nowWallMs: Long): Long? = days?.let { nowWallMs - it * 24L * 60 * 60 * 1000 }

/**
 * "Delete all data".
 *
 * Deletes every captured table, the staged events and the payload log copy, but keeps rules,
 * class actions, known originators, trusted packages and settings; then deletes the L2 files and writes one audit row
 * recording the deletion.
 */
interface DataWiper {
    suspend fun deleteAll(): Unit
}

@Singleton
class RoomDataWiper
    @Inject
    constructor(
        private val db: NightjarDatabase,
        private val audit: AuditLog,
        private val log: ALog,
        private val clock: Clock,
        private val staging: StagingSink,
        @ApplicationContext private val context: Context,
    ) : DataWiper {
        override suspend fun deleteAll() {
            db.withTransaction {
                db.messageDao().deleteAll()
                db.pduDao().deleteAll()
                db.traceStepDao().deleteAll()
                db.detectionDao().deleteAll()
                db.counterDao().deleteAll()
                db.coverageDao().deleteAll()
                db.hookStatusDao().deleteAll()
                db.selfTestRunDao().deleteAll()
                db.muteDao().deleteAll()
                db.savedViewDao().deleteAll()
                db.packageLabelDao().deleteAll()
                // `audit` is not wiped: the older rows stay until their own retention window passes, and the
                // DELETE_ALL row written below records when the data was deleted.
            }
            // The captured data outside the main database goes too.
            try {
                staging.clear()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(TAG, "staging not cleared", e)
            }
            PayloadLogStore.of(context).clear()
            log.deleteAll()
            log.i(TAG, "delete all", listOf("at" to clock.now()))
            audit.record(AuditAction.DELETE_ALL, AuditActor.USER, target = "all")
        }

        private companion object {
            const val TAG = "data"
        }
    }
