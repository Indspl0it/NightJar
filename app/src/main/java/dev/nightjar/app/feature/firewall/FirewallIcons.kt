package dev.nightjar.app.feature.firewall

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Glyphs of the Protection screen that [dev.nightjar.app.ui.components.NjIcons] does not have. Material
 * shapes, 24 dp, drawn in the tint of the icon.
 */
internal object FirewallIcons {
    /** A bell with a line through it: "Notify me" is off for this type. Material "notifications_off" shape. */
    val BellOff: ImageVector by lazy {
        ImageVector
            .Builder(name = "BellOff", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData =
                    addPathNodes(
                        "M20 18.69L7.84 6.14 5.27 3.49 4 4.76l2.8 2.8v.01c-.52.99-.8 2.16-.8 3.42v5l-2 2v1h13.73l2 2L21 19.72" +
                            "l-1-1.03zM12 22c1.11 0 2-.89 2-2h-4c0 1.11.89 2 2 2zm6-7.32V11c0-3.08-1.64-5.64-4.5-6.32V4" +
                            "c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68c-.15.03-.29.08-.42.12-.1.03-.2.07-.3.11h-.01" +
                            "c-.01 0-.01 0-.02.01-.23.09-.46.2-.68.31 0 0-.01 0-.01.01L18 14.68z",
                    ),
                fill = SolidColor(Color.Black),
            ).build()
    }
}
