package dev.nightjar.app.domain.detect

import dev.nightjar.contract.ClassId

/** One stored message, as the suspicious patterns see it. Never holds message text. */
data class PatternRecord(
    val eventId: String,
    val classId: ClassId,
    /** Subtype code of the stored message, or null when the row has none. */
    val subtype: String?,
    val originator: String?,
    val atWallMs: Long,
)

/**
 * Read model of the suspicious patterns. The app implements it over Room; tests use an in-memory
 * fake. Every method is a bounded, blocking read on the ingest worker — never called from the UI thread — and returns
 * at most `limit` rows, so a pattern never holds more than one window of small records in memory.
 *
 * The rows can include the event that is being classified (ingest stores a message before it classifies it), so
 * callers exclude it by event id.
 */
interface PatternHistory {
    /** Stored messages of [classes] with `sinceWallMs <= atWallMs <= untilWallMs`, newest first. */
    suspend fun recentOfClasses(
        classes: Set<ClassId>,
        sinceWallMs: Long,
        untilWallMs: Long,
        limit: Int,
    ): List<PatternRecord>

    /** Stored messages from this normalised originator with `atWallMs <= untilWallMs`, newest first. */
    suspend fun recentFromOriginator(
        normalizedOriginator: String,
        untilWallMs: Long,
        limit: Int,
    ): List<PatternRecord>

    companion object {
        /**
         * No stored messages. The rules that need history then never fire, and the "no earlier message" rule is off,
         * because without history every message would look like the first one.
         */
        val NONE: PatternHistory =
            object : PatternHistory {
                override suspend fun recentOfClasses(
                    classes: Set<ClassId>,
                    sinceWallMs: Long,
                    untilWallMs: Long,
                    limit: Int,
                ): List<PatternRecord> = emptyList()

                override suspend fun recentFromOriginator(
                    normalizedOriginator: String,
                    untilWallMs: Long,
                    limit: Int,
                ): List<PatternRecord> = emptyList()
            }
    }
}
