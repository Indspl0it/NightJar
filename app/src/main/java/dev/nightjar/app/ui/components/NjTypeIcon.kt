package dev.nightjar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.domain.ClassFamily
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme

/** Corner radius of the type icon square (redesign 7.2: 12 to 14 dp). */
private val TypeIconShape = RoundedCornerShape(14.dp)

/** The icon of a message type, taken from its [ClassFamily]. */
fun typeIconFor(family: ClassFamily): ImageVector =
    when (family) {
        ClassFamily.SILENT -> NjTypeGlyphs.Hidden
        ClassFamily.SIM -> NjTypeGlyphs.Sim
        ClassFamily.WAP -> NjTypeGlyphs.Globe
        ClassFamily.VOICEMAIL -> NjTypeGlyphs.Voicemail
        ClassFamily.APP -> NjTypeGlyphs.Apps
        ClassFamily.BROADCAST -> NjIcons.Broadcast
        ClassFamily.OUTBOUND -> NjIcons.Send
        ClassFamily.SYSTEM -> NjIcons.Info
    }

/**
 * The icon of a message type in a rounded square (14 dp radius, `s2` fill). The icon comes from the type's
 * [ClassFamily]. Colour the icon with [tint], for example the severity colour. TalkBack reads the type name. Set
 * [decorative] to true when a text with the same name is next to the icon.
 */
@Composable
fun NjTypeIcon(
    family: ClassFamily,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = NjTheme.roles.mu,
    decorative: Boolean = false,
) {
    val label = familyLabel(family)
    Box(
        modifier =
            modifier
                .size(size)
                .clip(TypeIconShape)
                .background(NjTheme.roles.s2)
                .then(
                    if (decorative) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier.semantics { contentDescription = label }
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(typeIconFor(family), contentDescription = null, tint = tint, modifier = Modifier.size(size / 2))
    }
}

@Composable
private fun PreviewTypeIcons() {
    Row(Modifier.padding(NjSpacing.m), horizontalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
        ClassFamily.entries.forEach { NjTypeIcon(it, decorative = true, tint = NjTheme.roles.sus) }
    }
}

@Preview(name = "Type icons dark", widthDp = 420)
@Composable
private fun NjTypeIconDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewTypeIcons() }
}

@Preview(name = "Type icons light", widthDp = 420)
@Composable
private fun NjTypeIconLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { PreviewTypeIcons() }
}
