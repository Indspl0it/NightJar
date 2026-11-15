package dev.nightjar.app.di

import android.content.Context
import android.os.UserManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.core.log.FileALog
import dev.nightjar.app.core.log.LogFileSink
import dev.nightjar.app.core.log.LogLevel
import dev.nightjar.app.core.log.LogcatSink
import dev.nightjar.app.core.root.LibsuRootOps
import dev.nightjar.app.core.root.RootOps
import dev.nightjar.app.core.root.ShellPort
import dev.nightjar.app.core.settings.DataStoreSettingsStore
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.core.settings.settingsDataStore
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.audit.RoomAuditLog
import dev.nightjar.app.data.db.AuditDao
import dev.nightjar.app.data.db.ClassActionDao
import dev.nightjar.app.data.db.CounterDao
import dev.nightjar.app.data.db.CoverageDao
import dev.nightjar.app.data.db.DatabaseFactory
import dev.nightjar.app.data.db.DbKeyStore
import dev.nightjar.app.data.db.DetectionDao
import dev.nightjar.app.data.db.HookStatusDao
import dev.nightjar.app.data.db.KnownOriginatorDao
import dev.nightjar.app.data.db.MessageDao
import dev.nightjar.app.data.db.MuteDao
import dev.nightjar.app.data.db.NightjarDatabase
import dev.nightjar.app.data.db.PackageLabelDao
import dev.nightjar.app.data.db.PduDao
import dev.nightjar.app.data.db.RuleDao
import dev.nightjar.app.data.db.RuleMetaDao
import dev.nightjar.app.data.db.SavedViewDao
import dev.nightjar.app.data.db.SelfTestRunDao
import dev.nightjar.app.data.db.TraceStepDao
import dev.nightjar.app.data.db.TrustedPackageDao
import dev.nightjar.app.data.db.sqlCipherFactory
import dev.nightjar.app.data.maintenance.DataWiper
import dev.nightjar.app.data.maintenance.Housekeeping
import dev.nightjar.app.data.maintenance.RoomDataWiper
import dev.nightjar.app.data.maintenance.RoomHousekeeping
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.data.repository.CounterRepository
import dev.nightjar.app.data.repository.CoverageRepository
import dev.nightjar.app.data.repository.DetectionRepository
import dev.nightjar.app.data.repository.HookStatusRepository
import dev.nightjar.app.data.repository.KnownOriginatorRepository
import dev.nightjar.app.data.repository.MessageRepository
import dev.nightjar.app.data.repository.MessageStore
import dev.nightjar.app.data.repository.MuteRepository
import dev.nightjar.app.data.repository.PackageLabelRepository
import dev.nightjar.app.data.repository.RoomCounterRepository
import dev.nightjar.app.data.repository.RoomCoverageRepository
import dev.nightjar.app.data.repository.RoomDetectionRepository
import dev.nightjar.app.data.repository.RoomHookStatusRepository
import dev.nightjar.app.data.repository.RoomKnownOriginatorRepository
import dev.nightjar.app.data.repository.RoomMessageRepository
import dev.nightjar.app.data.repository.RoomMessageStore
import dev.nightjar.app.data.repository.RoomMuteRepository
import dev.nightjar.app.data.repository.RoomPackageLabelRepository
import dev.nightjar.app.data.repository.RoomRulesRepository
import dev.nightjar.app.data.repository.RoomSavedViewRepository
import dev.nightjar.app.data.repository.RoomSelfTestRepository
import dev.nightjar.app.data.repository.RoomTrustedPackageRepository
import dev.nightjar.app.data.repository.RulesRepository
import dev.nightjar.app.data.repository.SavedViewRepository
import dev.nightjar.app.data.repository.SelfTestRepository
import dev.nightjar.app.data.repository.SystemClock
import dev.nightjar.app.data.repository.TrustedPackageRepository
import dev.nightjar.app.domain.ingest.AlertSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

/**
 * App-wide bindings: database, repositories, settings, logging and root.
 *
 * Nothing here touches the disk at injection time except the DataStore handle, which is lazy: `Application.onCreate`
 * must not read files (StrictMode clean on cold start). The database is opened on first use, behind [NightjarDatabase],
 * which is itself `@Singleton` and created lazily by Hilt.
 */
@Module
@InstallIn(SingletonComponent::class)
// One provider per injectable type; splitting the module would only rename the file that lists them.
@Suppress("TooManyFunctions")
object AppModule {
    /** Application-wide scope for fire-and-forget work that must outlive a screen. */
    @Provides
    @Singleton
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun clock(impl: SystemClock): Clock = impl

    /**
     * The Direct Boot provider asks whether the current user's credential-protected storage is
     * readable before it touches the main database. Application-scoped: the answer is a property of
     * the process, and creating a new manager per call would be pointless work on a hot path.
     */
    @Provides
    @Singleton
    fun userManager(
        @ApplicationContext context: Context,
    ): UserManager = context.getSystemService(UserManager::class.java)

    @Provides
    @Singleton
    fun dataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = context.settingsDataStore

    @Provides
    @Singleton
    fun settingsStore(impl: DataStoreSettingsStore): SettingsStore = impl

    @Provides
    @Singleton
    fun auditLog(impl: RoomAuditLog): AuditLog = impl

    @Provides
    @Singleton
    fun cbIngest(): dev.nightjar.app.data.cb.CbIngest =
        dev.nightjar.app.data.cb
            .CbIngest()

    // ---------------------------------------------------------------- logging

    @Provides
    @Singleton
    fun logSink(
        @ApplicationContext context: Context,
    ): LogFileSink =
        LogFileSink(File(context.noBackupFilesDir, "logs"))

    @Provides
    @Singleton
    fun logcat(): LogcatSink =
        object : LogcatSink {
            override fun log(
                level: LogLevel,
                tag: String,
                message: String,
            ) {
                when (level) {
                    LogLevel.E -> android.util.Log.e(tag, message)
                    LogLevel.W -> android.util.Log.w(tag, message)
                    LogLevel.I -> android.util.Log.i(tag, message)
                    LogLevel.D -> android.util.Log.d(tag, message)
                }
            }
        }

    @Provides
    @Singleton
    fun aLog(
        sink: LogFileSink,
        logcat: LogcatSink,
        settings: dagger.Lazy<SettingsStore>,
        clock: Clock,
        @ApplicationContext context: Context,
    ): ALog =
        FileALog(
            sink = sink,
            logcat = logcat,
            // The Direct Boot ingest path needs the logger before the first unlock. The settings file is in
            // credential-protected storage, so the store is built (and read) only once the user is unlocked.
            debugEnabled = {
                context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked == true &&
                    settings
                        .get()
                        .settings.value
                        .debugLoggingActive(clock.now())
            },
            release = !dev.nightjar.app.BuildConfig.DEBUG,
            clock = { clock.now() },
        )

    // ---------------------------------------------------------------- database

    @Provides
    @Singleton
    fun dbKeyStore(
        @ApplicationContext context: Context,
    ): DbKeyStore = DbKeyStore(File(context.filesDir, "keys"))

    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
        keys: DbKeyStore,
    ): NightjarDatabase {
        // 32 random bytes, 0600, in credential-protected storage. The key array is local; only the copy handed
        // to SQLCipher exists after this function returns.
        val key = keys.loadOrCreate(DbKeyStore.DB_KEY)
        return try {
            DatabaseFactory.open(context, NightjarDatabase.NAME, sqlCipherFactory(key))
        } finally {
            key.fill(0)
        }
    }

    @Provides fun messageDao(db: NightjarDatabase): MessageDao = db.messageDao()

    @Provides fun pduDao(db: NightjarDatabase): PduDao = db.pduDao()

    @Provides fun traceStepDao(db: NightjarDatabase): TraceStepDao = db.traceStepDao()

    @Provides fun detectionDao(db: NightjarDatabase): DetectionDao = db.detectionDao()

    @Provides fun ruleDao(db: NightjarDatabase): RuleDao = db.ruleDao()

    @Provides fun classActionDao(db: NightjarDatabase): ClassActionDao = db.classActionDao()

    @Provides fun knownOriginatorDao(db: NightjarDatabase): KnownOriginatorDao = db.knownOriginatorDao()

    @Provides fun trustedPackageDao(db: NightjarDatabase): TrustedPackageDao = db.trustedPackageDao()

    @Provides fun counterDao(db: NightjarDatabase): CounterDao = db.counterDao()

    @Provides fun coverageDao(db: NightjarDatabase): CoverageDao = db.coverageDao()

    @Provides fun hookStatusDao(db: NightjarDatabase): HookStatusDao = db.hookStatusDao()

    @Provides fun selfTestRunDao(db: NightjarDatabase): SelfTestRunDao = db.selfTestRunDao()

    @Provides fun auditDao(db: NightjarDatabase): AuditDao = db.auditDao()

    @Provides fun muteDao(db: NightjarDatabase): MuteDao = db.muteDao()

    @Provides fun savedViewDao(db: NightjarDatabase): SavedViewDao = db.savedViewDao()

    @Provides fun packageLabelDao(db: NightjarDatabase): PackageLabelDao = db.packageLabelDao()

    @Provides fun ruleMetaDao(db: NightjarDatabase): RuleMetaDao = db.ruleMetaDao()

    // ---------------------------------------------------------------- repositories

    @Provides
    @Singleton
    fun messageRepository(impl: RoomMessageRepository): MessageRepository = impl

    @Provides
    @Singleton
    fun messageStore(impl: RoomMessageStore): MessageStore = impl

    @Provides
    @Singleton
    fun detectionRepository(impl: RoomDetectionRepository): DetectionRepository = impl

    @Provides
    @Singleton
    fun rulesRepository(impl: RoomRulesRepository): RulesRepository = impl

    @Provides
    @Singleton
    fun knownOriginatorRepository(impl: RoomKnownOriginatorRepository): KnownOriginatorRepository = impl

    @Provides
    @Singleton
    fun trustedPackageRepository(impl: RoomTrustedPackageRepository): TrustedPackageRepository = impl

    @Provides
    @Singleton
    fun muteRepository(impl: RoomMuteRepository): MuteRepository = impl

    @Provides
    @Singleton
    fun coverageRepository(impl: RoomCoverageRepository): CoverageRepository = impl

    @Provides
    @Singleton
    fun counterRepository(impl: RoomCounterRepository): CounterRepository = impl

    @Provides
    @Singleton
    fun hookStatusRepository(impl: RoomHookStatusRepository): HookStatusRepository = impl

    @Provides
    @Singleton
    fun selfTestRepository(impl: RoomSelfTestRepository): SelfTestRepository = impl

    @Provides
    @Singleton
    fun savedViewRepository(impl: RoomSavedViewRepository): SavedViewRepository = impl

    @Provides
    @Singleton
    fun packageLabelRepository(impl: RoomPackageLabelRepository): PackageLabelRepository = impl

    @Provides
    @Singleton
    fun housekeeping(impl: RoomHousekeeping): Housekeeping = impl

    @Provides
    @Singleton
    fun dataWiper(impl: RoomDataWiper): DataWiper = impl

    /** The last pipeline step; bound here because only the production ingest wiring needs the interface. */
    @Provides
    @Singleton
    fun alertSink(impl: dev.nightjar.app.notifications.NotifierAlertSink): AlertSink = impl

    // ---------------------------------------------------------------- module files

    @Provides
    @Singleton
    fun moduleSettingsFactory(
        @ApplicationContext context: Context,
        settings: SettingsStore,
        outgoingBlocks: dev.nightjar.app.core.settings.OutgoingBlockStore,
    ): dev.nightjar.app.domain.protection.ModuleSettingsFactory =
        dev.nightjar.app.domain.protection
            .ModuleSettingsFactory(
                appPackage = { context.packageName },
                // The payload pins this value; a resolution failure is reported instead of sent empty.
                appCertSha256 = {
                    runCatching {
                        dev.nightjar.app.core.ipc.CertFingerprint
                            .ofPackage(context)
                    }.getOrNull().orEmpty()
                },
                // The packages the user blocked from sending SMS (`outgoing.blocked`), sorted for a stable document.
                outgoingBlocked = { outgoingBlocks.blocked().sorted() },
                current = { settings.settings.value },
            )

    // ---------------------------------------------------------------- root

    // APPDATA-X02: the libsu implementations are built here; nothing injects the concrete types.
    @Provides
    @Singleton
    fun shellPort(): ShellPort =
        dev.nightjar.app.core.root
            .LibsuShell()

    @Provides
    @Singleton
    fun rootOps(
        shell: ShellPort,
        log: ALog,
    ): RootOps = LibsuRootOps(shell, log)
}
