package dev.nightjar.app.domain

import dev.nightjar.app.domain.detect.DetectionHistory
import dev.nightjar.app.domain.detect.DetectorId
import dev.nightjar.app.domain.detect.OpenDetection
import dev.nightjar.app.domain.detect.SilentPingRecord
import dev.nightjar.app.domain.known.KnownOriginatorMatcher

/** In-memory [DetectionHistory] with the same semantics the Room implementation must have. */
class FakeDetectionHistory : DetectionHistory {
    val pings = mutableListOf<SilentPingRecord>()
    val seenOriginators = mutableSetOf<String>()
    val seenPackages = mutableSetOf<String>()

    /** Destinations the phone sent to before, compared by their digits. */
    val seenDestinations = mutableSetOf<String>()
    val open = LinkedHashMap<String, OpenDetection>()

    override suspend fun silentPingsSince(
        sinceWallMs: Long,
        limit: Int,
    ): List<SilentPingRecord> = pings.filter { it.atWallMs >= sinceWallMs }.sortedBy { it.atWallMs }.take(limit)

    override suspend fun originatorSeenBefore(
        normalizedOriginator: String,
        beforeWallMs: Long,
    ): Boolean = seenOriginators.contains(normalizedOriginator)

    override suspend fun packageSentBefore(
        packageName: String,
        beforeWallMs: Long,
    ): Boolean = seenPackages.contains(packageName)

    override suspend fun destinationSentBefore(
        destination: String?,
        beforeWallMs: Long,
    ): Boolean {
        val digits = destination?.filter(Char::isDigit).orEmpty()
        return digits.isNotEmpty() && seenDestinations.any { it.filter(Char::isDigit) == digits }
    }

    override suspend fun openDetection(
        detectorId: DetectorId,
        mergeKey: String,
    ): OpenDetection? = open["${detectorId.wire}|$mergeKey"]

    override suspend fun latestOpenDetection(detectorId: DetectorId): OpenDetection? =
        open.values.filter { it.detectorId == detectorId }.maxByOrNull { it.lastAtWallMs }

    fun addPing(
        eventId: String,
        originator: String?,
        at: Long,
    ) {
        pings += SilentPingRecord(eventId, originator, at)
        KnownOriginatorMatcher.normalize(originator)?.let(seenOriginators::add)
    }

    fun addOpen(detection: OpenDetection) {
        open["${detection.detectorId.wire}|${detection.mergeKey}"] = detection
    }
}
