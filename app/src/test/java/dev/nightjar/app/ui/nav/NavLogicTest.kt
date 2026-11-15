package dev.nightjar.app.ui.nav

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.nightjar.app.ui.components.NjGlassBarSpec
import dev.nightjar.app.ui.components.njFloatingBarBottomMargin
import dev.nightjar.app.ui.components.njFloatingBarContentInset
import dev.nightjar.app.ui.components.njGlassBarColors
import dev.nightjar.app.ui.components.njPillContentMax
import dev.nightjar.app.ui.components.njPillWidth
import dev.nightjar.app.ui.theme.NjAccent
import dev.nightjar.app.ui.theme.NjSurfaceMode
import dev.nightjar.app.ui.theme.njRoles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pure parts of the navigation (tab ownership of routes, layout choice, bar geometry, tab motion). */
class NavLogicTest {
    /** Every route that exists, one instance each, with the tab that must own it (null for the first-run flow). */
    private val routeOwners: List<Pair<Route, TopDestination?>> =
        listOf(
            Route.Onboarding to null,
            Route.Home to TopDestination.HOME,
            Route.Messages() to TopDestination.MESSAGES,
            Route.MessageDetail(1L) to TopDestination.MESSAGES,
            Route.KnownSenders() to TopDestination.MESSAGES,
            Route.Firewall to TopDestination.PROTECTION,
            Route.CustomRules to TopDestination.PROTECTION,
            Route.RuleEditor() to TopDestination.PROTECTION,
            Route.Health to TopDestination.PROTECTION,
            Route.BlindSpots to TopDestination.PROTECTION,
            Route.Settings to TopDestination.SETTINGS,
            Route.Export to TopDestination.SETTINGS,
            Route.Logs to TopDestination.SETTINGS,
            Route.AuditHistory to TopDestination.SETTINGS,
            Route.SendingApps to TopDestination.SETTINGS,
        )

    @Test
    fun `the bar has four tabs in the order Home Messages Protection Settings`() {
        assertEquals(
            listOf(TopDestination.HOME, TopDestination.MESSAGES, TopDestination.PROTECTION, TopDestination.SETTINGS),
            TopDestination.entries.toList(),
        )
    }

    @Test
    fun `each tab root belongs to its own tab`() {
        TopDestination.entries.forEach { assertTrue(it.matches(it.route), it.name) }
    }

    @Test
    fun `every route belongs to the right tab and to no other tab`() {
        routeOwners.forEach { (route, owner) ->
            val owners = TopDestination.entries.filter { it.matches(route) }
            assertEquals(listOfNotNull(owner), owners, route.toString())
        }
    }

    @Test
    fun `no route is a tab member twice and all routes are listed`() {
        val classes = TopDestination.entries.flatMap { it.routeClasses }
        assertEquals(classes.size, classes.toSet().size)
        // Onboarding is the only route without a tab.
        assertEquals(routeOwners.size - 1, classes.size)
    }

    @Test
    fun `matches is false for no route`() {
        TopDestination.entries.forEach { assertFalse(it.matches(null)) }
    }

    @Test
    fun `layout is the floating bar below 840 dp when the setting is on`() {
        assertEquals(NavLayout.FLOATING_BAR, navLayoutFor(360f, floatingBar = true))
        assertEquals(NavLayout.FLOATING_BAR, navLayoutFor(700f, floatingBar = true))
    }

    @Test
    fun `layout is the rail at Expanded width with either setting`() {
        assertEquals(NavLayout.RAIL, navLayoutFor(840f, floatingBar = true))
        assertEquals(NavLayout.RAIL, navLayoutFor(1200f, floatingBar = false))
    }

    @Test
    fun `layout is the Material bar when the setting is off at compact width`() {
        assertEquals(NavLayout.MATERIAL_BAR, navLayoutFor(360f, floatingBar = false))
        assertEquals(NavLayout.MATERIAL_BAR, navLayoutFor(599f, floatingBar = false))
        assertEquals(NavLayout.RAIL, navLayoutFor(600f, floatingBar = false))
    }

    @Test
    fun `the bar is 24 dp from the sides and 28 dp from the bottom, 64 dp high with a 32 dp radius`() {
        assertEquals(24.dp, NjGlassBarSpec.SideMargin)
        assertEquals(28.dp, NjGlassBarSpec.BottomMargin)
        assertEquals(64.dp, NjGlassBarSpec.Height)
        assertEquals(32.dp, NjGlassBarSpec.Radius)
    }

    @Test
    fun `the selected pill wraps the 24 dp icon and the 16 dp label line inside the bar`() {
        // The pill padding of the design (6 dp above and below), and the pill stays inside the 64 dp bar.
        val pillHeight = NjGlassBarSpec.PillPaddingVertical * 2 + 24.dp + NjGlassBarSpec.IconLabelGap + 16.dp
        assertTrue(pillHeight < NjGlassBarSpec.Height, "pill $pillHeight")
        assertTrue(NjGlassBarSpec.PillRadius * 2 <= pillHeight)
    }

    @Test
    fun `the pill side space shrinks in a narrow tab cell before the label is cut`() {
        // Pixel values at density 1: 10 dp side space, 2 dp at least.
        val padMax = NjGlassBarSpec.PillPaddingHorizontal.value.toInt()
        val padMin = NjGlassBarSpec.PillPaddingHorizontalMin.value.toInt()
        assertEquals(10, padMax)
        assertEquals(2, padMin)
        // A wide cell: the full 10 dp at each side.
        assertEquals(96, njPillContentMax(cellMax = 100, padMin = padMin))
        assertEquals(40 + 2 * padMax, njPillWidth(cellMax = 100, content = 40, padMax = padMax))
        // A 75 dp cell (360 dp screen) and a 60 dp label: the label gets 71 dp, the pill fills the cell.
        assertEquals(71, njPillContentMax(cellMax = 75, padMin = padMin))
        assertEquals(75, njPillWidth(cellMax = 75, content = 60, padMax = padMax))
        // A label that needs more than the cell: it is measured at the cell less 2 dp at each side.
        assertEquals(75, njPillWidth(cellMax = 75, content = njPillContentMax(75, padMin), padMax = padMax))
        // No width limit, and a cell smaller than the minimum space.
        assertEquals(Constraints.Infinity, njPillContentMax(cellMax = Constraints.Infinity, padMin = padMin))
        assertEquals(0, njPillContentMax(cellMax = 3, padMin = padMin))
    }

    @Test
    fun `bar colours come from the NightJar roles`() {
        // Card colour, thin outline, accent pill at 14 %.
        val roles = njRoles(NjSurfaceMode.DARK, NjAccent.NIGHT_BLUE.dark)
        val colors = njGlassBarColors(roles)
        assertEquals(roles.s1, colors.container)
        assertEquals(roles.line, colors.border)
        assertEquals(roles.acc.copy(alpha = 0.14f), colors.pill)
        assertEquals(roles.mu, colors.content)
    }

    @Test
    fun `bottom margin is 28 dp with a thin or no system bar and grows above a tall one`() {
        assertEquals(28.dp, njFloatingBarBottomMargin(0.dp))
        assertEquals(28.dp, njFloatingBarBottomMargin(20.dp))
        assertEquals(56.dp, njFloatingBarBottomMargin(48.dp))
    }

    @Test
    fun `the content inset is larger than the bar and its margin`() {
        assertEquals(28.dp + 64.dp + 16.dp, njFloatingBarContentInset(0.dp))
        assertEquals(56.dp + 64.dp + 16.dp, njFloatingBarContentInset(48.dp))
        assertTrue(njFloatingBarContentInset(0.dp) > NjGlassBarSpec.BottomMargin + NjGlassBarSpec.Height)
    }

    @Test
    fun `tab direction follows the bar order`() {
        assertEquals(1, tabDirection(TopDestination.HOME, TopDestination.SETTINGS))
        assertEquals(1, tabDirection(TopDestination.MESSAGES, TopDestination.PROTECTION))
        assertEquals(-1, tabDirection(TopDestination.SETTINGS, TopDestination.HOME))
        assertEquals(-1, tabDirection(TopDestination.PROTECTION, TopDestination.MESSAGES))
    }

    @Test
    fun `two tab roots get the tab slide`() {
        assertEquals(PageMotion.TAB, pageMotion(TopDestination.HOME, TopDestination.MESSAGES, false, false))
        assertEquals(PageMotion.TAB, pageMotion(TopDestination.SETTINGS, TopDestination.HOME, false, false))
    }

    @Test
    fun `a screen inside a tab gets the page slide both ways`() {
        assertEquals(PageMotion.PAGE, pageMotion(TopDestination.HOME, null, false, false))
        assertEquals(PageMotion.PAGE, pageMotion(null, TopDestination.PROTECTION, false, false))
        assertEquals(PageMotion.PAGE, pageMotion(null, null, false, false))
    }

    @Test
    fun `onboarding fades in and out`() {
        assertEquals(PageMotion.FADE, pageMotion(null, TopDestination.HOME, true, false))
        assertEquals(PageMotion.FADE, pageMotion(TopDestination.HOME, null, false, true))
    }

    @Test
    fun `the screen under an opened screen moves 30 percent and dims`() {
        assertEquals(0.3f, PAGE_PARALLAX_FRACTION)
        assertTrue(PAGE_UNDER_ALPHA in 0.5f..0.9f)
    }

    @Test
    fun `no shared axis inside one tab or outside the tabs`() {
        assertEquals(0, tabDirection(TopDestination.MESSAGES, TopDestination.MESSAGES))
        assertEquals(0, tabDirection(null, TopDestination.HOME))
        assertEquals(0, tabDirection(TopDestination.HOME, null))
        assertNull(TopDestination.entries.firstOrNull { it.matches(Route.Onboarding) })
    }
}
