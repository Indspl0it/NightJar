package dev.nightjar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.ui.theme.LocalNjMotionPrefs
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme

/**
 * The Allow / Hold / Block control. Three equal segments on an `s2` track. The selected segment has the colour of its
 * action (`ok`, `amb`, `hi`) as text and as a 16 percent fill, and a check, so colour is never the only cue. Each
 * segment is 48 dp high. [enabled] turns the whole control on or off. A segment that [enabledActions] leaves out is
 * dimmed, shows a small lock and cannot be selected.
 */
@Composable
fun NjActionSegmented(
    selected: NjAction?,
    onSelect: (NjAction) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    enabledActions: Set<NjAction> = NjAction.entries.toSet(),
) {
    val roles = NjTheme.roles
    val haptic = LocalHapticFeedback.current
    // A short tick when the user picks another action, only when "Haptic feedback" is on (note).
    val haptics = LocalNjMotionPrefs.current.haptics
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.5f)
                .clip(NjShape.field)
                .background(roles.s2)
                .padding(4.dp)
                .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // TalkBack says why a locked action is off, not only "disabled" (review screens-trust L7).
        val lockedText = stringResource(R.string.cd_action_locked)
        NjAction.entries.forEach { action ->
            val isSelected = action == selected
            val actionOn = action in enabledActions
            val color = njActionColor(roles, action)
            val content = if (isSelected) color else roles.mu
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .alpha(if (actionOn) 1f else 0.4f)
                        .clip(NjShape.chip)
                        .background(if (isSelected) badgeContainer(roles, color) else Color.Transparent)
                        .selectable(
                            selected = isSelected,
                            enabled = enabled && actionOn,
                            role = Role.RadioButton,
                        ) {
                            if (haptics && !isSelected) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(action)
                        }.semantics { if (!actionOn) stateDescription = lockedText }
                        .padding(horizontal = NjSpacing.s),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Icon(NjIcons.Check, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(NjSpacing.xs))
                    } else if (!actionOn) {
                        // A lock marks an action that NightJar cannot do for this type.
                        Icon(NjIcons.Lock, contentDescription = null, tint = content, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(NjSpacing.xs))
                    }
                    Text(
                        text = stringResource(njActionLabelRes(action)),
                        style = MaterialTheme.typography.titleSmall,
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewSegmented() {
    var selected by remember { mutableStateOf<NjAction?>(NjAction.HOLD) }
    NjActionSegmented(selected = selected, onSelect = { selected = it }, modifier = Modifier.padding(NjSpacing.m))
}

@Preview(name = "Action segmented dark", widthDp = 400)
@Composable
private fun NjActionSegmentedDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewSegmented() }
}

@Preview(name = "Action segmented light", widthDp = 400)
@Composable
private fun NjActionSegmentedLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { PreviewSegmented() }
}
