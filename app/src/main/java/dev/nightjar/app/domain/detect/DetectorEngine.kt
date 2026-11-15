package dev.nightjar.app.domain.detect

import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.app.domain.text.TextKey

/**
 * Runs every detector for one event (T04, 06).
 *
 * The engine owns no state: burst continuity comes from [DetectionHistory] and the pattern windows from
 * [PatternHistory], which the store persists, so a process restart cannot split one burst into two detections.
 *
 * [patternHistory] defaults to [PatternHistory.NONE]: the patterns that need no history still apply, and the ones that
 * count earlier messages stay off until the caller supplies the store.
 */
class DetectorEngine(
    config: DetectorConfig,
    history: DetectionHistory,
    knownOriginators: KnownOriginatorMatcher,
    patternHistory: PatternHistory = PatternHistory.NONE,
    clock: WallClock = WallClock { System.currentTimeMillis() },
) : Detectors {
    private val coerced = config.coerced()
    private val silent = SilentPingDetector(coerced, history, knownOriginators)
    private val classes = ClassDetectors(coerced, history, knownOriginators)
    private val patterns = PatternDetectors(coerced, patternHistory, knownOriginators, clock)

    override suspend fun evaluate(
        event: EventFacts,
        context: DetectContext,
    ): List<DetectionDraft> {
        val silentDrafts = silent.evaluate(event)
        val patternDrafts = patterns.evaluate(event, context).filterNot { redundant(it, silentDrafts) }
        return silentDrafts + classes.evaluate(event, context) + patternDrafts
    }

    /**
     * Already explains a silent ping from a sender that is new to the phone, at the same severity. A second
     * line that only says "not in Known senders" adds nothing on the detail screen.
     */
    private fun redundant(
        pattern: DetectionDraft,
        silentDrafts: List<DetectionDraft>,
    ): Boolean =
        pattern.detectorId == DetectorId.PAT_SILENT_UNKNOWN_SENDER &&
            silentDrafts.any { it.summaryKey == TextKey.DET_SILENT_NEW_SENDER }
}
