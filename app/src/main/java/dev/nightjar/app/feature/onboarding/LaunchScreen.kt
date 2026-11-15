package dev.nightjar.app.feature.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nightjar.app.R
import dev.nightjar.app.ui.theme.LocalNjRoles
import dev.nightjar.app.ui.theme.NjAccent
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjSurfaceMode
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.njRoles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The launch screen: it continues the system splash screen until the start destination is ready.
 *
 * The system splash screen (theme attributes in themes.xml) shows [R.drawable.splash_logo] centred in the window: a
 * 240 dp icon with a 160 dp circle. This screen draws the same drawable at the same place and size, and adds the name
 * under it, so the hand-over has no jump. It shows only while [ready] runs, and never longer than [LAUNCH_MAX_MS] in
 * total. It has no animation.
 */
@Composable
fun LaunchScreenOverlay(ready: suspend () -> Unit) {
    // Saveable: a configuration change does not show the launch screen again.
    var visible by rememberSaveable { mutableStateOf(true) }
    if (!visible) return
    LaunchedEffect(Unit) {
        withTimeoutOrNull(LAUNCH_MAX_MS) {
            try {
                ready()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The app under the launch screen shows its own error state. The launch screen only steps aside.
            }
            // Two frames: the start destination composes and draws below the launch screen before it goes.
            withFrameNanos { }
            withFrameNanos { }
        }
        visible = false
    }
    LaunchScreen()
}

/** The longest time that the launch screen shows (: less than one second). */
const val LAUNCH_MAX_MS: Long = 800

/** Size of the splash icon with an icon background (splash screen API: 240 dp, the circle is 160 dp). */
private val SplashIconSize = 240.dp

/** Space between the logo circle and the name (18 px). */
private val NameGap = NjSpacing.m + 2.dp

/**
 * The logo and the name on the dark app background. The splash screen is always dark, also in the light theme,
 * because the logo itself is dark: the dark roles apply here.
 */
@Composable
fun LaunchScreen(modifier: Modifier = Modifier) {
    val dark = remember { njRoles(NjSurfaceMode.DARK, NjAccent.NIGHT_BLUE.dark) }
    CompositionLocalProvider(LocalNjRoles provides dark) {
        val roles = NjTheme.roles
        val name = stringResource(R.string.app_name)
        Layout(
            // The overlay takes every touch, so a tap during the launch screen never reaches the screen below it.
            modifier = modifier.fillMaxSize().background(roles.bg).consumeAllPointerInput(),
            content = {
                Image(
                    painter = painterResource(R.drawable.splash_logo),
                    contentDescription = stringResource(R.string.setup_logo_description),
                    modifier = Modifier.size(SplashIconSize),
                )
                Text(
                    name,
                    style = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 30.sp),
                    color = roles.tx,
                )
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val logo = measurables[0].measure(loose)
            val label = measurables[1].measure(loose)
            layout(constraints.maxWidth, constraints.maxHeight) {
                // The logo stays at the exact centre of the window, as on the system splash screen. The name goes
                // below the visible circle (two thirds of the icon), not below the full icon box.
                val cx = constraints.maxWidth / 2
                val cy = constraints.maxHeight / 2
                logo.place(cx - logo.width / 2, cy - logo.height / 2)
                val circleBottom = cy + logo.height / 3
                label.place(cx - label.width / 2, circleBottom + NameGap.roundToPx())
            }
        }
    }
}

/** Consumes every pointer event in this area (L2). */
private fun Modifier.consumeAllPointerInput(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }

@Preview(name = "Launch screen")
@Composable
private fun LaunchScreenPreview() {
    LaunchScreen()
}
