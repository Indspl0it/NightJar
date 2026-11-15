package dev.nightjar.app.feature.onboarding

import dev.nightjar.app.domain.protection.InstallCheck
import dev.nightjar.app.domain.protection.InstallCheckId

/**
 * The three onboarding screens: Welcome, Setup (one live checklist) and Ready (notifications, detail level,
 * "Open NightJar").
 */
enum class OnboardingStep(
    val index: Int,
) {
    WELCOME(0),
    CHECKS(1),
    DONE(2),
    ;

    fun next(): OnboardingStep = entries[(index + 1).coerceAtMost(lastIndex)]

    fun previous(): OnboardingStep = entries[(index - 1).coerceAtLeast(0)]

    companion object {
        val lastIndex: Int = entries.last().index
    }
}

/** The four rows of the Setup screen, in screen order. */
enum class SetupRow {
    ROOT,
    FRAMEWORK,
    MODULE,
    REBOOT,
}

/** How a Setup row looks: done, needs the user (shows its steps), the next step, or not reached yet. */
enum class SetupRowState {
    OK,
    NEEDS_YOU,
    NEXT,
    WAITING,
}

data class SetupItem(
    val row: SetupRow,
    val state: SetupRowState,
)

/** The main action of the Setup screen: the button in the card of the current step, or a footer button. */
enum class SetupAction {
    /**
     * No check ran yet. The root card tells the user why NightJar needs root (before:). "Grant root"
     * starts the first check, and the check asks the root manager.
     */
    GRANT_ROOT,

    /** Root or the Xposed framework is missing: the user corrects it outside NightJar, then checks again. */
    CHECK_AGAIN,

    /** The framework is there but the module did not load: open Vector or LSPosed to turn it on. */
    OPEN_FRAMEWORK,

    /** The user turned on the module in this session: reboot so the module loads into the phone service. */
    REBOOT,

    /** The module is active this boot: go to the Ready screen. */
    CONTINUE,
}

/** The second way out of the current Setup state, next to [SetupAction]: "Check again", "Later" or "Continue". */
enum class SetupSecondary {
    NONE,
    CHECK_AGAIN,
    LATER,

    /** The 0.2.0 exit: the module is not active yet, but no check failed, so the user can go to Ready. */
    CONTINUE,
}

/** A button at the bottom of the Setup screen, below the dots. */
enum class SetupFooterButton {
    CHECK_AGAIN,

    /** Go to Ready. The only filled button of the footer when all steps are done, else a text button. */
    CONTINUE,

    /** Before the reboot: onboarding ends, and the user reboots the phone at a later time. */
    LATER,
}

/**
 * Pure decisions of the onboarding flow, kept out of the ViewModel so they can be tested without Android.
 *
 * The Setup screen shows the root, framework and module checks and a reboot row. The Android version and build profile
 * checks stay in [dev.nightjar.app.domain.protection.InstallChecklist], but the screen does not show them: an unknown
 * build only warns, and the app cannot install on Android older than 15.
 */
object OnboardingLogic {
    /**
     * The main action for the live [checks]. [checksRan] is false before the first run. [rebootPending] is true after
     * the user opened Vector or LSPosed in this session (the module loads only after a reboot). [error] is true when
     * the last run stopped with an exception: the user checks again, and does not see the "Grant root" card again.
     */
    fun setupAction(
        checks: List<InstallCheck>,
        checksRan: Boolean,
        rebootPending: Boolean,
        error: Boolean = false,
    ): SetupAction {
        if (checksRan && error) return SetupAction.CHECK_AGAIN
        if (!checksRan || checks.isEmpty()) return SetupAction.GRANT_ROOT
        val root = checks.stateOf(InstallCheckId.ROOT)
        val framework = checks.stateOf(InstallCheckId.FRAMEWORK)
        val module = checks.stateOf(InstallCheckId.MODULE)
        return when {
            root != InstallCheck.State.OK -> SetupAction.CHECK_AGAIN
            framework != InstallCheck.State.OK -> SetupAction.CHECK_AGAIN
            module == InstallCheck.State.OK -> SetupAction.CONTINUE
            rebootPending -> SetupAction.REBOOT
            else -> SetupAction.OPEN_FRAMEWORK
        }
    }

    /**
     * The second way out: "Later" before the reboot. While the module is off it is "Continue" when [canContinue] is true
     * (the 0.2.0 rule), else "Check again".
     */
    fun secondary(
        action: SetupAction,
        canContinue: Boolean,
    ): SetupSecondary =
        when (action) {
            SetupAction.OPEN_FRAMEWORK -> if (canContinue) SetupSecondary.CONTINUE else SetupSecondary.CHECK_AGAIN
            SetupAction.REBOOT -> SetupSecondary.LATER
            else -> SetupSecondary.NONE
        }

    /**
     * The 0.2.0 continue rule: after a check run, the root and module rows exist and no check failed. A module that is
     * not active yet (WARNING: framework found, no handshake this boot) does not block, the same as an unknown build.
     */
    fun canContinue(
        checks: List<InstallCheck>,
        checksRan: Boolean,
    ): Boolean =
        checksRan &&
            checks.stateOf(InstallCheckId.ROOT) != null &&
            checks.stateOf(InstallCheckId.MODULE) != null &&
            checks.none { it.state == InstallCheck.State.FAILED }

    /** True when "Next" on the Setup screen may go to Ready: the screen shows a "Continue" button. */
    fun canLeaveSetup(
        action: SetupAction,
        secondary: SetupSecondary,
    ): Boolean = action == SetupAction.CONTINUE || secondary == SetupSecondary.CONTINUE

    /**
     * The four Setup rows for [action], in [SetupRow] order. Only one row at a time needs the user. For
     * [SetupAction.CHECK_AGAIN] that row is root when [rootOk] is false, else the framework.
     */
    fun setupItems(
        action: SetupAction,
        rootOk: Boolean,
    ): List<SetupItem> {
        val ok = SetupRowState.OK
        val needs = SetupRowState.NEEDS_YOU
        val wait = SetupRowState.WAITING
        val states =
            when (action) {
                SetupAction.GRANT_ROOT -> listOf(needs, wait, wait, wait)
                SetupAction.CHECK_AGAIN -> if (rootOk) listOf(ok, needs, wait, wait) else listOf(needs, wait, wait, wait)
                SetupAction.OPEN_FRAMEWORK -> listOf(ok, ok, needs, wait)
                SetupAction.REBOOT -> listOf(ok, ok, ok, SetupRowState.NEXT)
                SetupAction.CONTINUE -> listOf(ok, ok, ok, ok)
            }
        return SetupRow.entries.zip(states) { row, state -> SetupItem(row, state) }
    }

    /**
     * The row that is the current step of the stepper: the row that needs the user, or the next step. Only
     * this row opens as a card. Null when all rows are done, or after a check error (no row has a result).
     */
    fun currentRow(items: List<SetupItem>): SetupRow? =
        items.firstOrNull { it.state == SetupRowState.NEEDS_YOU || it.state == SetupRowState.NEXT }?.row

    /**
     * The small filled button in the card of the current step, or null. "Check again" ([SetupAction.CHECK_AGAIN]) and
     * "Continue" ([SetupAction.CONTINUE]) are at the bottom of the screen, so their card has no button.
     */
    fun cardAction(action: SetupAction): SetupAction? =
        when (action) {
            SetupAction.GRANT_ROOT, SetupAction.OPEN_FRAMEWORK, SetupAction.REBOOT -> action
            SetupAction.CHECK_AGAIN, SetupAction.CONTINUE -> null
        }

    /**
     * The buttons below the dots, in screen order. Before the first check there is none: the root card has
     * "Grant root". While the module is off, "Check again" is there, and "Continue" next to it when [secondary] allows
     * the 0.2.0 exit. Before the reboot it is "Later". When all steps are done it is "Continue".
     */
    fun footerButtons(
        action: SetupAction,
        secondary: SetupSecondary,
    ): List<SetupFooterButton> =
        when (action) {
            SetupAction.GRANT_ROOT -> {
                emptyList()
            }

            SetupAction.CHECK_AGAIN -> {
                listOf(SetupFooterButton.CHECK_AGAIN)
            }

            SetupAction.OPEN_FRAMEWORK -> {
                if (secondary == SetupSecondary.CONTINUE) {
                    listOf(SetupFooterButton.CHECK_AGAIN, SetupFooterButton.CONTINUE)
                } else {
                    listOf(SetupFooterButton.CHECK_AGAIN)
                }
            }

            SetupAction.REBOOT -> {
                listOf(SetupFooterButton.LATER)
            }

            SetupAction.CONTINUE -> {
                listOf(SetupFooterButton.CONTINUE)
            }
        }

    /**
     * True when the Setup screen runs the checks again on resume. This is only so when the last check run found root
     * (the user comes back from Vector, LSPosed or another app). A run with no root, or one that stopped with an
     * [error], must not start a new check on resume: a new check asks the root manager again, and the closed root prompt
     * itself causes a resume, so the prompt could show again and again.
     */
    fun recheckOnResume(
        step: OnboardingStep,
        checks: List<InstallCheck>,
        checksRan: Boolean,
        checking: Boolean,
        error: Boolean,
    ): Boolean =
        step == OnboardingStep.CHECKS &&
            checksRan &&
            !checking &&
            !error &&
            checks.stateOf(InstallCheckId.ROOT) == InstallCheck.State.OK

    /**
     * The Setup rows for the live [checks]: [setupAction], then [setupItems]. After a run that stopped with an [error]
     * no row needs the user: the rows have no result, and the error text tells the user to check again.
     */
    fun setupItems(
        checks: List<InstallCheck>,
        checksRan: Boolean,
        rebootPending: Boolean,
        error: Boolean = false,
    ): List<SetupItem> {
        if (checksRan && error) return SetupRow.entries.map { SetupItem(it, SetupRowState.WAITING) }
        return setupItems(
            action = setupAction(checks, checksRan, rebootPending),
            rootOk = checks.stateOf(InstallCheckId.ROOT) == InstallCheck.State.OK,
        )
    }

    private fun List<InstallCheck>.stateOf(id: InstallCheckId): InstallCheck.State? = firstOrNull { it.id == id }?.state
}
