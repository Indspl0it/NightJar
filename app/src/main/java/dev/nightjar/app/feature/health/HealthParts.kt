package dev.nightjar.app.feature.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme

/** Section title of Health (redesign 7.2 and 7.3): Inter 18 sp bold, more space above than below. */
@Composable
internal fun StatusSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = NjTheme.roles.tx,
        modifier = modifier.fillMaxWidth().padding(start = NjSpacing.xs, top = NjSpacing.m).semantics { heading() },
    )
}

/** A 40 dp `s2` square with a 22 dp [icon] in [tint], the leading part of a check card. */
@Composable
internal fun StatusIconSquare(
    icon: ImageVector,
    tint: Color,
) {
    Box(
        Modifier.size(40.dp).clip(NjShape.iconSquare).background(NjTheme.roles.s2),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
