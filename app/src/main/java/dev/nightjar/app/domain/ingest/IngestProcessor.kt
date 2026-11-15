package dev.nightjar.app.domain.ingest

import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.data.db.MessageDao
import dev.nightjar.app.data.db.MessageEntity
import dev.nightjar.app.data.repository.MessageStore
import dev.nightjar.contract.ClassId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Builds one classification pass; a function object so [IngestProcessor] stays testable without Hilt. */
fun interface PipelineFactory {
    suspend fun create(): ProcessingPipeline
}

/**
 * Runs [ProcessingPipeline] for the rows ingest committed.
 *
 * `MessageStore.upsertBatch` sets `needsProcessing = 1` on every row it writes and the commit is all the IPC reply
 * waits for. This class is the other half: after the commit it classifies the affected rows, then clears the
 * flag. Running it in the same call keeps the reply honest: an acknowledgement means stored *and* classified, while
 * [resumePending] replays whatever a crash left behind — the flag is the durable queue.
 *
 * One row is processed at a time and a failing row does not take its batch down: it stays flagged, so the next start
 * retries it and the notification for the others still goes out. All work is serialised: the pipeline is not
 * thread-safe (the classifier and rate limiter are per batch, the sinks are the same singletons).
 */
@Singleton
class IngestProcessor
    @Inject
    constructor(
        private val store: MessageStore,
        private val messages: MessageDao,
        private val pipelines: PipelineFactory,
        private val reports: DeliveryReportLinker,
        private val log: ALog,
        private val labels: PackageLabelRecorder,
    ) {
        private val mutex = Mutex()

        /** Classifies [ids] and clears their flag; returns how many rows finished. */
        suspend fun process(ids: List<Long>): Int {
            if (ids.isEmpty()) return 0
            return mutex.withLock { runLocked(ids) }
        }

        /**
         * Drains the crash-recovery queue: loops [MessageStore.pendingProcessing] until a full batch makes no progress
         * (an empty queue, or rows that keep failing). Returns the number of rows processed.
         */
        suspend fun resumePending(): Int =
            mutex.withLock {
                var total = 0
                while (true) {
                    val ids = store.pendingProcessing(SWEEP_BATCH)
                    if (ids.isEmpty()) break
                    val done = runLocked(ids)
                    total += done
                    // No progress means the remaining rows fail every time; stop instead of spinning (they are retried
                    // on the next start, and the rows behind them have been processed already).
                    if (done == 0) break
                }
                if (total > 0) log.i(TAG, "pending rows processed", listOf("count" to total))
                total
            }

        private suspend fun runLocked(ids: List<Long>): Int {
            val rows = messages.findMany(ids)
            if (rows.isEmpty()) return 0
            val pipeline = pipelines.create()
            var done = 0
            for (row in rows) {
                if (row.classId == ClassId.STATUS.wire()) linkReport(row)
                recordLabels(row)
                // A row processed for an earlier revision (a later CB page or concat part) does not count or
                // alert again.
                val event = PendingEvent(row.id, row.toFacts(), row.detectContext(), firstPass = row.processedRevision == null)
                val processed =
                    runCatching { pipeline.process(listOf(event)) }
                        .onFailure { log.w(TAG, "post-processing failed", it) }
                        .getOrNull()
                if (processed.isNullOrEmpty()) continue
                store.markProcessed(listOf(row.id))
                done++
            }
            return done
        }

        /** Caches the app labels of the row's packages for the list; a failed lookup never stops ingest. */
        private suspend fun recordLabels(row: MessageEntity) {
            runCatching { labels.record(row.consumerPackage, row.callingPackage) }
                .onFailure { log.w(TAG, "package label not cached", it) }
        }

        /** A report that cannot be decoded or linked stays in the list without a link; it never stops ingest. */
        private suspend fun linkReport(row: MessageEntity) {
            runCatching { reports.link(row) }.onFailure { log.w(TAG, "delivery report not linked", it) }
        }

        private companion object {
            const val TAG = "ingest"

            /** Same bound as [MessageStore.pendingProcessing]'s default. */
            const val SWEEP_BATCH = 200
        }
    }
