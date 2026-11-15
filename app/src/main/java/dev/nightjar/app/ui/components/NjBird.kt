package dev.nightjar.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme

/** Default width of the bird in an empty state. */
internal val NjBirdWidth = 140.dp

private const val BIRD_W = 142f
private const val BIRD_H = 42f

/**
 * The bird of the launcher icon in flight, as two vectors with the same viewport: the body (drawn in the accent) and
 * the eye (drawn in `amb`). The shapes come from `ic_launcher_foreground.xml`. The art is decorative, so it has no description.
 */
private fun birdVector(
    name: String,
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
): ImageVector =
    ImageVector
        .Builder(name, BIRD_W.dp, BIRD_H.dp, BIRD_W, BIRD_H)
        .apply {
            // The source path starts at x 34, y 54 in the launcher icon: move it to the corner.
            group(translationX = -34f, translationY = -54f) {
                path(fill = SolidColor(Color.Black), pathBuilder = block)
            }
        }.build()

internal val NjBirdBody: ImageVector by lazy {
    birdVector("NjBirdBody") {
        moveTo(105f, 70f)
        curveTo(97f, 64f, 82f, 58f, 34f, 54f)
        curveTo(68f, 66f, 82f, 71f, 94f, 76f)
        lineTo(88f, 94f)
        lineTo(105f, 85f)
        lineTo(122f, 94f)
        lineTo(116f, 76f)
        curveTo(128f, 71f, 142f, 66f, 176f, 54f)
        curveTo(128f, 58f, 113f, 64f, 105f, 70f)
        close()
    }
}

internal val NjBirdEye: ImageVector by lazy {
    birdVector("NjBirdEye") {
        moveTo(102.8f, 67f)
        arcTo(2.2f, 2.2f, 0f, true, false, 107.2f, 67f)
        arcTo(2.2f, 2.2f, 0f, true, false, 102.8f, 67f)
        close()
    }
}

/**
 * The bird artwork. Give it a width with [modifier]; the height follows the aspect ratio. The body follows the accent
 * colour ([tint], default `acc`). The eye is always `amb`.
 */
@Composable
fun NjBird(
    modifier: Modifier = Modifier,
    tint: Color = NjTheme.roles.acc,
) {
    Box(modifier.aspectRatio(BIRD_W / BIRD_H)) {
        Icon(NjBirdBody, contentDescription = null, tint = tint, modifier = Modifier.matchParentSize())
        Icon(NjBirdEye, contentDescription = null, tint = NjTheme.roles.amb, modifier = Modifier.matchParentSize())
    }
}

@Preview(name = "Bird dark", widthDp = 200, heightDp = 80)
@Composable
private fun NjBirdPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { NjBird(Modifier.width(NjBirdWidth)) }
}
