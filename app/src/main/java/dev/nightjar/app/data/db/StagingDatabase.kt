package dev.nightjar.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/**
 * One event captured before the first unlock. Only the raw payload is kept — no decoded text,
 * no derived fields — so the staging database never holds more than the phone service already had.
 */
@Entity(tableName = "staged_event")
data class StagedEventEntity(
    @PrimaryKey val eventId: String,
    /** The event JSON exactly as the payload sent it (PDUs included). */
    val json: String,
    val receivedAt: Long,
)

@Dao
interface StagedEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: StagedEventEntity)

    @Query("SELECT json FROM staged_event WHERE eventId = :eventId")
    suspend fun jsonOf(eventId: String): String?

    @Query("SELECT * FROM staged_event ORDER BY receivedAt ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<StagedEventEntity>

    @Query("SELECT COUNT(*) FROM staged_event")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM staged_event")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM staged_event WHERE eventId IN (:ids)")
    suspend fun delete(ids: List<String>): Int

    @Query("DELETE FROM staged_event")
    suspend fun deleteAll(): Int

    /** Drops the oldest rows so the table never exceeds [keep] (cap of 500 events). */
    @Query("DELETE FROM staged_event WHERE eventId NOT IN (SELECT eventId FROM staged_event ORDER BY receivedAt DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int): Int
}

/**
 * Device-protected staging database.
 *
 * Lives in device-protected storage so it is readable before the user unlocks, and is encrypted with its own random key
 * (`keys/stage.key`, also in device-protected storage). A separate key means the main database key can stay in
 * credential-protected storage and the staging database never needs it.
 */
@Database(entities = [StagedEventEntity::class], version = StagingDatabase.VERSION, exportSchema = true)
abstract class StagingDatabase : RoomDatabase() {
    abstract fun stagedEventDao(): StagedEventDao

    companion object {
        const val NAME = "nightjar-stage.db"
        const val VERSION = 1

        /** At most 500 events are held before the first unlock. */
        const val MAX_EVENTS = 500
    }
}

/** Opens the staging database. [openHelperFactory] is SQLCipher in production, null in tests. */
object StagingDatabaseFactory {
    fun open(
        context: Context,
        openHelperFactory: androidx.sqlite.db.SupportSQLiteOpenHelper.Factory?,
    ): StagingDatabase {
        val builder = Room.databaseBuilder(context, StagingDatabase::class.java, StagingDatabase.NAME)
        if (openHelperFactory != null) builder.openHelperFactory(openHelperFactory)
        return builder.build()
    }
}

/**
 * Stores events while the user is locked.
 *
 * Every insert trims the table back to [StagingDatabase.MAX_EVENTS], so a long locked period cannot fill the device's
 * data partition. [drain] hands the events to the caller in arrival order and deletes them only after the caller has
 * ingested them: a crash mid-drain replays the same events, and the ingest upsert makes that a no-op
 * .
 *
 * A staged row is replaced only by the same or a higher `revision`. A late retry of an older revision must
 * not overwrite the newer content (the main store applies the same rule).
 */
class StagingStore(
    private val dao: StagedEventDao,
) {
    /** One writer at a time: the revision check and the insert must not interleave. */
    private val mutex = Mutex()

    suspend fun stage(
        eventId: String,
        json: String,
        receivedAt: Long,
    ) {
        mutex.withLock {
            val staged = dao.jsonOf(eventId)
            if (staged != null && revisionOf(staged) > revisionOf(json)) return
            dao.insert(StagedEventEntity(eventId, json, receivedAt))
            dao.trimTo(StagingDatabase.MAX_EVENTS)
        }
    }

    /** The C.3 `revision` of an event JSON; 1 when it is missing or the JSON is unreadable. */
    private fun revisionOf(json: String): Int = runCatching { JSONObject(json).optInt(REVISION, 1) }.getOrDefault(1)

    suspend fun count(): Int = dao.count()

    fun observeCount(): Flow<Int> = dao.observeCount()

    /**
     * Returns up to [limit] staged events, oldest first. The caller ingests them and then calls [remove].
     */
    suspend fun peek(limit: Int = 100): List<StagedEventEntity> = dao.oldest(limit)

    suspend fun remove(eventIds: List<String>): Int = if (eventIds.isEmpty()) 0 else dao.delete(eventIds)

    suspend fun clear(): Int = dao.deleteAll()

    private companion object {
        const val REVISION = "revision"
    }
}

/** Device-protected storage roots: key and database both live here, before unlock. */
object DeviceProtectedFiles {
    /** `context.createDeviceProtectedStorageContext()`; throws on a device without DE storage, which Android 15+ always has. */
    fun context(context: Context): Context = context.createDeviceProtectedStorageContext()

    fun keyDirectory(context: Context): File = File(context(context).filesDir, "keys")

    fun databaseName(): String = StagingDatabase.NAME
}
