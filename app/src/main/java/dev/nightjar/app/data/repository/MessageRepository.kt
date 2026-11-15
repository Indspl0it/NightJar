package dev.nightjar.app.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.db.FeedIdRow
import dev.nightjar.app.data.db.MessageDao
import dev.nightjar.app.data.db.MessageEntity
import dev.nightjar.app.data.db.PduDao
import dev.nightjar.app.data.db.StringListColumn
import dev.nightjar.app.data.db.TraceStepDao
import dev.nightjar.app.data.model.ClassCount
import dev.nightjar.app.data.model.FilterSpec
import dev.nightjar.app.data.model.MessageDetail
import dev.nightjar.app.data.model.MessageLink
import dev.nightjar.app.data.model.MessageListItem
import dev.nightjar.app.data.model.PartArrival
import dev.nightjar.app.data.model.parseWire
import dev.nightjar.app.data.model.toListItem
import dev.nightjar.app.data.model.toModel
import dev.nightjar.app.data.repository.MessageRepository.Companion.PAGE_SIZE
import dev.nightjar.app.domain.ingest.TrueFate
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Transport
import dev.nightjar.contract.Verdict
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The most ids in one `IN (:ids)` statement. "Select all" can select thousands of rows, and SQLite limits
 * the number of bound parameters of one statement.
 */
private const val IDS_PER_STATEMENT = 500

/** The four week counters. [hidden] holds the other three: each of them is a subset of it. */
data class WeekCounts(
    val hidden: Int = 0,
    val suspicious: Int = 0,
    val onHold: Int = 0,
    val blocked: Int = 0,
) {
    operator fun plus(other: WeekCounts): WeekCounts =
        WeekCounts(
            hidden = hidden + other.hidden,
            suspicious = suspicious + other.suspicious,
            onHold = onHold + other.onHold,
            blocked = blocked + other.blocked,
        )

    companion object {
        val ZERO = WeekCounts()
    }
}

/** The counters of one local day. */
data class WeekDay(
    val date: LocalDate,
    val counts: WeekCounts,
)

/** Home aggregates for the last 7 days: the week totals, one entry per day (oldest first), and per class. */
data class WeekStats(
    val totals: WeekCounts,
    val days: List<WeekDay>,
    val byClass: List<ClassCount>,
)

/**
 * The 7 local days that end with the day of `nowWall`, in [zone]. [bounds] has `days.size + 1` entries:
 * the start of each day and then the start of the day after the last one, so day `i` is `[bounds[i], bounds[i + 1])`.
 * Each limit comes from the zone rules, so a day with a time change is 23 or 25 hours long and the counts stay right.
 */
data class WeekWindow(
    val days: List<LocalDate>,
    val bounds: List<Long>,
) {
    val fromWall: Long get() = bounds.first()
    val toWall: Long get() = bounds.last()

    companion object {
        const val DAYS = 7

        fun of(
            nowWall: Long,
            zone: ZoneId,
        ): WeekWindow {
            val today = Instant.ofEpochMilli(nowWall).atZone(zone).toLocalDate()
            val days = (DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
            val bounds = (days + today.plusDays(1)).map { it.atStartOfDay(zone).toInstant().toEpochMilli() }
            return WeekWindow(days, bounds)
        }
    }
}

/** One delivery report linked to a sent message. [status] is `DELIVERED`, `PENDING` or `FAILED`. */
data class DeliveryReport(
    val id: Long,
    val receivedAtWall: Long,
    val status: String?,
)

/**
 * The delivery-report links of one message. A sent message has [reports]. A delivery report has [reportOf], the id
 * of the sent message it answers (null when none matched), and its own [status].
 */
data class DeliveryContext(
    val reportOf: Long? = null,
    val status: String? = null,
    val reports: List<DeliveryReport> = emptyList(),
) {
    companion object {
        val NONE = DeliveryContext()
    }
}

/**
 * The message feed, detail, counts and mutations.
 *
 * Every read is a Flow or a PagingSource, so the UI never polls. Writes are suspend and happen on the caller's
 * dispatcher (Room's own executor), never on the main thread.
 */
interface MessageRepository {
    /** feed. Excludes counters and self-test rows; delivery reports show unless the filter hides them. */
    fun feed(spec: FilterSpec): Flow<PagingData<MessageListItem>>

    /** Row count for the current filter, for the "n messages" header and the retention dialog. */
    suspend fun count(spec: FilterSpec): Int

    /** [count] that re-emits as messages arrive, are reviewed or are deleted, so a visible total never goes stale. */
    fun observeCount(spec: FilterSpec): Flow<Int> = flow { emit(count(spec)) }

    /**
     * Every row of [feed] for [spec], newest first, with the facts that the selection needs. "Select all"
     * and "Invert selection" use it, so they work on the full list and not only on the loaded pages.
     */
    suspend fun feedIds(spec: FilterSpec): List<FeedIdRow>

    fun observeQuarantinedCount(): Flow<Int>

    fun observeUnreviewedCount(): Flow<Int>

    suspend fun find(id: Long): MessageDetail?

    fun observe(id: Long): Flow<MessageDetail?>

    /** The delivery-report links of message [id]. The default is "no links", so a fake needs no override. */
    fun observeDeliveryContext(id: Long): Flow<DeliveryContext> = flowOf(DeliveryContext.NONE)

    suspend fun markReviewed(
        ids: List<Long>,
        reviewed: Boolean = true,
    ): Int

    suspend fun setStarred(
        ids: List<Long>,
        starred: Boolean,
    ): Int

    suspend fun delete(ids: List<Long>): Int

    /** Applies a detector/override severity. [overridden] records that the user's setting won. */
    suspend fun applySeverity(
        id: Long,
        severity: Severity,
        overridden: Boolean,
    ): Int

    /** Marks a message quarantined or released. */
    suspend fun setQuarantineState(
        id: Long,
        quarantined: Boolean,
    ): Int

    /**
     * The user blocks a held message (notification Block button, Detail). A message that is on hold is no longer
     * on hold, its fate becomes Blocked, and one BLOCK_HELD audit record is written. A message that is no longer on hold
     * keeps its fate and writes no audit record. The message is marked as reviewed in both cases. Returns true when the
     * message was blocked.
     */
    suspend fun blockHeld(id: Long): Boolean

    /**
     * Counts for the last 7 local days that end with the day of [nowWall]. Delivery reports are not counted.
     * "On hold" is the number of messages that are held now. "Blocked" is the number that were blocked.
     */
    fun weekStats(
        nowWall: Long,
        zone: ZoneId,
    ): Flow<WeekStats>

    /** Rows still waiting for post-commit processing (crash-safe resume). */
    suspend fun pendingProcessing(limit: Int = 200): List<Long>

    suspend fun markProcessed(ids: List<Long>): Int

    /** Purgeable count for the retention dialog before a retention change takes effect. */
    suspend fun countPurgeable(cutoffWall: Long): Int

    suspend fun countCaptured(): Int

    companion object {
        const val PAGE_SIZE = 50
    }
}

@Singleton
class RoomMessageRepository
    @Inject
    constructor(
        private val messages: MessageDao,
        private val pdus: PduDao,
        private val traces: TraceStepDao,
        private val detections: DetectionRepository,
        private val audit: AuditLog,
    ) : MessageRepository {
        override fun feed(spec: FilterSpec): Flow<PagingData<MessageListItem>> {
            val a = spec.toArgs()
            // SCREENS1-X01: no maxSize. A dropped page would break the Messages grouping (reports under the sent
            // message, day headers), which works on the loaded rows; a list row is small.
            return Pager(
                config =
                    PagingConfig(
                        pageSize = PAGE_SIZE,
                        prefetchDistance = PAGE_SIZE,
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = {
                    messages.feed(
                        includeStatus = a.includeStatus,
                        classCount = a.classCount,
                        classIds = a.classIds,
                        severityCount = a.severityCount,
                        severities = a.severities,
                        fateCount = a.fateCount,
                        fates = a.fates,
                        attentionOnly = a.attentionOnly,
                        attentionSeverity = a.attentionSeverity,
                        quarantinedOnly = a.quarantinedOnly,
                        unreviewedOnly = a.unreviewedOnly,
                        starredOnly = a.starredOnly,
                        direction = a.direction,
                        fromWall = a.fromWall,
                        toWall = a.toWall,
                        subId = a.subId,
                        slot = a.slot,
                        searchLike = a.searchLike,
                        searchNormLike = a.searchNormLike,
                        searchPort = a.searchPort,
                        originatorLike = a.originatorLike,
                        port = a.port,
                        contentTypeLike = a.contentTypeLike,
                        packageLike = a.packageLike,
                    )
                },
            ).flow.map { paging -> paging.map { row -> row.toListItem() } }
        }

        override suspend fun count(spec: FilterSpec): Int = observeCount(spec).first()

        override fun observeCount(spec: FilterSpec): Flow<Int> {
            val a = spec.toArgs()
            return messages.observeCountFeed(
                includeStatus = a.includeStatus,
                classCount = a.classCount,
                classIds = a.classIds,
                severityCount = a.severityCount,
                severities = a.severities,
                fateCount = a.fateCount,
                fates = a.fates,
                attentionOnly = a.attentionOnly,
                attentionSeverity = a.attentionSeverity,
                quarantinedOnly = a.quarantinedOnly,
                unreviewedOnly = a.unreviewedOnly,
                starredOnly = a.starredOnly,
                direction = a.direction,
                fromWall = a.fromWall,
                toWall = a.toWall,
                subId = a.subId,
                slot = a.slot,
                searchLike = a.searchLike,
                searchNormLike = a.searchNormLike,
                searchPort = a.searchPort,
                originatorLike = a.originatorLike,
                port = a.port,
                contentTypeLike = a.contentTypeLike,
                packageLike = a.packageLike,
            )
        }

        override suspend fun feedIds(spec: FilterSpec): List<FeedIdRow> {
            val a = spec.toArgs()
            return messages.feedIds(
                includeStatus = a.includeStatus,
                classCount = a.classCount,
                classIds = a.classIds,
                severityCount = a.severityCount,
                severities = a.severities,
                fateCount = a.fateCount,
                fates = a.fates,
                attentionOnly = a.attentionOnly,
                attentionSeverity = a.attentionSeverity,
                quarantinedOnly = a.quarantinedOnly,
                unreviewedOnly = a.unreviewedOnly,
                starredOnly = a.starredOnly,
                direction = a.direction,
                fromWall = a.fromWall,
                toWall = a.toWall,
                subId = a.subId,
                slot = a.slot,
                searchLike = a.searchLike,
                searchNormLike = a.searchNormLike,
                searchPort = a.searchPort,
                originatorLike = a.originatorLike,
                port = a.port,
                contentTypeLike = a.contentTypeLike,
                packageLike = a.packageLike,
            )
        }

        override fun observeQuarantinedCount(): Flow<Int> = messages.observeQuarantinedCount()

        override fun observeUnreviewedCount(): Flow<Int> = messages.observeUnreviewedCount()

        override suspend fun find(id: Long): MessageDetail? = messages.find(id)?.let { row -> detail(row) }

        override fun observe(id: Long): Flow<MessageDetail?> =
            messages.observe(id).map { row -> row?.let { detail(it) } }

        override fun observeDeliveryContext(id: Long): Flow<DeliveryContext> =
            combine(messages.observeReportFacts(id), messages.observeDeliveryReports(id)) { facts, reports ->
                DeliveryContext(
                    reportOf = facts?.relatedMessageId,
                    status = facts?.deliveryStatus,
                    reports = reports.map { DeliveryReport(it.id, it.receivedAtWall, it.deliveryStatus) },
                )
            }

        override suspend fun markReviewed(
            ids: List<Long>,
            reviewed: Boolean,
        ): Int = ids.chunked(IDS_PER_STATEMENT).sumOf { chunk -> messages.setReviewed(chunk, reviewed) }

        override suspend fun setStarred(
            ids: List<Long>,
            starred: Boolean,
        ): Int = ids.chunked(IDS_PER_STATEMENT).sumOf { chunk -> messages.setStarred(chunk, starred) }

        override suspend fun delete(ids: List<Long>): Int = ids.chunked(IDS_PER_STATEMENT).sumOf { chunk -> messages.deleteByIds(chunk) }

        override suspend fun applySeverity(
            id: Long,
            severity: Severity,
            overridden: Boolean,
        ): Int = messages.setSeverity(id, severity.wire(), overridden)

        override suspend fun setQuarantineState(
            id: Long,
            quarantined: Boolean,
        ): Int =
            messages.setQuarantined(
                listOf(id),
                quarantined,
                if (quarantined) Fate.QUARANTINED.wire() else Fate.RELEASED.wire(),
            )

        override suspend fun blockHeld(id: Long): Boolean {
            val row = messages.find(id) ?: return false
            val blocked = messages.blockIfHeld(id, held = Fate.QUARANTINED.wire(), blocked = Fate.BLOCKED.wire()) > 0
            if (blocked) audit.record(AuditAction.BLOCK_HELD, AuditActor.USER, target = row.eventId)
            messages.setReviewed(listOf(id), reviewed = true)
            return blocked
        }

        override fun weekStats(
            nowWall: Long,
            zone: ZoneId,
        ): Flow<WeekStats> {
            val window = WeekWindow.of(nowWall, zone)
            val dayFlows =
                window.days.indices.map { i ->
                    messages
                        .weekCountsBetween(
                            fromWall = window.bounds[i],
                            toWall = window.bounds[i + 1],
                            suspiciousSeverity = FilterSpec.ATTENTION_SEVERITIES.map { it.wire() },
                            blockedFate = Fate.BLOCKED.wire(),
                        ).map { WeekCounts(it.hidden, it.suspicious, it.onHold, it.blocked) }
                }
            val days =
                kotlinx.coroutines.flow.combine(dayFlows) { counts ->
                    window.days.mapIndexed { i, date -> WeekDay(date, counts[i]) }
                }
            val byClass = messages.classCountsBetween(window.fromWall, window.toWall)
            return kotlinx.coroutines.flow.combine(days, byClass) { perDay, classes ->
                WeekStats(
                    totals = perDay.fold(WeekCounts.ZERO) { sum, day -> sum + day.counts },
                    days = perDay,
                    byClass =
                        classes.map {
                            ClassCount(parseWire(ClassId::class.java, it.classId, ClassId.NORMAL), it.count)
                        },
                )
            }
        }

        override suspend fun pendingProcessing(limit: Int): List<Long> = messages.pendingProcessing(limit)

        override suspend fun markProcessed(ids: List<Long>): Int = if (ids.isEmpty()) 0 else messages.markProcessed(ids)

        override suspend fun countPurgeable(cutoffWall: Long): Int = messages.countPurgeable(cutoffWall)

        override suspend fun countCaptured(): Int = messages.countCaptured()

        /** Assembles Detail (05, 08): PDUs, trace, linked records, burst siblings, detections. */
        private suspend fun detail(row: MessageEntity): MessageDetail {
            val releasedTo = messages.releasedFrom(row.id)
            val releasedFrom = row.releasedFromId?.let { messages.find(it) }
            val found = detections.forMessage(row.id)
            // Burst siblings: the other members of every merged detection (one with a merge key) of this message.
            val siblingIds =
                found
                    .filter { it.mergeKey != null }
                    .flatMap { detections.memberIdsExcept(detectionId = it.id, excludeMessageId = row.id) }
                    .distinct()
            val siblings = if (siblingIds.isEmpty()) emptyList() else messages.findMany(siblingIds).map { it.toLink() }
            return MessageDetail(
                id = row.id,
                eventId = row.eventId,
                revision = row.revision,
                direction = parseWire(Direction::class.java, row.direction, Direction.IN),
                transport = parseWire(Transport::class.java, row.transport, Transport.CS),
                source = row.source,
                subId = row.subId,
                slot = row.slot,
                carrierName = row.carrierName,
                classId = parseWire(ClassId::class.java, row.classId, ClassId.NORMAL),
                severity = parseWire(Severity::class.java, row.severity, Severity.INFO),
                detectorSeverity = row.detectorSeverity?.let { parseWire(Severity::class.java, it, Severity.INFO) },
                severityOverridden = row.severityOverridden,
                title = row.title,
                originator = row.originator,
                smsc = row.smsc,
                destPort = row.destPort,
                contentType = row.contentType,
                textBody = row.textBody,
                textPreview = row.textPreview,
                receivedAtWall = row.receivedAtWall,
                receivedAtElapsedNs = row.receivedAtElapsedNs,
                modemTs = row.modemTs,
                ackResult = row.ackResult,
                verdict = parseWire(Verdict::class.java, row.verdict, Verdict.ALLOW),
                ruleId = row.ruleId,
                // Same true fate as the list row.
                fate =
                    TrueFate.of(
                        parseWire(Fate::class.java, row.fate, Fate.UNKNOWN),
                        parseWire(Direction::class.java, row.direction, Direction.IN),
                        row.ackResult,
                        replay = row.isReplay,
                    ),
                fateDetail = row.fateDetail,
                consumerPackage = row.consumerPackage,
                callingPackage = row.callingPackage,
                persistFlag = row.persistFlag,
                concatRef = row.concatRef,
                partsTotal = row.partsTotal,
                partsHave = row.partsHave,
                parts = parseParts(row.partsJson),
                purgeAt = row.purgeAt,
                isQuarantined = row.isQuarantined,
                isReviewed = row.isReviewed,
                isStarred = row.isStarred,
                isReplay = row.isReplay,
                isSelfTest = row.isSelfTest,
                releasedFromId = row.releasedFromId,
                buildFingerprint = row.buildFingerprint,
                payloadVersion = row.payloadVersion,
                pdus = pdus.forMessage(row.id).map { it.toModel() },
                trace = traces.forMessage(row.id).map { it.toModel() },
                releasedFrom = releasedFrom?.toLink(),
                releasedTo = releasedTo.map { it.toLink() },
                siblings = siblings,
                detections = found,
                subtype = row.subtype,
                extraSubtypes = StringListColumn.decode(row.extraSubtypes),
                anomalies = StringListColumn.decode(row.anomalies),
                clampedFrom = row.clampedFrom?.let { wire -> Verdict.entries.firstOrNull { it.wire() == wire } },
                clampReason = row.clampReason,
                simOtaHeader = parseSimOtaHeader(row.simOtaHeader),
                destination = row.destination,
                roaming = row.roaming,
                userUnlocked = row.userUnlocked,
                cbMessageId = row.cbMessageId,
                cbSerial = row.cbSerial,
                debugInjected = row.debugInjected == true,
            )
        }

        /** The stored `simOtaHeader` object as ordered text pairs; empty for null or unreadable JSON. */
        private fun parseSimOtaHeader(json: String?): Map<String, String> {
            val o = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (key in o.keys()) {
                if (!o.isNull(key)) out[key] = o.get(key).toString()
            }
            return out
        }

        private fun MessageEntity.toLink(): MessageLink =
            MessageLink(
                id = id,
                classId = parseWire(ClassId::class.java, classId, ClassId.NORMAL),
                severity = parseWire(Severity::class.java, severity, Severity.INFO),
                receivedAtWall = receivedAtWall,
                title = title,
                originator = originator,
            )

        private fun parseParts(json: String?): List<PartArrival> {
            if (json.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val o: JSONObject = array.optJSONObject(i) ?: return@mapNotNull null
                PartArrival(seq = o.optInt("seq"), tNs = o.optLong("tNs"))
            }
        }
    }

/** Current wall clock, injected so tests control time. */
fun interface Clock {
    fun now(): Long
}

/** System wall clock. */
@Singleton
class SystemClock
    @Inject
    constructor() : Clock {
        override fun now(): Long = System.currentTimeMillis()
    }
