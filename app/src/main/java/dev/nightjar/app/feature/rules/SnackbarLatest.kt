package dev.nightjar.app.feature.rules

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shows [message] at once and replaces the snackbar that is on screen.
 *
 * An event collector that waits for `showSnackbar` holds every later event until the old snackbar times out, so a
 * "Saved" after a failure closed the screen only seconds later. Here the wait runs in a child job of [this] scope (the
 * collector's `LaunchedEffect`), and [onAction] runs only when the user taps the action of this snackbar.
 */
internal fun CoroutineScope.showLatestSnackbar(
    snackbar: SnackbarHostState,
    message: String,
    actionLabel: String? = null,
    duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long,
    onAction: () -> Unit = {},
) {
    snackbar.currentSnackbarData?.dismiss()
    launch {
        val result = snackbar.showSnackbar(message, actionLabel, duration = duration)
        if (result == SnackbarResult.ActionPerformed) onAction()
    }
}
