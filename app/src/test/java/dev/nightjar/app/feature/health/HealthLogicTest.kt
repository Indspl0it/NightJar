package dev.nightjar.app.feature.health

import dev.nightjar.app.data.model.HookStatus
import dev.nightjar.contract.HookState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HealthLogicTest {
    private fun hook(
        id: String,
        state: HookState,
        fingerprint: String = "fp",
    ) = HookStatus(
        buildFingerprint = fingerprint,
        hookId = id,
        state = state,
        errorCount = 0,
        lastError = null,
        updatedAt = 0,
    )

    @Test
    fun `live over total counts every hook`() {
        val counts =
            hookCounts(
                listOf(
                    hook("HK-01", HookState.LIVE),
                    hook("HK-02", HookState.LIVE),
                    hook("HK-03", HookState.FAILED),
                ),
            )
        assertEquals(2, counts.live)
        assertEquals(3, counts.total)
        assertFalse(counts.allLive)
    }

    @Test
    fun `an empty hook table is not all live`() {
        assertFalse(hookCounts(emptyList()).allLive)
    }

    @Test
    fun `short fingerprint keeps three fields`() {
        assertEquals("google/akita/akita:17", shortFingerprint("google/akita/akita:17/CP3A/261005:user/release-keys"))
        assertEquals("", shortFingerprint(null))
    }

    @Test
    fun `database row appears only over the limit`() {
        assertFalse(shouldShowDatabaseRow(0))
        assertFalse(shouldShowDatabaseRow(dev.nightjar.app.data.maintenance.RetentionReport.SIZE_LIMIT_BYTES))
        assertTrue(shouldShowDatabaseRow(dev.nightjar.app.data.maintenance.RetentionReport.SIZE_LIMIT_BYTES + 1))
    }
}
