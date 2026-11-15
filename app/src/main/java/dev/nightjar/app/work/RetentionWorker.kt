package dev.nightjar.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.data.maintenance.Housekeeping
import dev.nightjar.app.data.repository.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Daily retention run.
 *
 * Runs once a day while the device is idle. The worker is idempotent: a second run on the same day deletes nothing.
 * The result carries [KEY_OVER_SIZE] so Health can show the storage size notice without querying the file system
 * itself.
 */
@HiltWorker
class RetentionWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val housekeeping: Housekeeping,
        private val clock: Clock,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            runCatching { housekeeping.run(clock.now()) }
                .map { report ->
                    Result.success(
                        workDataOf(
                            KEY_OVER_SIZE to report.overSizeLimit,
                            KEY_DELETED to report.messagesDeleted,
                            KEY_DB_BYTES to report.databaseBytes,
                        ),
                    )
                }.getOrElse { Result.retry() }

        companion object {
            const val NAME = "nightjar-retention"
            const val KEY_OVER_SIZE = "overSizeLimit"
            const val KEY_DELETED = "messagesDeleted"
            const val KEY_DB_BYTES = "databaseBytes"
        }
    }

/** Schedules the daily retention run and exposes an immediate run for the retention dialog. */
@Singleton
class RetentionScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** Idempotent: KEEP, so a restart never duplicates the job. */
        fun ensureScheduled() {
            val request =
                PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiresDeviceIdle(true).build())
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(RetentionWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Runs retention now, e.g. after the user shortens it. */
        fun runNow() {
            val request = OneTimeWorkRequestBuilder<RetentionWorker>().build()
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork("${RetentionWorker.NAME}-now", ExistingWorkPolicy.REPLACE, request)
        }
    }
