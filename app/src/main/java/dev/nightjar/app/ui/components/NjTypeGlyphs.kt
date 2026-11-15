package dev.nightjar.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Bundled glyphs for the message types that have no icon in [NjIcons]. 24 dp, black, tinted by the caller. */
internal object NjTypeGlyphs {
    /** Silent: an eye with a slash. */
    val Hidden: ImageVector by lazy {
        glyph("Hidden") {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(12f, 5f)
                curveTo(7f, 5f, 3f, 8.5f, 1.5f, 12f)
                curveTo(3f, 15.5f, 7f, 19f, 12f, 19f)
                curveTo(17f, 19f, 21f, 15.5f, 22.5f, 12f)
                curveTo(21f, 8.5f, 17f, 5f, 12f, 5f)
                close()
                moveTo(12f, 7f)
                curveTo(8.5f, 7f, 5.6f, 9.3f, 4.2f, 12f)
                curveTo(5.6f, 14.7f, 8.5f, 17f, 12f, 17f)
                curveTo(15.5f, 17f, 18.4f, 14.7f, 19.8f, 12f)
                curveTo(18.4f, 9.3f, 15.5f, 7f, 12f, 7f)
                close()
                circle(12f, 12f, 2.5f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(3.5f, 4.9f)
                lineTo(4.9f, 3.5f)
                lineTo(20.5f, 19.1f)
                lineTo(19.1f, 20.5f)
                close()
            }
        }
    }

    /** SIM: a card with a cut corner and a chip. */
    val Sim: ImageVector by lazy {
        glyph("Sim") {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(18f, 2f)
                horizontalLineTo(10f)
                lineTo(4f, 8f)
                verticalLineTo(20f)
                curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
                horizontalLineTo(18f)
                curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
                verticalLineTo(4f)
                curveTo(20f, 2.9f, 19.1f, 2f, 18f, 2f)
                close()
                moveTo(8f, 11f)
                horizontalLineTo(16f)
                verticalLineTo(19f)
                horizontalLineTo(8f)
                close()
            }
        }
    }

    /** WAP: a globe. */
    val Globe: ImageVector by lazy {
        glyph("Globe") {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                circle(12f, 12f, 10f)
                circle(12f, 12f, 8.2f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(11f, 2.5f)
                horizontalLineTo(13f)
                verticalLineTo(21.5f)
                horizontalLineTo(11f)
                close()
                moveTo(2.5f, 11f)
                horizontalLineTo(21.5f)
                verticalLineTo(13f)
                horizontalLineTo(2.5f)
                close()
            }
        }
    }

    /** Voicemail: two reels on a tape line. */
    val Voicemail: ImageVector by lazy {
        glyph("Voicemail") {
            path(fill = SolidColor(Color.Black)) {
                circle(6.5f, 10f, 3.5f)
                circle(17.5f, 10f, 3.5f)
                moveTo(6.5f, 14f)
                horizontalLineTo(17.5f)
                verticalLineTo(16.5f)
                horizontalLineTo(6.5f)
                close()
            }
        }
    }

    /** Carrier or app message: four squares. */
    val Apps: ImageVector by lazy {
        glyph("Apps") {
            path(fill = SolidColor(Color.Black)) {
                square(4f, 4f)
                square(14f, 4f)
                square(4f, 14f)
                square(14f, 14f)
            }
        }
    }

    private fun PathBuilder.square(
        x: Float,
        y: Float,
    ) {
        moveTo(x, y)
        horizontalLineTo(x + 6f)
        verticalLineTo(y + 6f)
        horizontalLineTo(x)
        close()
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

    private fun glyph(
        name: String,
        block: ImageVector.Builder.() -> Unit,
    ): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()
}
