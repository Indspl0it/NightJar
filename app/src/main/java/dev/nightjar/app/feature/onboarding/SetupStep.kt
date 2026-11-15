package dev.nightjar.app.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.ui.components.NjBannerKind
import dev.nightjar.app.ui.components.NjInfoBanner
import dev.nightjar.app.ui.components.NjLineIcons
import dev.nightjar.app.ui.components.NjPrimaryButton
import dev.nightjar.app.ui.components.NjTextButton
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme

/** Size of a step circle (28 dp). */
private val StepCircle = 28.dp

/** Width of the line that joins the step circles (2 dp). */
private val StepLine = 2.dp

/** Gap between the step circle and the title (14 dp). */
private val StepGap = NjSpacing.m - 2.dp

/** Corner radius of the card of the current step (18 dp). */
private val CardShape = RoundedCornerShape(18.dp)

/**
 * 2 Setup (figures 2 and 2b of nightjar-onboarding.html): the title, one vertical stepper (root, Vector or
 * LSPosed, module, reboot), the dots and the footer buttons. Only the current step opens as a card with its text and its
 * own button ([OnboardingUiState.cardAction]). The buttons below the dots follow [OnboardingUiState.footer].
 */
@Composable
internal fun ColumnScope.SetupStep(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    onOpenFramework: () -> Unit,
) {
    val roles = NjTheme.roles
    OnboardingBody(centred = false, spacing = 0.dp) {
        Text(
            stringResource(R.string.onb_setup_title),
            style = titleStyle(24),
            color = roles.tx,
            modifier = Modifier.padding(top = NjSpacing.m - 2.dp).semantics { heading() }.njEnter(0),
        )
        Text(
            stringResource(R.string.onb_setup_body),
            style = MaterialTheme.typography.bodyMedium,
            color = roles.mu,
            modifier = Modifier.padding(top = NjSpacing.s).njEnter(0),
        )
        if (state.error) {
            // After a check error no step is current, so the banner tells the user what to do.
            NjInfoBanner(
                text = stringResource(R.string.setup_check_error),
                kind = NjBannerKind.ERROR,
                modifier = Modifier.padding(top = NjSpacing.m - 4.dp),
            )
        }
        Column(Modifier.padding(top = NjSpacing.m + 2.dp).njEnter(1)) {
            val items = state.items
            items.forEachIndexed { index, item ->
                StepperRow(
                    item = item,
                    number = index + 1,
                    last = index == items.lastIndex,
                    state = state,
                    onEvent = onEvent,
                    onOpenFramework = onOpenFramework,
                )
            }
        }
    }
    OnboardingFooter(OnboardingPhase.SETUP) {
        SetupFooter(state, onEvent)
    }
}

/**
 * One step of the stepper: the circle, the title and the one-line detail of a done step. The current step also shows its
 * card. A line joins the circle to the next step: green at 50 % below a done step, else the line colour.
 */
@Composable
private fun StepperRow(
    item: SetupItem,
    number: Int,
    last: Boolean,
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    onOpenFramework: () -> Unit,
) {
    val roles = NjTheme.roles
    val current = item.row == state.currentRow
    val done = item.state == SetupRowState.OK
    val lineColor = if (done) roles.ok.copy(alpha = 0.5f) else roles.line
    val title = stringResource(rowTitle(item))
    val detail = rowDetail(item, state)
    val stateText =
        if (current && state.checking) {
            stringResource(R.string.onb_checking)
        } else {
            stringResource(stateLabel(item.state))
        }
    val description = listOfNotNull(stateText, title, detail).joinToString(", ")
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (!last) {
                    // The line runs from 2 dp below the circle to 2 dp above the next circle.
                    val centre = StepCircle.toPx() / 2f
                    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - centre else centre
                    drawLine(
                        color = lineColor,
                        start = Offset(x, StepCircle.toPx() + 2.dp.toPx()),
                        end = Offset(x, size.height - 2.dp.toPx()),
                        strokeWidth = StepLine.toPx(),
                    )
                }
            }.padding(bottom = if (last) 0.dp else NjSpacing.m),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = StepCircle).clearAndSetSemantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepCircleView(item.state, number, checking = current && state.checking)
            Spacer(Modifier.width(StepGap))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = if (item.state == SetupRowState.WAITING) roles.mu else roles.tx,
                )
                if (detail != null) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = roles.mu)
                }
            }
        }
        if (current) {
            StepCard(item.row, state, onEvent, onOpenFramework)
        }
    }
}

/**
 * Done: a green tint circle with a check. Current: an accent ring with the number (a turning ring while a check runs).
 * Not reached: a ring in the line colour with a muted number.
 */
@Composable
private fun StepCircleView(
    rowState: SetupRowState,
    number: Int,
    checking: Boolean,
) {
    val roles = NjTheme.roles
    val numberStyle = MaterialTheme.typography.labelMedium
    when {
        rowState == SetupRowState.OK -> {
            Box(Modifier.size(StepCircle).background(roles.ok.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(NjLineIcons.Check, contentDescription = null, tint = roles.ok, modifier = Modifier.size(16.dp))
            }
        }

        checking -> {
            Box(Modifier.size(StepCircle), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = roles.acc, strokeWidth = StepLine)
            }
        }

        else -> {
            val current = rowState == SetupRowState.NEEDS_YOU || rowState == SetupRowState.NEXT
            val color = if (current) roles.acc else roles.line
            Box(
                Modifier
                    .size(StepCircle)
                    .background(roles.bg, CircleShape)
                    .border(StepLine, color, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$number", style = numberStyle, color = if (current) roles.acc else roles.mu)
            }
        }
    }
}

/** The card of the current step: its text or numbered steps, then its own small button when it has one. */
@Composable
private fun StepCard(
    row: SetupRow,
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    onOpenFramework: () -> Unit,
) {
    val roles = NjTheme.roles
    Column(
        Modifier
            .padding(start = StepCircle + StepGap, top = NjSpacing.s)
            .fillMaxWidth()
            .background(roles.s1, CardShape)
            .border(1.dp, roles.line, CardShape)
            .padding(NjSpacing.m - 2.dp),
        verticalArrangement = Arrangement.spacedBy(NjSpacing.s),
    ) {
        StepCardText(row, state)
        val action = state.cardAction
        if (action != null) {
            NjPrimaryButton(
                text = stringResource(actionLabel(action)),
                onClick = {
                    when (action) {
                        SetupAction.GRANT_ROOT -> onEvent(OnboardingEvent.GrantRoot)
                        SetupAction.OPEN_FRAMEWORK -> onOpenFramework()
                        SetupAction.REBOOT -> onEvent(OnboardingEvent.Reboot)
                        SetupAction.CHECK_AGAIN -> onEvent(OnboardingEvent.Retry)
                        SetupAction.CONTINUE -> onEvent(OnboardingEvent.Next)
                    }
                },
                enabled = !state.checking && !state.rebooting,
                modifier = Modifier.padding(top = NjSpacing.xs),
            )
        }
    }
}

/** The text of the current step. The root card replaces the root dialog and keeps all of its facts. */
@Composable
private fun StepCardText(
    row: SetupRow,
    state: OnboardingUiState,
) {
    val roles = NjTheme.roles
    val style = MaterialTheme.typography.bodySmall
    when (row) {
        SetupRow.ROOT -> {
            if (state.action == SetupAction.GRANT_ROOT) {
                Text(stringResource(R.string.onb_root_reason), style = style, color = roles.mu)
                Text(stringResource(R.string.onb_root_more), style = style, color = roles.mu)
            } else {
                // Without a root shell NightJar cannot tell a denied prompt from a missing root manager (the manager is
                // probed only as root), so one neutral text covers both.
                Text(stringResource(R.string.setup_root_no_access), style = style, color = roles.mu)
            }
        }

        SetupRow.FRAMEWORK -> {
            Text(stringResource(R.string.onb_no_framework), style = style, color = roles.mu)
        }

        SetupRow.MODULE -> {
            listOf(
                R.string.setup_module_step_1,
                R.string.setup_module_step_2,
                R.string.setup_module_step_3,
            ).forEachIndexed { index, text ->
                Row {
                    Text("${index + 1}.", style = style, color = roles.mu, modifier = Modifier.width(18.dp))
                    Text(stringResource(text), style = style, color = roles.mu)
                }
            }
        }

        SetupRow.REBOOT -> {
            Text(stringResource(R.string.setup_reboot_keeps), style = style, color = roles.mu)
        }
    }
}

/**
 * The buttons below the dots. "Continue" is the one filled button when all steps are done. Else the buttons are text
 * buttons side by side. Every button waits while a check or the reboot runs.
 */
@Composable
private fun SetupFooter(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
) {
    val idle = !state.checking && !state.rebooting
    val buttons = state.footer
    if (state.action == SetupAction.CONTINUE) {
        NjPrimaryButton(
            text = stringResource(R.string.onb_continue),
            onClick = { onEvent(OnboardingEvent.Next) },
            enabled = idle,
            modifier = Modifier.fillMaxWidth().njEnter(2),
        )
        return
    }
    if (buttons.isEmpty()) return
    Row(Modifier.fillMaxWidth().njEnter(2), horizontalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
        buttons.forEach { button ->
            NjTextButton(
                text = stringResource(footerLabel(button)),
                onClick = {
                    when (button) {
                        SetupFooterButton.CHECK_AGAIN -> onEvent(OnboardingEvent.Retry)
                        SetupFooterButton.CONTINUE -> onEvent(OnboardingEvent.Next)
                        SetupFooterButton.LATER -> onEvent(OnboardingEvent.RebootLater)
                    }
                },
                enabled = idle,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@StringRes
private fun actionLabel(action: SetupAction): Int =
    when (action) {
        SetupAction.GRANT_ROOT -> R.string.onb_grant_root
        SetupAction.CHECK_AGAIN -> R.string.setup_check_again
        SetupAction.OPEN_FRAMEWORK -> R.string.setup_open_lsposed
        SetupAction.REBOOT -> R.string.onb_install_and_reboot
        SetupAction.CONTINUE -> R.string.onb_continue
    }

@StringRes
private fun footerLabel(button: SetupFooterButton): Int =
    when (button) {
        SetupFooterButton.CHECK_AGAIN -> R.string.setup_check_again
        SetupFooterButton.CONTINUE -> R.string.onb_continue
        SetupFooterButton.LATER -> R.string.setup_later
    }

@StringRes
private fun rowTitle(item: SetupItem): Int =
    when (item.row) {
        SetupRow.ROOT -> R.string.onb_root
        SetupRow.FRAMEWORK -> R.string.onb_framework
        SetupRow.MODULE -> if (item.state == SetupRowState.OK) R.string.setup_module_on else R.string.setup_module_off
        SetupRow.REBOOT -> R.string.setup_reboot
    }

@StringRes
private fun stateLabel(state: SetupRowState): Int =
    when (state) {
        SetupRowState.OK -> R.string.setup_state_ok
        SetupRowState.NEEDS_YOU -> R.string.setup_state_needs_you
        SetupRowState.NEXT -> R.string.setup_state_next
        SetupRowState.WAITING -> R.string.setup_state_waiting
    }

/** The one-line detail of a done step ("Magisk 30700", "Found"), or null. */
@Composable
private fun rowDetail(
    item: SetupItem,
    state: OnboardingUiState,
): String? =
    when {
        item.state != SetupRowState.OK -> null
        item.row == SetupRow.ROOT -> state.rootLabel
        item.row == SetupRow.FRAMEWORK -> stringResource(R.string.setup_found)
        else -> null
    }
