package dev.nightjar.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme

/** One pulse of the shimmer, from `s2` to `s3` and back. */
private const val SHIMMER_MS = 900

/**
 * A loading placeholder: a block that pulses between `s2` and `s3`. When animations are off (animator scale 0) it is a
 * still `s2` block, with no animation. It has no semantics, so TalkBack skips it. Give it a size with [modifier] or
 * use [NjShimmerCard].
 */
@Composable
fun NjShimmer(
    modifier: Modifier = Modifier,
    shape: Shape = NjShape.chip,
) {
    val roles = NjTheme.roles
    val motion = currentNjMotion()
    val color =
        if (motion.enabled) {
            val transition = rememberInfiniteTransition(label = "njShimmer")
            val mix by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing), RepeatMode.Reverse),
                label = "njShimmerMix",
            )
            lerp(roles.s2, roles.s3, mix)
        } else {
            roles.s2
        }
    Box(modifier.clip(shape).background(color).clearAndSetSemantics { })
}

/** A placeholder for one list card: a card with a title line and two text lines. */
@Composable
fun NjShimmerCard(
    modifier: Modifier = Modifier,
    lineHeight: Dp = 14.dp,
) {
    NjCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
            NjShimmer(Modifier.width(160.dp).height(lineHeight + 2.dp))
            NjShimmer(Modifier.fillMaxWidth().height(lineHeight))
            NjShimmer(Modifier.fillMaxWidth(0.7f).height(lineHeight))
        }
    }
}

@Composable
private fun PreviewShimmer() {
    Column(Modifier.padding(NjSpacing.m), verticalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
        NjShimmerCard()
        NjShimmerCard()
    }
}

@Preview(name = "Shimmer dark", widthDp = 400)
@Composable
private fun NjShimmerDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewShimmer() }
}

@Preview(name = "Shimmer light", widthDp = 400)
@Composable
private fun NjShimmerLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { PreviewShimmer() }
}
