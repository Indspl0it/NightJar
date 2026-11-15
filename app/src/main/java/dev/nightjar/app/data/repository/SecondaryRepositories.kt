package dev.nightjar.app.data.repository

import androidx.room.withTransaction
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.db.CounterDao
import dev.nightjar.app.data.db.CounterEntity
import dev.nightjar.app.data.db.CoverageDao
import dev.nightjar.app.data.db.HookStatusDao
import dev.nightjar.app.data.db.HookStatusEntity
import dev.nightjar.app.data.db.KnownOriginatorDao
import dev.nightjar.app.data.db.KnownOriginatorEntity
import dev.nightjar.app.data.db.MuteDao
import dev.nightjar.app.data.db.NightjarDatabase
import dev.nightjar.app.data.db.PackageLabelDao
import dev.nightjar.app.data.db.PackageLabelEntity
import dev.nightjar.app.data.db.SavedViewDao
import dev.nightjar.app.data.db.SavedViewEntity
import dev.nightjar.app.data.db.SelfTestRunDao
import dev.nightjar.app.data.db.SelfTestRunEntity
import dev.nightjar.app.data.db.TrustedPackageDao
import dev.nightjar.app.data.db.TrustedPackageEntity
import dev.nightjar.app.data.model.Coverage
import dev.nightjar.app.data.model.FilterSpec
import dev.nightjar.app.data.model.HookStatus
import dev.nightjar.app.data.model.KnownOriginator
import dev.nightjar.app.data.model.KnownOriginatorKind
import dev.nightjar.app.data.model.Mute
import dev.nightjar.app.data.model.MuteScope
import dev.nightjar.app.data.model.SavedView
import dev.nightjar.app.data.model.SelfTestRun
import dev.nightjar.app.data.model.TrustedPackage
import dev.nightjar.app.data.model.toModel
import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Masker
import dev.nightjar.rules.Numbers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Known originators. Permanent: never purged, never deleted by "delete all". */
interface KnownOriginatorRepository {
    fun observeAll(): Flow<List<KnownOriginator>>

    suspend fun all(): List<KnownOriginator>

    suspend fun add(
        value: String,
        kind: KnownOriginatorKind = KnownOriginatorKind.EXACT,
        label: String? = null,
    ): KnownOriginator?

    suspend fun remove(id: Long): Boolean

    suspend fun removeValue(value: String): Boolean

    /** True when the value matches an exact entry or a prefix entry (rule engine semantics). */
    suspend fun isKnown(originator: String): Boolean
}

@Singleton
class RoomKnownOriginatorRepository
    @Inject
    constructor(
        private val dao: KnownOriginatorDao,
        private val audit: AuditLog,
        private val clock: Clock,
    ) : KnownOriginatorRepository {
        override fun observeAll(): Flow<List<KnownOriginator>> =
            dao.observeAll().map { rows -> rows.map { it.toModel() } }

        override suspend fun all(): List<KnownOriginator> = dao.all().map { it.toModel() }

        override suspend fun add(
            value: String,
            kind: KnownOriginatorKind,
            label: String?,
        ): KnownOriginator? {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return null
            val now = clock.now()
            val id = dao.insert(KnownOriginatorEntity(0, trimmed, kind.wire, label, now))
            // An IGNORE insert that did nothing (the value is already known) is not a change.
            if (id <= 0) return null
            audit.record(
                action = AuditAction.KNOWN_SENDER_ADD,
                actor = AuditActor.USER,
                // The audit goes into diagnostics; never the full number.
                target = Masker.maskNumber(trimmed),
                after = AuditLog.scalar(kind.wire),
            )
            return KnownOriginator(id, trimmed, kind, label, now)
        }

        override suspend fun remove(id: Long): Boolean {
            val removed = dao.delete(id) > 0
            if (removed) {
                audit.record(AuditAction.KNOWN_SENDER_REMOVE, AuditActor.USER, "id:$id")
            }
            return removed
        }

        override suspend fun removeValue(value: String): Boolean {
            val removed = dao.deleteByValue(value) > 0
            if (removed) {
                audit.record(AuditAction.KNOWN_SENDER_REMOVE, AuditActor.USER, Masker.maskNumber(value))
            }
            return removed
        }

        /**
         * The rule engine semantics (NumberTrie): normalised and ASCII case-folded on both sides, and an
         * entry that normalises to nothing (for example a lone `+`) matches nothing.
         */
        override suspend fun isKnown(originator: String): Boolean {
            val normalized = KnownOriginatorMatcher.normalize(originator) ?: return false
            val rows = dao.all()
            return rows.any { row ->
                val entry = KnownOriginatorMatcher.normalize(row.value) ?: return@any false
                when (KnownOriginatorKind.from(row.kind)) {
                    KnownOriginatorKind.EXACT -> entry == normalized
                    KnownOriginatorKind.PREFIX -> normalized.startsWith(entry)
                }
            }
        }
    }

/** Trusted packages. Permanent. */
interface TrustedPackageRepository {
    fun observeAll(): Flow<List<TrustedPackage>>

    suspend fun all(): List<TrustedPackage>

    suspend fun add(
        packageName: String,
        label: String?,
    )

    suspend fun remove(packageName: String): Boolean

    suspend fun isTrusted(packageName: String): Boolean
}

@Singleton
class RoomTrustedPackageRepository
    @Inject
    constructor(
        private val dao: TrustedPackageDao,
        private val audit: AuditLog,
        private val clock: Clock,
    ) : TrustedPackageRepository {
        override fun observeAll(): Flow<List<TrustedPackage>> =
            dao.observeAll().map { rows -> rows.map { it.toModel() } }

        override suspend fun all(): List<TrustedPackage> = dao.all().map { it.toModel() }

        override suspend fun add(
            packageName: String,
            label: String?,
        ) {
            if (packageName.isBlank()) return
            dao.upsert(TrustedPackageEntity(packageName, label, clock.now()))
            audit.record(AuditAction.TRUST_APP_ADD, AuditActor.USER, packageName)
        }

        override suspend fun remove(packageName: String): Boolean {
            val removed = dao.delete(packageName) > 0
            if (removed) audit.record(AuditAction.TRUST_APP_REMOVE, AuditActor.USER, packageName)
            return removed
        }

        override suspend fun isTrusted(packageName: String): Boolean = dao.isTrusted(packageName)
    }

/** Temporary mutes. */
interface MuteRepository {
    fun observeActive(): Flow<List<Mute>>

    suspend fun active(): List<Mute>

    suspend fun mute(
        scope: MuteScope,
        value: String,
        untilWallMs: Long,
    )

    suspend fun clear(
        scope: MuteScope,
        value: String,
    ): Boolean

    suspend fun clearExpired(): Int

    /** True when a sender or its class is muted at [nowWallMs]. */
    suspend fun isMuted(
        originator: String?,
        classId: ClassId,
        nowWallMs: Long,
    ): Boolean
}

@Singleton
class RoomMuteRepository
    @Inject
    constructor(
        private val dao: MuteDao,
        private val clock: Clock,
    ) : MuteRepository {
        /**
         * The "active" cut is made again every minute, so a mute that expires while the screen is open
         * leaves the list. The query keeps every mute that has not been cleared (`until > 0`).
         */
        override fun observeActive(): Flow<List<Mute>> =
            combine(dao.observeActive(0L), minuteTicks()) { rows, _ ->
                val now = clock.now()
                rows.filter { it.until > now }.map { it.toModel() }
            }

        private fun minuteTicks(): Flow<Unit> =
            flow {
                while (true) {
                    emit(Unit)
                    delay(MUTE_REFRESH_MS)
                }
            }

        override suspend fun active(): List<Mute> = dao.active(clock.now()).map { it.toModel() }

        override suspend fun mute(
            scope: MuteScope,
            value: String,
            untilWallMs: Long,
        ) {
            dao.put(scope.wire, value, untilWallMs)
        }

        override suspend fun clear(
            scope: MuteScope,
            value: String,
        ): Boolean {
            // One path; the result says whether a mute that was still active existed.
            val existed = dao.find(scope.wire, value, clock.now()) != null
            dao.put(scope.wire, value, 0)
            dao.deleteExpired(clock.now())
            return existed
        }

        override suspend fun clearExpired(): Int = dao.deleteExpired(clock.now())

        override suspend fun isMuted(
            originator: String?,
            classId: ClassId,
            nowWallMs: Long,
        ): Boolean {
            if (dao.find(MuteScope.CLASS.wire, classId.wire(), nowWallMs) != null) return true
            val normalized = Numbers.normalize(originator) ?: return false
            return dao.active(nowWallMs).any {
                it.scope == MuteScope.ORIGINATOR.wire && Numbers.normalize(it.value) == normalized
            }
        }

        private companion object {
            const val MUTE_REFRESH_MS = 60_000L
        }
    }

/** Coverage per class. */
interface CoverageRepository {
    fun observeAll(): Flow<List<Coverage>>

    suspend fun all(): List<Coverage>

    suspend fun markSeenFromNetwork(
        classId: ClassId,
        atWallMs: Long,
    )

    suspend fun markSelfTest(
        classId: ClassId,
        atWallMs: Long,
        ok: Boolean,
    )
}

@Singleton
class RoomCoverageRepository
    @Inject
    constructor(
        private val dao: CoverageDao,
    ) : CoverageRepository {
        override fun observeAll(): Flow<List<Coverage>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

        override suspend fun all(): List<Coverage> = dao.all().map { it.toModel() }

        override suspend fun markSeenFromNetwork(
            classId: ClassId,
            atWallMs: Long,
        ) = dao.markSeenFromNetwork(classId.wire(), atWallMs)

        override suspend fun markSelfTest(
            classId: ClassId,
            atWallMs: Long,
            ok: Boolean,
        ) = dao.markSelfTest(classId.wire(), atWallMs, ok)
    }

/** Daily counters for CL-NORMAL / CL-MO-VISIBLE. Kept two years. */
interface CounterRepository {
    fun observeFrom(day: Int): Flow<List<CounterEntity>>

    suspend fun add(
        day: Int,
        subId: Int,
        direction: Direction,
        classId: ClassId,
        count: Long,
    )

    /** Adds every delta in one transaction: a retried batch after a failure never counts a row twice. */
    suspend fun addAll(rows: List<CounterEntity>)

    /** Total captured for one day, all classes and subscriptions. */
    suspend fun totalForDay(day: Int): Long

    suspend fun purgeBefore(day: Int): Int
}

@Singleton
class RoomCounterRepository
    @Inject
    constructor(
        private val db: NightjarDatabase,
        private val dao: CounterDao,
    ) : CounterRepository {
        override fun observeFrom(day: Int): Flow<List<CounterEntity>> = dao.observeFrom(day)

        override suspend fun add(
            day: Int,
            subId: Int,
            direction: Direction,
            classId: ClassId,
            count: Long,
        ) = dao.add(day, subId, direction.wire(), classId.wire(), count)

        override suspend fun addAll(rows: List<CounterEntity>) {
            if (rows.isEmpty()) return
            db.withTransaction { rows.forEach { dao.add(it.day, it.subId, it.direction, it.classId, it.count) } }
        }

        override suspend fun totalForDay(day: Int): Long = dao.totalForDay(day)

        override suspend fun purgeBefore(day: Int): Int = dao.deleteBefore(day)
    }

/** Hook status per build fingerprint. */
interface HookStatusRepository {
    fun observeForBuild(fingerprint: String): Flow<List<HookStatus>>

    suspend fun all(): List<HookStatus>

    suspend fun replaceForBuild(
        fingerprint: String,
        rows: List<HookStatusEntity>,
    )

    /** Keeps only the newest [keep] build fingerprints (housekeeping). */
    suspend fun keepNewestBuilds(keep: Int = 5): Int
}

@Singleton
class RoomHookStatusRepository
    @Inject
    constructor(
        private val db: NightjarDatabase,
        private val dao: HookStatusDao,
    ) : HookStatusRepository {
        override fun observeForBuild(fingerprint: String): Flow<List<HookStatus>> =
            dao.observeForBuild(fingerprint).map { rows -> rows.map { it.toModel() } }

        override suspend fun all(): List<HookStatus> = dao.all().map { it.toModel() }

        /** A hook that the payload no longer reports for this build is removed, in the same transaction. */
        override suspend fun replaceForBuild(
            fingerprint: String,
            rows: List<HookStatusEntity>,
        ) = db.withTransaction {
            dao.deleteForBuild(fingerprint)
            dao.upsertAll(rows.map { it.copy(buildFingerprint = fingerprint) })
        }

        override suspend fun keepNewestBuilds(keep: Int): Int = dao.keepOnlyNewestBuilds(keep)
    }

/** Self-test runs (TST-*). Only the last 20 are kept. */
interface SelfTestRepository {
    fun observeRecent(limit: Int = 20): Flow<List<SelfTestRun>>

    suspend fun recent(limit: Int = 20): List<SelfTestRun>

    suspend fun save(run: SelfTestRun)

    suspend fun keepNewest(keep: Int = 20): Int
}

@Singleton
class RoomSelfTestRepository
    @Inject
    constructor(
        private val dao: SelfTestRunDao,
    ) : SelfTestRepository {
        override fun observeRecent(limit: Int): Flow<List<SelfTestRun>> =
            dao.observeRecent(limit).map { rows -> rows.map { it.toModel() } }

        override suspend fun recent(limit: Int): List<SelfTestRun> = dao.recent(limit).map { it.toModel() }

        override suspend fun save(run: SelfTestRun) =
            dao.insert(
                SelfTestRunEntity(run.runId, run.startedAt, run.buildFingerprint, run.payloadVersion, run.resultsJson),
            )

        override suspend fun keepNewest(keep: Int): Int = dao.keepNewest(keep)
    }

/** Saved filter views. Permanent. */
interface SavedViewRepository {
    fun observeAll(): Flow<List<SavedView>>

    suspend fun save(
        name: String,
        spec: FilterSpec,
    ): Long

    suspend fun delete(id: Long): Boolean
}

@Singleton
class RoomSavedViewRepository
    @Inject
    constructor(
        private val dao: SavedViewDao,
    ) : SavedViewRepository {
        override fun observeAll(): Flow<List<SavedView>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

        override suspend fun save(
            name: String,
            spec: FilterSpec,
        ): Long = dao.insert(SavedViewEntity(0, name, spec.toJson()))

        override suspend fun delete(id: Long): Boolean = dao.delete(id) > 0
    }

/** Package label cache: one PackageManager lookup per package, reused by list and search. */
interface PackageLabelRepository {
    suspend fun label(packageName: String): String?

    suspend fun put(
        packageName: String,
        label: String?,
    )

    suspend fun all(): Map<String, String?>
}

@Singleton
class RoomPackageLabelRepository
    @Inject
    constructor(
        private val dao: PackageLabelDao,
        private val clock: Clock,
    ) : PackageLabelRepository {
        override suspend fun label(packageName: String): String? = dao.label(packageName)

        override suspend fun put(
            packageName: String,
            label: String?,
        ) = dao.putAll(listOf(PackageLabelEntity(packageName, label, clock.now())))

        override suspend fun all(): Map<String, String?> = dao.all().associate { it.packageName to it.label }
    }
