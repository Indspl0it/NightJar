package dev.nightjar.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import dev.nightjar.app.ui.theme.LocalNjMotionPrefs

/**
 * The enter motion of one section of a screen (redesign 7.4): fade in and move up 14 dp, 40 ms after the section before.
 * Every screen with a large title uses it. It plays one time for each [index]. There is no motion when the system
 * animations are off, when "Reduce motion" is on or when "List animations" is off. A screen that arrives with a
 * navigation motion ([LocalNjArrivedWithMotion]) shows its sections at once: the page slide is the motion, and a second
 * motion inside the sliding page reads as a stutter.
 *
 * The factory is not `@Composable` (lint ComposableModifierFactory): the state lives in [composed].
 */
fun Modifier.njEnter(index: Int): Modifier =
    composed {
        val motion = currentNjMotion()
        val animate = motion.enabled && LocalNjMotionPrefs.current.listAnimations && !LocalNjArrivedWithMotion.current
        var played by rememberSaveable { mutableStateOf(false) }
        val progress = remember { Animatable(if (animate && !played) 0f else 1f) }
        LaunchedEffect(animate) {
            if (animate && !played) {
                progress.animateTo(1f, motion.listEnterSpec<Float>(index))
            } else {
                progress.snapTo(1f)
            }
            played = true
        }
        val offsetPx = with(LocalDensity.current) { motion.listEnterOffset.toPx() }
        Modifier.graphicsLayer {
            val p = progress.value
            // The easing overshoots 1, so the alpha stays inside 0..1.
            alpha = p.coerceIn(0f, 1f)
            translationY = (1f - p) * offsetPx
        }
    }

/** True for a screen that arrived with a navigation motion (a page or tab slide). The navigation host provides it. */
val LocalNjArrivedWithMotion = compositionLocalOf { false }
