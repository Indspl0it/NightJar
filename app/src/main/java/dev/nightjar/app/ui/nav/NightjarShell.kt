package dev.nightjar.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.ui.components.LocalNjBottomInset
import dev.nightjar.app.ui.components.NjBannerKind
import dev.nightjar.app.ui.components.NjGlassBar
import dev.nightjar.app.ui.components.NjGlassBarItem
import dev.nightjar.app.ui.components.NjGlassBarSpec
import dev.nightjar.app.ui.components.NjInfoBanner
import dev.nightjar.app.ui.components.njFloatingBarBottomMargin
import dev.nightjar.app.ui.components.njFloatingBarContentInset
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.contract.Severity

/** The navigation element that the shell shows. */
enum class NavLayout {
    /** The floating tab bar over the content. */
    FLOATING_BAR,

    /** The Material navigation bar, in its own row under the content. */
    MATERIAL_BAR,

    /** The navigation rail at the start edge. */
    RAIL,

    /** No navigation at all: the first-run flow onboarding. */
    NONE,
}

/** Window width (dp) from which a tablet gets the rail even when the floating bar is on (width class Expanded). */
const val NAV_RAIL_EXPANDED_MIN_DP = 840f

/** Window width (dp) from which the Material bar changes to the rail (breakpoint, width class Medium). */
const val NAV_RAIL_MEDIUM_MIN_DP = 600f

/**
 * Chooses the navigation element for a window of [widthDp].
 *
 * The rail replaces everything at the Expanded width. Below that, the floating bar is shown when the setting is on.
 * With the setting off, the Material bar shows, as before, and it changes to the rail from 600 dp.
 */
fun navLayoutFor(
    widthDp: Float,
    floatingBar: Boolean,
): NavLayout =
    when {
        widthDp >= NAV_RAIL_EXPANDED_MIN_DP -> NavLayout.RAIL
        floatingBar -> NavLayout.FLOATING_BAR
        widthDp >= NAV_RAIL_MEDIUM_MIN_DP -> NavLayout.RAIL
        else -> NavLayout.MATERIAL_BAR
    }

/**
 * The app shell (S04, S05).
 *
 * - a floating tab bar (solid, in the Monet colours of the phone) below 840 dp, the Material bar when [floatingBar] is
 *   off, and the navigation rail at Expanded width ([navLayoutFor]);
 * - the floating bar belongs to the tab roots: the shell gives it to them in [LocalNjTabBar], so a screen that opens
 *   slides over it;
 * - the shell gives the bar height to the screens in [LocalNjBottomInset], so the last list item stays above the bar;
 *   a screen inside a tab gets only the system bar ([LocalNjPageBottomInset]);
 * - the Messages destination carries the quarantine badge (09);
 * - a global banner slot at the top and the snackbar host above the navigation bar;
 * - edge-to-edge: the shell consumes the safe-drawing insets once, so screens never have to.
 *
 * [floatingBar] is the setting "Floating bar" (Settings > Appearance).
 * With [showNavigation] false (the first-run flow) the shell shows no bar, no rail and no banner, but it keeps the
 * inset handling, so the one navigation host stays in the same place when the first-run flow ends.
 *
 * There is no back handling here: predictive back is the platform default for targetSdk 36 and the app installs no
 * `OnBackPressedCallback`.
 */
@Composable
fun NightjarShell(
    current: Route?,
    onNavigate: (TopDestination) -> Unit,
    modifier: Modifier = Modifier,
    quarantineBadge: Int = 0,
    banner: BannerState? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    floatingBar: Boolean = true,
    showNavigation: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    BoxWithConstraints(modifier) {
        val layout = if (showNavigation) navLayoutFor(maxWidth.value, floatingBar) else NavLayout.NONE
        val density = LocalDensity.current
        val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
        val navigationBarInset: Dp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
        // The keyboard hides the floating bar: the screen then uses the normal safe-drawing insets.
        val floating = layout == NavLayout.FLOATING_BAR && !keyboardOpen
        val bottomInset = if (floating) njFloatingBarContentInset(navigationBarInset) else 0.dp

        NavigationSuiteScaffold(
            modifier = Modifier.fillMaxSize(),
            layoutType =
                when (layout) {
                    NavLayout.FLOATING_BAR, NavLayout.NONE -> NavigationSuiteType.None
                    NavLayout.MATERIAL_BAR -> NavigationSuiteType.NavigationBar
                    NavLayout.RAIL -> NavigationSuiteType.NavigationRail
                },
            containerColor = MaterialTheme.colorScheme.background,
            navigationSuiteColors =
                NavigationSuiteDefaults.colors(
                    navigationBarContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    navigationRailContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            navigationSuiteItems = {
                TopDestination.entries.forEach { destination ->
                    val selected = destination.matches(current)
                    item(
                        selected = selected,
                        onClick = { onNavigate(destination) },
                        icon = {
                            NavIconBadge(
                                icon = destination.icon,
                                count = if (destination == TopDestination.MESSAGES) quarantineBadge else 0,
                            )
                        },
                        label = { Text(stringResource(destination.labelRes)) },
                        alwaysShowLabel = true,
                    )
                }
            },
        ) {
            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    contentWindowInsets =
                        if (floating) {
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
                        } else {
                            WindowInsets.safeDrawing
                        },
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbarHostState, Modifier.padding(bottom = bottomInset)) },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        // A slim inline note under the status bar, never a block; the screen below keeps its own title bar.
                        if (banner != null && showNavigation) {
                            NjInfoBanner(
                                text = banner.text,
                                kind =
                                    when (banner.severity) {
                                        Severity.INFO -> NjBannerKind.INFO
                                        Severity.NOTABLE -> NjBannerKind.WARN
                                        Severity.SUSPICIOUS -> NjBannerKind.ERROR
                                        Severity.HIGH -> NjBannerKind.ERROR
                                    },
                                modifier = Modifier.padding(horizontal = NjSpacing.m, vertical = NjSpacing.s),
                                actionLabel = banner.actionLabel,
                                onAction = banner.onAction,
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            // Each tab root draws the floating bar itself (LocalNjTabBar), so a screen that opens
                            // slides over the bar. A screen inside a tab gets only the system bar as bottom inset.
                            val tabBar: (@Composable (TopDestination) -> Unit)? =
                                if (floating) {
                                    { selected ->
                                        FloatingTabBar(selected, onNavigate, quarantineBadge, navigationBarInset)
                                    }
                                } else {
                                    null
                                }
                            CompositionLocalProvider(
                                LocalNjBottomInset provides bottomInset,
                                LocalNjPageBottomInset provides if (floating) navigationBarInset else 0.dp,
                                LocalNjTabBar provides tabBar,
                            ) {
                                content(PaddingValues())
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The floating tab bar with [selected] highlighted, at the bottom of the space it gets. */
@Composable
private fun FloatingTabBar(
    selected: TopDestination,
    onNavigate: (TopDestination) -> Unit,
    quarantineBadge: Int,
    navigationBarInset: Dp,
) {
    val barItems =
        TopDestination.entries.map { destination ->
            NjGlassBarItem(
                label = stringResource(destination.labelRes),
                selected = destination == selected,
                onClick = { onNavigate(destination) },
                icon = {
                    NavIconBadge(
                        icon = destination.icon,
                        count = if (destination == TopDestination.MESSAGES) quarantineBadge else 0,
                    )
                },
            )
        }
    Box(Modifier.fillMaxSize()) {
        NjGlassBar(
            items = barItems,
            navigationDescription = stringResource(R.string.nj_cmp_nav_bar_cd),
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(horizontal = NjGlassBarSpec.SideMargin)
                    .padding(bottom = njFloatingBarBottomMargin(navigationBarInset))
                    .widthIn(max = NjGlassBarSpec.MaxWidth),
        )
    }
}

/** Content of the global banner slot. */
data class BannerState(
    val text: String,
    val severity: Severity,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/**
 * An icon with an optional count badge (09). TalkBack reads the badge as "3 messages on hold" after the tab
 * label, not as a bare number.
 */
@Composable
private fun NavIconBadge(
    icon: NavIcon,
    count: Int,
) {
    if (count <= 0) {
        Icon(imageVector = icon.vector(), contentDescription = null)
        return
    }
    val description = pluralStringResource(R.plurals.state_quarantine_badge, count, count)
    BadgedBox(
        badge = {
            // Amber, the colour of "waits for you". The count is the number of "Waiting for you" in Messages.
            Badge(
                containerColor = NjTheme.roles.amb,
                contentColor = NjTheme.roles.bg,
                modifier =
                    Modifier.clearAndSetSemantics {
                        contentDescription = description
                    },
            ) { Text(if (count > 99) "99+" else count.toString()) }
        },
    ) {
        Icon(imageVector = icon.vector(), contentDescription = null)
    }
}

/** Bundled 24 dp glyphs for the four destinations. */
private fun NavIcon.vector(): ImageVector =
    when (this) {
        NavIcon.HOME -> NavIcons.Home
        NavIcon.MESSAGES -> NavIcons.Messages
        NavIcon.PROTECTION -> NavIcons.Firewall
        NavIcon.SETTINGS -> NavIcons.Settings
    }

/** The four destination glyphs, bundled so the app needs no downloadable icon font. */
object NavIcons {
    val Home: ImageVector by lazy {
        icon("Home") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 3f)
                lineTo(2f, 12f)
                horizontalLineTo(5f)
                verticalLineTo(21f)
                horizontalLineTo(10f)
                verticalLineTo(15f)
                horizontalLineTo(14f)
                verticalLineTo(21f)
                horizontalLineTo(19f)
                verticalLineTo(12f)
                horizontalLineTo(22f)
                close()
            }
        }
    }

    val Messages: ImageVector by lazy {
        icon("Messages") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 4f)
                horizontalLineTo(20f)
                verticalLineTo(16f)
                horizontalLineTo(8f)
                lineTo(4f, 20f)
                close()
            }
        }
    }

    val Firewall: ImageVector by lazy {
        icon("Firewall") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                lineTo(20f, 5f)
                verticalLineTo(11f)
                curveTo(20f, 16f, 16.6f, 20.5f, 12f, 22f)
                curveTo(7.4f, 20.5f, 4f, 16f, 4f, 11f)
                verticalLineTo(5f)
                close()
            }
        }
    }

    val Settings: ImageVector by lazy {
        icon("Settings") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 8f)
                curveTo(9.8f, 8f, 8f, 9.8f, 8f, 12f)
                curveTo(8f, 14.2f, 9.8f, 16f, 12f, 16f)
                curveTo(14.2f, 16f, 16f, 14.2f, 16f, 12f)
                curveTo(16f, 9.8f, 14.2f, 8f, 12f, 8f)
                close()
                moveTo(19.4f, 13f)
                lineTo(21.5f, 14.6f)
                lineTo(19.5f, 18.1f)
                lineTo(17f, 17.1f)
                curveTo(16.4f, 17.6f, 15.7f, 18f, 15f, 18.3f)
                lineTo(14.6f, 21f)
                horizontalLineTo(10.6f)
                lineTo(10.2f, 18.3f)
                curveTo(9.5f, 18f, 8.8f, 17.6f, 8.2f, 17.1f)
                lineTo(5.7f, 18.1f)
                lineTo(3.7f, 14.6f)
                lineTo(5.8f, 13f)
                curveTo(5.8f, 12.7f, 5.8f, 12.3f, 5.8f, 12f)
                curveTo(5.8f, 11.7f, 5.8f, 11.3f, 5.8f, 11f)
                lineTo(3.7f, 9.4f)
                lineTo(5.7f, 5.9f)
                lineTo(8.2f, 6.9f)
                curveTo(8.8f, 6.4f, 9.5f, 6f, 10.2f, 5.7f)
                lineTo(10.6f, 3f)
                horizontalLineTo(14.6f)
                lineTo(15f, 5.7f)
                curveTo(15.7f, 6f, 16.4f, 6.4f, 17f, 6.9f)
                lineTo(19.5f, 5.9f)
                lineTo(21.5f, 9.4f)
                lineTo(19.4f, 11f)
                curveTo(19.4f, 11.3f, 19.4f, 11.7f, 19.4f, 12f)
                close()
            }
        }
    }

    private fun icon(
        name: String,
        block: ImageVector.Builder.() -> Unit,
    ): ImageVector =
        ImageVector
            .Builder(name, 24.dp, 24.dp, 24f, 24f)
            .apply(block)
            .build()
}
