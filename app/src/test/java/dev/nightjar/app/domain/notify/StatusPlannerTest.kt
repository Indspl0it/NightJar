package dev.nightjar.app.domain.notify

import dev.nightjar.app.domain.notify.NotificationIds.StatusKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A status notification posts when the problem starts and goes when the problem ends. */
class StatusPlannerTest {
    @Test
    fun `a problem posts once and does not come back on every refresh`() {
        val planner = StatusPlanner()
        assertEquals(setOf(StatusKind.NOT_ACTIVE), planner.plan(setOf(StatusKind.NOT_ACTIVE)).post)
        // The user removed it; the next refreshes must not post it again while the problem stays.
        assertTrue(planner.plan(setOf(StatusKind.NOT_ACTIVE)).post.isEmpty())
        assertTrue(planner.plan(setOf(StatusKind.NOT_ACTIVE)).post.isEmpty())
    }

    @Test
    fun `a solved problem is removed and posts again when it comes back`() {
        val planner = StatusPlanner()
        planner.plan(setOf(StatusKind.NOT_ACTIVE))
        val solved = planner.plan(emptySet())
        assertTrue(StatusKind.NOT_ACTIVE in solved.cancel)
        assertTrue(solved.post.isEmpty())
        assertEquals(setOf(StatusKind.NOT_ACTIVE), planner.plan(setOf(StatusKind.NOT_ACTIVE)).post)
    }

    @Test
    fun `no problem removes every status notification`() {
        val changes = StatusPlanner().plan(emptySet())
        assertTrue(changes.post.isEmpty())
        assertEquals(StatusKind.entries.toSet(), changes.cancel)
    }

    @Test
    fun `a problem that the boot check posted is not posted again`() {
        val planner = StatusPlanner()
        val changes = planner.plan(setOf(StatusKind.NOT_ACTIVE), alreadyPostedThisBoot = setOf(StatusKind.NOT_ACTIVE))
        assertTrue(changes.post.isEmpty())
        assertTrue(StatusKind.NOT_ACTIVE !in changes.cancel)
    }

    @Test
    fun `problems are independent`() {
        val planner = StatusPlanner()
        planner.plan(setOf(StatusKind.NOT_ACTIVE, StatusKind.VERSION_MISMATCH))
        val changes = planner.plan(setOf(StatusKind.VERSION_MISMATCH))
        assertTrue(StatusKind.NOT_ACTIVE in changes.cancel)
        assertTrue(StatusKind.VERSION_MISMATCH !in changes.cancel)
        assertTrue(changes.post.isEmpty())
    }
}
