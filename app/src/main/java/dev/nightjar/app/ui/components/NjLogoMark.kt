package dev.nightjar.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme

// The part of the 108 x 108 viewport of ic_launcher_foreground.xml that the artwork covers (bird, arcs and the round
// caps of the arcs), so the mark has no empty safe-zone margin around it.
private const val MARK_X = 28f
private const val MARK_Y = 31f
private const val MARK_W = 52f
private const val MARK_H = 46f

/** The eye colour in ic_launcher_foreground.xml. Only this fill keeps an own colour ([NjTheme] `amb`). */
private val LauncherEye = Color(0xFFF4B740)

/**
 * The whole NightJar mark: the bird, the three radio arcs and the eye, read from `ic_launcher_foreground.xml`
 * so it stays the same artwork as the launcher icon. There is no tile background. The bird and the arcs are drawn in
 * [tint] (default `acc`, as [NjBird]) with the alpha steps of the arcs, and the eye in `amb`. Give it a width with
 * [modifier]; the height follows the aspect ratio. The art is decorative, so it has no description.
 */
@Composable
fun NjLogoMark(
    modifier: Modifier = Modifier,
    tint: Color = NjTheme.roles.acc,
) {
    val source = ImageVector.vectorResource(R.drawable.ic_launcher_foreground)
    val eye = NjTheme.roles.amb
    val mark = remember(source, tint, eye) { recolouredMark(source, tint, eye) }
    Image(imageVector = mark, contentDescription = null, modifier = modifier.aspectRatio(MARK_W / MARK_H))
}

/** A copy of [source], cropped to the artwork, with every visible fill and stroke in [tint] and the eye in [eye]. */
private fun recolouredMark(
    source: ImageVector,
    tint: Color,
    eye: Color,
): ImageVector {
    val builder =
        ImageVector.Builder(
            name = "NjLogoMark",
            defaultWidth = MARK_W.dp,
            defaultHeight = MARK_H.dp,
            viewportWidth = MARK_W,
            viewportHeight = MARK_H,
        )
    builder.addGroup(translationX = -MARK_X, translationY = -MARK_Y)
    copyGroup(builder, source.root, tint, eye)
    builder.clearGroup()
    return builder.build()
}

private fun copyGroup(
    builder: ImageVector.Builder,
    group: VectorGroup,
    tint: Color,
    eye: Color,
) {
    for (node in group) {
        when (node) {
            is VectorGroup -> {
                builder.addGroup(
                    name = node.name,
                    rotate = node.rotation,
                    pivotX = node.pivotX,
                    pivotY = node.pivotY,
                    scaleX = node.scaleX,
                    scaleY = node.scaleY,
                    translationX = node.translationX,
                    translationY = node.translationY,
                    clipPathData = node.clipPathData,
                )
                copyGroup(builder, node, tint, eye)
                builder.clearGroup()
            }

            is VectorPath -> {
                builder.addPath(
                    pathData = node.pathData,
                    pathFillType = node.pathFillType,
                    name = node.name,
                    fill = recolour(node.fill, tint, eye),
                    fillAlpha = node.fillAlpha,
                    stroke = recolour(node.stroke, tint, eye),
                    strokeAlpha = node.strokeAlpha,
                    strokeLineWidth = node.strokeLineWidth,
                    strokeLineCap = node.strokeLineCap,
                    strokeLineJoin = node.strokeLineJoin,
                    strokeLineMiter = node.strokeLineMiter,
                    trimPathStart = node.trimPathStart,
                    trimPathEnd = node.trimPathEnd,
                    trimPathOffset = node.trimPathOffset,
                )
            }
        }
    }
}

/** The eye colour becomes [eye], any other visible colour becomes [tint]. A missing or clear brush stays empty. */
private fun recolour(
    brush: Brush?,
    tint: Color,
    eye: Color,
): Brush? {
    val colour = (brush as? SolidColor)?.value ?: return null
    if (colour.alpha == 0f) return null
    return SolidColor(if (colour == LauncherEye) eye else tint)
}

@Preview(name = "Logo mark dark", widthDp = 120, heightDp = 110)
@Composable
private fun NjLogoMarkDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { NjLogoMark(Modifier.width(80.dp)) }
}

@Preview(name = "Logo mark light", widthDp = 120, heightDp = 110)
@Composable
private fun NjLogoMarkLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { NjLogoMark(Modifier.width(80.dp)) }
}
