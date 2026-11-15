package dev.nightjar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjTheme

/** Size of a top-bar button: search, filter and the menu button are all this size. */
val NjTopBarButtonSize = 40.dp

/** Size of the glyph in a top-bar button. */
private val NjTopBarGlyphSize = 20.dp

/**
 * A 40 dp icon button for the top bar of a screen: no fill, the glyph in the text colour. [highlighted] gives it
 * the `s2` circle, for example while its menu is open. [contentDescription] names the action.
 */
@Composable
fun NjTopBarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    tint: Color = NjTheme.roles.tx,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier =
            modifier
                .size(NjTopBarButtonSize)
                .then(if (highlighted) Modifier.background(NjTheme.roles.s2, NjShape.pill) else Modifier),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(NjTopBarGlyphSize))
    }
}

/** The menu does not get narrower than this. */
private val NjMenuMinWidth = 224.dp

/** Space at the left and the right of a menu item and of the divider. */
private val NjMenuItemPadding = 14.dp

/** Size of the leading glyph of a menu item. */
private val NjMenuGlyphSize = 20.dp

/**
 * The top-right menu of a screen (option A). Every screen with a top-right menu uses it, so all menus
 * look the same: a [NjTopBarIconButton] with the "more" glyph, and a dropdown on `s2` with 16 dp corners and a soft
 * shadow. The button shows the `s2` circle while the menu is open. [content] adds [NjMenuItem], [NjMenuSwitchItem] and
 * [NjMenuDivider]. It gets `close`, which an item calls before it runs its action. With [enabled] false the button
 * does not open the menu.
 */
@Composable
fun NjOverflowMenu(
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val close = { open = false }
    Box(modifier) {
        NjTopBarIconButton(
            icon = NjIcons.More,
            contentDescription = contentDescription,
            onClick = { open = true },
            highlighted = open,
            enabled = enabled,
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = close,
            modifier = Modifier.widthIn(min = NjMenuMinWidth),
            shape = NjShape.field,
            containerColor = NjTheme.roles.s2,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            content(close)
        }
    }
}

/**
 * One item of [NjOverflowMenu]: a leading [icon] in the grey text colour and the [text]. With [destructive] true the
 * text and the icon use the `hi` colour, for an action that cannot be undone (Delete).
 */
@Composable
fun NjMenuItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        modifier = modifier,
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(NjMenuGlyphSize)) },
        enabled = enabled,
        colors = if (destructive) njDestructiveMenuItemColors() else njMenuItemColors(),
        contentPadding = PaddingValues(horizontal = NjMenuItemPadding),
    )
}

/**
 * An item of [NjOverflowMenu] with a switch at the end (: safe mode): the [text], a [supporting] line under it and
 * a switch that shows [checked]. A tap on the row calls [onClick]. TalkBack reads the row as a switch.
 */
@Composable
fun NjMenuSwitchItem(
    text: String,
    supporting: String,
    icon: ImageVector,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(text)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = NjTheme.roles.mu)
            }
        },
        onClick = onClick,
        modifier =
            modifier.semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
            },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(NjMenuGlyphSize)) },
        trailingIcon = { Switch(checked = checked, onCheckedChange = null) },
        colors = njMenuItemColors(),
        contentPadding = PaddingValues(horizontal = NjMenuItemPadding),
    )
}

/** The thin line between two groups of [NjOverflowMenu] items, for example before Help. */
@Composable
fun NjMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = NjMenuItemPadding, vertical = 4.dp),
        color = NjTheme.roles.line,
    )
}

@Composable
private fun njDestructiveMenuItemColors() =
    MenuDefaults.itemColors(
        textColor = NjTheme.roles.hi,
        leadingIconColor = NjTheme.roles.hi,
        trailingIconColor = NjTheme.roles.hi,
    )

@Composable
private fun njMenuItemColors() =
    MenuDefaults.itemColors(
        textColor = NjTheme.roles.tx,
        leadingIconColor = NjTheme.roles.mu,
        trailingIconColor = NjTheme.roles.mu,
    )
