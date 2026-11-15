package dev.nightjar.app.ui.nav

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import dev.nightjar.app.ui.components.LocalNjArrivedWithMotion
import dev.nightjar.app.ui.components.LocalNjBottomInset
import dev.nightjar.app.ui.theme.NjMotion
import dev.nightjar.app.ui.theme.rememberNjMotion

/**
 * The screens the navigation graph renders.
 *
 * Owns the routes, the shell and the deep links; the screen layer implements this interface once, so the
 * graph stays a single file with no per-screen knowledge. Every method receives the route instance (typed arguments
 * already parsed) and a [NavActions] handle for navigation.
 */
interface NightjarScreens {
    @Composable fun Onboarding(actions: NavActions)

    @Composable fun Home(actions: NavActions)

    @Composable fun Messages(route: Route.Messages, actions: NavActions)

    @Composable fun MessageDetail(route: Route.MessageDetail, actions: NavActions)

    @Composable fun Firewall(actions: NavActions)

    @Composable fun CustomRules(actions: NavActions)

    @Composable fun RuleEditor(route: Route.RuleEditor, actions: NavActions)

    @Composable fun Health(actions: NavActions)

    @Composable fun BlindSpots(actions: NavActions)

    @Composable fun Settings(actions: NavActions)

    @Composable fun Export(actions: NavActions)

    @Composable fun Logs(actions: NavActions)

    @Composable fun KnownSenders(route: Route.KnownSenders, actions: NavActions)

    @Composable fun AuditHistory(actions: NavActions)

    @Composable fun SendingApps(actions: NavActions)
}

/** Navigation actions a screen may perform, so screens never touch the NavController directly. */
class NavActions internal constructor(
    private val controller: NavController,
) {
    fun open(route: Route) = controller.navigate(route)

    /**
     * On top of the current screen.
     *
     * A tap on a row must not rebuild the stack: the previous version reset to Home and pushed Home → Messages →
     * detail for every tap, which destroyed the list's ViewModel, scroll position, open search and multi-select.
     * [navigateToMessage] stays the notification entry point, handled by the activity.
     */
    fun openDetail(messageId: Long) {
        controller.navigate(Route.MessageDetail(messageId)) { launchSingleTop = true }
    }

    fun back(): Boolean = controller.popBackStack()

    /** Switches the shell to a top-level destination and clears everything above it. */
    fun upTo(destination: TopDestination) {
        controller.navigate(destination.route) {
            popUpTo(controller.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
}

/**
 * Registers every destination.
 *
 * Deep links:
 *  - `nightjar://message/{messageId}` → Detail with the synthetic back stack Home → Messages → Detail;
 *  - `nightjar://messages?filter=quarantined` → Messages with the chip pre-selected;
 *  - `nightjar://rule/new?messageId={id}` → Rule editor pre-filled from a message;
 *  - `nightjar://health` → Health.
 */
fun NavGraphBuilder.nightjarGraph(
    navController: NavController,
    screens: NightjarScreens,
) {
    val actions = NavActions(navController)
    composable<Route.Onboarding> { screens.Onboarding(actions) }
    composable<Route.Home> { TabRoot(TopDestination.HOME) { screens.Home(actions) } }
    composable<Route.Messages>(
        deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.MESSAGES_PATTERN }),
    ) { entry -> TabRoot(TopDestination.MESSAGES) { screens.Messages(entry.toRoute(), actions) } }
    // No `navDeepLink` on `nightjar://message/{id}` is parsed by MainActivity and pushed through
    // [navigateToMessage], so the back stack is Home → Messages → Detail instead of the library's [start, detail].
    composable<Route.MessageDetail> { entry -> PageScreen { screens.MessageDetail(entry.toRoute(), actions) } }
    composable<Route.Firewall> { TabRoot(TopDestination.PROTECTION) { screens.Firewall(actions) } }
    composable<Route.CustomRules> { PageScreen { screens.CustomRules(actions) } }
    composable<Route.RuleEditor>(
        deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.RULE_NEW_PATTERN }),
    ) { entry -> PageScreen { screens.RuleEditor(entry.toRoute(), actions) } }
    composable<Route.Health>(
        deepLinks = listOf(navDeepLink { uriPattern = DeepLinks.HEALTH_PATTERN }),
    ) { PageScreen { screens.Health(actions) } }
    composable<Route.BlindSpots> { PageScreen { screens.BlindSpots(actions) } }
    composable<Route.Settings> { TabRoot(TopDestination.SETTINGS) { screens.Settings(actions) } }
    composable<Route.Export> { PageScreen { screens.Export(actions) } }
    composable<Route.Logs> { PageScreen { screens.Logs(actions) } }
    composable<Route.KnownSenders> { entry -> PageScreen { screens.KnownSenders(entry.toRoute(), actions) } }
    composable<Route.AuditHistory> { PageScreen { screens.AuditHistory(actions) } }
    composable<Route.SendingApps> { PageScreen { screens.SendingApps(actions) } }
}

/**
 * Opens a message with the synthetic back stack Home → Messages → Detail.
 *
 * The notification's `PendingIntent` targets `nightjar://message/{id}`; pressing back twice must land on Home, not exit
 * the app, so the two parents are pushed explicitly before the detail.
 */
fun NavController.navigateToMessage(messageId: Long) {
    // Reset to Home, then push the two parents and the detail: back from the detail lands on Messages, then Home.
    navigate(Route.Home) {
        popUpTo<Route.Home> { inclusive = true }
        launchSingleTop = true
    }
    navigate(Route.Messages()) { launchSingleTop = true }
    navigate(Route.MessageDetail(messageId)) { launchSingleTop = true }
}

/** The tab that owns [destination], or null for onboarding and for an unknown destination. */
internal fun topDestinationOf(destination: NavDestination?): TopDestination? {
    if (destination == null) return null
    return TopDestination.entries.firstOrNull { top -> top.routeClasses.any { destination.hasRoute(it) } }
}

/**
 * Direction of a tab change: 1 when the target tab is after the source tab in the bar, -1 when it is before, and 0
 * when the two are the same tab or one of them is no tab.
 */
internal fun tabDirection(
    from: TopDestination?,
    to: TopDestination?,
): Int {
    if (from == null || to == null || from == to) return 0
    return if (to.ordinal > from.ordinal) 1 else -1
}

/** The tab whose root screen [destination] is, or null for a screen inside a tab, onboarding or no destination. */
internal fun tabRootOf(destination: NavDestination?): TopDestination? {
    if (destination == null) return null
    return TopDestination.entries.firstOrNull { destination.hasRoute(it.route::class) }
}

/** The kind of motion between two screens. */
internal enum class PageMotion {
    /** Between two tab roots: the page body slides over the full width, the bar stays in place. */
    TAB,

    /** A screen opens or closes: it slides over the full width, the screen under it moves 30 % and dims. */
    PAGE,

    /** To or from onboarding: a fade. */
    FADE,
}

/** Chooses the motion between [fromRoot] and [toRoot] (the tab roots, null for other screens). */
internal fun pageMotion(
    fromRoot: TopDestination?,
    toRoot: TopDestination?,
    fromOnboarding: Boolean,
    toOnboarding: Boolean,
): PageMotion =
    when {
        fromOnboarding || toOnboarding -> PageMotion.FADE
        fromRoot != null && toRoot != null -> PageMotion.TAB
        else -> PageMotion.PAGE
    }

/** The screen under an opened screen moves this fraction of the width (parallax). */
internal const val PAGE_PARALLAX_FRACTION = 0.3f

/** The screen under an opened screen keeps this alpha over the dark background, so it looks dimmed. */
internal const val PAGE_UNDER_ALPHA = 0.7f

/**
 * The tab direction of the motion that runs now. The navigation host writes it when a transition starts, and the tab
 * roots read it for the slide of their body. It is a plain field, not state: the host writes it while it composes the
 * transition, before the tab roots of that transition read it.
 */
internal class TabSlideState {
    var direction: Int = 0
}

internal val LocalTabSlideState = staticCompositionLocalOf { TabSlideState() }

/** The ViewModel owner outside the navigation host (the activity), for the tab roots that keep their ViewModel. */
internal val LocalTabViewModelOwner = staticCompositionLocalOf<ViewModelStoreOwner?> { null }

/**
 * The tab roots whose ViewModel lives as long as the activity. Their data is ready when the page slides in, so the
 * page does not show an empty state first. Messages is not in the list: its ViewModel reads the `filter` argument of
 * its own back stack entry.
 */
internal val TABS_WITH_KEPT_VIEWMODEL = setOf(TopDestination.HOME, TopDestination.PROTECTION, TopDestination.SETTINGS)

/**
 * The floating tab bar for one tab root, or null when no bar floats (a Material bar or a rail is in use, or the
 * keyboard is open). The shell provides it. Each tab root draws its own bar, so a screen that opens slides over it.
 */
val LocalNjTabBar = compositionLocalOf<(@Composable (TopDestination) -> Unit)?> { null }

/** The bottom inset for a screen inside a tab: the system bar only, because no tab bar floats over it. */
val LocalNjPageBottomInset = compositionLocalOf { 0.dp }

/**
 * The navigation host with the page motion.
 *
 * A screen opens with a full-width slide from the right, and the screen under it moves 30 % to the left and dims.
 * Back runs the same motion in reverse, and the predictive back gesture moves it with the finger. A tab change
 * slides the page body over the full width in the direction of the tab in the bar, and the bar stays in place.
 * Onboarding fades. With "Remove animations" on, nothing moves.
 */
@Composable
fun NightjarNavHost(
    navController: NavHostController,
    startDestination: Route,
    screens: NightjarScreens,
    modifier: Modifier = Modifier,
) {
    val motion = rememberNjMotion()
    val slide = remember { TabSlideState() }
    CompositionLocalProvider(
        LocalTabSlideState provides slide,
        LocalTabViewModelOwner provides LocalViewModelStoreOwner.current,
    ) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = modifier.fillMaxSize(),
            enterTransition = { pageEnter(motion, slide, pop = false) },
            exitTransition = { pageExit(motion, slide, pop = false) },
            popEnterTransition = { pageEnter(motion, slide, pop = true) },
            popExitTransition = { pageExit(motion, slide, pop = true) },
            predictivePopEnterTransition = { pageEnter(motion, slide, pop = true) },
            predictivePopExitTransition = { pageExit(motion, slide, pop = true) },
        ) {
            nightjarGraph(navController, screens)
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.kind(slide: TabSlideState): PageMotion {
    val from = initialState.destination
    val to = targetState.destination
    val fromRoot = tabRootOf(from)
    val toRoot = tabRootOf(to)
    val kind = pageMotion(fromRoot, toRoot, from.hasRoute<Route.Onboarding>(), to.hasRoute<Route.Onboarding>())
    slide.direction = if (kind == PageMotion.TAB) tabDirection(fromRoot, toRoot) else 0
    return kind
}

private fun <T> pageSpec(motion: NjMotion): FiniteAnimationSpec<T> =
    tween(durationMillis = motion.pageSlideMillis, easing = NjMotion.PageEasing)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pageEnter(
    motion: NjMotion,
    slide: TabSlideState,
    pop: Boolean,
): EnterTransition {
    val kind = kind(slide)
    if (!motion.enabled) return EnterTransition.None
    return when (kind) {
        PageMotion.FADE -> {
            fadeIn(tween(motion.tabChangeMillis))
        }

        // The tab root slides its body itself (TabRoot), so the bar stays in place.
        PageMotion.TAB -> {
            EnterTransition.None
        }

        PageMotion.PAGE -> {
            if (pop) {
                slideInHorizontally(pageSpec(motion)) { width -> -(width * PAGE_PARALLAX_FRACTION).toInt() } +
                    fadeIn(pageSpec(motion), initialAlpha = PAGE_UNDER_ALPHA)
            } else {
                slideInHorizontally(pageSpec(motion)) { width -> width }
            }
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pageExit(
    motion: NjMotion,
    slide: TabSlideState,
    pop: Boolean,
): ExitTransition {
    val kind = kind(slide)
    if (!motion.enabled) return ExitTransition.None
    return when (kind) {
        PageMotion.FADE -> {
            fadeOut(tween(motion.tabChangeMillis))
        }

        PageMotion.TAB -> {
            ExitTransition.None
        }

        PageMotion.PAGE -> {
            if (pop) {
                slideOutHorizontally(pageSpec(motion)) { width -> width }
            } else {
                slideOutHorizontally(pageSpec(motion)) { width -> -(width * PAGE_PARALLAX_FRACTION).toInt() } +
                    fadeOut(pageSpec(motion), targetAlpha = PAGE_UNDER_ALPHA)
            }
        }
    }
}

/**
 * A tab root: the screen body and, when the bar floats, the tab bar of [tab].
 *
 * In a tab change the body slides over the full width and the bar stays in place. The bar of the tab that goes away
 * fades out quickly, so the selection changes at the start of the motion also when that tab is drawn on top (back).
 * When a screen opens over the tab root, the root and its bar move as one layer, and the new screen covers the bar.
 */
@Composable
private fun AnimatedContentScope.TabRoot(
    tab: TopDestination,
    content: @Composable () -> Unit,
) {
    val motion = rememberNjMotion()
    val slide = LocalTabSlideState.current
    val bar = LocalNjTabBar.current
    val offset by transition.animateFloat(
        transitionSpec = { tween(durationMillis = motion.tabSlideMillis, easing = NjMotion.PageEasing) },
        label = "tab body",
    ) { state ->
        when (state) {
            EnterExitState.PreEnter -> slide.direction.toFloat()
            EnterExitState.Visible -> 0f
            EnterExitState.PostExit -> -slide.direction.toFloat()
        }
    }
    val barAlpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = motion.tabSlideMillis / BAR_FADE_DIVISOR) },
        label = "tab bar",
    ) { state -> if (state == EnterExitState.PostExit && slide.direction != 0) 0f else 1f }
    val arrivedWithMotion = remember { transition.currentState == EnterExitState.PreEnter }
    val activityOwner = LocalTabViewModelOwner.current
    val owner = if (tab in TABS_WITH_KEPT_VIEWMODEL && activityOwner != null) activityOwner else LocalViewModelStoreOwner.current
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offset * size.width }
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (owner != null) {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalNjArrivedWithMotion provides arrivedWithMotion,
                ) { content() }
            } else {
                CompositionLocalProvider(LocalNjArrivedWithMotion provides arrivedWithMotion) { content() }
            }
        }
        if (bar != null) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = barAlpha }) { bar(tab) }
        }
    }
}

/** The bar of the tab that goes away fades out in this part of the tab slide. */
private const val BAR_FADE_DIVISOR = 2

/**
 * A screen inside a tab: opaque, so it covers the screen under it, and with the system bar as its bottom inset. When it
 * arrives with the page slide, its sections show at once.
 */
@Composable
private fun AnimatedContentScope.PageScreen(content: @Composable () -> Unit) {
    val arrivedWithMotion = remember { transition.currentState == EnterExitState.PreEnter }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(
            LocalNjBottomInset provides LocalNjPageBottomInset.current,
            LocalNjArrivedWithMotion provides arrivedWithMotion,
        ) { content() }
    }
}
