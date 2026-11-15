package dev.nightjar.app.core.ipc

import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.data.cb.CbIngest
import dev.nightjar.app.data.db.StagedEventEntity
import dev.nightjar.app.data.repository.IncomingEvent
import dev.nightjar.app.data.repository.MessageStore
import dev.nightjar.app.data.repository.UpsertResult
import dev.nightjar.app.domain.ingest.IngestProcessor
import dev.nightjar.app.domain.selftest.SelfTestObservation
import dev.nightjar.app.domain.selftest.TraceStepView
import dev.nightjar.app.domain.text.Explainer
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Wire
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one commit path for captured events.
 *
 * Both ingest entry points end here — the live `events` call and the staged replay after the first unlock — so a
 * staged event and a live one are parsed, CB-folded, stored and post-processed by the same code. Two paths would drift
 * and the drift would be invisible (a staged CB page folded differently from a live one has no visible symptom).
 *
 * [commit] returns only after `upsertBatch`'s transaction, so a caller may acknowledge what it handed over;
 * post-processing runs inside it too, and a failure there leaves `needsProcessing = 1` for [IngestProcessor.resumePending]
 * instead of failing the commit that already happened.
 */
@Singleton
class EventIngest
    @Inject
    constructor(
        private val store: MessageStore,
        private val cbIngest: CbIngest,
        private val titles: Explainer,
        private val selfTestEvents: SelfTestEvents,
        private val processor: IngestProcessor,
        private val log: ALog,
    ) {
        /** Parses a whole `events` batch from the payload. Malformed items are skipped, never thrown. */
        fun parse(batch: String): List<IncomingEvent> = EventJson.parseBatch(batch) { fields -> title(fields) }

        /** Fold + one transaction + post-processing. Every event of [parsed] is represented in the result. */
        suspend fun commit(parsed: List<IncomingEvent>): UpsertResult {
            val result = store.upsertBatch(cbIngest.prepare(parsed) { eventId -> store.storedPdus(eventId) })
            publishSelfTest(parsed)
            // The data is committed; post-processing failures stay in the `needsProcessing` queue.
            runCatching { processor.process(result.messageIds) }
                .onFailure { log.w(TAG, "post-processing deferred", it) }
            return result
        }

        /**
         * Replays one staged row. Returns true after its commit, so [dev.nightjar.app.data.staging.StagingService.drain]
         * deletes the row. A row that cannot be parsed also returns true: a retry cannot fix it, and kept rows would
         * block every later drain. A failed commit throws, and the row stays for the next drain.
         */
        suspend fun ingestStaged(row: StagedEventEntity): Boolean {
            val parsed = runCatching { EventJson.parseEvent(JSONObject(row.json)) { fields -> title(fields) } }
            val event = parsed.getOrNull()
            if (event == null) {
                log.w(TAG, "staged event is unreadable, dropped", parsed.exceptionOrNull())
                return true
            }
            commit(listOf(event))
            return true
        }

        /**
         * The row title the feed shows; the UI can re-render it from the class, this keeps ingest independent of the
         * UI.
         * A class id that this app does not know gets the "Other message type" label.
         */
        private fun title(fields: EventJson.EventJsonFields): String =
            runCatching {
                if (fields.unknownClass) titles.render(TextKey.GENERIC_UNKNOWN_CLASS) else titles.title(EventJson.factsFor(fields))
            }.getOrElse { fields.classId.wire() }

        /**
         * Hand every canary event to the self-test evaluation after its transaction committed. This is the
         * only point where `runId`/`canaryClass` are still available; the stored row keeps just `isSelfTest`.
         */
        private fun publishSelfTest(events: List<IncomingEvent>) {
            for (event in events) {
                val runId = event.runId ?: continue
                val canaryClass = Wire.parse(ClassId::class.java, event.canaryClass, null) ?: continue
                selfTestEvents.publish(
                    SelfTestObservation(
                        runId = runId,
                        canaryClass = canaryClass,
                        classId = Wire.parse(ClassId::class.java, event.classId, ClassId.NORMAL),
                        fate = event.fate,
                        trace = event.trace.map { TraceStepView(it.stage, it.detail) },
                        receivedAtWallMs = event.receivedAtWall,
                    ),
                )
            }
        }

        private companion object {
            const val TAG = "ingest"
        }
    }
