package dev.nightjar.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.domain.text.MapStrings
import dev.nightjar.app.ui.text.LocalStrings

/**
 * The Material colour scheme for a set of roles (redesign 7.1). The Material surface roles map to the Nightjar roles:
 * `surface` = `bg`, the containers = `s1` `s2` `s3`, `onSurface` = `tx`, `onSurfaceVariant` = `mu`, `primary` = `acc` and
 * `error` = `hi`. The accent may come from the wallpaper (Monet); everything else is fixed.
 */
internal fun njColorScheme(roles: NjRoles): ColorScheme {
    val onAccent = if (roles.isDark) Color(0xFF0A0E15) else Color.White
    val accContainer = lerp(roles.s1, roles.acc, 0.22f)
    val errorContainer = lerp(roles.s1, roles.hi, 0.22f)
    val base = if (roles.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = roles.acc,
        onPrimary = onAccent,
        primaryContainer = accContainer,
        onPrimaryContainer = roles.tx,
        secondary = roles.acc,
        onSecondary = onAccent,
        secondaryContainer = roles.s3,
        onSecondaryContainer = roles.tx,
        tertiary = roles.acc,
        onTertiary = onAccent,
        tertiaryContainer = roles.s3,
        onTertiaryContainer = roles.tx,
        background = roles.bg,
        onBackground = roles.tx,
        surface = roles.bg,
        onSurface = roles.tx,
        surfaceVariant = roles.s2,
        onSurfaceVariant = roles.mu,
        surfaceTint = roles.acc,
        surfaceBright = roles.s2,
        surfaceDim = roles.bg,
        surfaceContainerLowest = roles.bg,
        surfaceContainerLow = roles.s1,
        surfaceContainer = roles.s1,
        surfaceContainerHigh = roles.s2,
        surfaceContainerHighest = roles.s3,
        outline = roles.mu,
        outlineVariant = roles.line,
        error = roles.hi,
        onError = onAccent,
        errorContainer = errorContainer,
        onErrorContainer = roles.tx,
    )
}

/** M3 Expressive shape scale. */
val NightjarShapes: Shapes =
    Shapes(
        extraSmall =
            androidx.compose.foundation.shape
                .RoundedCornerShape(4.dp),
        small =
            androidx.compose.foundation.shape
                .RoundedCornerShape(8.dp),
        medium =
            androidx.compose.foundation.shape
                .RoundedCornerShape(12.dp),
        large =
            androidx.compose.foundation.shape
                .RoundedCornerShape(16.dp),
        extraLarge =
            androidx.compose.foundation.shape
                .RoundedCornerShape(28.dp),
    )

/**
 * A text style in the Android system font. The app bundles no font: every style uses [FontFamily.Default], at
 * the sizes, weights and line heights of redesign 7.3.
 */
private fun sys(
    size: Int,
    weight: FontWeight,
    line: Int = size + 6,
) = TextStyle(fontFamily = FontFamily.Default, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

/**
 * Type scale (redesign 7.3). Large title = `displaySmall`, card title and big numbers = `headlineMedium` and
 * `headlineSmall`, section title = `titleLarge`, row title = `titleMedium`, body = `bodyMedium`, secondary = `bodySmall`
 * and `labelLarge`, badge and label = `labelMedium`. `labelSmall` stays monospace, for hex and codes.
 */
val NightjarTypography: Typography =
    Typography().let { base ->
        base.copy(
            // A larger title sat off the line of the top-right icons. It is
            // 28 sp, and the glyphs are centred in the line box (no extra top or bottom space), so the title centre
            // matches the icon centre in the title row.
            displaySmall =
                sys(28, FontWeight.Bold, line = 34).copy(
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                ),
            headlineMedium = sys(30, FontWeight.Bold, line = 36),
            headlineSmall = sys(28, FontWeight.Bold, line = 34),
            titleLarge = sys(18, FontWeight.Bold, line = 24),
            titleMedium = sys(15, FontWeight.SemiBold, line = 21),
            titleSmall = sys(14, FontWeight.SemiBold, line = 20),
            bodyLarge = sys(15, FontWeight.Normal, line = 22),
            bodyMedium = sys(14, FontWeight.Normal, line = 21),
            bodySmall = sys(13, FontWeight.Normal, line = 18),
            labelLarge = sys(13, FontWeight.Medium, line = 18),
            labelMedium = sys(12, FontWeight.SemiBold, line = 16),
            labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
    }

/**
 * True when animations may run, that is when the system animator duration scale is not 0 ("Remove animations").
 * [rememberNjMotion] has the full motion values and follows a change of the setting.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean = rememberNjMotion().enabled

/**
 * The app theme.
 *
 * [themeMode] and [dynamicColor] come from the settings store, so switching a theme recomposes without a restart.
 * [accent] is the colour theme. With [dynamicColor] on (Monet) the accent comes from the wallpaper. The severity colours
 * are the same in every theme and never follow the accent or the wallpaper. [ThemeMode.BLACK] and [trueBlack] (dark
 * theme only) give the true-black surfaces. [motionPrefs] are the motion settings of the user.
 */
@Composable
fun NightjarTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    accent: NjAccent = NjAccent.NIGHT_BLUE,
    trueBlack: Boolean = false,
    motionPrefs: NjMotionPrefs = NjMotionPrefs(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark =
        when (themeMode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK, ThemeMode.BLACK -> true
        }
    val mode =
        when {
            themeMode == ThemeMode.BLACK || (trueBlack && dark) -> NjSurfaceMode.BLACK
            dark -> NjSurfaceMode.DARK
            else -> NjSurfaceMode.LIGHT
        }
    // The wallpaper scheme reads system resources, so it is built again only when one of its inputs changes.
    val accentColor =
        remember(context, dark, dynamicColor, accent) {
            if (dynamicColor) {
                (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).primary
            } else {
                accent.color(dark)
            }
        }
    val roles = remember(mode, accentColor) { njRoles(mode, accentColor) }
    val scheme = remember(roles) { njColorScheme(roles) }
    val view = LocalView.current
    if (!view.isInEditMode) {
        // `uiMode` is in the activity's configChanges, so a system theme flip does not recreate the activity and
        // enableEdgeToEdge() never re-evaluates. The bars follow the in-app theme, not the system one.
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(
        LocalNjRoles provides roles,
        LocalNjMotionPrefs provides motionPrefs,
        LocalNjColors provides remember(roles) { njColors(roles) },
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = NightjarShapes,
            typography = NightjarTypography,
        ) {
            NjOverscrollGate(motionPrefs, content)
        }
    }
}

/**
 * "Bounce at list end" for every list of the app (note, redesign 7.4). When the setting is off, "Reduce motion" is
 * on or the system animations are off, no list shows an overscroll effect ([NjMotion.bounceAllowed]; [rememberNjMotion]
 * reads "Reduce motion" from [LocalNjMotionPrefs], which the caller provides). A preview always shows the effect. A
 * screen needs no gate of its own.
 */
@Composable
private fun NjOverscrollGate(
    motionPrefs: NjMotionPrefs,
    content: @Composable () -> Unit,
) {
    val motion = if (LocalInspectionMode.current) NjMotion.Full else rememberNjMotion()
    val bounce = motion.bounceAllowed(motionPrefs.bounce)
    CompositionLocalProvider(
        LocalOverscrollFactory provides (if (bounce) LocalOverscrollFactory.current else null),
        content = content,
    )
}

/**
 * [NightjarTheme] plus the English template strings, for `@Preview`s and screenshot tests.
 *
 * [LocalStrings] deliberately has no default — a release build must never fall back to the English templates instead of
 * `res/values-<qualifier>/strings.xml` — and a preview has no activity behind it, so previews wrap their content in this instead
 * of calling [NightjarTheme] directly.
 */
@Composable
fun PreviewTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean = false,
    accent: NjAccent = NjAccent.NIGHT_BLUE,
    content: @Composable () -> Unit,
) {
    NightjarTheme(themeMode = themeMode, dynamicColor = dynamicColor, accent = accent) {
        CompositionLocalProvider(LocalStrings provides MapStrings(), content = content)
    }
}

/** The activity behind a composition's view context, or null in a preview/embedded context. */
internal tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
