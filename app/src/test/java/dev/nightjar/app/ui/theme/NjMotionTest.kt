package dev.nightjar.app.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The motion values of redesign 7.4, and the switch-off when the system animator scale is 0. */
class NjMotionTest {
    @Test
    fun `full motion has the redesign durations`() {
        val m = NjMotion.forAnimatorScale(1f)
        assertTrue(m.enabled)
        assertEquals(450, m.listEnterMillis)
        assertEquals(300, m.tabChangeMillis)
        assertEquals(300, m.pageSlideMillis)
        assertEquals(225, m.tabSlideMillis)
        assertEquals(0.97f, m.pressScale)
    }

    @Test
    fun `the stagger is 40 ms per item and stops after 8 items`() {
        val m = NjMotion.forAnimatorScale(1f)
        assertEquals(0, m.staggerDelayMillis(0))
        assertEquals(40, m.staggerDelayMillis(1))
        assertEquals(280, m.staggerDelayMillis(7))
        assertEquals(280, m.staggerDelayMillis(8))
        assertEquals(280, m.staggerDelayMillis(100))
    }

    @Test
    fun `an animator scale of 0 gives zero durations and no bounce`() {
        listOf(0f, -1f).forEach { scale ->
            val m = NjMotion.forAnimatorScale(scale)
            assertFalse(m.enabled)
            assertEquals(0, m.listEnterMillis)
            assertEquals(0, m.tabChangeMillis)
            assertEquals(0, m.pageSlideMillis)
            assertEquals(0, m.tabSlideMillis)
            assertEquals(0, m.staggerDelayMillis(5))
            assertEquals(1f, m.pressScale)
            assertFalse(m.bounceAllowed(userBounce = true))
        }
    }

    @Test
    fun `bounce needs both the setting and animations`() {
        val on = NjMotion.forAnimatorScale(1.5f)
        assertTrue(on.bounceAllowed(userBounce = true))
        assertFalse(on.bounceAllowed(userBounce = false))
    }
}
