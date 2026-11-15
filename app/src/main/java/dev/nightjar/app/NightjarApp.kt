package dev.nightjar.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import dev.nightjar.app.core.ipc.IpcSyncCoordinator
import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.core.log.LogFileSink
import dev.nightjar.app.data.db.SqlCipherLib
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.work.IngestSweep
import dev.nightjar.app.work.RetentionScheduler
import dev.nightjar.app.work.StagingDrain
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * The application object.
 *
 * `onCreate` does no disk work on the main thread: the database, the DataStore and the settings snapshot are all lazy
 * and are touched on a background dispatcher or on first use. StrictMode is installed in debug builds so a regression
 * fails loudly on cold start.
 *
 * The process can start before the first unlock (the directBootAware provider and boot receiver). Everything that
 * needs credential-protected storage (WorkManager's database, the settings file, the encrypted database) starts only
 * when the user is unlocked: at once when the process starts after the unlock, otherwise from `ACTION_USER_UNLOCKED`.
 */
@HiltAndroidApp
class NightjarApp :
    Application(),
    Configuration.Provider {
    @Inject lateinit var log: ALog

    @Inject lateinit var sink: LogFileSink

    @Inject lateinit var clock: Clock

    @Inject lateinit var retention: RetentionScheduler

    /** U7: the weekly digest job. Lazy: it builds the notifier, which onCreate does not need. */
    @Inject lateinit var digest: dagger.Lazy<dev.nightjar.app.notifications.DigestScheduler>

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var phoneWatchdog: dagger.Lazy<dev.nightjar.app.feature.protection.PhoneWatchdog>

    @Inject lateinit var syncCoordinator: dagger.Lazy<IpcSyncCoordinator>

    /** Re-rates stored messages when a classifier setting, a known sender or a trusted app changes (rv-domain). */
    @Inject lateinit var reclassifyWatcher: dagger.Lazy<dev.nightjar.app.feature.classify.SeverityReclassifyWatcher>

    private val unlockedWorkStarted = AtomicBoolean(false)

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                // WorkManager opens its database in credential-protected storage; a failure there must not stop the
                // process (the provider must keep answering the payload).
                .setInitializationExceptionHandler { error -> log.w(TAG, "work manager could not start", error) }
                .build()

    override fun onCreate() {
        super.onCreate()
        // Before anything opens a database: sqlcipher-android does not load its own.so.
        SqlCipherLib.ensureLoaded()
        SqlCipherLib.loadError?.let { log.w(TAG, "sqlcipher library failed to load", it) }
        if (BuildConfig.DEBUG) installStrictMode()
        // Logging only starts a worker thread and queues one line; nothing is read from disk here.
        log.i(TAG, "app start", listOf("version" to BuildConfig.VERSION_NAME, "debug" to BuildConfig.DEBUG))
        if (StagingDrain.isUnlocked(this)) {
            startUnlockedWork()
        } else {
            registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context,
                        intent: Intent,
                    ) {
                        runCatching { unregisterReceiver(this) }
                        startUnlockedWork()
                    }
                },
                IntentFilter(Intent.ACTION_USER_UNLOCKED),
                Context.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /**
     * When the process starts after the unlock, the USER_UNLOCKED broadcast is already behind us, so the
     * staged events are drained and the interrupted pipeline runs are finished from here. Both are work requests: they
     * read the encrypted database, which StrictMode forbids on this thread. The sync coordinator pushes rule and
     * settings edits even when the payload stays quiet (it is idempotent).
     */
    private fun startUnlockedWork() {
        if (!unlockedWorkStarted.compareAndSet(false, true)) return
        runCatching {
            retention.ensureScheduled()
            digest.get().ensureScheduled()
            StagingDrain.schedule(this)
            IngestSweep.schedule(this)
        }.onFailure { log.w(TAG, "unlock work could not be scheduled", it) }
        runCatching { syncCoordinator.get().start() }.onFailure { log.w(TAG, "sync could not start", it) }
        runCatching { phoneWatchdog.get().start() }.onFailure { log.w(TAG, "watchdog could not start", it) }
        runCatching { reclassifyWatcher.get().start() }.onFailure { log.w(TAG, "reclassifier could not start", it) }
    }

    /** Thread and VM policies that turn a main-thread disk read into a loud log line. */
    private fun installStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode
                .ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode
                .VmPolicy
                .Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build(),
        )
    }

    private companion object {
        const val TAG = "app"
    }
}
