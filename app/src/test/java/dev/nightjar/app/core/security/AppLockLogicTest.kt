package dev.nightjar.app.core.security

import dev.nightjar.app.core.settings.LockTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Lock decision, without a device: the cold-start case and every grace period are the whole rule, so the
 * controller must not re-derive it inline.
 */
class AppLockLogicTest {
    private val now = 1_793_836_800_000L

    @Test
    fun `the lock is off when the setting is off`() {
        assertFalse(shouldLock(appLock = false, timeout = LockTimeout.IMMEDIATELY, backgroundedAt = null, now = now))
        assertFalse(shouldLock(appLock = false, timeout = LockTimeout.ONE_MIN, backgroundedAt = now, now = now))
    }

    @Test
    fun `a cold start always locks`() {
        LockTimeout.entries.forEach { timeout ->
            assertTrue(
                shouldLock(appLock = true, timeout = timeout, backgroundedAt = null, now = now),
                "cold start with $timeout must lock",
            )
        }
    }

    @Test
    fun `immediately locks as soon as the app was backgrounded at all`() {
        assertTrue(shouldLock(appLock = true, timeout = LockTimeout.IMMEDIATELY, backgroundedAt = now, now = now))
        // A clock that moved backwards must not unlock the app either.
        assertTrue(shouldLock(appLock = true, timeout = LockTimeout.IMMEDIATELY, backgroundedAt = now, now = now - 5_000))
    }

    @Test
    fun `the app locks when the grace period has fully passed, not one millisecond before`() {
        val fiveMin = LockTimeout.FIVE_MIN
        assertFalse(shouldLock(appLock = true, timeout = fiveMin, backgroundedAt = now, now = now + fiveMin.millis - 1))
        assertTrue(shouldLock(appLock = true, timeout = fiveMin, backgroundedAt = now, now = now + fiveMin.millis))
        assertTrue(shouldLock(appLock = true, timeout = fiveMin, backgroundedAt = now, now = now + fiveMin.millis + 60_000))
    }

    @Test
    fun `a lock surface that is already showing is not opened by a quick return`() {
        // Home pressed on the lock screen, app reopened inside the grace period: the decision alone says "unlocked".
        assertFalse(shouldLock(appLock = true, timeout = LockTimeout.FIVE_MIN, backgroundedAt = now, now = now + 1_000))
        assertTrue(lockAfterForeground(current = true, decision = false))
        assertFalse(lockAfterForeground(current = false, decision = false))
        assertFalse(lockAfterForeground(current = null, decision = false))
        assertTrue(lockAfterForeground(current = null, decision = true))
    }
}
