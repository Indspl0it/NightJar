package dev.nightjar.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray

/*
 * Room schema of the main database. Enum-valued columns store the :contract wire name (`WireEnum.wire`), so the
 * database never depends on Kotlin enum ordinals and a migration never has to remap integers.
 *
 * Columns added after the first schema:
 *  - message.revision, needsProcessing, partsJson
 *  - message.originatorNorm (indexed: search normalises both sides)
 *  - message.detectorSeverity (re-classification: keeps the detector's contribution on the row)
 *  - detection.detectorId / detection.mergeKey (burst collapsing survives a process restart)
 *  - package_label (: package labels cached in a side table)
 *  - message.subtype, extraSubtypes, anomalies (version 3: the IPC version 2 event fields)
 *  - message.destination, roaming, userUnlocked, cbMessageId, cbSerial, releaseOf, clampedFrom, clampReason,
 *  simOtaHeader (version 3: event fields that the detectors and the detail screen read)
 *  - message.emergencyCallActive and processedRevision, version 3
 *  - message.debugInjected, version 3 (legacy flag of a removed debug injector; always null now)
 */

/**
 * One captured message. `eventId` is unique; a higher [revision] replaces the row's PDUs and
 * trace, an equal revision is a duplicate and is ignored.
 */
@Entity(
    tableName = "message",
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["receivedAtWall"]),
        Index(value = ["classId", "receivedAtWall"]),
        Index(value = ["severity", "isReviewed"]),
        Index(value = ["originator"]),
        Index(value = ["originatorNorm"]),
        Index(value = ["isQuarantined"]),
        Index(value = ["isStarred"]),
        Index(value = ["needsProcessing"]),
        Index(value = ["releasedFromId"]),
        Index(value = ["purgeAt"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val sessionId: String?,
    val revision: Int,
    val direction: String,
    val transport: String,
    val source: Int,
    val subId: Int,
    val slot: Int,
    val carrierName: String?,
    val classId: String,
    val severity: String,
    /** Severity contributed by detectors, or null when no detector touched this message. */
    val detectorSeverity: String?,
    val severityOverridden: Boolean,
    val title: String,
    val originator: String?,
    /** Normalised originator: spaces, `+` and a leading `00` removed. */
    val originatorNorm: String?,
    val smsc: String?,
    val destPort: Int?,
    val contentType: String?,
    val textBody: String?,
    val textPreview: String,
    val receivedAtWall: Long,
    val receivedAtElapsedNs: Long,
    val modemTs: Long?,
    val ackResult: Int?,
    val verdict: String,
    val ruleId: Long?,
    val fate: String,
    /** JSON object from the event's `fateDetail`; null when the event carried none. */
    val fateDetail: String?,
    val consumerPackage: String?,
    val callingPackage: String?,
    val persistFlag: Boolean?,
    val concatRef: Int?,
    val partsTotal: Int,
    val partsHave: Int,
    /** JSON array `[{seq,tNs}]` of arrived parts (07). */
    val partsJson: String?,
    val purgeAt: Long?,
    val isQuarantined: Boolean,
    val isReviewed: Boolean,
    val isStarred: Boolean,
    val isReplay: Boolean,
    val isSelfTest: Boolean,
    /** Ingest committed the row but post-processing (severity, detectors, notifications) has not finished. */
    val needsProcessing: Boolean,
    val releasedFromId: Long?,
    val buildFingerprint: String?,
    val payloadVersion: String?,
    /** Delivery report: the id of the sent message it reports on; null when none matched. */
    val relatedMessageId: Long? = null,
    /** TP-MR: the message reference of a sent message, or the one a delivery report echoes. */
    val tpMr: Int? = null,
    /** Delivery report: `DELIVERED`, `PENDING` or `FAILED`, decoded from TP-ST. */
    val deliveryStatus: String? = null,
    /** Normalised destination of a sent message, or the recipient of a delivery report. */
    val destinationNorm: String? = null,
    /** Subtype code of the final class (`Ipc.EventKey.SUBTYPE`); null when the class has none or the event is v1. */
    val subtype: String? = null,
    /** JSON array of the other rule matches (`Ipc.EventKey.EXTRA_SUBTYPES`); null when there are none. */
    val extraSubtypes: String? = null,
    /** JSON array of `AnomalyCode` wire names (`Ipc.EventKey.ANOMALIES`); null when there are none. */
    val anomalies: String? = null,
    /** The raw TP-DA or recipient as the payload saw it; [destinationNorm] is the normalised copy. */
    val destination: String? = null,
    /** `env.roaming` at capture; null when the payload did not report it. */
    val roaming: Boolean? = null,
    /** `env.userUnlocked` at capture; false means the message arrived before the first unlock. */
    val userUnlocked: Boolean? = null,
    /** CB message identifier (TS 23.041); null for a message that is not a CB page. */
    val cbMessageId: Int? = null,
    /** CB serial number, all 16 bits; null for a message that is not a CB page. */
    val cbSerial: Int? = null,
    /** `flags.releaseOf`: the event id of the quarantined message that this event releases. */
    val releaseOf: String? = null,
    /** `verdict.clampedFrom`: the rule action before a guard clamped it, as a `Verdict` wire name. */
    val clampedFrom: String? = null,
    /** `verdict.clampReason`: why the guard clamped the action. */
    val clampReason: String? = null,
    /** JSON object of `Ipc.EventKey.SIM_OTA_HEADER` (keys in `Ipc.SimOtaKey`); header fields only. */
    val simOtaHeader: String? = null,
    /** `env.emergencyCallActive` at capture; null when the payload did not report it. */
    val emergencyCallActive: Boolean? = null,
    /**
     * The revision that post-processing last finished for; null when the row was never processed. A higher
     * revision keeps it, so the pipeline can skip the additive writes and the alert on a later pass.
     */
    val processedRevision: Int? = null,
    /**
     * Legacy flag of a removed debug injector (`flags.debug`). Always null in current builds; kept so existing
     * databases keep their schema.
     */
    val debugInjected: Boolean? = null,
)

/**
 * A list of short strings in one TEXT column: a JSON array, or null for an empty list. JSON keeps an entry
 * that contains a comma or a colon (a content type, an `<class>:<subtype>` entry) in one piece.
 */
object StringListColumn {
    fun encode(values: List<String>): String? = if (values.isEmpty()) null else JSONArray(values).toString()

    /** A column that is not a JSON array of strings reads as an empty list; a non-string entry is skipped. */
    fun decode(column: String?): List<String> {
        if (column.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(column) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.takeIf { s -> s.isNotEmpty() } }
    }
}

/** One PDU of a message. Deleted with its message. */
@Entity(
    tableName = "pdu",
    primaryKeys = ["messageId", "partNo"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["messageId"])],
)
data class PduEntity(
    val messageId: Long,
    val partNo: Int,
    val bytes: ByteArray,
    val format: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PduEntity) return false
        return messageId == other.messageId && partNo == other.partNo &&
            bytes.contentEquals(other.bytes) && format == other.format
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + partNo
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + format.hashCode()
        return result
    }
}

/** One stage of the payload pipeline. Deleted with its message. */
@Entity(
    tableName = "trace_step",
    primaryKeys = ["messageId", "seq"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["messageId"])],
)
data class TraceStepEntity(
    val messageId: Long,
    val seq: Int,
    val stage: String,
    @ColumnInfo(name = "tNanos") val tNanos: Long,
    val detail: String?,
)

/** A detector finding. `mergeKey` collapses repeated findings into one open row. */
@Entity(
    tableName = "detection",
    indices = [
        Index(value = ["isReviewed", "lastAt"]),
        Index(value = ["detectorId", "mergeKey"]),
    ],
)
data class DetectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The `DetectorId` wire value: `DET-01`…`DET-06` or `PAT-01`…`PAT-09`. */
    val detectorId: String,
    /** Non-null when findings of this kind grow one open detection; null for one-shot findings. */
    val mergeKey: String?,
    val severity: String,
    val summary: String,
    val firstAt: Long,
    val lastAt: Long,
    val count: Int,
    val isReviewed: Boolean,
    val isOpen: Boolean,
)

/** Message ↔ detection link. Deleted with either side. */
@Entity(
    tableName = "detection_member",
    primaryKeys = ["detectionId", "messageId"],
    foreignKeys = [
        ForeignKey(
            entity = DetectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["detectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["messageId"]), Index(value = ["detectionId"])],
)
data class DetectionMemberEntity(
    val detectionId: Long,
    val messageId: Long,
)

/** A firewall rule. Permanent. */
@Entity(tableName = "rule", indices = [Index(value = ["priority"])])
data class RuleEntity(
    @PrimaryKey val id: Long,
    val priority: Int,
    val enabled: Boolean,
    val name: String,
    /** The rule's `when` block, serialised by :rules. */
    val conditionsJson: String,
    val action: String,
    val origin: String,
    val createdAt: Long,
    val updatedAt: Long,
    val matchCount: Long,
    val lastMatchAt: Long?,
)

/** Per-class action. Permanent. */
@Entity(tableName = "class_action")
data class ClassActionEntity(
    @PrimaryKey val classId: String,
    val verdict: String,
    /** `PRESET` or `USER`. */
    val origin: String,
)

/** A sender the user marked as known. Permanent. */
@Entity(
    tableName = "known_originator",
    indices = [Index(value = ["value", "kind"], unique = true)],
)
data class KnownOriginatorEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val value: String,
    /** `EXACT` or `PREFIX`. */
    val kind: String,
    val label: String?,
    val addedAt: Long,
)

/** An app the user trusts. Permanent. */
@Entity(tableName = "trusted_package")
data class TrustedPackageEntity(
    @PrimaryKey val packageName: String,
    val label: String?,
    val addedAt: Long,
)

/** Daily counters for CL-NORMAL / CL-MO-VISIBLE only. Kept 2 years. */
@Entity(tableName = "counter", primaryKeys = ["day", "subId", "direction", "classId"])
data class CounterEntity(
    /** `yyyymmdd`. */
    val day: Int,
    val subId: Int,
    val direction: String,
    val classId: String,
    @ColumnInfo(name = "n") val count: Long,
)

/** Coverage per class. Permanent. */
@Entity(tableName = "coverage")
data class CoverageEntity(
    @PrimaryKey val classId: String,
    val lastSeenFromNetwork: Long?,
    val lastSelfTestAt: Long?,
    val lastSelfTestOk: Boolean?,
)

/**
 * Hook resolution state per build. Only the last 5 fingerprints are kept.
 *
 * `buildFingerprint` is a column name here, not an IPC key, so it is spelled locally.
 */
private const val COL_BUILD_FINGERPRINT = "buildFingerprint" // contract-ok: SQL column name, not an IPC Bundle key

@Entity(tableName = "hook_status", primaryKeys = [COL_BUILD_FINGERPRINT, "hookId"])
data class HookStatusEntity(
    val buildFingerprint: String,
    val hookId: String,
    val state: String,
    val errorCount: Int,
    val lastError: String?,
    val updatedAt: Long,
)

/** One self-test run. Only the last 20 are kept. */
@Entity(tableName = "self_test_run")
data class SelfTestRunEntity(
    @PrimaryKey val runId: String,
    val startedAt: Long,
    val buildFingerprint: String?,
    val payloadVersion: String?,
    val resultsJson: String,
)

/** Audit record. Kept 365 days; the only table a "delete all" leaves behind. */
@Entity(tableName = "audit", indices = [Index(value = ["at"])])
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    /** `USER`, `PRESET` or `SYSTEM`. */
    val actor: String,
    val action: String,
    val target: String?,
    val beforeJson: String?,
    val afterJson: String?,
)

/** A temporary mute. Removed on expiry. */
@Entity(
    tableName = "mute",
    indices = [Index(value = ["scope", "value"], unique = true)],
)
data class MuteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** `ORIGINATOR` or `CLASS`. */
    val scope: String,
    val value: String,
    val until: Long,
)

/** A saved filter. Permanent. */
@Entity(tableName = "saved_view")
data class SavedViewEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val filterJson: String,
)

/** Package label cache; avoids a PackageManager call per row and per search. */
@Entity(tableName = "package_label")
data class PackageLabelEntity(
    @PrimaryKey val packageName: String,
    val label: String?,
    val updatedAt: Long,
)

/**
 * Small key/value store for rule-document metadata (addition): the selected preset and the snapshot revision that
 * go into `rules.json` but are not per-class or per-rule. Keys are [KEY_PRESET] and [KEY_REVISION].
 */
@Entity(tableName = "rule_meta")
data class RuleMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
) {
    companion object {
        const val KEY_PRESET = "preset"
        const val KEY_REVISION = "revision"
    }
}
