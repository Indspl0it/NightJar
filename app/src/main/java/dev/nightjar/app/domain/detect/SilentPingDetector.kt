package dev.nightjar.app.domain.detect

import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity

/**
 * Silent pings.
 *
 * A `CL-SILENT` is Suspicious when any of these holds:
 * 1. the burst rule — at least `detect.silent.count` silent pings in `detect.silent.windowMin` minutes on any sub;
 * 2. the originator was never seen on this device and is not a known originator;
 * 3. the device is roaming.
 *
 * The burst is one detection that grows while it continues: a new ping extends the open detection when the gap since
 * its last member is at most the window. Conditions 2 and 3 each produce a detection of their own,
 * because they explain a single message rather than a pattern.
 */
class SilentPingDetector(
    private val config: DetectorConfig,
    private val history: DetectionHistory,
    private val knownOriginators: KnownOriginatorMatcher,
) {
    suspend fun evaluate(event: EventFacts): List<DetectionDraft> {
        if (event.classId != ClassId.SILENT) return emptyList()
        val now = event.receivedAtWallMs
        val windowMs = config.silentWindowMs
        val draft = burstDraft(event, now, windowMs) ?: newSenderDraft(event, now) ?: roamingDraft(event, now)
        return listOfNotNull(draft)
    }

    private suspend fun newSenderDraft(
        event: EventFacts,
        now: Long,
    ): DetectionDraft? =
        if (config.silentFlagNewSender && isNewSender(event, now)) {
            singleDraft(event, now, TextKey.DET_SILENT_NEW_SENDER)
        } else {
            null
        }

    private fun roamingDraft(
        event: EventFacts,
        now: Long,
    ): DetectionDraft? =
        if (config.silentFlagRoaming && event.roaming) singleDraft(event, now, TextKey.DET_SILENT_ROAMING) else null

    private fun singleDraft(
        event: EventFacts,
        now: Long,
        key: TextKey,
    ): DetectionDraft =
        DetectionDraft(
            detectorId = DetectorId.SILENT_PING,
            severity = Severity.SUSPICIOUS,
            summaryKey = key,
            summaryArgs = emptyMap(),
            eventIds = listOf(event.eventId),
            firstAtWallMs = now,
            lastAtWallMs = now,
            count = 1,
        )

    private suspend fun burstDraft(
        event: EventFacts,
        now: Long,
        windowMs: Long,
    ): DetectionDraft? {
        val windowStart = now - windowMs
        val prior =
            history
                .silentPingsSince(windowStart, BURST_HISTORY_LIMIT)
                .filter { it.atWallMs <= now && it.eventId != event.eventId }
        val total = prior.size + 1
        if (total < config.silentBurstCount) return null

        val open = history.latestOpenDetection(DetectorId.SILENT_PING)
        val continues = open != null && now - open.lastAtWallMs <= windowMs
        val senders = prior.mapNotNull { normalized(it.originator) }.toSet() + listOfNotNull(normalized(event.originator))
        val firstAt = if (continues) open!!.firstAtWallMs else prior.minOfOrNull { it.atWallMs } ?: now
        val count = if (continues) open!!.count + 1 else total
        val spanMinutes = (((now - firstAt) / 60_000L).toInt()).coerceAtLeast(1)
        return DetectionDraft(
            detectorId = DetectorId.SILENT_PING,
            severity = Severity.SUSPICIOUS,
            summaryKey = TextKey.DET_SILENT_BURST,
            summaryArgs =
                mapOf(
                    "count" to count.toString(),
                    "minutes" to spanMinutes.toString(),
                    "senders" to senders.size.toString(),
                ),
            // A continuing burst adds only the new member: the store adds members with INSERT OR IGNORE, so earlier ones
            // are already there. A new burst links the earlier pings of the window too, so its members match its count.
            eventIds = if (continues) listOf(event.eventId) else listOf(event.eventId) + prior.map { it.eventId },
            firstAtWallMs = firstAt,
            lastAtWallMs = now,
            count = count,
            mergeKey = if (continues) open!!.mergeKey else MERGE_PREFIX + firstAt,
        )
    }

    private suspend fun isNewSender(
        event: EventFacts,
        now: Long,
    ): Boolean {
        val originator = event.originator ?: return false
        if (knownOriginators.matches(originator)) return false
        val normalized = normalized(originator) ?: return false
        return !history.originatorSeenBefore(normalized, now)
    }

    private fun normalized(originator: String?): String? = KnownOriginatorMatcher.normalize(originator)

    companion object {
        private const val MERGE_PREFIX = "silent-burst:"
        private const val BURST_HISTORY_LIMIT = 200
    }
}
