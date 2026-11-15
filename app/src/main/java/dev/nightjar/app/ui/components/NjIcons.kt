package dev.nightjar.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.nightjar.app.ui.nav.NavIcons
import dev.nightjar.app.ui.theme.SeverityTokens

/**
 * Bundled 24 dp glyphs used by the design system, so the app needs no icon font or icons dependency. All are simple
 * filled shapes drawn in black and tinted by `Icon(tint = ...)`.
 */
object NjIcons {
    /** Shield: protection. */
    val Shield: ImageVector get() = NavIcons.Firewall

    /** Gear. */
    val Gear: ImageVector get() = NavIcons.Settings

    /** Circle with an "i". */
    val Info: ImageVector get() = SeverityTokens.Info

    /** Warning triangle. */
    val Warning: ImageVector get() = SeverityTokens.Suspicious

    /** Diamond. */
    val Diamond: ImageVector get() = SeverityTokens.Notable

    val Back: ImageVector by lazy {
        icon("Back") {
            moveTo(20f, 11f)
            horizontalLineTo(7.8f)
            lineTo(13.4f, 5.4f)
            lineTo(12f, 4f)
            lineTo(4f, 12f)
            lineTo(12f, 20f)
            lineTo(13.4f, 18.6f)
            lineTo(7.8f, 13f)
            horizontalLineTo(20f)
            close()
        }
    }

    val Chevron: ImageVector by lazy {
        icon("Chevron") {
            moveTo(9f, 5.4f)
            lineTo(10.4f, 4f)
            lineTo(18.4f, 12f)
            lineTo(10.4f, 20f)
            lineTo(9f, 18.6f)
            lineTo(15.6f, 12f)
            close()
        }
    }

    val Check: ImageVector by lazy {
        icon("Check") {
            moveTo(9f, 16.2f)
            lineTo(4.8f, 12f)
            lineTo(3.4f, 13.4f)
            lineTo(9f, 19f)
            lineTo(21f, 7f)
            lineTo(19.6f, 5.6f)
            close()
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            moveTo(19f, 6.4f)
            lineTo(17.6f, 5f)
            lineTo(12f, 10.6f)
            lineTo(6.4f, 5f)
            lineTo(5f, 6.4f)
            lineTo(10.6f, 12f)
            lineTo(5f, 17.6f)
            lineTo(6.4f, 19f)
            lineTo(12f, 13.4f)
            lineTo(17.6f, 19f)
            lineTo(19f, 17.6f)
            lineTo(13.4f, 12f)
            close()
        }
    }

    /** Bin with a lid: delete. */
    val Delete: ImageVector by lazy {
        icon("Delete") {
            moveTo(6f, 19f)
            curveTo(6f, 20.1f, 6.9f, 21f, 8f, 21f)
            horizontalLineTo(16f)
            curveTo(17.1f, 21f, 18f, 20.1f, 18f, 19f)
            verticalLineTo(7f)
            horizontalLineTo(6f)
            close()
            moveTo(19f, 4f)
            horizontalLineTo(15.5f)
            lineTo(14.5f, 3f)
            horizontalLineTo(9.5f)
            lineTo(8.5f, 4f)
            horizontalLineTo(5f)
            verticalLineTo(6f)
            horizontalLineTo(19f)
            close()
        }
    }

    val Search: ImageVector by lazy {
        icon("Search") {
            moveTo(15.5f, 14f)
            horizontalLineTo(14.7f)
            lineTo(14.4f, 13.7f)
            curveTo(15.4f, 12.6f, 16f, 11.1f, 16f, 9.5f)
            curveTo(16f, 5.9f, 13.1f, 3f, 9.5f, 3f)
            curveTo(5.9f, 3f, 3f, 5.9f, 3f, 9.5f)
            curveTo(3f, 13.1f, 5.9f, 16f, 9.5f, 16f)
            curveTo(11.1f, 16f, 12.6f, 15.4f, 13.7f, 14.4f)
            lineTo(14f, 14.7f)
            verticalLineTo(15.5f)
            lineTo(19f, 20.5f)
            lineTo(20.5f, 19f)
            close()
            moveTo(9.5f, 14f)
            curveTo(7f, 14f, 5f, 12f, 5f, 9.5f)
            curveTo(5f, 7f, 7f, 5f, 9.5f, 5f)
            curveTo(12f, 5f, 14f, 7f, 14f, 9.5f)
            curveTo(14f, 12f, 12f, 14f, 9.5f, 14f)
            close()
        }
    }

    val Bell: ImageVector by lazy {
        icon("Bell") {
            moveTo(12f, 22f)
            curveTo(13.1f, 22f, 14f, 21.1f, 14f, 20f)
            horizontalLineTo(10f)
            curveTo(10f, 21.1f, 10.9f, 22f, 12f, 22f)
            close()
            moveTo(18f, 16f)
            verticalLineTo(11f)
            curveTo(18f, 7.9f, 16.4f, 5.4f, 13.5f, 4.7f)
            verticalLineTo(4f)
            curveTo(13.5f, 3.2f, 12.8f, 2.5f, 12f, 2.5f)
            curveTo(11.2f, 2.5f, 10.5f, 3.2f, 10.5f, 4f)
            verticalLineTo(4.7f)
            curveTo(7.6f, 5.4f, 6f, 7.9f, 6f, 11f)
            verticalLineTo(16f)
            lineTo(4f, 18f)
            verticalLineTo(19f)
            horizontalLineTo(20f)
            verticalLineTo(18f)
            close()
        }
    }

    val Lock: ImageVector by lazy {
        icon("Lock") {
            moveTo(18f, 8f)
            horizontalLineTo(17f)
            verticalLineTo(6f)
            curveTo(17f, 3.2f, 14.8f, 1f, 12f, 1f)
            curveTo(9.2f, 1f, 7f, 3.2f, 7f, 6f)
            verticalLineTo(8f)
            horizontalLineTo(6f)
            curveTo(4.9f, 8f, 4f, 8.9f, 4f, 10f)
            verticalLineTo(20f)
            curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
            horizontalLineTo(18f)
            curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
            verticalLineTo(10f)
            curveTo(20f, 8.9f, 19.1f, 8f, 18f, 8f)
            close()
            moveTo(9f, 6f)
            curveTo(9f, 4.3f, 10.3f, 3f, 12f, 3f)
            curveTo(13.7f, 3f, 15f, 4.3f, 15f, 6f)
            verticalLineTo(8f)
            horizontalLineTo(9f)
            close()
        }
    }

    /** Sliders: detection thresholds and other tuning. */
    val Tune: ImageVector by lazy {
        icon("Tune") {
            moveTo(3f, 17f)
            verticalLineTo(19f)
            horizontalLineTo(9f)
            verticalLineTo(17f)
            close()
            moveTo(3f, 5f)
            verticalLineTo(7f)
            horizontalLineTo(13f)
            verticalLineTo(5f)
            close()
            moveTo(13f, 21f)
            verticalLineTo(19f)
            horizontalLineTo(21f)
            verticalLineTo(17f)
            horizontalLineTo(13f)
            verticalLineTo(15f)
            horizontalLineTo(11f)
            verticalLineTo(21f)
            close()
            moveTo(7f, 9f)
            verticalLineTo(11f)
            horizontalLineTo(3f)
            verticalLineTo(13f)
            horizontalLineTo(7f)
            verticalLineTo(15f)
            horizontalLineTo(9f)
            verticalLineTo(9f)
            close()
            moveTo(21f, 13f)
            verticalLineTo(11f)
            horizontalLineTo(11f)
            verticalLineTo(13f)
            close()
            moveTo(15f, 9f)
            horizontalLineTo(17f)
            verticalLineTo(7f)
            horizontalLineTo(21f)
            verticalLineTo(5f)
            horizontalLineTo(17f)
            verticalLineTo(3f)
            horizontalLineTo(15f)
            close()
        }
    }

    /** Antenna with signal arcs: cell broadcasts. */
    val Broadcast: ImageVector by lazy {
        icon("Broadcast") {
            circle(12f, 12f, 2.5f)
            moveTo(7.8f, 7.8f)
            lineTo(6.4f, 6.4f)
            curveTo(4.9f, 7.9f, 4f, 9.9f, 4f, 12f)
            curveTo(4f, 14.1f, 4.9f, 16.1f, 6.4f, 17.6f)
            lineTo(7.8f, 16.2f)
            curveTo(6.7f, 15.1f, 6f, 13.6f, 6f, 12f)
            curveTo(6f, 10.4f, 6.7f, 8.9f, 7.8f, 7.8f)
            close()
            moveTo(16.2f, 7.8f)
            curveTo(17.3f, 8.9f, 18f, 10.4f, 18f, 12f)
            curveTo(18f, 13.6f, 17.3f, 15.1f, 16.2f, 16.2f)
            lineTo(17.6f, 17.6f)
            curveTo(19.1f, 16.1f, 20f, 14.1f, 20f, 12f)
            curveTo(20f, 9.9f, 19.1f, 7.9f, 17.6f, 6.4f)
            close()
        }
    }

    /** Paper plane: outgoing messages. */
    val Send: ImageVector by lazy {
        icon("Send") {
            moveTo(2f, 21f)
            lineTo(23f, 12f)
            lineTo(2f, 3f)
            verticalLineTo(10f)
            lineTo(17f, 12f)
            lineTo(2f, 14f)
            close()
        }
    }

    /** Palette-ish half-filled circle: appearance. */
    val Appearance: ImageVector by lazy {
        icon("Appearance") {
            moveTo(12f, 2f)
            curveTo(6.5f, 2f, 2f, 6.5f, 2f, 12f)
            curveTo(2f, 17.5f, 6.5f, 22f, 12f, 22f)
            curveTo(17.5f, 22f, 22f, 17.5f, 22f, 12f)
            curveTo(22f, 6.5f, 17.5f, 2f, 12f, 2f)
            close()
            moveTo(12f, 20f)
            verticalLineTo(4f)
            curveTo(16.4f, 4f, 20f, 7.6f, 20f, 12f)
            curveTo(20f, 16.4f, 16.4f, 20f, 12f, 20f)
            close()
        }
    }

    /** Stacked disks: stored data. */
    val Database: ImageVector by lazy {
        icon("Database") {
            moveTo(12f, 3f)
            curveTo(7.6f, 3f, 4f, 4.3f, 4f, 6f)
            verticalLineTo(18f)
            curveTo(4f, 19.7f, 7.6f, 21f, 12f, 21f)
            curveTo(16.4f, 21f, 20f, 19.7f, 20f, 18f)
            verticalLineTo(6f)
            curveTo(20f, 4.3f, 16.4f, 3f, 12f, 3f)
            close()
            moveTo(12f, 5f)
            curveTo(15.3f, 5f, 17.7f, 5.8f, 18f, 6f)
            curveTo(17.7f, 6.2f, 15.3f, 7f, 12f, 7f)
            curveTo(8.7f, 7f, 6.3f, 6.2f, 6f, 6f)
            curveTo(6.3f, 5.8f, 8.7f, 5f, 12f, 5f)
            close()
            moveTo(18f, 18f)
            curveTo(17.7f, 18.2f, 15.3f, 19f, 12f, 19f)
            curveTo(8.7f, 19f, 6.3f, 18.2f, 6f, 18f)
            verticalLineTo(14.5f)
            curveTo(7.5f, 15.3f, 9.7f, 15.8f, 12f, 15.8f)
            curveTo(14.3f, 15.8f, 16.5f, 15.3f, 18f, 14.5f)
            close()
            moveTo(18f, 12.3f)
            curveTo(17.7f, 12.5f, 15.3f, 13.3f, 12f, 13.3f)
            curveTo(8.7f, 13.3f, 6.3f, 12.5f, 6f, 12.3f)
            verticalLineTo(8.8f)
            curveTo(7.5f, 9.6f, 9.7f, 10.1f, 12f, 10.1f)
            curveTo(14.3f, 10.1f, 16.5f, 9.6f, 18f, 8.8f)
            close()
        }
    }

    /** Wrench-like square with a notch: advanced tools. */
    val Tools: ImageVector by lazy {
        icon("Tools") {
            moveTo(22.7f, 19f)
            lineTo(13.6f, 9.9f)
            curveTo(14.5f, 7.6f, 14f, 4.9f, 12.1f, 3f)
            curveTo(10.1f, 1f, 7.1f, 0.6f, 4.7f, 1.7f)
            lineTo(9f, 6f)
            lineTo(6f, 9f)
            lineTo(1.6f, 4.7f)
            curveTo(0.4f, 7.1f, 0.9f, 10.1f, 2.9f, 12.1f)
            curveTo(4.8f, 14f, 7.5f, 14.5f, 9.8f, 13.6f)
            lineTo(19f, 22.7f)
            curveTo(19.4f, 23.1f, 20f, 23.1f, 20.4f, 22.7f)
            lineTo(22.7f, 20.4f)
            curveTo(23.1f, 20f, 23.1f, 19.4f, 22.7f, 19f)
            close()
        }
    }

    /** Three dots, vertical. */
    val More: ImageVector by lazy {
        icon("More") {
            circle(12f, 5f, 2f)
            circle(12f, 12f, 2f)
            circle(12f, 19f, 2f)
        }
    }

    /** Magnifier-less tray: empty state. */
    val Inbox: ImageVector by lazy {
        icon("Inbox") {
            moveTo(19f, 3f)
            horizontalLineTo(5f)
            curveTo(3.9f, 3f, 3f, 3.9f, 3f, 5f)
            verticalLineTo(19f)
            curveTo(3f, 20.1f, 3.9f, 21f, 5f, 21f)
            horizontalLineTo(19f)
            curveTo(20.1f, 21f, 21f, 20.1f, 21f, 19f)
            verticalLineTo(5f)
            curveTo(21f, 3.9f, 20.1f, 3f, 19f, 3f)
            close()
            moveTo(19f, 15f)
            horizontalLineTo(15f)
            curveTo(15f, 16.7f, 13.7f, 18f, 12f, 18f)
            curveTo(10.3f, 18f, 9f, 16.7f, 9f, 15f)
            horizontalLineTo(5f)
            verticalLineTo(5f)
            horizontalLineTo(19f)
            close()
        }
    }

    private fun PathBuilder.circle(
        cx: Float,
        cy: Float,
        r: Float,
    ) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, true, true, cx + r, cy)
        arcTo(r, r, 0f, true, true, cx - r, cy)
        close()
    }

    private fun icon(
        name: String,
        block: PathBuilder.() -> Unit,
    ): ImageVector =
        ImageVector
            .Builder(name, 24.dp, 24.dp, 24f, 24f)
            .apply { path(fill = SolidColor(Color.Black), pathBuilder = block) }
            .build()
}
