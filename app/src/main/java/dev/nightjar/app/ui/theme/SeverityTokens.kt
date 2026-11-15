package dev.nightjar.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.contract.Severity

/**
 * The four status colours (redesign 7.1): [ok] active or delivered, [amb] on hold, [sus] suspicious, [hi] high or Block.
 * They are fixed: no accent theme and no dynamic colour changes them, so a colour always keeps its meaning. Each one
 * keeps a contrast of 4.5:1 or more on the card colour `s1` of its own theme (checked by `NjPaletteContrastTest`).
 */
@Immutable
data class NjStatusColors(
    val ok: Color,
    val amb: Color,
    val sus: Color,
    val hi: Color,
) {
    companion object {
        val Dark = NjStatusColors(ok = Color(0xFF5FD3A8), amb = Color(0xFFF4B740), sus = Color(0xFFFFA552), hi = Color(0xFFFF8A8A))

        // amb light is #9A6208, not #B7791F: that one has only 3.6:1 on white.
        val Light = NjStatusColors(ok = Color(0xFF1B7F57), amb = Color(0xFF9A6208), sus = Color(0xFFB4500A), hi = Color(0xFFC02D2D))
    }
}

/**
 * Severity icons and labels: ⓘ info, ◆ notable, ⚠ suspicious, ⛔ high.
 *
 * The vectors are bundled in code so no downloadable font is needed and the icons exist before the drawable
 * pipeline runs. Each is a 24 dp Material Symbols Rounded–style glyph.
 */
object SeverityTokens {
    /** ⓘ — a circle with an "i". */
    val Info: ImageVector by lazy {
        materialIcon("Info") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
                curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
                close()
                moveTo(13f, 17f)
                horizontalLineTo(11f)
                verticalLineTo(11f)
                horizontalLineTo(13f)
                verticalLineTo(17f)
                close()
                moveTo(13f, 9f)
                horizontalLineTo(11f)
                verticalLineTo(7f)
                horizontalLineTo(13f)
                verticalLineTo(9f)
                close()
            }
        }
    }

    /** ◆ — a diamond. */
    val Notable: ImageVector by lazy {
        materialIcon("Notable") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                lineTo(22f, 12f)
                lineTo(12f, 22f)
                lineTo(2f, 12f)
                close()
            }
        }
    }

    /** ⚠ — a warning triangle. */
    val Suspicious: ImageVector by lazy {
        materialIcon("Suspicious") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                lineTo(23f, 21f)
                horizontalLineTo(1f)
                close()
                moveTo(13f, 18f)
                horizontalLineTo(11f)
                verticalLineTo(16f)
                horizontalLineTo(13f)
                close()
                moveTo(13f, 14f)
                horizontalLineTo(11f)
                verticalLineTo(9f)
                horizontalLineTo(13f)
                close()
            }
        }
    }

    /** ⛔ — an octagon with an exclamation mark. */
    val High: ImageVector by lazy {
        materialIcon("High") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(8.27f, 2f)
                horizontalLineTo(15.73f)
                lineTo(22f, 8.27f)
                verticalLineTo(15.73f)
                lineTo(15.73f, 22f)
                horizontalLineTo(8.27f)
                lineTo(2f, 15.73f)
                verticalLineTo(8.27f)
                close()
                moveTo(13f, 17f)
                horizontalLineTo(11f)
                verticalLineTo(15f)
                horizontalLineTo(13f)
                close()
                moveTo(13f, 13f)
                horizontalLineTo(11f)
                verticalLineTo(7f)
                horizontalLineTo(13f)
                close()
            }
        }
    }

    fun icon(severity: Severity): ImageVector =
        when (severity) {
            Severity.INFO -> Info
            Severity.NOTABLE -> Notable
            Severity.SUSPICIOUS -> Suspicious
            Severity.HIGH -> High
        }

    /** Text label resource, so the severity is never conveyed by colour alone. */
    fun labelRes(severity: Severity): Int =
        when (severity) {
            Severity.INFO -> R.string.severity_info
            Severity.NOTABLE -> R.string.severity_notable
            Severity.SUSPICIOUS -> R.string.severity_suspicious
            Severity.HIGH -> R.string.severity_high
        }

    @Composable
    fun label(severity: Severity): String = stringResource(labelRes(severity))
}

/** Builder shorthand: a 24 dp icon whose paths are described in the block. */
private fun materialIcon(
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
