package dev.nightjar.app.core.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat

/**
 * The only runtime permission the app asks for.
 *
 * Nightjar is **not** the default SMS app and never asks for `READ_SMS`, `RECEIVE_SMS` or `READ_PHONE_STATE`:
 * it observes the phone process instead of the inbox. `RECEIVE_BOOT_COMPLETED`, `USE_BIOMETRIC` and
 * `QUERY_ALL_PACKAGES` are install-time. So the entire runtime flow is `POST_NOTIFICATIONS`, plus the
 * system notification-settings screen for channel-level choices (AC2).
 */
enum class RuntimePermission(
    val manifestName: String,
) {
    POST_NOTIFICATIONS(Manifest.permission.POST_NOTIFICATIONS),
}

/** State of one runtime permission. */
enum class PermissionState {
    GRANTED,
    DENIED,
    ;

    val isGranted: Boolean get() = this == GRANTED
}

/** Reads the current state without prompting. */
fun permissionState(
    context: Context,
    permission: RuntimePermission = RuntimePermission.POST_NOTIFICATIONS,
): PermissionState =
    if (ContextCompat.checkSelfPermission(context, permission.manifestName) == PackageManager.PERMISSION_GRANTED) {
        PermissionState.GRANTED
    } else {
        PermissionState.DENIED
    }

/**
 * True when the platform will still show the dialog. On Android 13+ a denial is permanent only after the user picked
 * "Don't allow" twice or the app asked too often, so the UI must offer the settings screen instead of a dead button
 * ("no dead ends"). Only an [Activity] can answer, so the caller passes one.
 */
fun shouldShowRationale(
    activity: Activity,
    permission: RuntimePermission = RuntimePermission.POST_NOTIFICATIONS,
): Boolean = activity.shouldShowRequestPermissionRationale(permission.manifestName)

/** Opens the system notification settings for this app (AC2: "each switch links to the channel settings"). */
fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

/** Opens the system app-notification settings filtered to a channel. */
fun channelSettingsIntent(
    context: Context,
    channelId: String,
): Intent =
    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)

/**
 * Composable helper for the POST_NOTIFICATIONS flow.
 *
 * Returns the current state and a `request` lambda. The caller decides *when* to ask (onboarding asks after the module is
 * installed), so this never prompts on composition.
 */
class NotificationPermissionHandle internal constructor(
    val state: MutableState<PermissionState>,
    private val request: () -> Unit,
) {
    fun requestPermission() = request()

    fun refresh(context: Context) {
        state.value = permissionState(context)
    }
}

@Composable
fun rememberNotificationPermission(context: Context): NotificationPermissionHandle {
    val state = remember { mutableStateOf(permissionState(context)) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            state.value = if (granted) PermissionState.GRANTED else PermissionState.DENIED
        }
    return remember(context) {
        NotificationPermissionHandle(state) { launcher.launch(RuntimePermission.POST_NOTIFICATIONS.manifestName) }
    }
}
