package dev.nightjar.app.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.UiMode
import dev.nightjar.app.ui.components.NjGroup
import dev.nightjar.app.ui.components.NjLineIcons
import dev.nightjar.app.ui.components.NjPrimaryButton
import dev.nightjar.app.ui.components.NjRowDivider
import dev.nightjar.app.ui.components.NjSwitchRow
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme

/** The rounded square behind the shield (72 dp, 24 dp radius). */
private val BadgeShape = RoundedCornerShape(24.dp)

/**
 * 3 Ready (figure 3 of nightjar-onboarding.html): a green badge with a shield, the title, the SIM text, the
 * notification and Researcher mode switches in one card, the dots and "Open NightJar". The body says "The Recommended
 * preset is on." only when [OnboardingUiState.recommendedPreset] is true.
 */
@Composable
internal fun ColumnScope.ReadyStep(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val roles = NjTheme.roles
    OnboardingBody(centred = true, spacing = 0.dp) {
        Box(
            Modifier.size(72.dp).background(roles.ok.copy(alpha = 0.14f), BadgeShape).njEnter(0),
            contentAlignment = Alignment.Center,
        ) {
            Icon(NjLineIcons.SafeMode, contentDescription = null, tint = roles.ok, modifier = Modifier.size(34.dp))
        }
        Text(
            stringResource(R.string.setup_ready_title),
            style = titleStyle(26),
            color = roles.tx,
            modifier = Modifier.padding(top = NjSpacing.m - 6.dp).semantics { heading() }.njEnter(1),
        )
        Text(
            if (state.recommendedPreset) {
                stringResource(R.string.onb_ready_body_recommended, state.simNames)
            } else {
                stringResource(R.string.setup_ready_body, state.simNames)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = roles.mu,
            modifier = Modifier.padding(top = NjSpacing.s).njEnter(1),
        )
        NjGroup(modifier = Modifier.fillMaxWidth().padding(top = NjSpacing.m + 4.dp).njEnter(2)) {
            NjSwitchRow(
                title = stringResource(R.string.setup_notifications),
                supporting = stringResource(R.string.setup_notifications_body),
                checked = state.notificationsAllowed,
                // Android has no "revoke" for an app: turning the switch off opens the system notification settings.
                onCheckedChange = { on -> if (on) onRequestNotifications() else onOpenNotificationSettings() },
            )
            NjRowDivider()
            NjSwitchRow(
                title = stringResource(R.string.setup_researcher),
                supporting = stringResource(R.string.setup_researcher_body),
                checked = state.mode == UiMode.RESEARCHER,
                onCheckedChange = { on -> onEvent(OnboardingEvent.SelectMode(if (on) UiMode.RESEARCHER else UiMode.SIMPLE)) },
            )
        }
    }
    OnboardingFooter(OnboardingPhase.FINISH) {
        NjPrimaryButton(
            text = stringResource(R.string.onb_open_nightjar),
            onClick = { onEvent(OnboardingEvent.Next) },
            modifier = Modifier.fillMaxWidth().njEnter(3),
        )
    }
}
