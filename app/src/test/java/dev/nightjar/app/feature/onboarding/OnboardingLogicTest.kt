package dev.nightjar.app.feature.onboarding

import dev.nightjar.app.domain.protection.InstallCheck
import dev.nightjar.app.domain.protection.InstallCheckId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OnboardingLogicTest {
    private fun check(
        id: InstallCheckId,
        state: InstallCheck.State,
    ) = InstallCheck(id, state)

    private val ready =
        listOf(
            check(InstallCheckId.ROOT, InstallCheck.State.OK),
            check(InstallCheckId.FRAMEWORK, InstallCheck.State.OK),
            check(InstallCheckId.ANDROID_VERSION, InstallCheck.State.OK),
            check(InstallCheckId.BUILD_PROFILE, InstallCheck.State.OK),
            check(InstallCheckId.MODULE, InstallCheck.State.OK),
        )

    private fun List<InstallCheck>.with(
        id: InstallCheckId,
        state: InstallCheck.State,
    ) = map { if (it.id == id) check(id, state) else it }

    /** Framework present, module not loaded this boot: InstallChecklist gives a warning, not a failure. */
    private val moduleOff = ready.with(InstallCheckId.MODULE, InstallCheck.State.WARNING)

    private fun states(items: List<SetupItem>) = items.map { it.state }

    @Test
    fun `steps advance and stop at the last one`() {
        assertEquals(OnboardingStep.CHECKS, OnboardingStep.WELCOME.next())
        assertEquals(OnboardingStep.DONE, OnboardingStep.CHECKS.next())
        assertEquals(OnboardingStep.DONE, OnboardingStep.DONE.next())
        assertEquals(OnboardingStep.CHECKS, OnboardingStep.DONE.previous())
        assertEquals(OnboardingStep.WELCOME, OnboardingStep.WELCOME.previous())
    }

    @Test
    fun `before the first check the button asks for root`() {
        assertEquals(SetupAction.GRANT_ROOT, OnboardingLogic.setupAction(emptyList(), checksRan = false, rebootPending = false))
        assertEquals(SetupAction.GRANT_ROOT, OnboardingLogic.setupAction(ready, checksRan = false, rebootPending = false))
        assertEquals(SetupSecondary.NONE, OnboardingLogic.secondary(SetupAction.GRANT_ROOT, canContinue = false))
        val items = OnboardingLogic.setupItems(emptyList(), checksRan = false, rebootPending = false)
        assertEquals(SetupRow.entries, items.map { it.row })
        assertEquals(
            listOf(SetupRowState.NEEDS_YOU, SetupRowState.WAITING, SetupRowState.WAITING, SetupRowState.WAITING),
            states(items),
        )
    }

    @Test
    fun `denied root or a missing framework asks the user to check again`() {
        val denied = ready.with(InstallCheckId.ROOT, InstallCheck.State.FAILED)
        assertEquals(SetupAction.CHECK_AGAIN, OnboardingLogic.setupAction(denied, checksRan = true, rebootPending = false))
        assertEquals(
            listOf(SetupRowState.NEEDS_YOU, SetupRowState.WAITING, SetupRowState.WAITING, SetupRowState.WAITING),
            states(OnboardingLogic.setupItems(denied, checksRan = true, rebootPending = false)),
        )

        val noFramework =
            ready
                .with(InstallCheckId.FRAMEWORK, InstallCheck.State.FAILED)
                .with(InstallCheckId.MODULE, InstallCheck.State.FAILED)
        assertEquals(SetupAction.CHECK_AGAIN, OnboardingLogic.setupAction(noFramework, checksRan = true, rebootPending = false))
        assertEquals(
            listOf(SetupRowState.OK, SetupRowState.NEEDS_YOU, SetupRowState.WAITING, SetupRowState.WAITING),
            states(OnboardingLogic.setupItems(noFramework, checksRan = true, rebootPending = false)),
        )
        assertEquals(SetupSecondary.NONE, OnboardingLogic.secondary(SetupAction.CHECK_AGAIN, canContinue = false))
    }

    @Test
    fun `a module that is not loaded opens LSPosed, with continue below (0_2_0 rule)`() {
        assertEquals(SetupAction.OPEN_FRAMEWORK, OnboardingLogic.setupAction(moduleOff, checksRan = true, rebootPending = false))
        assertTrue(OnboardingLogic.canContinue(moduleOff, checksRan = true))
        assertEquals(SetupSecondary.CONTINUE, OnboardingLogic.secondary(SetupAction.OPEN_FRAMEWORK, canContinue = true))
        assertEquals(SetupSecondary.CHECK_AGAIN, OnboardingLogic.secondary(SetupAction.OPEN_FRAMEWORK, canContinue = false))
        assertTrue(OnboardingLogic.canLeaveSetup(SetupAction.OPEN_FRAMEWORK, SetupSecondary.CONTINUE))
        assertFalse(OnboardingLogic.canLeaveSetup(SetupAction.OPEN_FRAMEWORK, SetupSecondary.CHECK_AGAIN))
        assertEquals(
            listOf(SetupRowState.OK, SetupRowState.OK, SetupRowState.NEEDS_YOU, SetupRowState.WAITING),
            states(OnboardingLogic.setupItems(moduleOff, checksRan = true, rebootPending = false)),
        )
    }

    @Test
    fun `after LSPosed was opened the next step is the reboot, with later below`() {
        assertEquals(SetupAction.REBOOT, OnboardingLogic.setupAction(moduleOff, checksRan = true, rebootPending = true))
        assertEquals(SetupSecondary.LATER, OnboardingLogic.secondary(SetupAction.REBOOT, canContinue = true))
        assertEquals(
            listOf(SetupRowState.OK, SetupRowState.OK, SetupRowState.OK, SetupRowState.NEXT),
            states(OnboardingLogic.setupItems(moduleOff, checksRan = true, rebootPending = true)),
        )
    }

    @Test
    fun `an active module continues to Ready`() {
        assertEquals(SetupAction.CONTINUE, OnboardingLogic.setupAction(ready, checksRan = true, rebootPending = false))
        assertEquals(SetupAction.CONTINUE, OnboardingLogic.setupAction(ready, checksRan = true, rebootPending = true))
        assertEquals(SetupSecondary.NONE, OnboardingLogic.secondary(SetupAction.CONTINUE, canContinue = true))
        assertTrue(OnboardingLogic.canLeaveSetup(SetupAction.CONTINUE, SetupSecondary.NONE))
        assertEquals(
            List(SetupRow.entries.size) { SetupRowState.OK },
            states(OnboardingLogic.setupItems(ready, checksRan = true, rebootPending = false)),
        )
    }

    @Test
    fun `an unknown build only warns and does not block`() {
        val warn = ready.with(InstallCheckId.BUILD_PROFILE, InstallCheck.State.WARNING)
        assertEquals(SetupAction.CONTINUE, OnboardingLogic.setupAction(warn, checksRan = true, rebootPending = false))
    }

    @Test
    fun `a missing root or module row does not crash`() {
        val noRoot = ready.filter { it.id != InstallCheckId.ROOT }
        assertEquals(SetupAction.CHECK_AGAIN, OnboardingLogic.setupAction(noRoot, checksRan = true, rebootPending = false))
        val noModule = ready.filter { it.id != InstallCheckId.MODULE }
        assertEquals(SetupAction.OPEN_FRAMEWORK, OnboardingLogic.setupAction(noModule, checksRan = true, rebootPending = false))
    }

    @Test
    fun `continue follows the 0_2_0 rule`() {
        assertTrue(OnboardingLogic.canContinue(ready, checksRan = true))
        assertTrue(OnboardingLogic.canContinue(moduleOff, checksRan = true))
        assertTrue(OnboardingLogic.canContinue(moduleOff.with(InstallCheckId.BUILD_PROFILE, InstallCheck.State.WARNING), checksRan = true))
        // Before the first run, and after a run with no result, nothing allows Continue.
        assertFalse(OnboardingLogic.canContinue(moduleOff, checksRan = false))
        assertFalse(OnboardingLogic.canContinue(emptyList(), checksRan = true))
        // Any failed check blocks, the same as 0.2.0 canContinue().
        assertFalse(OnboardingLogic.canContinue(moduleOff.with(InstallCheckId.ROOT, InstallCheck.State.FAILED), checksRan = true))
        assertFalse(OnboardingLogic.canContinue(moduleOff.with(InstallCheckId.FRAMEWORK, InstallCheck.State.FAILED), checksRan = true))
        assertFalse(OnboardingLogic.canContinue(ready.with(InstallCheckId.MODULE, InstallCheck.State.FAILED), checksRan = true))
        val oldAndroid = moduleOff.with(InstallCheckId.ANDROID_VERSION, InstallCheck.State.FAILED)
        assertFalse(OnboardingLogic.canContinue(oldAndroid, checksRan = true))
        // 0.2.0 retried when the root or module row was missing.
        assertFalse(OnboardingLogic.canContinue(moduleOff.filter { it.id != InstallCheckId.ROOT }, checksRan = true))
        assertFalse(OnboardingLogic.canContinue(moduleOff.filter { it.id != InstallCheckId.MODULE }, checksRan = true))
    }

    @Test
    fun `resume runs the checks again only when the last run had root`() {
        fun recheck(
            checks: List<InstallCheck>,
            step: OnboardingStep = OnboardingStep.CHECKS,
            checksRan: Boolean = true,
            checking: Boolean = false,
            error: Boolean = false,
        ) = OnboardingLogic.recheckOnResume(step, checks, checksRan, checking, error)

        // Back from LSPosed with root: check again.
        assertTrue(recheck(moduleOff))
        assertTrue(recheck(ready))
        // Back from a denied root prompt: never ask for root again by itself.
        assertFalse(recheck(moduleOff.with(InstallCheckId.ROOT, InstallCheck.State.FAILED)))
        assertFalse(recheck(moduleOff.filter { it.id != InstallCheckId.ROOT }))
        // A run that stopped with an error has no root result.
        assertFalse(recheck(emptyList(), error = true))
        assertFalse(recheck(moduleOff, error = true))
        // No run yet, a run in progress, or another screen.
        assertFalse(recheck(emptyList(), checksRan = false))
        assertFalse(recheck(moduleOff, checking = true))
        assertFalse(recheck(moduleOff, step = OnboardingStep.WELCOME))
        assertFalse(recheck(moduleOff, step = OnboardingStep.DONE))
    }

    @Test
    fun `a check error asks to check again, not for root`() {
        fun action(
            checks: List<InstallCheck>,
            checksRan: Boolean = true,
            rebootPending: Boolean = false,
        ) = OnboardingLogic.setupAction(checks, checksRan, rebootPending, error = true)

        // The first run threw: no checks, but the run happened.
        assertEquals(SetupAction.CHECK_AGAIN, action(emptyList()))
        // A later run threw: the old checks stay, but they are not a result.
        assertEquals(SetupAction.CHECK_AGAIN, action(ready))
        assertEquals(SetupAction.CHECK_AGAIN, action(moduleOff, rebootPending = true))
        assertEquals(SetupSecondary.NONE, OnboardingLogic.secondary(SetupAction.CHECK_AGAIN, canContinue = true))
        assertEquals(
            List(SetupRow.entries.size) { SetupRowState.WAITING },
            states(OnboardingLogic.setupItems(ready, checksRan = true, rebootPending = false, error = true)),
        )
        // Without a run, the error flag does not skip the root rationale.
        assertEquals(SetupAction.GRANT_ROOT, action(emptyList(), checksRan = false))
    }

    @Test
    fun `the current step is the row that needs the user or the next step`() {
        fun current(
            checks: List<InstallCheck>,
            checksRan: Boolean = true,
            rebootPending: Boolean = false,
            error: Boolean = false,
        ) = OnboardingLogic.currentRow(OnboardingLogic.setupItems(checks, checksRan, rebootPending, error))

        val denied = ready.with(InstallCheckId.ROOT, InstallCheck.State.FAILED)
        val noFramework = ready.with(InstallCheckId.FRAMEWORK, InstallCheck.State.FAILED)
        assertEquals(SetupRow.ROOT, current(emptyList(), checksRan = false))
        assertEquals(SetupRow.ROOT, current(denied))
        assertEquals(SetupRow.FRAMEWORK, current(noFramework))
        assertEquals(SetupRow.MODULE, current(moduleOff))
        assertEquals(SetupRow.REBOOT, current(moduleOff, rebootPending = true))
        // All steps done, or a check error: no step opens as a card.
        assertNull(current(ready))
        assertNull(current(ready, error = true))
    }

    @Test
    fun `the card has its own button only when the action is in the card`() {
        assertEquals(SetupAction.GRANT_ROOT, OnboardingLogic.cardAction(SetupAction.GRANT_ROOT))
        assertEquals(SetupAction.OPEN_FRAMEWORK, OnboardingLogic.cardAction(SetupAction.OPEN_FRAMEWORK))
        assertEquals(SetupAction.REBOOT, OnboardingLogic.cardAction(SetupAction.REBOOT))
        assertNull(OnboardingLogic.cardAction(SetupAction.CHECK_AGAIN))
        assertNull(OnboardingLogic.cardAction(SetupAction.CONTINUE))
    }

    @Test
    fun `the footer buttons follow the setup state`() {
        fun footer(
            action: SetupAction,
            canContinue: Boolean,
        ) = OnboardingLogic.footerButtons(action, OnboardingLogic.secondary(action, canContinue))

        // Figure 2b: before the first check, "Grant root" is in the root card and the footer has no button.
        assertEquals(emptyList<SetupFooterButton>(), footer(SetupAction.GRANT_ROOT, canContinue = false))
        assertEquals(listOf(SetupFooterButton.CHECK_AGAIN), footer(SetupAction.CHECK_AGAIN, canContinue = false))
        assertEquals(listOf(SetupFooterButton.CHECK_AGAIN), footer(SetupAction.OPEN_FRAMEWORK, canContinue = false))
        // The 0.2.0 exit stays reachable next to "Check again".
        assertEquals(
            listOf(SetupFooterButton.CHECK_AGAIN, SetupFooterButton.CONTINUE),
            footer(SetupAction.OPEN_FRAMEWORK, canContinue = true),
        )
        assertEquals(listOf(SetupFooterButton.LATER), footer(SetupAction.REBOOT, canContinue = true))
        assertEquals(listOf(SetupFooterButton.CONTINUE), footer(SetupAction.CONTINUE, canContinue = true))
    }

    @Test
    fun `every state where setup can be left shows a continue button`() {
        SetupAction.entries.forEach { action ->
            listOf(true, false).forEach { canContinue ->
                val secondary = OnboardingLogic.secondary(action, canContinue)
                assertEquals(
                    OnboardingLogic.canLeaveSetup(action, secondary),
                    SetupFooterButton.CONTINUE in OnboardingLogic.footerButtons(action, secondary),
                    "$action $canContinue",
                )
            }
        }
    }

    @Test
    fun `a check error shows check again in the footer and no card`() {
        val action = OnboardingLogic.setupAction(ready, checksRan = true, rebootPending = false, error = true)
        assertEquals(listOf(SetupFooterButton.CHECK_AGAIN), OnboardingLogic.footerButtons(action, OnboardingLogic.secondary(action, true)))
        assertNull(OnboardingLogic.cardAction(action))
    }

    @Test
    fun `only one row at a time needs the user`() {
        SetupAction.entries.forEach { action ->
            listOf(true, false).forEach { rootOk ->
                val items = OnboardingLogic.setupItems(action, rootOk)
                assertEquals(SetupRow.entries, items.map { it.row })
                assertTrue(items.count { it.state == SetupRowState.NEEDS_YOU } <= 1, "$action $rootOk")
            }
        }
    }
}
