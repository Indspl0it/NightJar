package dev.nightjar.app.domain.notify

/** What [StatusPlanner.plan] decided: the status notifications to post now and the ones to remove. */
data class StatusChanges(
    val post: Set<NotificationIds.StatusKind>,
    val cancel: Set<NotificationIds.StatusKind>,
)

/**
 * Status notifications follow the problem, not the refresh.
 *
 * A status notification posts once when its problem starts and is removed when the problem ends. The monitor refreshes
 * on every handshake, ping and screen visit. If each refresh posted again, a notification that the user swiped away
 * would come back again and again while the problem stays. The planner keeps the set of problems that it already
 * posted in this process.
 */
class StatusPlanner {
    private val posted = HashSet<NotificationIds.StatusKind>()

    /**
     * [wanted] are the problems that exist now. [alreadyPostedThisBoot] are the problems that another path (the boot
     * check) already posted. The result posts each new problem and removes each problem that ended.
     */
    @Synchronized
    fun plan(
        wanted: Set<NotificationIds.StatusKind>,
        alreadyPostedThisBoot: Set<NotificationIds.StatusKind> = emptySet(),
    ): StatusChanges {
        val post = wanted - posted - alreadyPostedThisBoot
        val cancel = NotificationIds.StatusKind.entries.toSet() - wanted
        posted += wanted
        posted -= cancel
        return StatusChanges(post, cancel)
    }
}
