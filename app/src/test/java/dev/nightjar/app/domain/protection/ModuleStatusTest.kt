package dev.nightjar.app.domain.protection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Module status and OTA. The setup checklist is tested in OnboardingLogicTest. */
class ModuleStatusTest {
    private val prop = ModuleProp(id = "nightjar", name = "NightJar", version = "0.1.0", versionCode = 1)

    @Test
    fun `not installed when there is no module prop`() {
        val status = ModuleStatus(null, null, false, false, bundledVersionCode = 1)
        assertEquals(ModuleInstallState.NOT_INSTALLED, status.state)
        assertFalse(status.updateAvailable)
    }

    @Test
    fun `an older installed version offers an update`() {
        val status =
            ModuleStatus(ModuleProp("nightjar", "nightjar", "0.0.9", 9), null, false, false, 10)
        assertEquals(ModuleInstallState.UPDATE_AVAILABLE, status.state)
        assertTrue(status.updateAvailable)
    }

    @Test
    fun `a staged update is pending reboot`() {
        val status =
            ModuleStatus(
                installed = prop,
                pendingUpdate = ModuleProp("nightjar", "nightjar", "0.2.0", 2),
                disabled = false,
                removalPending = false,
                bundledVersionCode = 2,
            )
        assertEquals(ModuleInstallState.PENDING_REBOOT, status.state)
    }

    @Test
    fun `disabled and removal markers win over versions`() {
        val disabled = ModuleStatus(prop, null, disabled = true, removalPending = false, 1)
        assertEquals(ModuleInstallState.DISABLED, disabled.state)
        val removing = ModuleStatus(prop, null, disabled = false, removalPending = true, 1)
        assertEquals(ModuleInstallState.REMOVAL_PENDING, removing.state)
    }

    @Test
    fun `OTA detection reports a changed fingerprint once`() {
        val first = OtaDetector.check(null, "fp1")
        assertTrue(first.firstRun)
        assertFalse(first.changed)

        val same = OtaDetector.check("fp1", "fp1")
        assertFalse(same.changed)

        val changed = OtaDetector.check("fp1", "fp2")
        assertTrue(changed.changed)
        assertTrue(changed.shouldPrompt(alreadyPromptedFor = "fp1"))
        assertFalse(changed.shouldPrompt(alreadyPromptedFor = "fp2"))
        assertFalse(OtaDetector.check("fp1", null).changed)
    }
}
