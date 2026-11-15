package dev.nightjar.app.data.audit

import dev.nightjar.app.data.db.AuditDao
import dev.nightjar.app.data.db.AuditEntity
import dev.nightjar.app.data.model.AuditEntry
import dev.nightjar.app.data.model.toModel
import dev.nightjar.app.data.repository.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes and reads audit records.
 *
 * Every configuration change stores its before/after as JSON so Audit history can show what changed and diagnostics can include
 * the trail. Records are never edited or deleted except by the 365-day retention run, and "delete all data" keeps them
 *  apart from the single `DELETE_ALL` row it writes afterwards.
 */
interface AuditLog {
    suspend fun record(
        action: AuditAction,
        actor: AuditActor,
        target: String? = null,
        before: String? = null,
        after: String? = null,
    )

    /** Convenience for a scalar setting change: stores `{"value":…}` on both sides. */
    suspend fun recordValue(
        action: AuditAction,
        actor: AuditActor,
        target: String,
        before: Any?,
        after: Any?,
    ) {
        record(action, actor, target, scalar(before), scalar(after))
    }

    fun observe(limit: Int = 500): Flow<List<AuditEntry>>

    /** Records within the diagnostics window (last 30 days). */
    suspend fun since(atWallMs: Long): List<AuditEntry>

    suspend fun purgeBefore(cutoffWallMs: Long): Int

    companion object {
        /** JSON object holding one scalar, or `null` when the value is null. */
        fun scalar(value: Any?): String? =
            when (value) {
                null -> null
                is Number, is Boolean -> """{"value":$value}"""
                else -> """{"value":${quote(value.toString())}}"""
            }

        /** JSON string escaping for the one-scalar form; enough for keys, verdicts and enum wire names. */
        private fun quote(text: String): String =
            buildString(text.length + 2) {
                append('"')
                text.forEach { c ->
                    when (c) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                    }
                }
                append('"')
            }
    }
}

@Singleton
class RoomAuditLog
    @Inject
    constructor(
        private val dao: AuditDao,
        private val clock: Clock,
    ) : AuditLog {
        override suspend fun record(
            action: AuditAction,
            actor: AuditActor,
            target: String?,
            before: String?,
            after: String?,
        ) {
            dao.insert(
                AuditEntity(
                    at = clock.now(),
                    actor = actor.name,
                    action = action.name,
                    target = target,
                    beforeJson = before,
                    afterJson = after,
                ),
            )
        }

        override fun observe(limit: Int): Flow<List<AuditEntry>> =
            dao.observeRecent(limit).map { rows -> rows.map { it.toModel() } }

        override suspend fun since(atWallMs: Long): List<AuditEntry> =
            dao.since(atWallMs).map { it.toModel() }

        override suspend fun purgeBefore(cutoffWallMs: Long): Int = dao.deleteBefore(cutoffWallMs)
    }
