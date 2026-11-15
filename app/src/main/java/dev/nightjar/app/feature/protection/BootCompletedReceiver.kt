package dev.nightjar.app.feature.protection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.nightjar.app.domain.notify.NotificationIds
import dev.nightjar.app.domain.protection.ProtectionStateEngine
import dev.nightjar.app.notifications.NotificationChannels
import dev.nightjar.app.work.IngestSweep
import dev.nightjar.app.work.StagingDrain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * `BOOT_COMPLETED` schedules the 120 s check that decides NOT_ACTIVE;
 * `ACTION_USER_UNLOCKED` starts the work that needs credential-protected storage.
 *
 * The receiver does no work itself — it only enqueues workers — so the process can be killed immediately afterwards.
 * It injects nothing, so it is not a Hilt entry point.
 * It must not touch WorkManager before the first unlock, so `LOCKED_BOOT_COMPLETED` is ignored.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            // WorkManager needs credential-protected storage and throws IllegalStateException during Direct Boot, which
            // crashed the app at every boot. BOOT_COMPLETED arrives after the first unlock, so LOCKED_BOOT_COMPLETED is
            // deliberately not handled here.
            Intent.ACTION_BOOT_COMPLETED -> {
                BootCheck.schedule(context)
            }

            // The events staged during Direct Boot are drained only once the CE database can be opened, and the
            // rows a crash left unprocessed are finished in the same window.
            Intent.ACTION_USER_UNLOCKED -> {
                StagingDrain.schedule(context)
                IngestSweep.schedule(context)
            }

            else -> {
                Unit
            }
        }
    }
}

/** Enqueues the single boot-check work request. */
object BootCheck {
    private const val WORK_NAME = "nightjar-boot-check"

    fun schedule(context: Context) {
        val request =
            OneTimeWorkRequestBuilder<BootCheckWorker>()
                .setInitialDelay(ProtectionStateEngine.BOOT_CHECK_DELAY_MS, TimeUnit.MILLISECONDS)
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

/**
 * The 120 s post-boot check.
 *
 * If the payload has not handshaked in this boot, the state is NOT_ACTIVE and one status notification is posted. The
 * check also re-creates the notification channels, which is cheap and covers the case where the app data was restored
 * without the channels.
 *
 * `@HiltWorker`/`@AssistedInject` is what lets [androidx.hilt.work.HiltWorkerFactory] build it: without them the
 * reflective default factory looks for a `(Context, WorkerParameters)` constructor, finds none and fails the work with
 * "Could not instantiate", so NOT_ACTIVE was never posted and the channels were never recreated.
 */
@HiltWorker
class BootCheckWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val monitor: ProtectionMonitor,
        private val channels: NotificationChannels,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            withContext(Dispatchers.IO) {
                try {
                    channels.ensureCreated()
                    val report = monitor.refresh()
                    if (report.state == dev.nightjar.contract.ProtectionState.NOT_ACTIVE) {
                        monitor.markPosted(NotificationIds.StatusKind.NOT_ACTIVE)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failed check must never crash the app or retry forever: the next app start recomputes the state.
                }
                Result.success()
            }
    }
