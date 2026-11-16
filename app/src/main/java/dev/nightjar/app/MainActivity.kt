package dev.nightjar.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.nightjar.app.core.security.AppLockController
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.feature.onboarding.LaunchScreenOverlay
import dev.nightjar.app.ui.components.LockSurface
import dev.nightjar.app.ui.mode.ProvideMode
import dev.nightjar.app.ui.nav.NightjarAppRoot
import dev.nightjar.app.ui.nav.NightjarScreensImpl
import dev.nightjar.app.ui.text.LocalStrings
import dev.nightjar.app.ui.text.rememberStrings
import dev.nightjar.app.ui.theme.NightjarTheme
import dev.nightjar.app.ui.theme.NjMotionPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The single activity.
 *
 * Edge-to-edge with no orientation lock and no back override: predictive back is the platform default. The
 * theme and the mode come from the settings store, so a change applies without a restart.
 *
 * Three things live here and nowhere else:
 *  - the app-lock gate: while [AppLockController.locked] the navigation host is not composed at all, so no screen's
 *    ViewModel keeps collecting and no row flashes behind the lock;
 *  - `FLAG_SECURE` from `privacy.blockScreenshots`, applied once for every screen and while locked, so no screen's
 *    disposal can clear the flag for another;
 *  - the `nightjar://message/{id}` entry point, parsed from the launching and the new intent and handed to the graph,
 *  which pushes the Home → Messages → Detail back stack;
 *  - every other new intent (for example a notification that opens a screen of the graph while the app is
 *    open) goes to [NightjarAppRoot] as `deepLinkIntent`. The launching intent is not passed: NavHost reads it itself
 *    when it sets the graph.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsStore

    @Inject lateinit var appLock: AppLockController

    @Inject lateinit var screens: NightjarScreensImpl

    /** A `nightjar://message/{id}` from the launching or the latest intent, until the graph consumes it. */
    private val pendingMessage = MutableStateFlow<Long?>(null)

    /** The latest intent from [onNewIntent], until the graph handled it. Never the launching intent. */
    private val pendingIntent = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // After a recreation (font scale, locale, process death) the navigation state is restored, so the old intent
        // must not push the message again over the screen the user had open. Only a link not yet consumed comes back.
        pendingMessage.value =
            if (savedInstanceState == null) {
                intent?.let(::messageIdOf)
            } else {
                savedInstanceState.getLong(KEY_PENDING_MESSAGE, NO_MESSAGE).takeIf { it != NO_MESSAGE }
            }
        setContent {
            NightjarContent()
            // Continues the system splash screen until the lock decision and the settings are read.
            LaunchScreenOverlay(
                ready = {
                    appLock.locked.first { it != null }
                    settings.current()
                },
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingMessage.value?.let { outState.putLong(KEY_PENDING_MESSAGE, it) }
    }

    override fun onStart() {
        super.onStart()
        // The controller reads the stored settings, not the eagerly-defaulted snapshot: `onStart` runs before
        // DataStore's first emission, and the default `appLock = false` would leave a configured lock off.
        lifecycleScope.launch { appLock.onForeground() }
    }

    override fun onStop() {
        appLock.onBackground()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingMessage.value = messageIdOf(intent)
        pendingIntent.value = intent
    }

    @Composable
    private fun NightjarContent() {
        val snapshot by settings.settings.collectAsStateWithLifecycle()
        // null until the first decision after process start: compose neither the app nor the lock before that, so a
        // locked cold start cannot flash a screen built from the default settings snapshot.
        val locked by appLock.locked.collectAsStateWithLifecycle()
        val deepLink by pendingMessage.collectAsStateWithLifecycle()
        val deepLinkIntent by pendingIntent.collectAsStateWithLifecycle()
        val strings = rememberStrings()
        val scope = rememberCoroutineScope()

        // Every screen shows message data (Home's "Needs attention", Export, Logs), so the flag is
        // applied once for the whole window rather than per screen — and it stays on while the lock surface is up.
        val secure = snapshot.blockScreenshots || locked == true
        LaunchedEffect(secure) {
            if (secure) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        NightjarTheme(
            themeMode = snapshot.theme,
            dynamicColor = snapshot.dynamicColor,
            accent = snapshot.accent,
            trueBlack = snapshot.trueBlack,
            motionPrefs =
                NjMotionPrefs(
                    reduceMotion = snapshot.reduceMotion,
                    listAnimations = snapshot.listAnimations,
                    bounce = snapshot.listBounce,
                    haptics = snapshot.haptics,
                ),
        ) {
            CompositionLocalProvider(LocalStrings provides strings) {
                ProvideMode(snapshot.mode) {
                    when (locked) {
                        null -> {
                            Unit
                        }

                        true -> {
                            LockSurface(
                                onAuthenticated = { appLock.unlock() },
                                onNoCredential = {
                                    scope.launch { appLock.unlockWithoutCredential() }
                                },
                            )
                        }

                        false -> {
                            NightjarAppRoot(
                                screens = screens,
                                floatingBar = snapshot.floatingBar,
                                deepLinkMessage = deepLink,
                                onDeepLinkHandled = { pendingMessage.value = null },
                                deepLinkIntent = deepLinkIntent,
                                onDeepLinkIntentHandled = { pendingIntent.value = null },
                            )
                        }
                    }
                }
            }
        }
    }

    /** `nightjar://message/{id}` → the message id; every other intent (launcher, notification "Review") → null. */
    private fun messageIdOf(intent: Intent): Long? {
        val data = intent.data ?: return null
        if (data.scheme != "nightjar" || data.host != "message") return null
        return data.lastPathSegment?.toLongOrNull()
    }

    private companion object {
        const val KEY_PENDING_MESSAGE = "nightjar.pendingMessage"
        const val NO_MESSAGE = Long.MIN_VALUE
    }
}
