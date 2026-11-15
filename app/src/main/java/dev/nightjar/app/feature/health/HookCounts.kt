package dev.nightjar.app.feature.health

import dev.nightjar.app.data.maintenance.RetentionReport
import dev.nightjar.app.data.model.HookStatus
import dev.nightjar.contract.HookState

/** Live/total hook counts for the Health summary (02). */
data class HookCounts(
    val live: Int,
    val total: Int,
) {
    val allLive: Boolean get() = total > 0 && live == total
}

fun hookCounts(hooks: List<HookStatus>): HookCounts =
    HookCounts(
        live = hooks.count { it.state == HookState.LIVE },
        total = hooks.size,
    )

/** The short build label of the Health screen: the fingerprint's first three fields. */
fun shortFingerprint(full: String?): String {
    if (full.isNullOrBlank()) return ""
    return full.split('/').take(3).joinToString("/")
}

/** The database row is shown only above the retention report's limit. */
fun shouldShowDatabaseRow(bytes: Long): Boolean = bytes > RetentionReport.SIZE_LIMIT_BYTES
