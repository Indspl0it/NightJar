package dev.nightjar.app.feature.export

import dev.nightjar.app.data.db.MessageEntity
import dev.nightjar.app.data.db.PduEntity
import dev.nightjar.app.data.db.StringListColumn
import dev.nightjar.app.data.db.TraceStepEntity

/** One PDU in an export. [decodedJson] is filled only for JSON with `includeTree`; the writer decides what it shows. */
data class ExportPdu(
    val partNo: Int,
    val format: String,
    val hex: String,
    val decodedJson: String? = null,
)

data class ExportTrace(
    val seq: Int,
    val stage: String,
    val tNs: Long,
    val detail: String?,
)

/**
 * The export projection of one message.
 *
 * Plain values only, so the writers are pure JVM code and the mapping from Room lives in one place
 * ([toExportMessage]). Normal SMS bodies are not in the database at all, so no option can make them appear.
 */
data class ExportMessage(
    val id: Long,
    val eventId: String,
    val revision: Int,
    val receivedAtWall: Long,
    val direction: String,
    val transport: String,
    val source: Int,
    val subId: Int,
    val slot: Int,
    val carrierName: String?,
    val classId: String,
    val severity: String,
    val detectorSeverity: String?,
    val severityOverridden: Boolean,
    val title: String,
    val originator: String?,
    val smsc: String?,
    val destPort: Int?,
    val contentType: String?,
    val textBody: String?,
    val textPreview: String,
    val verdict: String,
    val ruleId: Long?,
    val fate: String,
    val fateDetail: String?,
    val consumerPackage: String?,
    val callingPackage: String?,
    val persistFlag: Boolean?,
    val concatRef: Int?,
    val partsTotal: Int,
    val partsHave: Int,
    val isQuarantined: Boolean,
    val isReviewed: Boolean,
    val isStarred: Boolean,
    val isReplay: Boolean,
    val buildFingerprint: String?,
    val payloadVersion: String?,
    val pdus: List<ExportPdu>,
    val trace: List<ExportTrace>,
    /** IPC version 2 fields: null and empty for a message from an older module. */
    val subtype: String? = null,
    val extraSubtypes: List<String> = emptyList(),
    val anomalies: List<String> = emptyList(),
)

internal fun MessageEntity.toExportMessage(
    pdus: List<PduEntity>,
    trace: List<TraceStepEntity>,
    includeTree: Boolean,
): ExportMessage =
    ExportMessage(
        id = id,
        eventId = eventId,
        revision = revision,
        receivedAtWall = receivedAtWall,
        direction = direction,
        transport = transport,
        source = source,
        subId = subId,
        slot = slot,
        carrierName = carrierName,
        classId = classId,
        severity = severity,
        detectorSeverity = detectorSeverity,
        severityOverridden = severityOverridden,
        title = title,
        originator = originator,
        smsc = smsc,
        destPort = destPort,
        contentType = contentType,
        textBody = textBody,
        textPreview = textPreview,
        verdict = verdict,
        ruleId = ruleId,
        fate = fate,
        fateDetail = fateDetail,
        consumerPackage = consumerPackage,
        callingPackage = callingPackage,
        persistFlag = persistFlag,
        concatRef = concatRef,
        partsTotal = partsTotal,
        partsHave = partsHave,
        isQuarantined = isQuarantined,
        isReviewed = isReviewed,
        isStarred = isStarred,
        isReplay = isReplay,
        buildFingerprint = buildFingerprint,
        payloadVersion = payloadVersion,
        pdus =
            pdus.sortedBy { it.partNo }.map { pdu ->
                ExportPdu(
                    partNo = pdu.partNo,
                    format = pdu.format,
                    hex = pdu.bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) },
                    decodedJson =
                        if (includeTree) {
                            runCatching {
                                dev.nightjar.decoder.FieldJson.toJson(
                                    dev.nightjar.decoder.Decoders
                                        .decodeBytes(dev.nightjar.decoder.Decoders.TPDU, pdu.bytes),
                                )
                            }.getOrNull()
                        } else {
                            null
                        },
                )
            },
        trace =
            trace.sortedBy { it.seq }.map {
                ExportTrace(it.seq, it.stage, it.tNanos, it.detail)
            },
        subtype = subtype,
        extraSubtypes = StringListColumn.decode(extraSubtypes),
        anomalies = StringListColumn.decode(anomalies),
    )
