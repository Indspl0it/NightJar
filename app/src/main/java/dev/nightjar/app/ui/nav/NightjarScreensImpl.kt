package dev.nightjar.app.ui.nav

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import dev.nightjar.app.R
import dev.nightjar.app.feature.detail.MessageDetailScreen
import dev.nightjar.app.feature.export.ExportScreen
import dev.nightjar.app.feature.export.exportOne
import dev.nightjar.app.feature.firewall.FirewallScreen
import dev.nightjar.app.feature.health.BlindSpotsScreen
import dev.nightjar.app.feature.health.HealthScreen
import dev.nightjar.app.feature.home.HomeScreen
import dev.nightjar.app.feature.known.KnownSendersScreen
import dev.nightjar.app.feature.known.KnownTab
import dev.nightjar.app.feature.logs.AuditHistoryScreen
import dev.nightjar.app.feature.logs.LogsScreen
import dev.nightjar.app.feature.messages.MessagesScreen
import dev.nightjar.app.feature.onboarding.OnboardingScreen
import dev.nightjar.app.feature.rules.CustomRulesScreen
import dev.nightjar.app.feature.rules.RuleEditorScreen
import dev.nightjar.app.feature.settings.SendingAppsScreen
import dev.nightjar.app.feature.settings.SettingsScreen
import dev.nightjar.app.feature.shell.AppShellResumeEffect
import dev.nightjar.app.feature.shell.AppShellViewModel
import dev.nightjar.app.feature.shell.ShellBanner
import dev.nightjar.contract.Severity
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * The app's screens.
 *
 * [nightjarGraph] owns the graph, and this class binds every destination to its screen. Each destination belongs to
 * its own feature package and is reached through its public composables only.
 */
class NightjarScreensImpl
    @Inject
    constructor() : NightjarScreens {
        @Composable
        override fun Onboarding(actions: NavActions) {
            OnboardingScreen(onFinished = { actions.upTo(TopDestination.HOME) })
        }

        @Composable
        override fun Home(actions: NavActions) {
            HomeScreen(
                onOpenMessage = { id -> actions.openDetail(id) },
                onOpenHealth = { actions.open(Route.Health) },
                onOpenMessages = { actions.open(Route.Messages()) },
                onOpenBlindSpots = { actions.open(Route.BlindSpots) },
                // "create rule" from an attention row opens the editor for that message.
                onOpenRule = { id -> actions.open(Route.RuleEditor(messageId = id)) },
                // The search button of Home opens Messages with the focus in the search field.
                onSearchMessages = { actions.open(Route.Messages(search = true)) },
                // The "Blocking off" card opens the Protection tab, where the Blocking switch is.
                onOpenProtection = { actions.upTo(TopDestination.PROTECTION) },
            )
        }

        @Composable
        override fun Messages(
            route: Route.Messages,
            actions: NavActions,
        ) {
            MessagesScreen(
                focusSearch = route.search,
                onOpenMessage = { id -> actions.openDetail(id) },
                onOpenRule = { id -> actions.open(Route.RuleEditor(messageId = id)) },
                onExport = { actions.open(Route.Export) },
                detailPane = { id, onClose ->
                    MessageDetailScreen(
                        messageId = id,
                        onBack = onClose,
                        onOpenMessage = { next -> actions.openDetail(next) },
                        onOpenRule = { messageId -> actions.open(Route.RuleEditor(messageId = messageId)) },
                        // Scope the export before opening the screen, otherwise Export shows the
                        // previous selection.
                        onExport = {
                            exportOne(id)
                            actions.open(Route.Export)
                        },
                    )
                },
            )
        }

        @Composable
        override fun MessageDetail(
            route: Route.MessageDetail,
            actions: NavActions,
        ) {
            MessageDetailScreen(
                messageId = route.messageId,
                onBack = { actions.back() },
                onOpenMessage = { id -> actions.openDetail(id) },
                onOpenRule = { id -> actions.open(Route.RuleEditor(messageId = id)) },
                onExport = {
                    exportOne(route.messageId)
                    actions.open(Route.Export)
                },
            )
        }

        @Composable
        override fun Firewall(actions: NavActions) = FirewallScreen(actions)

        @Composable
        override fun CustomRules(actions: NavActions) = CustomRulesScreen(actions)

        @Composable
        override fun RuleEditor(
            route: Route.RuleEditor,
            actions: NavActions,
        ) = RuleEditorScreen(route, actions)

        @Composable
        override fun Health(actions: NavActions) {
            HealthScreen(
                onBack = { actions.back() },
                onOpenBlindSpots = { actions.open(Route.BlindSpots) },
                onOpenLogs = { actions.open(Route.Logs) },
            )
        }

        @Composable
        override fun BlindSpots(actions: NavActions) {
            BlindSpotsScreen(onBack = { actions.back() })
        }

        @Composable
        override fun Settings(actions: NavActions) = SettingsScreen(actions)

        @Composable
        override fun Export(actions: NavActions) = ExportScreen(actions)

        @Composable
        override fun Logs(actions: NavActions) = LogsScreen(actions)

        @Composable
        override fun KnownSenders(
            route: Route.KnownSenders,
            actions: NavActions,
        ) = KnownSendersScreen(
            actions = actions,
            initialTab = KnownTab.entries.firstOrNull { it.name == route.tab } ?: KnownTab.SENDERS,
        )

        @Composable
        override fun AuditHistory(actions: NavActions) = AuditHistoryScreen(actions)

        @Composable
        override fun SendingApps(actions: NavActions) = SendingAppsScreen(actions)
    }

/**
 * The whole app: the shell, the banner and the navigation graph.
 *
 * The start destination waits for the first settings read, so a returning user never sees onboarding flash for a frame.
 *
 * [deepLinkMessage] is a `nightjar://message/{id}` the activity parsed. It goes through
 * [navigateToMessage] so the back stack is Home → Messages → Detail, and it is dropped while onboarding is the start destination:
 * an external intent must not skip first-run setup. [onDeepLinkHandled] clears it once it has been applied.
 *
 * [deepLinkIntent] is any other `nightjar://` intent from `onNewIntent` (singleTask): `messages?filter`,
 * `rule/new` and `health`. A URI that matches no graph deep link (for example `message/{id}`) is ignored. NavHost
 * reads the launching intent itself when it sets the graph, so the activity passes only new intents here. The URI goes
 * to the graph's own deep links without the intent flags: a NEW_TASK flag would make NavController start a new task.
 * [onDeepLinkIntentHandled] clears it once it has been applied.
 */
@Composable
fun NightjarAppRoot(
    screens: NightjarScreens,
    deepLinkMessage: Long? = null,
    onDeepLinkHandled: () -> Unit = {},
    deepLinkIntent: Intent? = null,
    onDeepLinkIntentHandled: () -> Unit = {},
    floatingBar: Boolean = true,
) {
    val shell: AppShellViewModel = hiltViewModel()
    val shellState by shell.uiState.collectAsStateWithLifecycle()
    val startOnboarding = shellState.startOnboarding
    if (startOnboarding == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    // The protection banner must re-read the payload state when the app comes back to the
    // foreground; without this the banner showed the state from the last cold start.
    AppShellResumeEffect(shell)
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val top = topDestinationOf(destination)
    val onHome = destination?.hasRoute<Route.Home>() == true
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(deepLinkMessage, startOnboarding) {
        if (deepLinkMessage == null) return@LaunchedEffect
        if (!startOnboarding) {
            // The navigation host is composed inside the shell's scaffold (a subcomposition), so the graph can be set
            // after this effect starts. Wait for the first back stack entry: navigate() before setGraph() throws.
            nav.currentBackStackEntryFlow.first()
            nav.navigateToMessage(deepLinkMessage)
        }
        onDeepLinkHandled()
    }

    LaunchedEffect(deepLinkIntent, startOnboarding) {
        val uri = deepLinkIntent?.data ?: return@LaunchedEffect
        if (!startOnboarding) {
            nav.currentBackStackEntryFlow.first()
            if (nav.graph.hasDeepLink(uri)) nav.navigate(uri, navOptions { launchSingleTop = true })
        }
        onDeepLinkIntentHandled()
    }

    // The start destination is fixed for the life of the graph. A new value would make NavHost build a new graph and
    // reset the back stack. The end of onboarding goes to Home through NavActions.upTo, which also removes onboarding.
    val startDestination: Route = remember { if (startOnboarding) Route.Onboarding else Route.Home }
    // The bar and the banner appear for every destination except onboarding is a full-screen first-run flow.
    // There is one navigation host in one place, so the end of onboarding does not create a second host.
    val onboarding = destination?.hasRoute<Route.Onboarding>() ?: startOnboarding
    NightjarShell(
        current = top?.route,
        onNavigate = { destinationTop -> nav.navigateToTop(destinationTop) },
        quarantineBadge = shellState.quarantineBadge,
        floatingBar = floatingBar,
        showNavigation = !onboarding,
        // Home's status hero already says the same thing with an action, so the protection banner is hidden there.
        banner =
            shellState.banner
                ?.takeUnless { onHome && !it.isNotification && !it.isOta }
                ?.toBannerState(context, nav),
        snackbarHostState = snackbar,
    ) {
        NightjarNavHost(navController = nav, startDestination = startDestination, screens = screens)
    }
}

/**
 * Bottom-bar navigation: a tab always lands on its own root screen, over a Home base.
 *
 * Saved/restored tab state is deliberately not used. It made the Home tab reopen whatever screen of that tab was last
 * left (Protection health, a message detail) instead of Home, which read as "Home does nothing", and it depended on the
 * graph's start destination, which is onboarding until onboarding has finished. Home is found by route, so the base is
 * correct whichever start destination the graph was built with, and a missing Home is recreated.
 */
internal fun NavHostController.navigateToTop(destination: TopDestination) {
    val current = currentDestination
    val atRoot =
        when (destination) {
            TopDestination.HOME -> current?.hasRoute<Route.Home>() == true
            else -> current?.hasRoute(destination.route::class) == true
        }
    if (atRoot) return
    // One navigation step, so a tab change plays one shared-axis motion, not a pop and then a push.
    val homeInStack = runCatching { getBackStackEntry<Route.Home>() }.isSuccess
    if (homeInStack) {
        if (destination == TopDestination.HOME) {
            popBackStack<Route.Home>(inclusive = false)
        } else {
            navigate(destination.route) {
                popUpTo<Route.Home> { inclusive = false }
                launchSingleTop = true
            }
        }
        return
    }
    navigate(Route.Home) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
    if (destination != TopDestination.HOME) {
        navigate(destination.route) { launchSingleTop = true }
    }
}

private fun ShellBanner.toBannerState(
    context: android.content.Context,
    nav: NavHostController,
): BannerState =
    BannerState(
        text =
            bannerText(
                title = if (titleRes != 0) context.getString(titleRes) else title,
                body = if (bodyRes != 0) context.getString(bodyRes) else body,
            ),
        severity = if (isNotification || isOta) Severity.NOTABLE else Severity.SUSPICIOUS,
        actionLabel = bannerActionLabel(context, isNotification),
        onAction = {
            if (isNotification) {
                // Some ROMs have no handler for this screen (ActivityNotFoundException): do nothing then.
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            } else {
                nav.navigate(Route.Health) { launchSingleTop = true }
            }
        },
    )

/** One sentence of banner text: the title and body often overlap, so say it once. */
internal fun bannerText(
    title: String,
    body: String,
): String =
    when {
        body.isBlank() -> title
        title.isBlank() -> body
        body.contains(title, ignoreCase = true) -> body
        title.contains(body, ignoreCase = true) -> title
        else -> "$title. $body"
    }

/** The banner button text. The banner state is built outside a composition, so it reads the resource from [context]. */
private fun bannerActionLabel(
    context: android.content.Context,
    notifications: Boolean,
): String =
    context.getString(if (notifications) R.string.shell_open_settings else R.string.shell_health)
