package dev.nightjar.app.domain.protection

import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ProtectionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Table-driven over every PS rule. */
class ProtectionStateEngineTest {
    private fun handshake(
        bootCount: Int? = 7,
        profile: ProfileStatus = ProfileStatus.KNOWN,
        captureLive: Boolean = true,
        firewallLive: Boolean = true,
        firewallEnabled: Boolean = true,
        failedHook: String? = null,
    ) = HandshakeSnapshot(
        bootCount = bootCount,
        payloadVersion = "0.1.0",
        buildFingerprint = "google/akita/akita:17/CP3A/1",
        profile = profile,
        captureTierLive = captureLive,
        firewallTierLive = firewallLive,
        firewallEnabled = firewallEnabled,
        receivedAtWallMs = 1_000,
        failedFirewallHook = failedHook,
    )

    private fun inputs(
        handshake: HandshakeSnapshot? = handshake(),
        bootCount: Int? = 7,
        userSafeMode: Boolean = false,
    ) = ProtectionInputs(handshake, bootCount, userSafeMode)

    @Test
    fun `capture and firewall live gives PROTECTED`() {
        val report = ProtectionStateEngine.evaluate(inputs())
        assertEquals(ProtectionState.PROTECTED, report.state)
        assertEquals(ProtectionReason.NONE, report.reason)
        assertTrue(report.firewallAvailable)
        assertFalse(report.blockingPaused)
        assertFalse(report.needsBanner)
    }

    @Test
    fun `no handshake is NOT_ACTIVE`() {
        val report = ProtectionStateEngine.evaluate(inputs(handshake = null))
        assertEquals(ProtectionState.NOT_ACTIVE, report.state)
        assertEquals(ProtectionReason.NO_HANDSHAKE_THIS_BOOT, report.reason)
        assertTrue(report.needsBanner)
        assertFalse(report.firewallAvailable)
    }

    @Test
    fun `a handshake from an earlier boot is stale`() {
        val report = ProtectionStateEngine.evaluate(inputs(handshake = handshake(bootCount = 6), bootCount = 7))
        assertEquals(ProtectionState.NOT_ACTIVE, report.state)
    }

    @Test
    fun `without BOOT_COUNT the handshake counts`() {
        val report = ProtectionStateEngine.evaluate(inputs(handshake = handshake(bootCount = null), bootCount = null))
        assertEquals(ProtectionState.PROTECTED, report.state)
    }

    @Test
    fun `a boot count known on one side only cannot be shown current`() {
        assertEquals(
            ProtectionState.NOT_ACTIVE,
            ProtectionStateEngine.evaluate(inputs(handshake = handshake(bootCount = null), bootCount = 7)).state,
        )
        assertEquals(
            ProtectionState.NOT_ACTIVE,
            ProtectionStateEngine.evaluate(inputs(handshake = handshake(bootCount = 7), bootCount = null)).state,
        )
    }

    @Test
    fun `a failed capture tier is UNSUPPORTED`() {
        val report = ProtectionStateEngine.evaluate(inputs(handshake = handshake(captureLive = false)))
        assertEquals(ProtectionState.UNSUPPORTED, report.state)
        assertEquals(ProtectionReason.CAPTURE_TIER_FAILED, report.reason)
        assertTrue(report.needsBanner)
    }

    @Test
    fun `a failed firewall tier stays PROTECTED and keeps the hook for Health`() {
        val report =
            ProtectionStateEngine.evaluate(inputs(handshake = handshake(firewallLive = false, failedHook = "HK-08")))
        assertEquals(ProtectionState.PROTECTED, report.state)
        assertEquals("HK-08", report.failedHook)
        assertFalse(report.firewallAvailable)
        assertFalse(report.needsBanner)
    }

    @Test
    fun `an unknown profile never holds back protection`() {
        val report = ProtectionStateEngine.evaluate(inputs(handshake = handshake(profile = ProfileStatus.UNKNOWN)))
        assertEquals(ProtectionState.PROTECTED, report.state)
        assertEquals(ProtectionReason.NONE, report.reason)
        assertTrue(report.firewallAvailable)
        assertFalse(report.needsBanner)
    }

    @Test
    fun `a build flag for the firewall never changes the state`() {
        val report = ProtectionStateEngine.evaluate(inputs(handshake = handshake(firewallEnabled = false)))
        assertEquals(ProtectionState.PROTECTED, report.state)
        assertTrue(report.firewallAvailable)
        // The report says blocking is off when this build is not enabled.
        assertFalse(report.firewallEnabled)
        assertTrue(ProtectionStateEngine.evaluate(inputs()).firewallEnabled)
    }

    @Test
    fun `user safe mode keeps PROTECTED and only pauses blocking`() {
        val report = ProtectionStateEngine.evaluate(inputs(userSafeMode = true))
        assertEquals(ProtectionState.PROTECTED, report.state)
        assertTrue(report.blockingPaused)
        assertFalse(report.needsBanner)
    }

    @Test
    fun `hero copy distinguishes every state`() {
        val protected = ProtectionStateEngine.evaluate(inputs())
        assertEquals(TextKey.MSG_ST_PROT, ProtectionCopy.hero(protected, "SIM 1").titleKey)

        val paused = ProtectionStateEngine.evaluate(inputs(userSafeMode = true))
        assertEquals(TextKey.MSG_ST_PAUSED, ProtectionCopy.hero(paused, "SIM 1").titleKey)

        // The user turned off the Blocking switch.
        val off = ProtectionStateEngine.evaluate(inputs(handshake = handshake(firewallEnabled = false)))
        assertTrue(off.blockingOff)
        assertEquals(TextKey.MSG_ST_BLOCK_OFF, ProtectionCopy.hero(off, "SIM 1").titleKey)

        val unknown = ProtectionStateEngine.evaluate(inputs(handshake = handshake(profile = ProfileStatus.UNKNOWN)))
        assertEquals(TextKey.MSG_ST_PROT, ProtectionCopy.hero(unknown, "SIM 1").titleKey)

        val firewallFailed =
            ProtectionStateEngine.evaluate(inputs(handshake = handshake(firewallLive = false, failedHook = "HK-08")))
        assertEquals(TextKey.MSG_ST_PROT, ProtectionCopy.hero(firewallFailed, "SIM 1").titleKey)

        val inactive = ProtectionCopy.hero(ProtectionStateEngine.evaluate(inputs(handshake = null)), "SIM 1")
        assertEquals(TextKey.HERO_NA_TITLE, inactive.titleKey)

        val unsupported =
            ProtectionCopy.hero(ProtectionStateEngine.evaluate(inputs(handshake = handshake(captureLive = false))), "SIM 1")
        assertEquals(TextKey.HERO_UNS_TITLE, unsupported.titleKey)

        // No state shows the same text as title and subtitle.
        for (state in ProtectionState.entries) {
            val copy = ProtectionCopy.hero(ProtectionReport(state, ProtectionReason.NONE, false, false, false), "SIM 1")
            assertNotEquals(copy.titleKey, copy.subtitleKey, state.name)
        }
    }
}
