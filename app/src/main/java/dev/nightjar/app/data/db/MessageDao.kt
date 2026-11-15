package dev.nightjar.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Repeats of one kind from one sender inside the same minute show as a single row (the newest) with a count.
 * Held messages are never merged: each one has to be released or deleted on its own. Delivery reports are never
 * merged either: each report answers one sent message.
 */
private const val SAME_BURST =
    """
    e.isSelfTest = 0 AND e.isQuarantined = 0 AND e.classId <> 'CL-STATUS'
    AND e.classId = m.classId AND e.direction = m.direction
    AND COALESCE(e.originator, '') = COALESCE(m.originator, '')
    AND e.receivedAtWall / 60000 = m.receivedAtWall / 60000
    """

/**
 * CL-NORMAL and CL-MO-VISIBLE only feed counters, except a row the payload blocked: a send that the
 * outgoing gate refused is always a full row and must show and count as blocked. [FEED_CLASS_M] is for the alias `m`.
 */
private const val FEED_CLASS_M = "(m.classId NOT IN ('CL-NORMAL', 'CL-MO-VISIBLE') OR m.fate = 'FT-BLOCKED')"
private const val FEED_CLASS = "(classId NOT IN ('CL-NORMAL', 'CL-MO-VISIBLE') OR fate = 'FT-BLOCKED')"

private const val NOT_MERGED_AWAY =
    """
    AND (m.isQuarantined = 1 OR NOT EXISTS (
        SELECT 1 FROM message AS e WHERE """ + SAME_BURST + """
        AND (e.receivedAtWall > m.receivedAtWall OR (e.receivedAtWall = m.receivedAtWall AND e.id > m.id))))
    """

/** Projection for the Messages feed: no BLOBs, no trace, nothing the list does not render. */
data class MessageRow(
    val id: Long,
    val eventId: String,
    val receivedAtWall: Long,
    val classId: String,
    val severity: String,
    val title: String,
    val originator: String?,
    val textPreview: String,
    val fate: String,
    val verdict: String,
    val isQuarantined: Boolean,
    val isReviewed: Boolean,
    val isStarred: Boolean,
    val direction: String,
    val subId: Int,
    val slot: Int,
    val carrierName: String?,
    val partsHave: Int,
    val partsTotal: Int,
    val consumerPackage: String?,
    val consumerPackageLabel: String?,
    val isReplay: Boolean,
    val repeatCount: Int,
    /** Delivery report: `DELIVERED`, `PENDING` or `FAILED`; null for every other row. */
    val deliveryStatus: String? = null,
    /** Delivery report: the id of the sent message it reports on; null when none matched. */
    val relatedMessageId: Long? = null,
    /** Legacy flag of a removed debug injector. Always null in current builds; kept for the existing schema. */
    val debugInjected: Boolean? = null,
    /** The recipient of a sent message as the payload saw it; null for a received one. */
    val destination: String? = null,
    /** The app that sent the message (`SmsController` caller); null for a received one. */
    val callingPackage: String? = null,
    /** The dispatch result, so a row stored as FT-UNKNOWN can show its true fate. */
    val ackResult: Int? = null,
    /** The cached app label of [callingPackage]. */
    val callingPackageLabel: String? = null,
)

/**
 * One row of the Messages feed for "Select all" and "Invert selection". [isHeld] uses the rule of the list:
 * on hold and not blocked.
 */
data class FeedIdRow(
    val id: Long,
    val isReviewed: Boolean,
    val isHeld: Boolean,
)

/** One app that sent SMS, for the Settings list "Apps that send SMS". */
data class SendingAppRow(
    val packageName: String,
    val lastSentWall: Long,
    val count: Int,
)

/** One bucket of the "by type" block. */
data class ClassCountRow(
    val classId: String,
    val count: Int,
)

/**
 * The four week counters of one time range. [hidden] is every counted message; the other three are subsets of
 * it. Delivery reports and counter-only classes are never in [hidden].
 */
data class WeekCountsRow(
    val hidden: Int,
    val suspicious: Int,
    val onHold: Int,
    val blocked: Int,
)

/** One delivery report that answers a sent message. [deliveryStatus] is `DELIVERED`, `PENDING` or `FAILED`. */
data class DeliveryReportRow(
    val id: Long,
    val receivedAtWall: Long,
    val deliveryStatus: String?,
)

/** The delivery-report facts of one message: the sent message it answers and the status it reports. */
data class ReportFactsRow(
    val relatedMessageId: Long?,
    val deliveryStatus: String?,
    val tpMr: Int?,
)

/**
 * One row of the rule-preview pre-filter: only the facts the engine reads, plus the id and time the
 * preview lists.
 */
data class PreviewRow(
    val id: Long,
    val classId: String,
    val receivedAtWall: Long,
    val direction: String,
    val originator: String?,
    val smsc: String?,
    val destPort: Int?,
    val contentType: String?,
    val slot: Int,
    val textBody: String?,
    /** SCREENS1-X06, the facts a rule condition reads that the row stores since version 3. */
    val subtype: String? = null,
    val cbMessageId: Int? = null,
    val roaming: Boolean? = null,
)

/**
 * Queries over `message`.
 *
 * The feed query is a single static statement with every filter optional, so Room can pre-compile it, PagingSource
 * keeps using the `(receivedAtWall)` index for the order, and no user input is ever concatenated into SQL. The
 * mandatory exclusions live in the SQL, not in callers: counters (`CL-NORMAL`, `CL-MO-VISIBLE`, unless blocked) and
 * self-test rows.
 * Delivery reports (`CL-STATUS`) show by default; only `includeStatus = false` (the user's setting) hides them.
 *
 * One query per feed/detail/home use case: the statements are the unit of work, not a reusable abstraction.
 */
@Suppress("TooManyFunctions")
@Dao
interface MessageDao {
    @Query(
        """
        SELECT m.id, m.eventId, m.receivedAtWall, m.classId, m.severity, m.title, m.originator,
               m.textPreview, m.fate, m.verdict, m.isQuarantined, m.isReviewed, m.isStarred,
               m.direction, m.subId, m.slot, m.carrierName, m.partsHave, m.partsTotal, m.consumerPackage,
               pl.label AS consumerPackageLabel, m.isReplay,
               CASE WHEN m.isQuarantined = 1 OR m.classId = 'CL-STATUS' THEN 1
                    ELSE (SELECT COUNT(*) FROM message AS e WHERE """ + SAME_BURST + """) END AS repeatCount,
               m.deliveryStatus, m.relatedMessageId, m.debugInjected, m.destination, m.callingPackage,
               m.ackResult, cpl.label AS callingPackageLabel
        FROM message AS m
        LEFT JOIN package_label AS pl ON pl.packageName = m.consumerPackage
        LEFT JOIN package_label AS cpl ON cpl.packageName = m.callingPackage
        WHERE m.isSelfTest = 0
          AND """ + FEED_CLASS_M + """
          """ + NOT_MERGED_AWAY + """
          AND (:includeStatus = 1 OR m.classId <> 'CL-STATUS')
          AND (:classCount = 0 OR m.classId IN (:classIds))
          AND (:severityCount = 0 OR m.severity IN (:severities))
          AND (:fateCount = 0 OR m.fate IN (:fates))
          AND (:attentionOnly = 0 OR (m.isReviewed = 0 AND (m.isQuarantined = 1 OR m.severity IN (:attentionSeverity))))
          AND (:quarantinedOnly = 0 OR m.isQuarantined = 1)
          AND (:unreviewedOnly = 0 OR m.isReviewed = 0)
          AND (:starredOnly = 0 OR m.isStarred = 1)
          AND (:direction IS NULL OR m.direction = :direction)
          AND (:fromWall IS NULL OR m.receivedAtWall >= :fromWall)
          AND (:toWall IS NULL OR m.receivedAtWall < :toWall)
          AND (:subId IS NULL OR m.subId = :subId)
          AND (:slot IS NULL OR m.slot = :slot)
          AND (:searchLike IS NULL
               OR m.textPreview LIKE :searchLike ESCAPE '\'
               OR m.contentType LIKE :searchLike ESCAPE '\'
               OR pl.label LIKE :searchLike ESCAPE '\'
               OR m.originatorNorm LIKE :searchNormLike ESCAPE '\'
               OR m.destPort = :searchPort)
          AND (:originatorLike IS NULL OR m.originatorNorm LIKE :originatorLike ESCAPE '\')
          AND (:port IS NULL OR m.destPort = :port)
          AND (:contentTypeLike IS NULL OR m.contentType LIKE :contentTypeLike ESCAPE '\')
          AND (:packageLike IS NULL OR pl.label LIKE :packageLike ESCAPE '\'
               OR m.consumerPackage LIKE :packageLike ESCAPE '\' OR m.callingPackage LIKE :packageLike ESCAPE '\')
        ORDER BY m.receivedAtWall DESC, m.id DESC
        """,
    )
    // One parameter per optional feed filter; each is bound by name in the SQL above, so a bag type would not shorten it.
    @Suppress("LongParameterList")
    fun feed(
        includeStatus: Boolean,
        classCount: Int,
        classIds: List<String>,
        severityCount: Int,
        severities: List<String>,
        fateCount: Int,
        fates: List<String>,
        attentionOnly: Boolean,
        attentionSeverity: List<String>,
        quarantinedOnly: Boolean,
        unreviewedOnly: Boolean,
        starredOnly: Boolean,
        direction: String?,
        fromWall: Long?,
        toWall: Long?,
        subId: Int?,
        slot: Int?,
        searchLike: String?,
        searchNormLike: String?,
        searchPort: Int?,
        originatorLike: String?,
        port: Int?,
        contentTypeLike: String?,
        packageLike: String?,
    ): PagingSource<Int, MessageRow>

    @Query(
        """
        SELECT COUNT(*) FROM message AS m
        LEFT JOIN package_label AS pl ON pl.packageName = m.consumerPackage
        WHERE m.isSelfTest = 0
          AND """ + FEED_CLASS_M + """
          """ + NOT_MERGED_AWAY + """
          AND (:includeStatus = 1 OR m.classId <> 'CL-STATUS')
          AND (:classCount = 0 OR m.classId IN (:classIds))
          AND (:severityCount = 0 OR m.severity IN (:severities))
          AND (:fateCount = 0 OR m.fate IN (:fates))
          AND (:attentionOnly = 0 OR (m.isReviewed = 0 AND (m.isQuarantined = 1 OR m.severity IN (:attentionSeverity))))
          AND (:quarantinedOnly = 0 OR m.isQuarantined = 1)
          AND (:unreviewedOnly = 0 OR m.isReviewed = 0)
          AND (:starredOnly = 0 OR m.isStarred = 1)
          AND (:direction IS NULL OR m.direction = :direction)
          AND (:fromWall IS NULL OR m.receivedAtWall >= :fromWall)
          AND (:toWall IS NULL OR m.receivedAtWall < :toWall)
          AND (:subId IS NULL OR m.subId = :subId)
          AND (:slot IS NULL OR m.slot = :slot)
          AND (:searchLike IS NULL
               OR m.textPreview LIKE :searchLike ESCAPE '\'
               OR m.contentType LIKE :searchLike ESCAPE '\'
               OR pl.label LIKE :searchLike ESCAPE '\'
               OR m.originatorNorm LIKE :searchNormLike ESCAPE '\'
               OR m.destPort = :searchPort)
          AND (:originatorLike IS NULL OR m.originatorNorm LIKE :originatorLike ESCAPE '\')
          AND (:port IS NULL OR m.destPort = :port)
          AND (:contentTypeLike IS NULL OR m.contentType LIKE :contentTypeLike ESCAPE '\')
          AND (:packageLike IS NULL OR pl.label LIKE :packageLike ESCAPE '\'
               OR m.consumerPackage LIKE :packageLike ESCAPE '\' OR m.callingPackage LIKE :packageLike ESCAPE '\')
        """,
    )
    // Same filter set as [feed]; the count query needs every bound parameter too.
    @Suppress("LongParameterList")
    fun observeCountFeed(
        includeStatus: Boolean,
        classCount: Int,
        classIds: List<String>,
        severityCount: Int,
        severities: List<String>,
        fateCount: Int,
        fates: List<String>,
        attentionOnly: Boolean,
        attentionSeverity: List<String>,
        quarantinedOnly: Boolean,
        unreviewedOnly: Boolean,
        starredOnly: Boolean,
        direction: String?,
        fromWall: Long?,
        toWall: Long?,
        subId: Int?,
        slot: Int?,
        searchLike: String?,
        searchNormLike: String?,
        searchPort: Int?,
        originatorLike: String?,
        port: Int?,
        contentTypeLike: String?,
        packageLike: String?,
    ): Flow<Int>

    /**
     * The ids of every row of [feed] for the same filters, not only of the loaded pages, so "Select all"
     * selects the full list. Same WHERE as [feed] and [observeCountFeed], so the ids agree with the count line.
     */
    @Query(
        """
        SELECT m.id, m.isReviewed, (m.isQuarantined = 1 AND m.fate <> 'FT-BLOCKED') AS isHeld
        FROM message AS m
        LEFT JOIN package_label AS pl ON pl.packageName = m.consumerPackage
        WHERE m.isSelfTest = 0
          AND """ + FEED_CLASS_M + """
          """ + NOT_MERGED_AWAY + """
          AND (:includeStatus = 1 OR m.classId <> 'CL-STATUS')
          AND (:classCount = 0 OR m.classId IN (:classIds))
          AND (:severityCount = 0 OR m.severity IN (:severities))
          AND (:fateCount = 0 OR m.fate IN (:fates))
          AND (:attentionOnly = 0 OR (m.isReviewed = 0 AND (m.isQuarantined = 1 OR m.severity IN (:attentionSeverity))))
          AND (:quarantinedOnly = 0 OR m.isQuarantined = 1)
          AND (:unreviewedOnly = 0 OR m.isReviewed = 0)
          AND (:starredOnly = 0 OR m.isStarred = 1)
          AND (:direction IS NULL OR m.direction = :direction)
          AND (:fromWall IS NULL OR m.receivedAtWall >= :fromWall)
          AND (:toWall IS NULL OR m.receivedAtWall < :toWall)
          AND (:subId IS NULL OR m.subId = :subId)
          AND (:slot IS NULL OR m.slot = :slot)
          AND (:searchLike IS NULL
               OR m.textPreview LIKE :searchLike ESCAPE '\'
               OR m.contentType LIKE :searchLike ESCAPE '\'
               OR pl.label LIKE :searchLike ESCAPE '\'
               OR m.originatorNorm LIKE :searchNormLike ESCAPE '\'
               OR m.destPort = :searchPort)
          AND (:originatorLike IS NULL OR m.originatorNorm LIKE :originatorLike ESCAPE '\')
          AND (:port IS NULL OR m.destPort = :port)
          AND (:contentTypeLike IS NULL OR m.contentType LIKE :contentTypeLike ESCAPE '\')
          AND (:packageLike IS NULL OR pl.label LIKE :packageLike ESCAPE '\'
               OR m.consumerPackage LIKE :packageLike ESCAPE '\' OR m.callingPackage LIKE :packageLike ESCAPE '\')
        ORDER BY m.receivedAtWall DESC, m.id DESC
        """,
    )
    // Same filter set as [feed]; the ids query needs every bound parameter too.
    @Suppress("LongParameterList")
    suspend fun feedIds(
        includeStatus: Boolean,
        classCount: Int,
        classIds: List<String>,
        severityCount: Int,
        severities: List<String>,
        fateCount: Int,
        fates: List<String>,
        attentionOnly: Boolean,
        attentionSeverity: List<String>,
        quarantinedOnly: Boolean,
        unreviewedOnly: Boolean,
        starredOnly: Boolean,
        direction: String?,
        fromWall: Long?,
        toWall: Long?,
        subId: Int?,
        slot: Int?,
        searchLike: String?,
        searchNormLike: String?,
        searchPort: Int?,
        originatorLike: String?,
        port: Int?,
        contentTypeLike: String?,
        packageLike: String?,
    ): List<FeedIdRow>

    /**
     * The SQL pre-filter of the rule preview.
     *
     * The window, the selected classes and the slot are decided here so the evaluator only sees rows that can match;
     * everything else (sender, port, content type, text, roaming) is a rule condition and stays in the engine. The
     * `(classId, receivedAtWall)` and `(receivedAtWall)` indices serve the filter and the order.
     *
     * Self-test rows are excluded: they are our own canaries, not messages the user received.
     */
    @Query(
        """
        SELECT id, classId, receivedAtWall, direction, originator, smsc, destPort, contentType, slot, textBody,
               subtype, cbMessageId, roaming
        FROM message
        WHERE receivedAtWall >= :sinceWall
          AND isSelfTest = 0
          AND (:classCount = 0 OR classId IN (:classIds))
          AND (:slot IS NULL OR slot = :slot)
        ORDER BY receivedAtWall DESC, id DESC
        """,
    )
    suspend fun previewCandidates(
        sinceWall: Long,
        classCount: Int,
        classIds: List<String>,
        slot: Int?,
    ): List<PreviewRow>

    /**
     * The sent messages a delivery report can answer. A report carries the TP-MR of the message it answers; the
     * caller compares the destination, because the number format of the report and the request can differ.
     */
    @Query(
        """
        SELECT * FROM message
        WHERE direction = :direction AND tpMr = :tpMr AND isSelfTest = 0
          AND receivedAtWall >= :fromWall AND receivedAtWall <= :toWall
        ORDER BY receivedAtWall DESC, id DESC
        """,
    )
    suspend fun sentWithReference(
        direction: String,
        tpMr: Int,
        fromWall: Long,
        toWall: Long,
    ): List<MessageEntity>

    /** Stores what ingest decoded from a delivery report and the link to its sent message. */
    @Query(
        """
        UPDATE message
        SET relatedMessageId = :relatedMessageId, tpMr = :tpMr, deliveryStatus = :deliveryStatus,
            destinationNorm = :destinationNorm
        WHERE id = :id
        """,
    )
    suspend fun setReportFacts(
        id: Long,
        relatedMessageId: Long?,
        tpMr: Int?,
        deliveryStatus: String?,
        destinationNorm: String?,
    ): Int

    @Query("SELECT COUNT(*) FROM message WHERE isQuarantined = 1 AND isSelfTest = 0")
    fun observeQuarantinedCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM message
        WHERE isReviewed = 0 AND isSelfTest = 0 AND isQuarantined = 0
          AND classId <> 'CL-STATUS' AND """ + FEED_CLASS + """
        """,
    )
    fun observeUnreviewedCount(): Flow<Int>

    /**
     * The week counters for [fromWall] (inclusive) up to [toWall] (exclusive). The caller passes the day limits, so the
     * device time zone stays out of SQL and tests can fix it. Every counter excludes CL-STATUS (delivery reports), and
     * the counter-only classes CL-NORMAL and CL-MO-VISIBLE unless the row was blocked.
     */
    @Query(
        """
        SELECT COUNT(*) AS hidden,
               COALESCE(SUM(CASE WHEN severity IN (:suspiciousSeverity) THEN 1 ELSE 0 END), 0) AS suspicious,
               COALESCE(SUM(CASE WHEN isQuarantined = 1 THEN 1 ELSE 0 END), 0) AS onHold,
               COALESCE(SUM(CASE WHEN fate = :blockedFate THEN 1 ELSE 0 END), 0) AS blocked
        FROM message
        WHERE isSelfTest = 0 AND receivedAtWall >= :fromWall AND receivedAtWall < :toWall
          AND classId <> 'CL-STATUS' AND """ + FEED_CLASS + """
        """,
    )
    fun weekCountsBetween(
        fromWall: Long,
        toWall: Long,
        suspiciousSeverity: List<String>,
        blockedFate: String,
    ): Flow<WeekCountsRow>

    /** Count per class for the same range as [weekCountsBetween], most frequent first. */
    @Query(
        """
        SELECT classId, COUNT(*) AS count
        FROM message
        WHERE isSelfTest = 0 AND receivedAtWall >= :fromWall AND receivedAtWall < :toWall
          AND classId <> 'CL-STATUS' AND """ + FEED_CLASS + """
        GROUP BY classId
        ORDER BY count DESC
        """,
    )
    fun classCountsBetween(
        fromWall: Long,
        toWall: Long,
    ): Flow<List<ClassCountRow>>

    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun find(id: Long): MessageEntity?

    @Query("SELECT * FROM message WHERE id = :id")
    fun observe(id: Long): Flow<MessageEntity?>

    /** The delivery reports linked to the sent message [messageId], oldest first. */
    @Query(
        """
        SELECT id, receivedAtWall, deliveryStatus FROM message
        WHERE relatedMessageId = :messageId AND isSelfTest = 0
        ORDER BY receivedAtWall ASC, id ASC
        """,
    )
    fun observeDeliveryReports(messageId: Long): Flow<List<DeliveryReportRow>>

    /** The report facts of one message. The flow emits null when the row does not exist. */
    @Query("SELECT relatedMessageId, deliveryStatus, tpMr FROM message WHERE id = :id")
    fun observeReportFacts(id: Long): Flow<ReportFactsRow?>

    @Query("SELECT * FROM message WHERE eventId = :eventId")
    suspend fun findByEventId(eventId: String): MessageEntity?

    /** The newest rows of every class, self-test rows included (debug dump). */
    @Query("SELECT * FROM message ORDER BY receivedAtWall DESC, id DESC LIMIT :limit")
    suspend fun newest(limit: Int): List<MessageEntity>

    /** A released copy arrived, so the original is no longer on hold. */
    @Query("UPDATE message SET isQuarantined = 0 WHERE id = :id")
    suspend fun clearQuarantine(id: Long): Int

    @Query(
        """
        SELECT * FROM message
        WHERE classId = :classId AND receivedAtWall >= :sinceWall AND isSelfTest = 0
        ORDER BY receivedAtWall DESC LIMIT :limit
        """,
    )
    suspend fun recentForDetector(
        classId: String,
        sinceWall: Long,
        limit: Int,
    ): List<MessageEntity>

    /**
     * Detector history of one class in `[sinceWall, untilWall]`. The end bound is in SQL, so the limit
     * counts only rows in the window. Replayed rows and released copies are not new traffic and are left out.
     */
    @Query(
        """
        SELECT * FROM message
        WHERE classId = :classId AND receivedAtWall >= :sinceWall AND receivedAtWall <= :untilWall
          AND isSelfTest = 0 AND isReplay = 0 AND releasedFromId IS NULL AND releaseOf IS NULL
        ORDER BY receivedAtWall DESC LIMIT :limit
        """,
    )
    suspend fun historyForDetector(
        classId: String,
        sinceWall: Long,
        untilWall: Long,
        limit: Int,
    ): List<MessageEntity>

    /** DOMAIN-X09: the same window rules as [historyForDetector], for one normalised originator. */
    @Query(
        """
        SELECT * FROM message
        WHERE originatorNorm = :originatorNorm AND receivedAtWall <= :untilWall
          AND isSelfTest = 0 AND isReplay = 0 AND releasedFromId IS NULL AND releaseOf IS NULL
        ORDER BY receivedAtWall DESC LIMIT :limit
        """,
    )
    suspend fun historyFromOriginator(
        originatorNorm: String,
        untilWall: Long,
        limit: Int,
    ): List<MessageEntity>

    /**
     * True when a sent row with [destinationNorm] exists before [beforeWall]. Leaves out self-test,
     * replay rows and released copies. [direction] is `Direction.OUT.wire()`; delivery reports also carry a
     * destinationNorm and must not count.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM message
            WHERE direction = :direction AND destinationNorm = :destinationNorm AND receivedAtWall < :beforeWall
              AND isSelfTest = 0 AND isReplay = 0 AND releasedFromId IS NULL AND releaseOf IS NULL)
        """,
    )
    suspend fun destinationSentBefore(
        direction: String,
        destinationNorm: String,
        beforeWall: Long,
    ): Boolean

    /**
     * True when a row of [classIds] sent by [packageName] exists before [beforeWall]. A row without a calling
     * package counts as [internalPackage] (the phone process itself).
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM message
            WHERE classId IN (:classIds) AND COALESCE(callingPackage, :internalPackage) = :packageName
              AND receivedAtWall < :beforeWall
              AND isSelfTest = 0 AND isReplay = 0 AND releasedFromId IS NULL AND releaseOf IS NULL)
        """,
    )
    suspend fun packageSentBefore(
        classIds: List<String>,
        packageName: String,
        internalPackage: String,
        beforeWall: Long,
    ): Boolean

    /**
     * Every package that sent SMS, newest first. [direction] is `Direction.OUT.wire()`. Rows without a calling
     * package (the phone process itself) are left out, as are self-test and replay rows and released copies.
     */
    @Query(
        """
        SELECT callingPackage AS packageName, MAX(receivedAtWall) AS lastSentWall, COUNT(*) AS count
        FROM message
        WHERE direction = :direction AND callingPackage IS NOT NULL AND callingPackage <> ''
          AND isSelfTest = 0 AND isReplay = 0 AND releasedFromId IS NULL AND releaseOf IS NULL
        GROUP BY callingPackage
        ORDER BY lastSentWall DESC, packageName ASC
        """,
    )
    fun observeSendingApps(direction: String): Flow<List<SendingAppRow>>

    @Query("SELECT id FROM message WHERE needsProcessing = 1 ORDER BY id ASC LIMIT :limit")
    suspend fun pendingProcessing(limit: Int): List<Long>

    @Query("SELECT * FROM message WHERE id IN (:ids)")
    suspend fun findMany(ids: List<Long>): List<MessageEntity>

    @Query("SELECT * FROM message WHERE releasedFromId = :id ORDER BY receivedAtWall ASC")
    suspend fun releasedFrom(id: Long): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: MessageEntity): Long

    @Update suspend fun update(entity: MessageEntity)

    // A merged feed row stands for every message of the same kind from the same sender in that minute, so reviewing it
    // reviews them all; otherwise the count would come straight back as "unread". The merge rule is SAME_BURST of the
    // feed: held messages and delivery reports are never merged, so they are reviewed one by one.
    @Query(
        """
        UPDATE message SET isReviewed = :reviewed
        WHERE id IN (:ids)
           OR (isSelfTest = 0 AND isQuarantined = 0 AND classId <> 'CL-STATUS' AND EXISTS (
                SELECT 1 FROM message AS m WHERE m.id IN (:ids) AND m.isQuarantined = 0
                  AND m.classId = message.classId AND m.direction = message.direction
                  AND COALESCE(m.originator, '') = COALESCE(message.originator, '')
                  AND m.receivedAtWall / 60000 = message.receivedAtWall / 60000))
        """,
    )
    suspend fun setReviewed(
        ids: List<Long>,
        reviewed: Boolean,
    ): Int

    @Query("UPDATE message SET isStarred = :starred WHERE id IN (:ids)")
    suspend fun setStarred(
        ids: List<Long>,
        starred: Boolean,
    ): Int

    @Query("UPDATE message SET isQuarantined = :quarantined, fate = :fate WHERE id IN (:ids)")
    suspend fun setQuarantined(
        ids: List<Long>,
        quarantined: Boolean,
        fate: String,
    ): Int

    /** Blocks a message that is on hold ([held] fate). Returns 0 when the message is no longer on hold. */
    @Query("UPDATE message SET isQuarantined = 0, fate = :blocked WHERE id = :id AND fate = :held")
    suspend fun blockIfHeld(
        id: Long,
        held: String,
        blocked: String,
    ): Int

    @Query("UPDATE message SET severity = :severity, severityOverridden = :overridden WHERE id = :id")
    suspend fun setSeverity(
        id: Long,
        severity: String,
        overridden: Boolean,
    ): Int

    /** Also records the revision that was processed, so a later revision knows it is not the first pass. */
    @Query("UPDATE message SET needsProcessing = 0, processedRevision = revision WHERE id IN (:ids)")
    suspend fun markProcessed(ids: List<Long>): Int

    @Query("DELETE FROM message WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("DELETE FROM message")
    suspend fun deleteAll(): Int

    /** Retention candidates: not quarantined and not starred. */
    @Query("SELECT COUNT(*) FROM message WHERE receivedAtWall < :cutoff AND isQuarantined = 0 AND isStarred = 0")
    suspend fun countPurgeable(cutoff: Long): Int

    /** Repairs titles stored by builds that kept the raw `{port}`/`{contentType}`/`{channel}` template. */
    @Query(
        """
        UPDATE message SET title = replace(replace(replace(title,
            '{port}', COALESCE(CAST(destPort AS TEXT), '?')),
            '{contentType}', COALESCE(contentType, '?')),
            ' {channel}', '')
        WHERE title LIKE '%{%}%'
        """,
    )
    suspend fun repairPlaceholderTitles(): Int

    /**
     * Gives each row stored as FT-UNKNOWN (before) the true fate it shows, with the rules of
     * [dev.nightjar.app.domain.ingest.TrueFate]. Returns the number of rows changed.
     */
    @Query(
        """
        UPDATE message SET fate = CASE
            WHEN direction = :outbound AND fateDetail LIKE :noDispatchLike THEN :notSent
            WHEN direction = :outbound THEN :sendUnconfirmed
            WHEN ackResult IS NULL AND isReplay = 1 THEN :accepted
            WHEN ackResult IS NULL THEN :notDispatched
            WHEN ackResult = :outOfMemory THEN :nackStorage
            WHEN ackResult IN (:ok, :handled) THEN :accepted
            ELSE :rejected
        END
        WHERE fate = :unknown
        """,
    )
    @Suppress("LongParameterList")
    suspend fun repairUnknownFates(
        unknown: String,
        outbound: String,
        noDispatchLike: String,
        notSent: String,
        sendUnconfirmed: String,
        accepted: String,
        notDispatched: String,
        nackStorage: String,
        rejected: String,
        outOfMemory: Int,
        ok: Int,
        handled: Int,
    ): Int

    @Query("DELETE FROM message WHERE receivedAtWall < :cutoff AND isQuarantined = 0 AND isStarred = 0")
    suspend fun deletePurgeable(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM message")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM message WHERE isSelfTest = 0")
    suspend fun countCaptured(): Int
}
