package dev.nightjar.app.domain.detect

import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.contract.ClassId

/** In-memory [PatternHistory] with the same semantics the Room implementation must have. */
class FakePatternHistory : PatternHistory {
    val records = mutableListOf<PatternRecord>()

    override suspend fun recentOfClasses(
        classes: Set<ClassId>,
        sinceWallMs: Long,
        untilWallMs: Long,
        limit: Int,
    ): List<PatternRecord> =
        records
            .filter { it.classId in classes && it.atWallMs in sinceWallMs..untilWallMs }
            .sortedByDescending { it.atWallMs }
            .take(limit)

    override suspend fun recentFromOriginator(
        normalizedOriginator: String,
        untilWallMs: Long,
        limit: Int,
    ): List<PatternRecord> =
        // The store writes originatorNorm with KnownOriginatorMatcher.normalize, and the DAO compares that
        // column with the argument as it is. The argument is not normalized again here, so a caller that passes another
        // form finds nothing, the same as on the device.
        records
            .filter { KnownOriginatorMatcher.normalize(it.originator) == normalizedOriginator && it.atWallMs <= untilWallMs }
            .sortedByDescending { it.atWallMs }
            .take(limit)

    fun add(
        eventId: String,
        classId: ClassId,
        at: Long,
        originator: String? = null,
        subtype: String? = null,
    ) {
        records += PatternRecord(eventId, classId, subtype, originator, at)
    }
}
