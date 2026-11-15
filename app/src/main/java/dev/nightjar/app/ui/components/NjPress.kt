package dev.nightjar.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import dev.nightjar.app.ui.theme.NjMotion
import dev.nightjar.app.ui.theme.rememberNjMotion

/** The motion values for the current system setting. A preview has no system setting, so it gets full motion. */
@Composable
internal fun currentNjMotion(): NjMotion = if (LocalInspectionMode.current) NjMotion.Full else rememberNjMotion()

/**
 * Press feedback of redesign 7.4: scale 0.97 with a spring. No scale when animations are off.
 *
 * The factory is not `@Composable` (lint ComposableModifierFactory): the state lives in [composed].
 */
internal fun Modifier.njPressScale(source: InteractionSource): Modifier =
    composed {
        val motion = currentNjMotion()
        val pressed by source.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) motion.pressScale else 1f,
            animationSpec = motion.pressSpec<Float>(),
            label = "njPress",
        )
        Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    }
