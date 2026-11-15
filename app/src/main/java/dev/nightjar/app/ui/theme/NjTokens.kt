package dev.nightjar.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.nightjar.contract.Severity

/**
 * Nightjar design tokens (v0.2 design system). Everything a screen needs besides the Material colour scheme lives here:
 * spacing, shapes and the semantic status / verdict colours. Screens must not hard-code any of these values.
 */
object NjSpacing {
    /** 4 dp: icon-to-text gaps. */
    val xs = 4.dp

    /** 8 dp: gaps inside a component. */
    val s = 8.dp

    /** 16 dp: screen gutter and the gap between cards. */
    val m = 16.dp

    /** 24 dp: gap between sections. */
    val l = 24.dp
}

/** Shape tokens (redesign 7.2): cards 24 dp, list groups 22 dp, chips and banners 12 dp, text fields 16 dp. */
object NjShape {
    val card = RoundedCornerShape(24.dp)
    val group = RoundedCornerShape(22.dp)
    val iconSquare = RoundedCornerShape(12.dp)
    val chip = RoundedCornerShape(12.dp)
    val field = RoundedCornerShape(16.dp)
    val pill = RoundedCornerShape(percent = 50)
}

/** Protection state of the app (home hero, banners). */
enum class NjStatus {
    PROTECTED,
    DEGRADED,
    INACTIVE,
    ;

    companion object {
        /** Maps the severity token the protection copy already produces. */
        fun fromSeverity(severity: Severity): NjStatus =
            when (severity) {
                Severity.INFO -> PROTECTED
                Severity.NOTABLE -> DEGRADED
                Severity.SUSPICIOUS -> INACTIVE
                Severity.HIGH -> INACTIVE
            }
    }
}

/** What happened to a message; drives the colour of chips and highlighted stat tiles. */
enum class NjVerdict {
    HIDDEN,
    SUSPICIOUS,
    QUARANTINED,
    INFO,
    ;

    companion object {
        fun fromSeverity(severity: Severity): NjVerdict =
            when (severity) {
                Severity.INFO -> INFO
                Severity.NOTABLE -> HIDDEN
                Severity.SUSPICIOUS -> SUSPICIOUS
                Severity.HIGH -> SUSPICIOUS
            }
    }
}

/** A colour triple: a tonal [container], the [content] drawn on it and a stronger [accent] for icons and buttons. */
@Immutable
data class NjTone(
    val container: Color,
    val content: Color,
    val accent: Color,
)

/** Semantic colours, fixed across themes and independent of dynamic colour so meaning never changes with wallpaper. */
@Immutable
data class NjColors(
    val secure: NjTone,
    val degraded: NjTone,
    val inactive: NjTone,
    val hidden: NjTone,
    val suspicious: NjTone,
    val quarantined: NjTone,
    val info: NjTone,
) {
    fun of(status: NjStatus): NjTone =
        when (status) {
            NjStatus.PROTECTED -> secure
            NjStatus.DEGRADED -> degraded
            NjStatus.INACTIVE -> inactive
        }

    fun of(verdict: NjVerdict): NjTone =
        when (verdict) {
            NjVerdict.HIDDEN -> hidden
            NjVerdict.SUSPICIOUS -> suspicious
            NjVerdict.QUARANTINED -> quarantined
            NjVerdict.INFO -> info
        }
}

/** The colour themes of the redesign (7.1). Each one has a dark and a light accent. Monet replaces the accent only. */
enum class NjAccent(
    val wire: String,
    val dark: Color,
    val light: Color,
) {
    NIGHT_BLUE("night_blue", Color(0xFF8DB0FF), Color(0xFF2F5BD3)),
    MOSS("moss", Color(0xFF7ED6A7), Color(0xFF1F7A4D)),
    EMBER("ember", Color(0xFFFFB17A), Color(0xFFB4500A)),
    ORCHID("orchid", Color(0xFFD4A5FF), Color(0xFF7B3FC4)),
    MONO("mono", Color(0xFFC9CED6), Color(0xFF3B4250)),
    ;

    fun color(dark: Boolean): Color = if (dark) this.dark else light

    companion object {
        fun from(wire: String?): NjAccent = entries.firstOrNull { it.wire == wire } ?: NIGHT_BLUE
    }
}

/** How dark the surfaces are. [BLACK] is the true-black option: `bg` #000000 and `s1` #0E1219 (AMOLED). */
enum class NjSurfaceMode { LIGHT, DARK, BLACK }

/**
 * The colour roles of redesign 7.1: surfaces `bg` `s1` `s2` `s3`, the divider `line`, text `tx` and `mu`, the accent
 * `acc`, the four fixed status colours and the floating-bar colour `glass`. Screens read these through [NjTheme.roles].
 */
@Immutable
data class NjRoles(
    val mode: NjSurfaceMode,
    val bg: Color,
    val s1: Color,
    val s2: Color,
    val s3: Color,
    val line: Color,
    val tx: Color,
    val mu: Color,
    val acc: Color,
    val amb: Color,
    val sus: Color,
    val hi: Color,
    val ok: Color,
    val glass: Color,
) {
    val isDark: Boolean get() = mode != NjSurfaceMode.LIGHT
}

/** Builds the roles for a surface mode and an accent colour. The status colours do not depend on the accent. */
fun njRoles(
    mode: NjSurfaceMode,
    acc: Color,
): NjRoles {
    val status = if (mode == NjSurfaceMode.LIGHT) NjStatusColors.Light else NjStatusColors.Dark
    return when (mode) {
        NjSurfaceMode.LIGHT -> {
            NjRoles(
                mode,
                bg = Color(0xFFF2F3F7),
                s1 = Color(0xFFFFFFFF),
                s2 = Color(0xFFF5F6FA),
                s3 = Color(0xFFE4E8F0),
                line = Color(0xFFE3E6EE),
                tx = Color(0xFF14181F),
                mu = Color(0xFF566072),
                acc = acc,
                amb = status.amb,
                sus = status.sus,
                hi = status.hi,
                ok = status.ok,
                glass = Color(0xD1FFFFFF),
            )
        }

        NjSurfaceMode.DARK -> {
            NjRoles(
                mode,
                bg = Color(0xFF0A0E15),
                s1 = Color(0xFF131925),
                s2 = Color(0xFF1A2231),
                s3 = Color(0xFF26303F),
                line = Color(0xFF263045),
                tx = Color(0xFFE9EDF4),
                mu = Color(0xFFA3ADBD),
                acc = acc,
                amb = status.amb,
                sus = status.sus,
                hi = status.hi,
                ok = status.ok,
                glass = Color(0xC7181F2D),
            )
        }

        NjSurfaceMode.BLACK -> {
            NjRoles(
                mode,
                bg = Color(0xFF000000),
                s1 = Color(0xFF0E1219),
                s2 = Color(0xFF1A2231),
                s3 = Color(0xFF26303F),
                line = Color(0xFF263045),
                tx = Color(0xFFE9EDF4),
                mu = Color(0xFFA3ADBD),
                acc = acc,
                amb = status.amb,
                sus = status.sus,
                hi = status.hi,
                ok = status.ok,
                glass = Color(0xC70E1219),
            )
        }
    }
}

/** A tone for [accent]: a container of the accent over the card colour, with the main text colour on it. */
private fun NjRoles.tone(accent: Color): NjTone = NjTone(container = lerp(s1, accent, 0.16f), content = tx, accent = accent)

/**
 * The existing semantic tones, mapped to the roles: secure = `ok`, degraded and quarantined = `amb` (on hold),
 * suspicious = `sus`, inactive and info = `mu`. Hidden keeps its fixed blue. None of them follow the accent.
 */
internal fun njColors(roles: NjRoles): NjColors =
    NjColors(
        secure = roles.tone(roles.ok),
        degraded = roles.tone(roles.amb),
        inactive = roles.tone(roles.mu),
        hidden = roles.tone(Color(if (roles.isDark) 0xFF8DB8EE else 0xFF2F62A5)),
        suspicious = roles.tone(roles.sus),
        quarantined = roles.tone(roles.amb),
        info = roles.tone(roles.mu),
    )

internal val NjLightRoles = njRoles(NjSurfaceMode.LIGHT, NjAccent.NIGHT_BLUE.light)
internal val NjLightColors = njColors(NjLightRoles)

/** The colour roles for the current theme. */
val LocalNjRoles = staticCompositionLocalOf { NjLightRoles }

/** The semantic colours for the current theme. Read through [NjTheme.colors]. */
val LocalNjColors = staticCompositionLocalOf { NjLightColors }

/** Entry point for reading tokens inside a composition: `NjTheme.colors.of(NjStatus.PROTECTED)`. */
object NjTheme {
    val colors: NjColors
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalNjColors.current

    val roles: NjRoles
        @androidx.compose.runtime.Composable
        @androidx.compose.runtime.ReadOnlyComposable
        get() = LocalNjRoles.current
}
