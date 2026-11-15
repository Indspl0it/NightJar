package dev.nightjar.app.feature.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.ui.components.NjLineIcons
import dev.nightjar.app.ui.components.NjLogoMark
import dev.nightjar.app.ui.components.NjPrimaryButton
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme

/** Corner radius of a point card (16 dp). */
private val PointShape = RoundedCornerShape(16.dp)

/**
 * 1 Welcome (welcome text option "B: Question"): the whole logo, the question, one short paragraph, three
 * point cards, the privacy footnote, the dots and "Start".
 */
@Composable
internal fun ColumnScope.WelcomeStep(onEvent: (OnboardingEvent) -> Unit) {
    val roles = NjTheme.roles
    OnboardingBody(centred = true, spacing = 0.dp) {
        NjLogoMark(Modifier.width(96.dp).njEnter(0))
        Text(
            stringResource(R.string.onb_welcome_title),
            // 24, not 26 as on Ready: the longer title takes about 5 lines at 26 on a 360 dp screen at the
            // largest font size. The body scrolls, so a long title is never cut.
            style = titleStyle(24),
            color = roles.tx,
            modifier = Modifier.padding(top = NjSpacing.m - 6.dp).semantics { heading() }.njEnter(1),
        )
        Text(
            stringResource(R.string.onb_welcome_body),
            style = MaterialTheme.typography.bodyMedium,
            color = roles.mu,
            modifier = Modifier.padding(top = NjSpacing.s).njEnter(1),
        )
        Column(
            Modifier.padding(top = NjSpacing.l - 2.dp).njEnter(2),
            verticalArrangement = Arrangement.spacedBy(NjSpacing.s + 2.dp),
        ) {
            WelcomePoint(NjLineIcons.Ping, R.string.onb_point_silent, R.string.onb_point_silent_body)
            WelcomePoint(NjLineIcons.SettingsPush, R.string.onb_point_settings, R.string.onb_point_settings_body)
            WelcomePoint(NjLineIcons.Location, R.string.onb_point_location, R.string.onb_point_location_body)
        }
        PrivacyFootnote(Modifier.padding(top = NjSpacing.m - 2.dp).njEnter(2))
    }
    OnboardingFooter(OnboardingPhase.WELCOME) {
        NjPrimaryButton(
            text = stringResource(R.string.onb_get_started),
            onClick = { onEvent(OnboardingEvent.Start) },
            modifier = Modifier.fillMaxWidth().njEnter(3),
        )
    }
}

/** One point card: an accent line icon, a bold name and one muted line. */
@Composable
private fun WelcomePoint(
    icon: ImageVector,
    @StringRes name: Int,
    @StringRes body: Int,
) {
    val roles = NjTheme.roles
    Row(
        Modifier
            .fillMaxWidth()
            .background(roles.s1, PointShape)
            .padding(horizontal = NjSpacing.m - 2.dp, vertical = NjSpacing.m - 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NjSpacing.m - 4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = roles.acc, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(name),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = roles.tx,
            )
            Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = roles.mu)
        }
    }
}

/** The quiet privacy line below the points, with a small lock. */
@Composable
private fun PrivacyFootnote(modifier: Modifier = Modifier) {
    val roles = NjTheme.roles
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NjSpacing.s),
    ) {
        Icon(NjLineIcons.Lock, contentDescription = null, tint = roles.mu, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.onb_privacy), style = MaterialTheme.typography.labelMedium, color = roles.mu)
    }
}
