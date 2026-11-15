package dev.nightjar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme

/** Tonal rounded square holding a row's leading [icon]. */
@Composable
private fun RowIcon(
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(NjShape.chip)
            .background(NjTheme.roles.s2),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun RowText(
    title: String,
    supporting: String?,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The standard list row: optional leading icon (or custom [leading] slot), [title], optional [supporting] line, and on
 * the right a [trailingValue], a custom [trailing] slot and/or a chevron. Rows with [onClick] are clickable and show the
 * chevron by default; set [destructive] for danger actions. Use inside [NjGroup].
 */
@Composable
fun NjListRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leadingIcon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    trailingValue: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    destructive: Boolean = false,
) {
    val titleColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val base = modifier.fillMaxWidth()
    Row(
        modifier =
            (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base)
                .heightIn(min = 56.dp)
                .padding(horizontal = NjSpacing.m, vertical = NjSpacing.s + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(NjSpacing.m))
        } else if (leadingIcon != null) {
            RowIcon(leadingIcon, if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(NjSpacing.m))
        }
        RowText(title, supporting, titleColor, Modifier.weight(1f))
        if (trailingValue != null) {
            Spacer(Modifier.width(NjSpacing.s))
            Text(
                trailingValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(NjSpacing.s))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(NjSpacing.xs))
            Icon(
                NjIcons.Chevron,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A row with a [Switch]; the whole row toggles. */
@Composable
fun NjSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .heightIn(min = 56.dp)
                .padding(horizontal = NjSpacing.m, vertical = NjSpacing.s + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            RowIcon(leadingIcon, MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(NjSpacing.m))
        }
        RowText(title, supporting, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        Spacer(Modifier.width(NjSpacing.m))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A single-choice row with a radio button, for pickers inside sheets and dialogs. */
@Composable
fun NjChoiceRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    // A disabled row cannot be selected, TalkBack reads it as disabled, and its title uses the disabled alpha.
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
                .heightIn(min = 52.dp)
                .padding(horizontal = NjSpacing.m, vertical = NjSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(NjSpacing.m))
        val titleColor = MaterialTheme.colorScheme.onSurface
        RowText(title, supporting, if (enabled) titleColor else titleColor.copy(alpha = DISABLED_CONTENT_ALPHA), Modifier.weight(1f))
    }
}

/** The Material 3 alpha for disabled content. */
private const val DISABLED_CONTENT_ALPHA = 0.38f
