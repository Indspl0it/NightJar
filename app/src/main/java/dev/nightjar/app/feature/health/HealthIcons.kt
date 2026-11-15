package dev.nightjar.app.feature.health

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The refresh glyph of the Health top bar, and the row glyphs of the blind spots page.
 *
 * Bundled in code like [dev.nightjar.app.ui.nav.NavIcons] and separate from [SeverityTokens]: those are status tokens
 * (an info bubble, a diamond, a warning triangle) and must not double as actions.
 */
internal object HealthIcons {
    val Refresh: ImageVector by lazy {
        healthIcon("Refresh") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17.65f, 6.35f)
                curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
                curveTo(7.58f, 4f, 4.01f, 7.58f, 4f, 12f)
                curveTo(4f, 16.42f, 7.58f, 20f, 12f, 20f)
                curveTo(15.73f, 20f, 18.84f, 17.45f, 19.73f, 14f)
                horizontalLineTo(17.65f)
                curveTo(16.83f, 16.33f, 14.61f, 18f, 12f, 18f)
                curveTo(8.69f, 18f, 6f, 15.31f, 6f, 12f)
                curveTo(6f, 8.69f, 8.69f, 6f, 12f, 6f)
                curveTo(13.66f, 6f, 15.14f, 6.69f, 16.22f, 7.78f)
                lineTo(13f, 11f)
                horizontalLineTo(20f)
                verticalLineTo(4f)
                close()
            }
        }
    }

    /** A modem chip, for "Modem-only messages" on the blind spots page. Material "memory" shape. */
    val Chip: ImageVector by lazy {
        pathIcon(
            "Chip",
            "M15 9H9v6h6V9zm-2 4h-2v-2h2v2zm8-2V9h-2V7c0-1.1-.9-2-2-2h-2V3h-2v2h-2V3H9v2H7c-1.1 0-2 .9-2 2v2H3v2h2v2H3v2h2" +
                "v2c0 1.1.9 2 2 2h2v2h2v-2h2v2h2v-2h2c1.1 0 2-.9 2-2v-2h2v-2h-2v-2h2zm-4 6H7V7h10v10z",
        )
    }

    /** A cloud, for "RCS chats" (they use the internet). Material "cloud_queue" shape. */
    val Cloud: ImageVector by lazy {
        pathIcon(
            "Cloud",
            "M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13" +
                "c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM19 18H6c-2.21 0-4-1.79-4-4 0-2.05 1.53-3.76 3.56-3.97l1.07-.11" +
                ".5-.95C8.08 7.14 9.94 6 12 6c2.62 0 4.88 1.86 5.39 4.43l.3 1.5 1.53.11c1.56.1 2.78 1.41 2.78 2.96 0 1.65-1.35" +
                " 3-3 3z",
        )
    }

    /** A phone, for "OnePlus and Oppo". Material "smartphone" shape. */
    val Phone: ImageVector by lazy {
        pathIcon(
            "Phone",
            "M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z",
        )
    }
}

/** A 24 dp glyph from SVG path data, filled in the tint of the icon. */
private fun pathIcon(
    name: String,
    pathData: String,
): ImageVector =
    ImageVector
        .Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        .build()

/** Builder shorthand: a 24 dp glyph whose paths are described in the block. */
private fun healthIcon(
    name: String,
    block: ImageVector.Builder.() -> Unit,
): ImageVector =
    ImageVector
        .Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block)
        .build()
