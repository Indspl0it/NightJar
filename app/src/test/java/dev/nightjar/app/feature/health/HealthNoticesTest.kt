package dev.nightjar.app.feature.health

import dev.nightjar.app.core.ipc.HookHealth
import dev.nightjar.app.domain.protection.ProfileStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The rules row of Health from the payload's hook-health document. */
class HealthNoticesTest {
    private fun health(
        revision: Long? = 7,
        error: String? = null,
        skipped: Int? = 0,
    ) = HookHealth(
        profile = ProfileStatus.KNOWN,
        captureTierLive = true,
        firewallTierLive = true,
        failedFirewallHook = null,
        rulesRevision = revision,
        rulesError = error,
        rulesSkipped = skipped,
    )

    @Test
    fun `no health document gives no rules row`() {
        assertNull(rulesHealth(null, appRevision = 7))
    }

    @Test
    fun `the same revision and no error or skipped rule is OK`() {
        assertEquals(RulesHealth(), rulesHealth(health(), appRevision = 7))
    }

    @Test
    fun `another revision than the app pushed means older rules`() {
        assertEquals(RulesHealth(behind = true), rulesHealth(health(revision = 6), appRevision = 7))
    }

    @Test
    fun `an unknown revision on either side is not reported as older rules`() {
        assertEquals(RulesHealth(), rulesHealth(health(revision = null), appRevision = 7))
        assertEquals(RulesHealth(), rulesHealth(health(revision = 6), appRevision = null))
    }

    @Test
    fun `the error keeps the simple class name only`() {
        val rules = rulesHealth(health(error = "org.json.JSONException"), appRevision = 7)
        assertEquals("JSONException", rules?.error)
    }

    @Test
    fun `an error with text other than a class name cannot show that text`() {
        assertEquals("Exceptionatline3", errorClassName("Exception at line 3"))
        assertEquals("Exception", errorClassName(" : "))
        assertEquals(64, errorClassName("A".repeat(200)).length)
    }

    @Test
    fun `skipped rules are counted and a negative count is zero`() {
        assertEquals(3, rulesHealth(health(skipped = 3), appRevision = 7)?.skipped)
        assertEquals(0, rulesHealth(health(skipped = -1), appRevision = 7)?.skipped)
        assertEquals(0, rulesHealth(health(skipped = null), appRevision = 7)?.skipped)
    }
}
