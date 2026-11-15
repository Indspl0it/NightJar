package dev.nightjar.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.nightjar.app.domain.ClassFamily
import dev.nightjar.app.ui.theme.NjAccent
import dev.nightjar.app.ui.theme.NjSurfaceMode
import dev.nightjar.app.ui.theme.njRoles
import dev.nightjar.contract.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pure parts of the component library (colours, bar heights, collapse threshold, type icons). */
class NjComponentLogicTest {
    private val dark = njRoles(NjSurfaceMode.DARK, NjAccent.NIGHT_BLUE.dark)
    private val light = njRoles(NjSurfaceMode.LIGHT, NjAccent.NIGHT_BLUE.light)

    @Test
    fun `allow hold and block use ok amb and hi in both themes`() {
        listOf(dark, light).forEach { roles ->
            assertEquals(roles.ok, njActionColor(roles, NjAction.ALLOW))
            assertEquals(roles.amb, njActionColor(roles, NjAction.HOLD))
            assertEquals(roles.hi, njActionColor(roles, NjAction.BLOCK))
        }
    }

    @Test
    fun `each action has its own label resource`() {
        val labels = NjAction.entries.map { njActionLabelRes(it) }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun `severity colours follow info mu notable amb suspicious sus`() {
        assertEquals(dark.mu, severityColor(dark, Severity.INFO))
        assertEquals(dark.amb, severityColor(dark, Severity.NOTABLE))
        assertEquals(dark.sus, severityColor(dark, Severity.SUSPICIOUS))
    }

    @Test
    fun `badge fill is the colour mixed into s1`() {
        assertEquals(lerp(dark.s1, dark.hi, 0.16f), badgeContainer(dark, dark.hi))
        assertNotEquals(Color.Unspecified, badgeContainer(light, light.ok))
    }

    @Test
    fun `tallest bar has fraction 1 and others scale`() {
        val f = barFractions(listOf(4, 6, 3, 9, 7, 12, 6), 0.1f)
        assertEquals(7, f.size)
        assertEquals(1f, f[5])
        assertEquals(0.5f, f[1])
    }

    @Test
    fun `a bar of 0 keeps the minimum height`() {
        val f = barFractions(listOf(0, 10), 0.12f)
        assertEquals(0.12f, f[0])
        assertEquals(1f, f[1])
    }

    @Test
    fun `no data gives minimum bars and no crash`() {
        assertEquals(listOf(0.12f, 0.12f), barFractions(listOf(0, 0), 0.12f))
        assertTrue(barFractions(emptyList(), 0.12f).isEmpty())
    }

    @Test
    fun `a negative value counts as 0`() {
        assertEquals(0.1f, barFractions(listOf(-3, 5), 0.1f)[0])
    }

    @Test
    fun `title collapses after the first item or after the threshold`() {
        assertFalse(titleCollapsed(0, 0, 100f))
        assertFalse(titleCollapsed(0, 100, 100f))
        assertTrue(titleCollapsed(0, 101, 100f))
        assertTrue(titleCollapsed(1, 0, 100f))
    }

    @Test
    fun `every class family has a type icon`() {
        ClassFamily.entries.forEach { assertNotEquals(null, typeIconFor(it)) }
    }
}
