package dev.nightjar.app.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The motion values of redesign 7.4. When the system animator duration scale is 0 ("Remove animations"), every
 * duration is 0 and the list does not bounce at its end.
 *
 * Build it with [rememberNjMotion] in a screen, or with [NjMotion.forAnimatorScale] in a test.
 */
@Immutable
class NjMotion private constructor(
    val enabled: Boolean,
) {
    /** List item enter: fade and move up [listEnterOffset], with a small overshoot. */
    val listEnterMillis: Int = ms(LIST_ENTER_MS)
    val listEnterOffset: Dp = if (enabled) 14.dp else 0.dp
    val tabChangeMillis: Int = ms(TAB_CHANGE_MS)

    /** A screen that opens or closes slides over the full width in this time. */
    val pageSlideMillis: Int = ms(PAGE_SLIDE_MS)

    /** A tab change slides the page body over the full width in this time. */
    val tabSlideMillis: Int = ms(TAB_SLIDE_MS)

    /** Press scale: 0.97 with a spring. No scale at all when animations are off. */
    val pressScale: Float = if (enabled) PRESS_SCALE else 1f

    /** Delay before list item [index] starts: 40 ms per item, no more than 8 items count. */
    fun staggerDelayMillis(index: Int): Int = if (enabled) STAGGER_MS * index.coerceIn(0, STAGGER_MAX_ITEMS - 1) else 0

    /** True when the list may bounce at its end. [userBounce] is the setting "Bounce at list end". */
    fun bounceAllowed(userBounce: Boolean): Boolean = enabled && userBounce

    fun <T> listEnterSpec(index: Int = 0): FiniteAnimationSpec<T> =
        tween(durationMillis = listEnterMillis, delayMillis = staggerDelayMillis(index), easing = ListEnterEasing)

    fun <T> tabChangeSpec(): FiniteAnimationSpec<T> = tween(durationMillis = tabChangeMillis)

    fun <T> pressSpec(): FiniteAnimationSpec<T> =
        if (enabled) {
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
        } else {
            tween(durationMillis = 0)
        }

    private fun ms(value: Int): Int = if (enabled) value else 0

    companion object {
        const val LIST_ENTER_MS = 450
        const val STAGGER_MS = 40
        const val STAGGER_MAX_ITEMS = 8
        const val TAB_CHANGE_MS = 300
        const val PAGE_SLIDE_MS = 300
        const val TAB_SLIDE_MS = 225
        const val PRESS_SCALE = 0.97f

        /** cubic-bezier(0.2, 0.8, 0.2, 1.15): the end control point above 1 gives the overshoot. */
        val ListEnterEasing: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1.15f)

        /** Material emphasized decelerate: a page starts fast and settles softly. */
        val PageEasing: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

        val Full = NjMotion(enabled = true)
        val Off = NjMotion(enabled = false)

        /** Motion for a system animator duration scale. A scale of 0 or less turns all motion off. */
        fun forAnimatorScale(scale: Float): NjMotion = if (scale > 0f) Full else Off
    }
}

/**
 * The motion settings of the user. [reduceMotion] sets every duration to 0, like the system "Remove animations".
 * [listAnimations], [bounce] and [haptics] are for the screens that animate a list or give haptic feedback.
 */
@Immutable
data class NjMotionPrefs(
    val reduceMotion: Boolean = false,
    val listAnimations: Boolean = true,
    val bounce: Boolean = true,
    val haptics: Boolean = true,
)

/** The user's motion settings. [NightjarTheme] provides it from the settings store. */
val LocalNjMotionPrefs = staticCompositionLocalOf { NjMotionPrefs() }

/**
 * The motion for the current system setting. It follows a change of the animator duration scale while the screen is on,
 * so "Remove animations" takes effect without a restart.
 */
@Composable
fun rememberNjMotion(): NjMotion {
    val context = LocalContext.current
    val reduce = LocalNjMotionPrefs.current.reduceMotion
    val scale = remember(context) { mutableFloatStateOf(readAnimatorScale(context.contentResolver)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    scale.floatValue = readAnimatorScale(resolver)
                }
            }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        scale.floatValue = readAnimatorScale(resolver)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return if (reduce) NjMotion.Off else NjMotion.forAnimatorScale(scale.floatValue)
}

private fun readAnimatorScale(resolver: android.content.ContentResolver): Float =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
