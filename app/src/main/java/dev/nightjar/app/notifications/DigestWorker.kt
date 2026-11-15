package dev.nightjar.app.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.data.repository.MessageRepository
import dev.nightjar.app.domain.notify.DigestCounts
import dev.nightjar.app.domain.notify.DigestSchedule
import dev.nightjar.app.domain.notify.NotificationIds
import dev.nightjar.app.feature.classify.ClassifierFactory
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The weekly digest.
 *
 * It replaces the old firewall summary, which every held or blocked message posted again, so the summary was always
 * in the shade. The digest posts at most once a week, from this job only, with the Home week counts (read from
 * the database, so a process restart loses nothing). It never alerts and Android removes it after one day.
 * [DigestSchedule] decides; this worker only reads the inputs and posts.
 */
@HiltWorker
class DigestWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val settings: SettingsStore,
        private val messages: MessageRepository,
        private val factory: ClassifierFactory,
        private val notifier: Notifier,
        private val clock: Clock,
    ) : CoroutineWorker(context, params) {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override suspend fun doWork(): Result {
            // A failure loses one digest. A retry could post it late, next to the next one, so the job never retries.
            runCatching { postIfDue() }
            return Result.success()
        }

        private suspend fun postIfDue() {
            settings.loaded.first { it }
            val now = clock.now()
            val week = messages.weekStats(now, ZoneId.systemDefault()).first().totals
            val counts = DigestCounts(week.hidden, week.suspicious, week.onHold, week.blocked)
            val last = prefs.getLong(KEY_LAST_POSTED, 0L).takeIf { it > 0L }
            if (!DigestSchedule.shouldPost(settings.settings.value.notifyDigest, counts, last, now)) return
            val plan = factory.router().weeklyDigest(counts) ?: return
            if (notifier.post(plan)) prefs.edit().putLong(KEY_LAST_POSTED, now).apply()
        }

        companion object {
            const val NAME = "nightjar-digest"
            private const val PREFS = "nightjar_digest"
            private const val KEY_LAST_POSTED = "lastPostedAt"
        }
    }

/** Schedules the weekly digest job (U7). */
@Singleton
class DigestScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val notifier: Notifier,
    ) {
        /**
         * Idempotent: KEEP, so a restart never moves or duplicates the job. The first run is one week after the first
         * schedule, so a new install does not post a digest at once. The job runs also when the digest is OFF and then
         * posts nothing, so a change of the setting needs no new schedule.
         */
        fun ensureScheduled() {
            // An older version posted the firewall summary on every held message; remove a copy that is still shown.
            notifier.cancel(NotificationIds.legacyFirewallSummary)
            val request =
                PeriodicWorkRequestBuilder<DigestWorker>(DigestSchedule.PERIOD_DAYS, TimeUnit.DAYS)
                    .setInitialDelay(DigestSchedule.PERIOD_DAYS, TimeUnit.DAYS)
                    .build()
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(DigestWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
