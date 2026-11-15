package dev.nightjar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.ui.theme.NjRoles
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme
import dev.nightjar.app.ui.theme.SeverityTokens
import dev.nightjar.contract.Severity

/** The colour of a severity: info = `mu`, notable = `amb`, suspicious = `sus`, high = `hi` (redesign 7.1). */
internal fun severityColor(
    roles: NjRoles,
    severity: Severity,
): Color =
    when (severity) {
        Severity.INFO -> roles.mu
        Severity.NOTABLE -> roles.amb
        Severity.SUSPICIOUS -> roles.sus
        Severity.HIGH -> roles.hi
    }

/** The fill of a badge: [color] mixed 16 percent into the card colour `s1`. */
internal fun badgeContainer(
    roles: NjRoles,
    color: Color,
): Color = lerp(roles.s1, color, 0.16f)

/**
 * A pill with [text] in 12 sp semibold, in the colour [color] on a fill of the same colour. It is not a button. Pass an
 * [icon] when the colour is the only difference between two badges, so colour is never the only cue.
 */
@Composable
fun NjBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier =
            modifier
                .clip(NjShape.pill)
                .background(badgeContainer(NjTheme.roles, color))
                .padding(horizontal = NjSpacing.s + 2.dp, vertical = NjSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(NjSpacing.xs))
        }
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/** A badge for a [severity]: the severity colour, icon and label. */
@Composable
fun NjBadge(
    severity: Severity,
    modifier: Modifier = Modifier,
) {
    NjBadge(
        text = SeverityTokens.label(severity),
        color = severityColor(NjTheme.roles, severity),
        modifier = modifier,
        icon = SeverityTokens.icon(severity),
    )
}

@Composable
private fun PreviewBadges() {
    Row(Modifier.padding(NjSpacing.m), horizontalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
        Severity.entries.forEach { NjBadge(it) }
    }
}

@Preview(name = "Badge dark")
@Composable
private fun NjBadgeDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewBadges() }
}

@Preview(name = "Badge light")
@Composable
private fun NjBadgeLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { PreviewBadges() }
}
