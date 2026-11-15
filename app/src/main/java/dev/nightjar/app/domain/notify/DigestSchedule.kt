package dev.nightjar.app.domain.notify

import dev.nightjar.app.core.settings.DigestMode

/** The week totals that the digest reports (the same counts as the Home week card). */
data class DigestCounts(
    val hidden: Int,
    val suspicious: Int,
    val held: Int,
    val blocked: Int,
) {
    val isEmpty: Boolean get() = hidden == 0 && suspicious == 0 && held == 0 && blocked == 0
}

/**
 * When the weekly digest may post.
 *
 * The digest posts at most once in [PERIOD_MS], never when the mode is OFF, and never as an ongoing notification:
 * Android removes it after [TIMEOUT_MS] if the user does not. A weekly WorkManager job asks [shouldPost]. The time of
 * the last post is kept, so a job that runs early (a re-enqueue, a retry) cannot post a second digest in the same week.
 */
object DigestSchedule {
    const val PERIOD_DAYS = 7L

    const val PERIOD_MS = PERIOD_DAYS * 24 * 60 * 60 * 1000

    /** WorkManager can start a periodic job a little early; a run this much before the full week still counts. */
    const val SLACK_MS = 6L * 60 * 60 * 1000

    /** A posted digest leaves the shade after one day. */
    const val TIMEOUT_MS = 24L * 60 * 60 * 1000

    fun shouldPost(
        mode: DigestMode,
        counts: DigestCounts,
        lastPostedAtMs: Long?,
        nowMs: Long,
    ): Boolean {
        if (mode != DigestMode.WEEKLY || counts.isEmpty) return false
        if (lastPostedAtMs == null) return true
        // A clock that moved back must not stop the digest until the clock catches up again.
        if (nowMs < lastPostedAtMs) return true
        return nowMs - lastPostedAtMs >= PERIOD_MS - SLACK_MS
    }
}
