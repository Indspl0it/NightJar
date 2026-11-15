package dev.nightjar.app.data.db

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

/**
 * The encrypted main database. Every entity is defined in `Entities.kt`.
 *
 * Version 1 is the initial schema, version 2 adds the delivery-report columns, version 3 adds the subtype and
 * anomaly columns; `app/schemas/…/N.json` is checked in. Destructive migration is forbidden:
 * [DatabaseFactory] adds [NightjarMigrations.ALL] and never calls `fallbackToDestructiveMigration`.
 */
@Database(
    entities = [
        MessageEntity::class,
        PduEntity::class,
        TraceStepEntity::class,
        DetectionEntity::class,
        DetectionMemberEntity::class,
        RuleEntity::class,
        ClassActionEntity::class,
        KnownOriginatorEntity::class,
        TrustedPackageEntity::class,
        CounterEntity::class,
        CoverageEntity::class,
        HookStatusEntity::class,
        SelfTestRunEntity::class,
        AuditEntity::class,
        MuteEntity::class,
        SavedViewEntity::class,
        PackageLabelEntity::class,
        RuleMetaEntity::class,
    ],
    version = NightjarDatabase.VERSION,
    exportSchema = true,
)
abstract class NightjarDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    abstract fun pduDao(): PduDao

    abstract fun traceStepDao(): TraceStepDao

    abstract fun detectionDao(): DetectionDao

    abstract fun ruleDao(): RuleDao

    abstract fun classActionDao(): ClassActionDao

    abstract fun knownOriginatorDao(): KnownOriginatorDao

    abstract fun trustedPackageDao(): TrustedPackageDao

    abstract fun counterDao(): CounterDao

    abstract fun coverageDao(): CoverageDao

    abstract fun hookStatusDao(): HookStatusDao

    abstract fun selfTestRunDao(): SelfTestRunDao

    abstract fun auditDao(): AuditDao

    abstract fun muteDao(): MuteDao

    abstract fun savedViewDao(): SavedViewDao

    abstract fun packageLabelDao(): PackageLabelDao

    abstract fun ruleMetaDao(): RuleMetaDao

    companion object {
        const val NAME = "nightjar.db"
        const val VERSION = 3
    }
}

/** Every migration, in order. A schema change adds one entry plus a test. */
object NightjarMigrations {
    /** Four nullable columns on `message` for delivery reports. Existing rows keep null. */
    val MIGRATION_1_2: Migration =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `message` ADD COLUMN `relatedMessageId` INTEGER")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `tpMr` INTEGER")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `deliveryStatus` TEXT")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `destinationNorm` TEXT")
            }
        }

    /**
     * Three nullable TEXT columns on `message` for the IPC version 2 event fields (subtype, other rule matches,
     * anomaly codes). Existing rows keep null, which reads as "no subtype" and empty lists.
     *
     * Nine nullable columns for event fields that the app did not
     * keep (raw destination, roaming, unlock state, CB message id and serial, `releaseOf`, the clamp of the verdict,
     * the SIM OTA header). Existing rows keep null, which reads as "not reported".
     */
    val MIGRATION_2_3: Migration =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `message` ADD COLUMN `subtype` TEXT")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `extraSubtypes` TEXT")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `anomalies` TEXT")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `destination` TEXT")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `roaming` INTEGER")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `userUnlocked` INTEGER")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `cbMessageId` INTEGER")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `cbSerial` INTEGER")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `releaseOf` TEXT")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `clampedFrom` TEXT")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `clampReason` TEXT")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `simOtaHeader` TEXT")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `emergencyCallActive` INTEGER")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `processedRevision` INTEGER")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `debugInjected` INTEGER")
            }
        }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}

/**
 * Opens the main database.
 *
 * [openHelperFactory] is the SQLCipher factory in production and `null` in tests, where the platform SQLite is used so
 * DAO and migration tests run on the JVM. Production never passes null: `di/AppModule` always supplies the factory.
 */
object DatabaseFactory {
    // Room's addMigrations only accepts varargs; ALL is a fixed array and this runs once per database open.
    @Suppress("SpreadOperator")
    fun open(
        context: Context,
        name: String,
        openHelperFactory: SupportSQLiteOpenHelper.Factory?,
    ): NightjarDatabase {
        val builder =
            Room
                .databaseBuilder(context, NightjarDatabase::class.java, name)
                .addMigrations(*NightjarMigrations.ALL)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        if (openHelperFactory != null) builder.openHelperFactory(openHelperFactory)
        return builder.build()
    }
}

/**
 * The database key: 32 random bytes from [SecureRandom], stored unwrapped in app-private storage with
 * mode 0600 so a root backup restores a database that still opens on a new ROM. The key is never logged and never
 * exported.
 *
 * The write is temp file → fsync → rename, so a crash cannot leave a half-written key next to a database encrypted with
 * the old one.
 */
class DbKeyStore(
    private val directory: File,
    private val random: SecureRandom = SecureRandom(),
) {
    /**
     * Returns the existing key or creates one. [name] is a bare file name, e.g. `db.key`.
     *
     * A key file that exists but has the wrong length is a hard error: minting a new key would silently orphan every
     * row encrypted with the old one. A key is only ever generated when the directory can actually take it, so a
     * Direct Boot call in credential-protected storage fails with an exception instead of "succeeding" into nothing.
     */
    fun loadOrCreate(name: String): ByteArray {
        val target = File(directory, name)
        if (target.exists()) {
            if (target.length() != KEY_BYTES.toLong()) {
                error("database key $name is ${target.length()} bytes, expected $KEY_BYTES")
            }
            return target.readBytes()
        }
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            error("cannot create key directory ${directory.path}")
        }
        if (!directory.canWrite()) {
            // Direct Boot before the first unlock: generating a key into the CE directory would fail on write anyway.
            error("key directory ${directory.path} is not writable")
        }
        val key = ByteArray(KEY_BYTES).also { random.nextBytes(it) }
        val temp = File(directory, "$name.tmp")
        FileOutputStream(temp).use { out ->
            out.write(key)
            out.flush()
            out.fd.sync()
        }
        Os.chmod(temp.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        if (!temp.renameTo(target)) {
            // A rename can fail if the target appeared concurrently; keep the existing key and drop ours.
            temp.delete()
            if (target.isFile && target.length() == KEY_BYTES.toLong()) {
                key.fill(0)
                return target.readBytes()
            }
            error("cannot install database key at $name")
        }
        Os.chmod(target.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IWUSR)
        return key
    }

    companion object {
        /** AES-256. */
        const val KEY_BYTES = 32

        /** Credential-protected key (main database). */
        const val DB_KEY = "db.key"

        /** Device-protected key (Direct Boot staging). */
        const val STAGE_KEY = "stage.key"
    }
}

/** SQLCipher factory for a key. The key copy is zeroed after the helper is built. */
fun sqlCipherFactory(key: ByteArray): SupportOpenHelperFactory {
    // A caller that arrives before Application.onCreate (the Direct Boot provider) still gets a loaded library.
    SqlCipherLib.ensureLoaded()
    return SupportOpenHelperFactory(key.copyOf(), null, true)
}

/** Size of the database files on disk, including WAL and SHM. */
fun databaseSizeBytes(context: Context, name: String): Long {
    val base = context.getDatabasePath(name)
    return listOf(base, File("${base.path}-wal"), File("${base.path}-shm"))
        .filter { it.isFile }
        .sumOf { it.length() }
}
