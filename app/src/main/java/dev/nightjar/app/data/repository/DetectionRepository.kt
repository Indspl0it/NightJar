package dev.nightjar.app.data.repository

import androidx.room.withTransaction
import dev.nightjar.app.data.db.DetectionDao
import dev.nightjar.app.data.db.DetectionEntity
import dev.nightjar.app.data.db.DetectionMemberEntity
import dev.nightjar.app.data.db.NightjarDatabase
import dev.nightjar.app.data.model.Detection
import dev.nightjar.app.data.model.toModel
import dev.nightjar.contract.Severity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detector findings (DET-*).
 *
 * [openOrTouch] is the single entry point for ingest: when [mergeKey] is non-null and an open row with the same
 * `(detectorId, mergeKey)` exists, the row grows (count, span, summary, severity) and the new message joins it; that is
 * what lets a burst collapse into one notification even across a process restart. Members are inserted with IGNORE so
 * a retried event cannot double-count.
 */
interface DetectionRepository {
    suspend fun openOrTouch(
        detectorId: String,
        mergeKey: String?,
        severity: Severity,
        summary: String,
        messageIds: List<Long>,
        firstAtWallMs: Long,
        lastAtWallMs: Long,
        count: Int,
    ): Long

    suspend fun close(detectionId: Long): Int

    suspend fun markReviewed(
        detectionId: Long,
        reviewed: Boolean = true,
    ): Int

    suspend fun forMessage(messageId: Long): List<Detection>

    fun observeForMessage(messageId: Long): Flow<List<Detection>>

    fun observeOpen(): Flow<List<Detection>>

    fun observeRecent(limit: Int = 100): Flow<List<Detection>>

    /** Message ids of a detection except [excludeMessageId] (08 siblings). */
    suspend fun memberIdsExcept(
        detectionId: Long,
        excludeMessageId: Long,
    ): List<Long>

    suspend fun purgeReviewedBefore(cutoffWallMs: Long): Int
}

@Singleton
class RoomDetectionRepository
    @Inject
    constructor(
        private val db: NightjarDatabase,
        private val dao: DetectionDao,
    ) : DetectionRepository {
        override suspend fun openOrTouch(
            detectorId: String,
            mergeKey: String?,
            severity: Severity,
            summary: String,
            messageIds: List<Long>,
            firstAtWallMs: Long,
            lastAtWallMs: Long,
            count: Int,
        ): Long =
            // The read and the insert are one transaction, so two ingests cannot open two rows for one key.
            db.withTransaction {
                openOrTouchLocked(detectorId, mergeKey, severity, summary, messageIds, firstAtWallMs, lastAtWallMs, count)
            }

        private suspend fun openOrTouchLocked(
            detectorId: String,
            mergeKey: String?,
            severity: Severity,
            summary: String,
            messageIds: List<Long>,
            firstAtWallMs: Long,
            lastAtWallMs: Long,
            count: Int,
        ): Long {
            val existing = if (mergeKey == null) null else dao.findOpen(detectorId, mergeKey)
            val id =
                if (existing != null) {
                    dao.update(
                        existing.copy(
                            severity = severity.wire(),
                            summary = summary,
                            lastAt = maxOf(existing.lastAt, lastAtWallMs),
                            count = maxOf(existing.count, count),
                            isOpen = true,
                        ),
                    )
                    existing.id
                } else {
                    dao.insert(
                        DetectionEntity(
                            detectorId = detectorId,
                            mergeKey = mergeKey,
                            severity = severity.wire(),
                            summary = summary,
                            firstAt = firstAtWallMs,
                            lastAt = lastAtWallMs,
                            count = count,
                            isReviewed = false,
                            isOpen = true,
                        ),
                    )
                }
            if (messageIds.isNotEmpty()) {
                dao.insertMembers(messageIds.distinct().map { DetectionMemberEntity(id, it) })
            }
            return id
        }

        override suspend fun close(detectionId: Long): Int = dao.close(detectionId)

        override suspend fun markReviewed(
            detectionId: Long,
            reviewed: Boolean,
        ): Int = dao.setReviewed(detectionId, reviewed)

        override suspend fun forMessage(messageId: Long): List<Detection> =
            dao.forMessage(messageId).map { it.toModel() }

        override fun observeForMessage(messageId: Long): Flow<List<Detection>> =
            dao.observeForMessage(messageId).map { rows -> rows.map { it.toModel() } }

        override fun observeOpen(): Flow<List<Detection>> = dao.observeOpen().map { rows -> rows.map { it.toModel() } }

        override fun observeRecent(limit: Int): Flow<List<Detection>> =
            dao.observeRecent(limit).map { rows -> rows.map { it.toModel() } }

        override suspend fun memberIdsExcept(
            detectionId: Long,
            excludeMessageId: Long,
        ): List<Long> = dao.memberIdsExcept(detectionId, excludeMessageId)

        override suspend fun purgeReviewedBefore(cutoffWallMs: Long): Int = dao.deleteReviewedBefore(cutoffWallMs)
    }
