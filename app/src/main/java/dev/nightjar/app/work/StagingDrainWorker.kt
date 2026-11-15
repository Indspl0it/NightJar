package dev.nightjar.app.work

import android.content.Context
import android.os.UserManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.nightjar.app.core.ipc.EventIngest
import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.data.staging.StagingSink

/**
 * Replays the events staged during Direct Boot.
 *
 * The payload writes to the device-protected staging database until the user unlocks. This worker is the unlock side:
 * it hands each row to the shared ingest commit path and the staging store deletes only the rows whose commit returned
 * true, so a crash mid-drain replays them and the idempotent upsert makes that a no-op.
 *
 * It runs as work rather than inline on `ACTION_USER_UNLOCKED` because the receiver has a ~10 s budget and opening the
 * credential-protected database (SQLCipher key derivation) can exceed it.
 */
@HiltWorker
class StagingDrainWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val staging: StagingSink,
        private val ingest: EventIngest,
        private val log: ALog,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            // Credential-protected storage is only readable after the unlock; before it the rows simply stay put.
            if (!StagingDrain.isUnlocked(applicationContext)) return Result.success()
            val drained =
                try {
                    staging.drain { row -> ingest.ingestStaged(row) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w(TAG, "staging drain failed", e)
                    0
                }
            return Result.success(workDataOf(StagingDrain.KEY_DRAINED to drained))
        }

        private companion object {
            const val TAG = "staging"
        }
    }

/** Enqueues the staged-event drain; the single place that names the work. */
object StagingDrain {
    const val NAME = "nightjar-staging-drain"

    /** Number of rows the last run committed. */
    const val KEY_DRAINED = "drained"

    /**
     * True once the user's credential-protected storage is available. Every caller checks this rather than trusting
     * the payload's handshake flag, which is frozen at phone-process start.
     */
    fun isUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked == true

    /** Idempotent: KEEP, so a second unlock broadcast or app start does not queue a duplicate drain. */
    fun schedule(context: Context) {
        if (!isUnlocked(context)) return
        val request = OneTimeWorkRequestBuilder<StagingDrainWorker>().build()
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
    }
}
