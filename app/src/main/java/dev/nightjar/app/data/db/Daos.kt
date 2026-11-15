package dev.nightjar.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** PDUs of a message. Rows cascade-delete with the message. */
@Dao
interface PduDao {
    @Query("SELECT * FROM pdu WHERE messageId = :messageId ORDER BY partNo ASC")
    suspend fun forMessage(messageId: Long): List<PduEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PduEntity>)

    @Query("DELETE FROM pdu WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: Long): Int

    @Query("DELETE FROM pdu")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM pdu")
    suspend fun count(): Int
}

/** Pipeline trace of a message. Rows cascade-delete with the message. */
@Dao
interface TraceStepDao {
    @Query("SELECT * FROM trace_step WHERE messageId = :messageId ORDER BY seq ASC")
    suspend fun forMessage(messageId: Long): List<TraceStepEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<TraceStepEntity>)

    @Query("DELETE FROM trace_step WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: Long): Int

    @Query("DELETE FROM trace_step")
    suspend fun deleteAll(): Int
}

/** Detections and their members (DET-*). */
@Dao
interface DetectionDao {
    @Query("SELECT * FROM detection WHERE id = :id")
    suspend fun find(id: Long): DetectionEntity?

    @Query("SELECT * FROM detection WHERE detectorId = :detectorId AND mergeKey = :mergeKey AND isOpen = 1 LIMIT 1")
    suspend fun findOpen(
        detectorId: String,
        mergeKey: String,
    ): DetectionEntity?

    @Query("SELECT * FROM detection ORDER BY lastAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DetectionEntity>>

    @Query("SELECT * FROM detection WHERE isOpen = 1 ORDER BY lastAt DESC")
    fun observeOpen(): Flow<List<DetectionEntity>>

    @Query(
        "SELECT d.* FROM detection AS d INNER JOIN detection_member AS m ON m.detectionId = d.id " +
            "WHERE m.messageId = :messageId ORDER BY d.lastAt DESC",
    )
    suspend fun forMessage(messageId: Long): List<DetectionEntity>

    @Query(
        "SELECT d.* FROM detection AS d INNER JOIN detection_member AS m ON m.detectionId = d.id " +
            "WHERE m.messageId = :messageId ORDER BY d.lastAt DESC",
    )
    fun observeForMessage(messageId: Long): Flow<List<DetectionEntity>>

    @Query("SELECT messageId FROM detection_member WHERE detectionId = :detectionId AND messageId <> :excludeMessageId")
    suspend fun memberIdsExcept(
        detectionId: Long,
        excludeMessageId: Long,
    ): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembers(rows: List<DetectionMemberEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: DetectionEntity): Long

    @Update suspend fun update(entity: DetectionEntity)

    @Query("UPDATE detection SET isReviewed = :reviewed WHERE id = :id")
    suspend fun setReviewed(
        id: Long,
        reviewed: Boolean,
    ): Int

    @Query("UPDATE detection SET isOpen = 0 WHERE id = :id")
    suspend fun close(id: Long): Int

    @Query("DELETE FROM detection WHERE lastAt < :cutoff AND isReviewed = 1")
    suspend fun deleteReviewedBefore(cutoff: Long): Int

    /**
     * A detection whose messages were all deleted (retention or "delete") has nothing left to show, also
     * when it was never reviewed. [before] keeps a detection that is being written right now.
     */
    @Query(
        "DELETE FROM detection WHERE lastAt < :before AND NOT EXISTS " +
            "(SELECT 1 FROM detection_member AS m WHERE m.detectionId = detection.id)",
    )
    suspend fun deleteMemberless(before: Long): Int

    @Query("DELETE FROM detection")
    suspend fun deleteAll(): Int
}

/** Rules. Permanent: never deleted by retention or "delete all". */
@Dao
interface RuleDao {
    @Query("SELECT * FROM rule ORDER BY priority ASC, id ASC")
    fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rule ORDER BY priority ASC, id ASC")
    suspend fun all(): List<RuleEntity>

    @Query("SELECT * FROM rule WHERE id = :id")
    suspend fun find(id: Long): RuleEntity?

    @Query("SELECT MAX(id) FROM rule")
    suspend fun maxId(): Long?

    /** Highest user-rule id, i.e. below the reserved preset range. */
    @Query("SELECT MAX(id) FROM rule WHERE id < :below")
    suspend fun maxIdBelow(below: Long): Long?

    @Query("SELECT COUNT(*) FROM rule WHERE origin = :origin")
    suspend fun countByOrigin(origin: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<RuleEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: RuleEntity)

    @Update suspend fun update(entity: RuleEntity)

    @Query("DELETE FROM rule WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM rule WHERE origin = :origin")
    suspend fun deleteByOrigin(origin: String): Int

    @Query("UPDATE rule SET priority = :priority WHERE id = :id")
    suspend fun setPriority(
        id: Long,
        priority: Int,
    ): Int

    @Query("UPDATE rule SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(
        id: Long,
        enabled: Boolean,
    ): Int

    @Query("UPDATE rule SET matchCount = matchCount + 1, lastMatchAt = :at WHERE id = :id")
    suspend fun incrementMatch(
        id: Long,
        at: Long,
    ): Int

    @Query("DELETE FROM rule")
    suspend fun deleteAll(): Int
}

/** Per-class actions. Permanent. */
@Dao
interface ClassActionDao {
    @Query("SELECT * FROM class_action")
    fun observeAll(): Flow<List<ClassActionEntity>>

    @Query("SELECT * FROM class_action")
    suspend fun all(): List<ClassActionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ClassActionEntity>)

    @Query("DELETE FROM class_action")
    suspend fun deleteAll(): Int
}

/** Known originators. Permanent. */
@Dao
interface KnownOriginatorDao {
    @Query("SELECT * FROM known_originator ORDER BY value ASC")
    fun observeAll(): Flow<List<KnownOriginatorEntity>>

    @Query("SELECT * FROM known_originator ORDER BY value ASC")
    suspend fun all(): List<KnownOriginatorEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM known_originator WHERE value = :value AND kind = :kind)")
    suspend fun exists(
        value: String,
        kind: String,
    ): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: KnownOriginatorEntity): Long

    @Query("DELETE FROM known_originator WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM known_originator WHERE value = :value")
    suspend fun deleteByValue(value: String): Int

    @Query("DELETE FROM known_originator")
    suspend fun deleteAll(): Int
}

/** Trusted packages. Permanent. */
@Dao
interface TrustedPackageDao {
    @Query("SELECT * FROM trusted_package ORDER BY packageName ASC")
    fun observeAll(): Flow<List<TrustedPackageEntity>>

    @Query("SELECT * FROM trusted_package ORDER BY packageName ASC")
    suspend fun all(): List<TrustedPackageEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM trusted_package WHERE packageName = :packageName)")
    suspend fun isTrusted(packageName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrustedPackageEntity)

    @Query("DELETE FROM trusted_package WHERE packageName = :packageName")
    suspend fun delete(packageName: String): Int

    @Query("DELETE FROM trusted_package")
    suspend fun deleteAll(): Int
}

/** Daily counters for CL-NORMAL / CL-MO-VISIBLE. Kept two years. */
@Dao
interface CounterDao {
    @Query(
        """
        INSERT INTO counter (day, subId, direction, classId, n) VALUES (:day, :subId, :direction, :classId, :count)
        ON CONFLICT(day, subId, direction, classId) DO UPDATE SET n = n + excluded.n
        """,
    )
    suspend fun add(
        day: Int,
        subId: Int,
        direction: String,
        classId: String,
        count: Long,
    )

    @Query("SELECT * FROM counter WHERE day >= :fromDay ORDER BY day ASC")
    fun observeFrom(fromDay: Int): Flow<List<CounterEntity>>

    @Query("SELECT * FROM counter WHERE day >= :fromDay ORDER BY day ASC")
    suspend fun from(fromDay: Int): List<CounterEntity>

    @Query("SELECT COALESCE(SUM(n), 0) FROM counter WHERE day = :day")
    suspend fun totalForDay(day: Int): Long

    @Query("DELETE FROM counter WHERE day < :day")
    suspend fun deleteBefore(day: Int): Int

    @Query("DELETE FROM counter")
    suspend fun deleteAll(): Int
}

/** Coverage per class. Permanent. */
@Dao
interface CoverageDao {
    @Query("SELECT * FROM coverage ORDER BY classId ASC")
    fun observeAll(): Flow<List<CoverageEntity>>

    @Query("SELECT * FROM coverage ORDER BY classId ASC")
    suspend fun all(): List<CoverageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<CoverageEntity>)

    @Query(
        """
        INSERT INTO coverage (classId, lastSeenFromNetwork, lastSelfTestAt, lastSelfTestOk)
        VALUES (:classId, :at, NULL, NULL)
        ON CONFLICT(classId) DO UPDATE SET lastSeenFromNetwork = :at
        """,
    )
    suspend fun markSeenFromNetwork(
        classId: String,
        at: Long,
    )

    @Query(
        """
        INSERT INTO coverage (classId, lastSeenFromNetwork, lastSelfTestAt, lastSelfTestOk)
        VALUES (:classId, NULL, :at, :ok)
        ON CONFLICT(classId) DO UPDATE SET lastSelfTestAt = :at, lastSelfTestOk = :ok
        """,
    )
    suspend fun markSelfTest(
        classId: String,
        at: Long,
        ok: Boolean,
    )

    @Query("DELETE FROM coverage")
    suspend fun deleteAll(): Int
}

/** Hook resolution per build fingerprint. Only the last five fingerprints are kept. */
@Dao
interface HookStatusDao {
    @Query("SELECT * FROM hook_status WHERE buildFingerprint = :fingerprint ORDER BY hookId ASC")
    fun observeForBuild(fingerprint: String): Flow<List<HookStatusEntity>>

    @Query("SELECT * FROM hook_status ORDER BY buildFingerprint ASC, hookId ASC")
    suspend fun all(): List<HookStatusEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<HookStatusEntity>)

    @Query(
        """
        DELETE FROM hook_status WHERE buildFingerprint NOT IN (
            SELECT buildFingerprint FROM (
                SELECT buildFingerprint, MAX(updatedAt) AS newest FROM hook_status
                GROUP BY buildFingerprint ORDER BY newest DESC LIMIT :keep
            )
        )
        """,
    )
    suspend fun keepOnlyNewestBuilds(keep: Int): Int

    @Query("SELECT DISTINCT buildFingerprint FROM hook_status")
    suspend fun fingerprints(): List<String>

    @Query("DELETE FROM hook_status WHERE buildFingerprint = :fingerprint")
    suspend fun deleteForBuild(fingerprint: String): Int

    @Query("DELETE FROM hook_status")
    suspend fun deleteAll(): Int
}

/** Self-test runs. Only the last 20 are kept. */
@Dao
interface SelfTestRunDao {
    @Query("SELECT * FROM self_test_run ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SelfTestRunEntity>>

    @Query("SELECT * FROM self_test_run ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SelfTestRunEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SelfTestRunEntity)

    @Query(
        """
        DELETE FROM self_test_run WHERE runId NOT IN (
            SELECT runId FROM self_test_run ORDER BY startedAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun keepNewest(keep: Int): Int

    @Query("DELETE FROM self_test_run")
    suspend fun deleteAll(): Int
}

/** Audit records. Kept 365 days; the last table a "delete all" spares. */
@Dao
interface AuditDao {
    @Query("SELECT * FROM audit ORDER BY at DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditEntity>>

    @Query("SELECT * FROM audit WHERE at >= :since ORDER BY at DESC, id DESC")
    suspend fun since(since: Long): List<AuditEntity>

    @Query("SELECT * FROM audit ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AuditEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: AuditEntity): Long

    @Query("DELETE FROM audit WHERE at < :cutoff")
    suspend fun deleteBefore(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM audit")
    suspend fun count(): Int
}

/** Temporary mutes. */
@Dao
interface MuteDao {
    @Query("SELECT * FROM mute WHERE until > :now ORDER BY until ASC")
    suspend fun active(now: Long): List<MuteEntity>

    @Query("SELECT * FROM mute WHERE until > :now ORDER BY until ASC")
    fun observeActive(now: Long): Flow<List<MuteEntity>>

    @Query("SELECT * FROM mute WHERE scope = :scope AND value = :value AND until > :now LIMIT 1")
    suspend fun find(
        scope: String,
        value: String,
        now: Long,
    ): MuteEntity?

    @Query(
        """
        INSERT INTO mute (scope, value, until) VALUES (:scope, :value, :until)
        ON CONFLICT(scope, value) DO UPDATE SET until = :until
        """,
    )
    suspend fun put(
        scope: String,
        value: String,
        until: Long,
    )

    @Query("DELETE FROM mute WHERE until <= :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("DELETE FROM mute")
    suspend fun deleteAll(): Int
}

/** Saved filter views. Permanent. */
@Dao
interface SavedViewDao {
    @Query("SELECT * FROM saved_view ORDER BY name ASC")
    fun observeAll(): Flow<List<SavedViewEntity>>

    @Query("SELECT * FROM saved_view WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): SavedViewEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SavedViewEntity): Long

    @Query("DELETE FROM saved_view WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM saved_view")
    suspend fun deleteAll(): Int
}

/** Package label cache. */
@Dao
interface PackageLabelDao {
    @Query("SELECT label FROM package_label WHERE packageName = :packageName")
    suspend fun label(packageName: String): String?

    @Query("SELECT * FROM package_label")
    suspend fun all(): List<PackageLabelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(rows: List<PackageLabelEntity>)

    @Query("DELETE FROM package_label")
    suspend fun deleteAll(): Int
}

/** Rule-document metadata (addition: preset and revision for `rules.json`). */
@Dao
interface RuleMetaDao {
    @Query("SELECT value FROM rule_meta WHERE key = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: RuleMetaEntity)

    @Query("SELECT value FROM rule_meta")
    fun observeAll(): Flow<List<String>>
}
