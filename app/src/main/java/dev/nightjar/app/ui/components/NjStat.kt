package dev.nightjar.app.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** One column of [NjStatStrip]: an [icon] in a [tint], a [value] and a [label]. A tap calls [onClick]. */
@Immutable
data class NjStat(
    val icon: ImageVector,
    val value: Int,
    val label: String,
    val tint: Color,
    val onClick: () -> Unit,
)
