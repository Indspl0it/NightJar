package dev.nightjar.app.domain.ingest

import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.classify.Classification
import dev.nightjar.app.domain.classify.EventClassifier
import dev.nightjar.app.domain.detect.DetectContext
import dev.nightjar.app.domain.detect.DetectionDraft
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId

/** Persists a detection and returns its row id (`DetectionRepository.openOrTouch`). */
fun interface DetectionSink {
    suspend fun record(draft: DetectionDraft): Long
}

/** Coverage bookkeeping. */
interface CoverageSink {
    suspend fun seenFromNetwork(
        classId: ClassId,
        atWallMs: Long,
    )

    /** A self-test canary of [classId] arrived. The pass or fail verdict comes from `SelfTestResults`, not from here. */
    suspend fun selfTestArrived(
        classId: ClassId,
        atWallMs: Long,
    )
}

/** Rule usage counters shown on Custom rules (03). */
fun interface RuleMatchSink {
    suspend fun increment(
        ruleId: Long,
        atWallMs: Long,
    )
}

/** Writes the computed severity back to the stored message (`MessageRepository`). */
fun interface MessageSeveritySink {
    suspend fun apply(
        messageId: Long,
        severity: Severity,
        overridden: Boolean,
    )
}

/** Counter upsert. */
fun interface CounterSink {
    suspend fun add(
        day: Int,
        subId: Int,
        direction: Direction,
        classId: ClassId,
        n: Int,
    )
}

/** The notifier. Called last, after everything is persisted. */
fun interface AlertSink {
    suspend fun alert(processed: ProcessedEvent)
}

/**
 * One stored message waiting for post-commit processing (`needsProcessing` rows).
 *
 * [firstPass] is false when the row was processed before and a later revision (a CB page, a late concat part, a
 * fate update) flagged it again. Such a pass refreshes severity and detections but does not add to the
 * rule count or the counter a second time and does not notify again.
 */
data class PendingEvent(
    val messageId: Long,
    val facts: EventFacts,
    val context: DetectContext = DetectContext.NONE,
    val firstPass: Boolean = true,
)

/** One processed message, ready to be shown or notified. */
data class ProcessedEvent(
    val messageId: Long,
    val facts: EventFacts,
    val classification: Classification,
) {
    val severity: Severity get() = classification.severity

    val primaryDetection: DetectionDraft? get() = classification.primary
}

/**
 * The post-commit half of ingest: severity, detectors, coverage, notify, then rule usage and counters.
 *
 * Runs on the ingest worker in event order. The severity write and coverage are idempotent per message id. Each
 * detection has a merge key ([DetectionDraft.storeKey]): one key per message for a one-shot finding, one key per burst
 * for a pattern, so a retry touches the same detection row instead of opening a second one. The two additive writes
 * run last and the alert cannot fail the row, so a retried row cannot double-count a counter. A row processed
 * again for a later revision ([PendingEvent.firstPass] false) skips the additive writes and the alert. A row with the
 * legacy debug flag is processed as traffic.
 */
class ProcessingPipeline(
    private val classifier: EventClassifier,
    private val detections: DetectionSink,
    private val coverage: CoverageSink,
    private val counters: CounterSink,
    private val ruleMatches: RuleMatchSink,
    private val severities: MessageSeveritySink,
    private val alerts: AlertSink,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun process(pending: List<PendingEvent>): List<ProcessedEvent> {
        val out = ArrayList<ProcessedEvent>(pending.size)
        for (row in pending) {
            // A row with the legacy debug flag (from a removed debug injector) runs as real traffic.
            val facts = asTraffic(row.facts)
            val classification = classifier.classify(facts, row.context)
            severities.apply(row.messageId, classification.severity, classification.severityOverridden)
            val processed = ProcessedEvent(row.messageId, facts, classification)
            // B3: a self-test canary is not traffic. It records its self-test coverage and reaches the alert sink
            // (which drops it), but it adds no finding, no rule count and no counter.
            val canary = facts.flags.selfTest
            if (!canary) recordDetections(classification)
            recordCoverage(facts)
            if (!row.firstPass) {
                out += processed
                continue
            }
            // A notification that cannot be posted must not leave the row flagged: the retry would run the additive
            // writes below a second time. Cancellation still propagates.
            try {
                alerts.alert(processed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // the message and its detections are stored; only the alert is lost
            }
            // Last, because they add to a total (not idempotent): nothing that can fail runs between them and the
            // caller clearing the row's flag, so a retry cannot count the same message twice.
            if (!canary) {
                facts.ruleId?.let { ruleMatches.increment(it, facts.receivedAtWallMs) }
                recordCounter(facts)
            }
            out += processed
        }
        return out
    }

    /** The facts with the debug flag cleared: everything after the commit treats the injection as traffic. */
    private fun asTraffic(facts: EventFacts): EventFacts =
        if (facts.flags.debug) facts.copy(flags = facts.flags.copy(debug = false)) else facts

    private suspend fun recordDetections(classification: Classification) {
        for (draft in classification.detections) detections.record(draft)
    }

    private suspend fun recordCoverage(event: EventFacts) {
        // Coverage answers "which hidden classes does this modem forward"; CL-NORMAL always arrives, so it
        // would say nothing and would cost a write per visible SMS. This stays class-based for a blocked row too.
        if (ClassRegistry.isCounterOnly(event.classId)) return
        if (event.flags.selfTest) {
            coverage.selfTestArrived(event.classId, event.receivedAtWallMs)
            return
        }
        if (event.fromNetwork) coverage.seenFromNetwork(event.classId, event.receivedAtWallMs)
    }

    private suspend fun recordCounter(event: EventFacts) {
        // A blocked message of a counter-only class (an app send that the outgoing gate stopped, a
        // rule block) is a feed row and is counted there, so the daily counter must not count it again.
        if (!ClassRegistry.isCounterOnly(event.classId) || event.fate == Fate.BLOCKED) return
        counters.add(counterDay(event.receivedAtWallMs), event.subId, event.direction, event.classId, 1)
    }

    /** `yyyymmdd` in the device zone, the key used by the `counter` table and by Home's counts. */
    fun counterDay(wallMs: Long): Int {
        val date = Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate()
        return date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth
    }
}
