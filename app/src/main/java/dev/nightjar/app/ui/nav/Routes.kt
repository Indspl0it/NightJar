package dev.nightjar.app.ui.nav

import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/**
 * Every destination of the app, as type-safe navigation routes.
 *
 * Each route is `@Serializable`, so `composable<Messages>()` gets its arguments from the route itself instead of a
 * string map, and a renamed field is a compile error rather than a runtime crash. Deep links are declared on the route
 * they open ([DeepLinks]).
 */
@Serializable
sealed interface Route {
    /** Onboarding (first run only). */
    @Serializable data object Onboarding : Route

    /** Home. */
    @Serializable data object Home : Route

    /**
     * Messages. [filter] carries a chip wire name from a notification deep link ("Review"). [search] puts the
     * focus in the search field when the screen opens (Home search button).
     */
    @Serializable
    data class Messages(
        val filter: String? = null,
        val search: Boolean = false,
    ) : Route

    /** Message detail. */
    @Serializable
    data class MessageDetail(
        val messageId: Long,
    ) : Route

    /** Firewall. */
    @Serializable data object Firewall : Route

    /** Custom rules. */
    @Serializable data object CustomRules : Route

    /**
     * Rule editor. [ruleId] edits an existing rule; [messageId] pre-fills from a message ("Rule, " action);
     * both null creates an empty rule.
     */
    @Serializable
    data class RuleEditor(
        val ruleId: Long? = null,
        val messageId: Long? = null,
    ) : Route

    /** Protection health. */
    @Serializable data object Health : Route

    /** What Nightjar can't see. */
    @Serializable data object BlindSpots : Route

    /** Settings. */
    @Serializable data object Settings : Route

    /** Export. */
    @Serializable data object Export : Route

    /** Logs (Researcher). */
    @Serializable data object Logs : Route

    /**
     * Known senders and trusted apps. [tab] is the name of the `KnownTab` the screen opens on (Settings > Trusted
     * apps opens APPS); null or an unknown name opens the senders tab.
     */
    @Serializable
    data class KnownSenders(
        val tab: String? = null,
    ) : Route

    /** Audit history (Researcher). */
    @Serializable data object AuditHistory : Route

    /** Apps that send SMS, each with a "Block sending" switch (Settings > Outgoing messages). */
    @Serializable data object SendingApps : Route
}

/**
 * The four top-level destinations of the navigation suite.
 *
 * Order is the bar order: Home, Messages, Protection, Settings. Every route that is not a tab belongs to exactly one
 * tab through [routeClasses]. That list selects the tab in the bar and gives the direction of the tab-change motion.
 * Protection owns the firewall, the rules and the protection health screens.
 */
enum class TopDestination(
    val route: Route,
    val labelRes: Int,
    val icon: NavIcon,
    val routeClasses: List<KClass<out Route>>,
) {
    HOME(
        Route.Home,
        dev.nightjar.app.R.string.nav_home,
        NavIcon.HOME,
        listOf(Route.Home::class),
    ),
    MESSAGES(
        Route.Messages(),
        dev.nightjar.app.R.string.nav_messages,
        NavIcon.MESSAGES,
        listOf(
            Route.Messages::class,
            Route.MessageDetail::class,
            Route.KnownSenders::class,
        ),
    ),
    PROTECTION(
        Route.Firewall,
        dev.nightjar.app.R.string.nj_cmp_nav_protection,
        NavIcon.PROTECTION,
        listOf(
            Route.Firewall::class,
            Route.CustomRules::class,
            Route.RuleEditor::class,
            Route.Health::class,
            Route.BlindSpots::class,
        ),
    ),
    SETTINGS(
        Route.Settings,
        dev.nightjar.app.R.string.nav_settings,
        NavIcon.SETTINGS,
        listOf(
            Route.Settings::class,
            Route.Export::class,
            Route.Logs::class,
            Route.AuditHistory::class,
            Route.SendingApps::class,
        ),
    ),
    ;

    /** True when [route] is this destination or one of its descendants (for selection highlighting). */
    fun matches(route: Route?): Boolean = route != null && routeClasses.any { it.isInstance(route) }
}

/** Bundled navigation icons (no downloadable icon font). */
enum class NavIcon { HOME, MESSAGES, PROTECTION, SETTINGS }

/** Deep-link base paths. */
object DeepLinks {
    /** `nightjar://message/{messageId}` — review or tap a notification. */
    const val MESSAGE = "nightjar://message"

    /** `nightjar://messages?filter=quarantined` — Messages with the "On hold" chip. */
    const val MESSAGES = "nightjar://messages"

    /** `nightjar://rule/new?messageId={messageId}` — the notification's "Rule…" action (pre-filled). */
    const val RULE_NEW = "nightjar://rule/new"

    /** `nightjar://health` — status notification tap. */
    const val HEALTH = "nightjar://health"

    /** Navigation URI patterns: the argument names match the route fields. */
    const val MESSAGES_PATTERN = "nightjar://messages?filter={filter}"

    const val RULE_NEW_PATTERN = "nightjar://rule/new?messageId={messageId}"

    const val HEALTH_PATTERN = "nightjar://health"
}
