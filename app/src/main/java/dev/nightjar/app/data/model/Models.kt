package dev.nightjar.app.data.model

import dev.nightjar.app.data.db.AuditEntity
import dev.nightjar.app.data.db.CoverageEntity
import dev.nightjar.app.data.db.DetectionEntity
import dev.nightjar.app.data.db.HookStatusEntity
import dev.nightjar.app.data.db.KnownOriginatorEntity
import dev.nightjar.app.data.db.MessageEntity
import dev.nightjar.app.data.db.MessageRow
import dev.nightjar.app.data.db.MuteEntity
import dev.nightjar.app.data.db.PduEntity
import dev.nightjar.app.data.db.RuleEntity
import dev.nightjar.app.data.db.SavedViewEntity
import dev.nightjar.app.data.db.SelfTestRunEntity
import dev.nightjar.app.data.db.TraceStepEntity
import dev.nightjar.app.data.db.TrustedPackageEntity
import dev.nightjar.app.domain.ingest.TrueFate
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.HookState
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Transport
import dev.nightjar.contract.Verdict
import dev.nightjar.contract.Wire

/*
 * Typed view of the storage layer. Entities keep wire strings (stable schema); these models expose the :contract
 * enums, which is what the UI and the classification layer consume.
 */

/** Parses a stored wire value; [fallback] keeps a corrupt or forward-compatible row readable instead of crashing. */
internal fun <E> parseWire(type: Class<E>, wire: String?, fallback: E): E where E : Enum<E>, E : dev.nightjar.contract.WireEnum =
    Wire.parse(type, wire, fallback)

/** The feed row (01): everything a list item needs, nothing that costs a BLOB read. */
data class MessageListItem(
    val id: Long,
    val eventId: String,
    val receivedAtWall: Long,
    val classId: ClassId,
    val severity: Severity,
    val title: String,
    val originator: String?,
    val textPreview: String,
    val fate: Fate,
    val verdict: Verdict,
    val isQuarantined: Boolean,
    val isReviewed: Boolean,
    val isStarred: Boolean,
    val direction: Direction,
    val subId: Int,
    /** SIM slot the message arrived on (0-based); -1 when the payload did not report one. */
    val slot: Int,
    /** Operator name of the SIM the message arrived on; null when unknown. */
    val carrierName: String? = null,
    val partsHave: Int,
    val partsTotal: Int,
    val consumerPackage: String?,
    val consumerPackageLabel: String?,
    val isReplay: Boolean,
    /** How many messages of this kind from this sender arrived in the same minute; 1 when it stands alone. */
    val repeatCount: Int = 1,
    /** Delivery report: `DELIVERED`, `PENDING` or `FAILED`; null for every other row. */
    val deliveryStatus: String? = null,
    /** Delivery report: the id of the sent message it reports on; null when none matched. */
    val relatedMessageId: Long? = null,
    /** Legacy flag of a removed debug injector. Always false in current builds. */
    val debugInjected: Boolean = false,
    /** The recipient of a sent message (raw, mask it for display like [originator]); null for a received one. */
    val destination: String? = null,
    /** The package of the app that sent the message; null for a received one or when the payload saw none. */
    val callingPackage: String? = null,
    /** The app label of [callingPackage] from the label cache; null when the cache has none. */
    val callingPackageLabel: String? = null,
)

/** A PDU as stored. */
data class Pdu(
    val partNo: Int,
    val bytes: ByteArray,
    val format: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Pdu) return false
        return partNo == other.partNo && format == other.format && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * (31 * partNo + format.hashCode()) + bytes.contentHashCode()
}

/** One pipeline stage (Trace tab). */
data class TraceStep(
    val seq: Int,
    val stage: String,
    val tNanos: Long,
    val detail: String?,
)

/** A detector finding (DET-*). */
data class Detection(
    val id: Long,
    val detectorId: String,
    val mergeKey: String?,
    val severity: Severity,
    val summary: String,
    val firstAt: Long,
    val lastAt: Long,
    val count: Int,
    val isReviewed: Boolean,
    val isOpen: Boolean,
)

/** Full message for Detail (01, 08). */
data class MessageDetail(
    val id: Long,
    val eventId: String,
    val revision: Int,
    val direction: Direction,
    val transport: Transport,
    val source: Int,
    val subId: Int,
    val slot: Int,
    val carrierName: String?,
    val classId: ClassId,
    val severity: Severity,
    val detectorSeverity: Severity?,
    val severityOverridden: Boolean,
    val title: String,
    val originator: String?,
    val smsc: String?,
    val destPort: Int?,
    val contentType: String?,
    val textBody: String?,
    val textPreview: String,
    val receivedAtWall: Long,
    val receivedAtElapsedNs: Long,
    val modemTs: Long?,
    val ackResult: Int?,
    val verdict: Verdict,
    val ruleId: Long?,
    val fate: Fate,
    val fateDetail: String?,
    val consumerPackage: String?,
    val callingPackage: String?,
    val persistFlag: Boolean?,
    val concatRef: Int?,
    val partsTotal: Int,
    val partsHave: Int,
    val parts: List<PartArrival>,
    val purgeAt: Long?,
    val isQuarantined: Boolean,
    val isReviewed: Boolean,
    val isStarred: Boolean,
    val isReplay: Boolean,
    val isSelfTest: Boolean,
    val releasedFromId: Long?,
    val buildFingerprint: String?,
    val payloadVersion: String?,
    val pdus: List<Pdu>,
    val trace: List<TraceStep>,
    /** The quarantine record this message was released from, and messages released from this one (08). */
    val releasedFrom: MessageLink?,
    val releasedTo: List<MessageLink>,
    /** Sibling messages of the same burst detection (08). */
    val siblings: List<MessageLink>,
    val detections: List<Detection>,
    /** IPC version 2 fields; see `EventFacts` for the formats. */
    val subtype: String? = null,
    val extraSubtypes: List<String> = emptyList(),
    val anomalies: List<String> = emptyList(),
    /**
     * The rule action before a guard changed it (for example Block clamped to Allow during an emergency call),
     * and why. Both null when no guard changed the action.
     */
    val clampedFrom: Verdict? = null,
    val clampReason: String? = null,
    /**
     * The SIM OTA command header of a CL-SIM-DD message (`cpl`, `chl`, `spi`, `kic`, `kid`, `tar`,
     * `cntr`), in the order the payload sent it, values as text. Empty when the payload sent none. Never secured data.
     */
    val simOtaHeader: Map<String, String> = emptyMap(),
    /** The raw recipient of a sent message; null for a received one. */
    val destination: String? = null,
    /** `env.roaming` and `env.userUnlocked` at capture; null when the payload did not report them. */
    val roaming: Boolean? = null,
    val userUnlocked: Boolean? = null,
    /** The cell broadcast message identifier and serial number; null for other classes. */
    val cbMessageId: Int? = null,
    val cbSerial: Int? = null,
    /** Legacy flag of a removed debug injector. Always false in current builds. */
    val debugInjected: Boolean = false,
)

/** A one-line reference to another message (linked records, siblings). */
data class MessageLink(
    val id: Long,
    val classId: ClassId,
    val severity: Severity,
    val receivedAtWall: Long,
    val title: String,
    val originator: String?,
)

/** One arrived part of a multipart message (07). */
data class PartArrival(
    val seq: Int,
    val tNs: Long,
)

/** A stored rule. */
data class Rule(
    val id: Long,
    val priority: Int,
    val enabled: Boolean,
    val name: String,
    val conditionsJson: String,
    val action: Verdict,
    val origin: String,
    val createdAt: Long,
    val updatedAt: Long,
    val matchCount: Long,
    val lastMatchAt: Long?,
)

/** `EXACT` or `PREFIX`. */
enum class KnownOriginatorKind(val wire: String) {
    EXACT("EXACT"),
    PREFIX("PREFIX"),
    ;

    companion object {
        fun from(wire: String?): KnownOriginatorKind = if (wire == PREFIX.wire) PREFIX else EXACT
    }
}

data class KnownOriginator(
    val id: Long,
    val value: String,
    val kind: KnownOriginatorKind,
    val label: String?,
    val addedAt: Long,
)

data class TrustedPackage(
    val packageName: String,
    val label: String?,
    val addedAt: Long,
)

/** Mute scope. */
enum class MuteScope(val wire: String) {
    ORIGINATOR("ORIGINATOR"),
    CLASS("CLASS"),
    ;

    companion object {
        fun from(wire: String?): MuteScope = if (wire == CLASS.wire) CLASS else ORIGINATOR
    }
}

data class Mute(
    val id: Long,
    val scope: MuteScope,
    val value: String,
    val until: Long,
)

data class Coverage(
    val classId: ClassId,
    val lastSeenFromNetwork: Long?,
    val lastSelfTestAt: Long?,
    val lastSelfTestOk: Boolean?,
)

data class HookStatus(
    val buildFingerprint: String,
    val hookId: String,
    val state: HookState,
    val errorCount: Int,
    val lastError: String?,
    val updatedAt: Long,
)

data class SelfTestRun(
    val runId: String,
    val startedAt: Long,
    val buildFingerprint: String?,
    val payloadVersion: String?,
    val resultsJson: String,
)

data class SavedView(
    val id: Long,
    val name: String,
    val filterJson: String,
)

/** One audit row. */
data class AuditEntry(
    val id: Long,
    val at: Long,
    val actor: String,
    val action: String,
    val target: String?,
    val beforeJson: String?,
    val afterJson: String?,
)

/** Per-class count for the Home "by type" block. */
data class ClassCount(
    val classId: ClassId,
    val count: Int,
)

// ------------------------------------------------------------------ entity → model

internal fun MessageEntity.toListItem(consumerPackageLabel: String? = null): MessageListItem =
    MessageListItem(
        id = id,
        eventId = eventId,
        receivedAtWall = receivedAtWall,
        classId = parseWire(ClassId::class.java, classId, ClassId.NORMAL),
        severity = parseWire(Severity::class.java, severity, Severity.INFO),
        title = title,
        originator = originator,
        textPreview = textPreview,
        // A row stored by an older version can still say FT-UNKNOWN; show the true fate the row shows.
        fate =
            TrueFate.of(
                parseWire(Fate::class.java, fate, Fate.UNKNOWN),
                parseWire(Direction::class.java, direction, Direction.IN),
                ackResult,
                replay = isReplay,
            ),
        verdict = parseWire(Verdict::class.java, verdict, Verdict.ALLOW),
        isQuarantined = isQuarantined,
        isReviewed = isReviewed,
        isStarred = isStarred,
        direction = parseWire(Direction::class.java, direction, Direction.IN),
        subId = subId,
        slot = slot,
        carrierName = carrierName,
        partsHave = partsHave,
        partsTotal = partsTotal,
        consumerPackage = consumerPackage,
        consumerPackageLabel = consumerPackageLabel,
        isReplay = isReplay,
        debugInjected = debugInjected == true,
        destination = destination,
        callingPackage = callingPackage,
    )

internal fun MessageRow.toListItem(): MessageListItem =
    MessageListItem(
        id = id,
        eventId = eventId,
        receivedAtWall = receivedAtWall,
        classId = parseWire(ClassId::class.java, classId, ClassId.NORMAL),
        severity = parseWire(Severity::class.java, severity, Severity.INFO),
        title = title,
        originator = originator,
        textPreview = textPreview,
        // A row stored by an older version can still say FT-UNKNOWN; show the true fate the row shows.
        fate =
            TrueFate.of(
                parseWire(Fate::class.java, fate, Fate.UNKNOWN),
                parseWire(Direction::class.java, direction, Direction.IN),
                ackResult,
                replay = isReplay,
            ),
        verdict = parseWire(Verdict::class.java, verdict, Verdict.ALLOW),
        isQuarantined = isQuarantined,
        isReviewed = isReviewed,
        isStarred = isStarred,
        direction = parseWire(Direction::class.java, direction, Direction.IN),
        subId = subId,
        slot = slot,
        carrierName = carrierName,
        partsHave = partsHave,
        partsTotal = partsTotal,
        consumerPackage = consumerPackage,
        consumerPackageLabel = consumerPackageLabel,
        isReplay = isReplay,
        repeatCount = repeatCount,
        deliveryStatus = deliveryStatus,
        relatedMessageId = relatedMessageId,
        debugInjected = debugInjected == true,
        destination = destination,
        callingPackage = callingPackage,
        callingPackageLabel = callingPackageLabel,
    )

internal fun PduEntity.toModel(): Pdu = Pdu(partNo, bytes, format)

internal fun TraceStepEntity.toModel(): TraceStep = TraceStep(seq, stage, tNanos, detail)

internal fun DetectionEntity.toModel(): Detection =
    Detection(
        id = id,
        detectorId = detectorId,
        mergeKey = mergeKey,
        severity = parseWire(Severity::class.java, severity, Severity.INFO),
        summary = summary,
        firstAt = firstAt,
        lastAt = lastAt,
        count = count,
        isReviewed = isReviewed,
        isOpen = isOpen,
    )

internal fun RuleEntity.toModel(): Rule =
    Rule(
        id = id,
        priority = priority,
        enabled = enabled,
        name = name,
        conditionsJson = conditionsJson,
        action = parseWire(Verdict::class.java, action, Verdict.ALLOW),
        origin = origin,
        createdAt = createdAt,
        updatedAt = updatedAt,
        matchCount = matchCount,
        lastMatchAt = lastMatchAt,
    )

internal fun KnownOriginatorEntity.toModel(): KnownOriginator =
    KnownOriginator(id, value, KnownOriginatorKind.from(kind), label, addedAt)

internal fun TrustedPackageEntity.toModel(): TrustedPackage = TrustedPackage(packageName, label, addedAt)

internal fun MuteEntity.toModel(): Mute = Mute(id, MuteScope.from(scope), value, until)

internal fun CoverageEntity.toModel(): Coverage =
    Coverage(
        classId = parseWire(ClassId::class.java, classId, ClassId.NORMAL),
        lastSeenFromNetwork = lastSeenFromNetwork,
        lastSelfTestAt = lastSelfTestAt,
        lastSelfTestOk = lastSelfTestOk,
    )

internal fun HookStatusEntity.toModel(): HookStatus =
    HookStatus(
        buildFingerprint = buildFingerprint,
        hookId = hookId,
        state = parseWire(HookState::class.java, state, HookState.NOT_APPLICABLE),
        errorCount = errorCount,
        lastError = lastError,
        updatedAt = updatedAt,
    )

internal fun SelfTestRunEntity.toModel(): SelfTestRun =
    SelfTestRun(runId, startedAt, buildFingerprint, payloadVersion, resultsJson)

internal fun SavedViewEntity.toModel(): SavedView = SavedView(id, name, filterJson)

internal fun AuditEntity.toModel(): AuditEntry =
    AuditEntry(id, at, actor, action, target, beforeJson, afterJson)
