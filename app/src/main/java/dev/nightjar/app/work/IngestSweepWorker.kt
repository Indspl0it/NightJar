package dev.nightjar.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.nightjar.app.domain.ingest.IngestProcessor

/**
 * Finishes post-processing rows a crash interrupted.
 *
 * Ingest clears `needsProcessing` in the same call that stored the row, so this only has work when the process died
 * between the commit and the pipeline, or when a row failed to classify. [IngestProcessor.resumePending] loops
 * `pendingProcessing(200)` until the queue is empty, which is what makes the flag a durable recovery queue rather than
 * a leak.
 *
 * Work, not `Application.onCreate`: the sweep reads the encrypted database and StrictMode forbids that on the main
 * thread during a cold start.
 */
@HiltWorker
class IngestSweepWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val processor: IngestProcessor,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            runCatching { processor.resumePending() }
                .map { processed -> Result.success(workDataOf(IngestSweep.KEY_PROCESSED to processed)) }
                // Retrying cannot make a row classify; the next start tries again, and the flag keeps it visible.
                .getOrElse { Result.success() }
    }

/** Enqueues the pending-post-processing sweep. */
object IngestSweep {
    const val NAME = "nightjar-ingest-sweep"

    /** Number of rows the last run finished. */
    const val KEY_PROCESSED = "processed"

    /** Idempotent: KEEP, so repeated app starts do not stack sweeps. */
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<IngestSweepWorker>().build()
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
    }
}
