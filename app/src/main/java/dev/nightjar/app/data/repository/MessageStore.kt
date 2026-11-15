package dev.nightjar.app.data.repository

import androidx.room.withTransaction
import dev.nightjar.app.data.db.MessageDao
import dev.nightjar.app.data.db.MessageEntity
import dev.nightjar.app.data.db.NightjarDatabase
import dev.nightjar.app.data.db.PduDao
import dev.nightjar.app.data.db.PduEntity
import dev.nightjar.app.data.db.StringListColumn
import dev.nightjar.app.data.db.TraceStepDao
import dev.nightjar.app.data.db.TraceStepEntity
import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.contract.Ipc
import javax.inject.Inject
import javax.inject.Singleton

/** What happened to one event during [MessageStore.upsert]. */
enum class UpsertOutcome {
    /** First time this `eventId` was seen. */
    INSERTED,

    /** A higher revision replaced the row's content; PDUs and trace were replaced. */
    UPDATED,

    /** Same or lower revision: a duplicate. Acknowledged without a write. */
    DUPLICATE,
}

/** Result of one batch upsert. [acked] is what the IPC reply carries. */
data class UpsertResult(
    val acked: List<String>,
    val inserted: Int,
    val updated: Int,
    val duplicates: Int,
    val messageIds: List<Long>,
)

/** The columns of one event that the store persists, already typed and parsed. */
data class IncomingEvent(
    val eventId: String,
    val revision: Int,
    val sessionId: String?,
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
    val receivedAtWall: Long,
    val receivedAtElapsedNs: Long,
    val modemTs: Long?,
    val ackResult: Int?,
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
    val partsJson: String?,
    val purgeAt: Long?,
    val isQuarantined: Boolean,
    val isReviewed: Boolean,
    val isStarred: Boolean,
    val isReplay: Boolean,
    val isSelfTest: Boolean,
    val releasedFromId: Long?,
    val buildFingerprint: String?,
    val payloadVersion: String?,
    val pdus: List<PduPayload>,
    val trace: List<TracePayload>,
    /**
     * Self-test correlation: the run the payload attributed this event to and the class its canary expected.
     * Not persisted — the `message` table has no column for either; the evaluation reads them at ingest.
     */
    val runId: String? = null,
    val canaryClass: String? = null,
    /** Sent message: the TP-MR the payload saw at the result stage. Null for every other row. */
    val tpMr: Int? = null,
    /** Sent message: normalised destination. Null for every other row. */
    val destinationNorm: String? = null,
    /** Subtype code of the final class; null for a class without one and for an IPC version 1 event. */
    val subtype: String? = null,
    /** The other rule matches in the [EventFacts.extraSubtypes] format, in rule order. */
    val extraSubtypes: List<String> = emptyList(),
    /** `AnomalyCode` wire names, in parser order. */
    val anomalies: List<String> = emptyList(),
    /** Columns; see [MessageEntity] for each one. Null when the event did not carry the field. */
    val destination: String? = null,
    val roaming: Boolean? = null,
    val userUnlocked: Boolean? = null,
    val cbMessageId: Int? = null,
    val cbSerial: Int? = null,
    /** `flags.releaseOf`: the event id of the original that this event releases. */
    val releaseOf: String? = null,
    val clampedFrom: String? = null,
    val clampReason: String? = null,
    val simOtaHeader: String? = null,
    val emergencyCallActive: Boolean? = null,
    /** `flags.debug`: legacy flag of a removed debug injector. Always null in current builds. */
    val debugInjected: Boolean? = null,
)

data class PduPayload(
    val partNo: Int,
    val bytes: ByteArray,
    val format: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PduPayload) return false
        return partNo == other.partNo && format == other.format && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * (31 * partNo + format.hashCode()) + bytes.contentHashCode()
}

data class TracePayload(
    val seq: Int,
    val stage: String,
    val tNs: Long,
    val detail: String?,
)

/**
 * The persistence half of ingest (T05, consumed by).
 *
 * One [upsertBatch] is one transaction: every row of a batch is written before the caller replies `acked`.
 * Duplicates are detected by the unique `eventId` and by revision:
 *  - unknown `eventId` → insert, `needsProcessing = true`;
 *  - same `eventId`, higher revision → replace the row's content and its PDUs/trace, `needsProcessing = true`;
 *  - same or lower revision → no write, still acked.
 *
 * A higher revision keeps what the app added to the row: review and star, the user severity override, the
 * delivery link and status and the release link. A released copy (`releaseOf`) gets the row id of its original
 * in `releasedFromId`, and the original is no longer on hold.
 *
 * Rows are inserted with `needsProcessing = true` so a crash between commit and post-processing is recoverable: the
 * pipeline picks them up from [pendingProcessing] on the next start.
 */
interface MessageStore {
    suspend fun upsertBatch(events: List<IncomingEvent>): UpsertResult

    suspend fun upsert(event: IncomingEvent): UpsertOutcome

    suspend fun pendingProcessing(limit: Int = 200): List<Long>

    suspend fun markProcessed(ids: List<Long>): Int

    /** The stored PDUs of the row with [eventId], in part order; empty when there is no such row. */
    suspend fun storedPdus(eventId: String): List<PduPayload> = emptyList()
}

@Singleton
class RoomMessageStore
    @Inject
    constructor(
        private val db: NightjarDatabase,
        private val messages: MessageDao,
        private val pdus: PduDao,
        private val traces: TraceStepDao,
    ) : MessageStore {
        override suspend fun upsertBatch(events: List<IncomingEvent>): UpsertResult =
            // Every row of the batch is committed before the caller replies `acked`.
            db.withTransaction { upsertLocked(events) }

        private suspend fun upsertLocked(events: List<IncomingEvent>): UpsertResult {
            val acked = ArrayList<String>(events.size)
            val ids = ArrayList<Long>(events.size)
            var inserted = 0
            var updated = 0
            var duplicates = 0
            events.forEach { event ->
                val existing = messages.findByEventId(event.eventId)
                when {
                    existing == null -> {
                        // VR01-A04: a link the event already carries wins; otherwise `releaseOf` names the original.
                        val id = messages.insert(event.toEntity().copy(releasedFromId = event.releasedFromId ?: releaseOriginal(event)))
                        replaceParts(id, event)
                        inserted++
                        ids += id
                    }

                    event.revision > existing.revision -> {
                        // A higher revision replaces the row's content; user state and app links survive.
                        val fresh = event.toEntity()
                        val overridden = existing.severityOverridden
                        val row =
                            fresh.copy(
                                id = existing.id,
                                isReviewed = existing.isReviewed,
                                isStarred = existing.isStarred,
                                severity = if (overridden) existing.severity else fresh.severity,
                                severityOverridden = overridden,
                                detectorSeverity = existing.detectorSeverity,
                                relatedMessageId = existing.relatedMessageId,
                                deliveryStatus = existing.deliveryStatus,
                                releasedFromId = existing.releasedFromId ?: releaseOriginal(event),
                                // The earlier pass stays known; markProcessed sets the new revision.
                                processedRevision = existing.processedRevision,
                            )
                        messages.update(row)
                        replaceParts(existing.id, event)
                        updated++
                        ids += existing.id
                    }

                    else -> {
                        duplicates++
                    }
                }
                acked += Ipc.ackKey(event.eventId, event.revision)
            }
            return UpsertResult(acked, inserted, updated, duplicates, ids)
        }

        override suspend fun upsert(event: IncomingEvent): UpsertOutcome =
            upsertBatch(listOf(event)).let { result ->
                when {
                    result.inserted > 0 -> UpsertOutcome.INSERTED
                    result.updated > 0 -> UpsertOutcome.UPDATED
                    else -> UpsertOutcome.DUPLICATE
                }
            }

        override suspend fun pendingProcessing(limit: Int): List<Long> = messages.pendingProcessing(limit)

        override suspend fun markProcessed(ids: List<Long>): Int = if (ids.isEmpty()) 0 else messages.markProcessed(ids)

        override suspend fun storedPdus(eventId: String): List<PduPayload> {
            val row = messages.findByEventId(eventId) ?: return emptyList()
            return pdus.forMessage(row.id).map { PduPayload(it.partNo, it.bytes, it.format) }
        }

        /**
         * The row id of the quarantined original that [event] releases, or null. The original stops being on
         * hold; its fate stays as it was, so the history still shows the quarantine.
         */
        private suspend fun releaseOriginal(event: IncomingEvent): Long? {
            val ofEventId = event.releaseOf ?: return null
            if (ofEventId == event.eventId) return null
            val original = messages.findByEventId(ofEventId) ?: return null
            if (original.isQuarantined) messages.clearQuarantine(original.id)
            return original.id
        }

        private suspend fun replaceParts(
            messageId: Long,
            event: IncomingEvent,
        ) {
            pdus.deleteForMessage(messageId)
            traces.deleteForMessage(messageId)
            if (event.pdus.isNotEmpty()) {
                pdus.insertAll(event.pdus.map { PduEntity(messageId, it.partNo, it.bytes, it.format) })
            }
            if (event.trace.isNotEmpty()) {
                traces.insertAll(event.trace.map { TraceStepEntity(messageId, it.seq, it.stage, it.tNs, it.detail) })
            }
        }

        private fun IncomingEvent.toEntity(): MessageEntity =
            MessageEntity(
                eventId = eventId,
                sessionId = sessionId,
                revision = revision,
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
                // Stores the originator as received and adds the normalised copy for search. The same
                // normalisation as the classifier, so a detector lookup by originator finds the row.
                originatorNorm = KnownOriginatorMatcher.normalize(originator),
                smsc = smsc,
                destPort = destPort,
                contentType = contentType,
                textBody = textBody,
                textPreview = cutPreview(textPreview),
                receivedAtWall = receivedAtWall,
                receivedAtElapsedNs = receivedAtElapsedNs,
                modemTs = modemTs,
                ackResult = ackResult,
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
                partsJson = partsJson,
                purgeAt = purgeAt,
                isQuarantined = isQuarantined,
                isReviewed = isReviewed,
                isStarred = isStarred,
                isReplay = isReplay,
                isSelfTest = isSelfTest,
                needsProcessing = true,
                releasedFromId = releasedFromId,
                buildFingerprint = buildFingerprint,
                payloadVersion = payloadVersion,
                tpMr = tpMr,
                destinationNorm = destinationNorm,
                subtype = subtype,
                extraSubtypes = StringListColumn.encode(extraSubtypes),
                anomalies = StringListColumn.encode(anomalies),
                destination = destination,
                roaming = roaming,
                userUnlocked = userUnlocked,
                cbMessageId = cbMessageId,
                cbSerial = cbSerial,
                releaseOf = releaseOf,
                clampedFrom = clampedFrom,
                clampReason = clampReason,
                simOtaHeader = simOtaHeader,
                emergencyCallActive = emergencyCallActive,
                debugInjected = debugInjected,
            )

        /** cap. The cut never splits a surrogate pair. */
        private fun cutPreview(text: String): String {
            if (text.length <= MAX_PREVIEW) return text
            val cut = if (Character.isHighSurrogate(text[MAX_PREVIEW - 1])) MAX_PREVIEW - 1 else MAX_PREVIEW
            return text.substring(0, cut)
        }

        private companion object {
            /** The preview is never longer than 120 characters. */
            const val MAX_PREVIEW = 120
        }
    }
