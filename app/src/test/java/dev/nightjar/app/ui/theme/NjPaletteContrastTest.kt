package dev.nightjar.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.pow

/** The colour roles keep the WCAG contrast the redesign needs, and the window colour matches `bg`. */
class NjPaletteContrastTest {
    private val modes = NjSurfaceMode.entries
    private val minimum = 4.5

    private fun channel(value: Float): Double {
        val v = value.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun roles(
        mode: NjSurfaceMode,
        accent: NjAccent = NjAccent.NIGHT_BLUE,
    ) = njRoles(mode, accent.color(mode != NjSurfaceMode.LIGHT))

    @Test
    fun `every severity colour has 4_5 to 1 on s1 in each theme`() {
        modes.forEach { mode ->
            val r = roles(mode)
            mapOf("ok" to r.ok, "amb" to r.amb, "sus" to r.sus, "hi" to r.hi).forEach { (name, color) ->
                val ratio = contrast(color, r.s1)
                assertTrue(ratio >= minimum, "$name on s1 in $mode is $ratio")
            }
        }
    }

    @Test
    fun `every accent has 4_5 to 1 on s1 and bg in each theme`() {
        modes.forEach { mode ->
            NjAccent.entries.forEach { accent ->
                val r = roles(mode, accent)
                assertTrue(contrast(r.acc, r.s1) >= minimum, "$accent on s1 in $mode")
                assertTrue(contrast(r.acc, r.bg) >= minimum, "$accent on bg in $mode")
            }
        }
    }

    @Test
    fun `text roles have 4_5 to 1 on every surface`() {
        modes.forEach { mode ->
            val r = roles(mode)
            listOf(r.bg, r.s1, r.s2, r.s3).forEach { surface ->
                assertTrue(contrast(r.tx, surface) >= minimum, "tx on $surface in $mode")
                assertTrue(contrast(r.mu, surface) >= minimum, "mu on $surface in $mode")
            }
        }
    }

    @Test
    fun `the text on an accent button has 4_5 to 1`() {
        modes.forEach { mode ->
            NjAccent.entries.forEach { accent ->
                val scheme = njColorScheme(roles(mode, accent))
                assertTrue(contrast(scheme.onPrimary, scheme.primary) >= minimum, "$accent in $mode")
            }
        }
    }

    @Test
    fun `the status colours do not change with the accent`() {
        modes.forEach { mode ->
            val base = roles(mode)
            NjAccent.entries.forEach { accent ->
                val r = roles(mode, accent)
                assertEquals(listOf(base.ok, base.amb, base.sus, base.hi), listOf(r.ok, r.amb, r.sus, r.hi))
            }
            val wallpaper = njRoles(mode, Color(0xFF123456))
            assertEquals(listOf(base.ok, base.amb, base.sus, base.hi), listOf(wallpaper.ok, wallpaper.amb, wallpaper.sus, wallpaper.hi))
        }
    }

    @Test
    fun `true black uses bg 000000 and s1 0E1219`() {
        val black = roles(NjSurfaceMode.BLACK)
        assertEquals(Color(0xFF000000), black.bg)
        assertEquals(Color(0xFF0E1219), black.s1)
        assertEquals(Color(0xFF0A0E15), roles(NjSurfaceMode.DARK).bg)
        assertEquals(Color(0xFFF2F3F7), roles(NjSurfaceMode.LIGHT).bg)
    }

    @Test
    fun `the accent themes use the redesign values`() {
        assertEquals(Color(0xFF8DB0FF), NjAccent.NIGHT_BLUE.dark)
        assertEquals(Color(0xFF2F5BD3), NjAccent.NIGHT_BLUE.light)
        assertEquals(Color(0xFF7ED6A7), NjAccent.MOSS.dark)
        assertEquals(Color(0xFFB4500A), NjAccent.EMBER.light)
        assertEquals(Color(0xFFD4A5FF), NjAccent.ORCHID.dark)
        assertEquals(Color(0xFF3B4250), NjAccent.MONO.light)
        assertEquals(NjAccent.NIGHT_BLUE, NjAccent.from(null))
        assertEquals(NjAccent.MOSS, NjAccent.from("moss"))
    }

    @Test
    fun `the window background colours in colors_xml match bg`() {
        // Gradle runs the test in the module directory; a run from the repository root uses the longer paths.
        val xml =
            listOf("src/main/res/values", "app/src/main/res/values", "nightjar/app/src/main/res/values")
                .map { File(it, "colors.xml") }
                .firstOrNull { it.isFile }
                ?.readText()
                ?: error("colors.xml not found from " + File("").absolutePath)

        fun value(name: String): String =
            Regex("""<color name="$name">#([0-9A-Fa-f]{6})</color>""")
                .find(xml)
                ?.groupValues
                ?.get(1)
                ?.uppercase()
                ?: error("missing color $name")

        fun hex(c: Color) = "%06X".format(c.value.shr(32).toLong() and 0xFFFFFF)
        assertEquals(hex(roles(NjSurfaceMode.LIGHT).bg), value("window_background"))
        assertEquals(hex(roles(NjSurfaceMode.DARK).bg), value("window_background_dark"))
    }
}
