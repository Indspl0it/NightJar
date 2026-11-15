package dev.nightjar.app.feature.settings

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.SendingApp
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.feature.rules.ToolGroupRow
import dev.nightjar.app.feature.rules.ToolRowPosition
import dev.nightjar.app.feature.rules.ToolScreen
import dev.nightjar.app.feature.rules.toolCard
import dev.nightjar.app.feature.rules.toolLoading
import dev.nightjar.app.ui.components.NjCaption
import dev.nightjar.app.ui.components.NjDialog
import dev.nightjar.app.ui.components.NjEmptyState
import dev.nightjar.app.ui.components.NjIcons
import dev.nightjar.app.ui.components.NjListRow
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.nav.NavActions
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Apps that send SMS": the apps that sent SMS, each with a "Block sending" switch. */
@Composable
fun SendingAppsScreen(
    actions: NavActions,
    viewModel: SendingAppsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val saveFailed = stringResource(R.string.settings_save_failed)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                SendingAppsEvent.SaveFailed -> snackbar.showSnackbar(saveFailed)
            }
        }
    }
    SendingAppsContent(
        state = state,
        snackbar = snackbar,
        onBack = { actions.back() },
        onSwitch = viewModel::onSwitch,
    )
    state.confirmBlock?.let { packageName ->
        val label = rememberAppInfo(packageName).label
        NjDialog(
            title = stringResource(R.string.settings_sending_apps_block_title, label),
            text =
                listOfNotNull(
                    stringResource(R.string.settings_sending_apps_block_body),
                    stringResource(R.string.settings_sending_apps_block_default).takeIf { state.confirmDefaultSmsApp },
                ).joinToString("\n\n"),
            confirmText = stringResource(R.string.settings_sending_apps_block_confirm),
            destructive = true,
            onDismiss = viewModel::dismissBlock,
            onConfirm = viewModel::confirmBlock,
        )
    }
}

@Composable
private fun SendingAppsContent(
    state: SendingAppsUiState,
    snackbar: SnackbarHostState?,
    onBack: () -> Unit,
    onSwitch: (packageName: String, block: Boolean) -> Unit,
) {
    val title = stringResource(R.string.settings_sending_apps)
    ToolScreen(title = title, onBack = onBack, snackbar = snackbar) {
        item(key = "intro") {
            NjCaption(stringResource(R.string.settings_sending_apps_intro), Modifier.toolCard().njEnter(1))
        }
        when {
            state.loading -> {
                toolLoading(3)
            }

            state.apps.isEmpty() -> {
                item(key = "empty") {
                    NjEmptyState(
                        title = title,
                        body = stringResource(R.string.settings_sending_apps_empty),
                        modifier = Modifier.njEnter(2),
                    )
                }
            }

            else -> {
                itemsIndexed(state.apps, key = { _, app -> "app-" + app.packageName }) { index, app ->
                    ToolGroupRow(position = ToolRowPosition.of(index, state.apps.size), modifier = Modifier.njEnter(2 + index)) {
                        SendingAppRow(app = app, onSwitch = onSwitch)
                    }
                }
            }
        }
    }
}

/** One app: icon, label, how many SMS it sent and when it sent the last one, and the "Block sending" switch. */
@Composable
private fun SendingAppRow(
    app: SendingApp,
    onSwitch: (packageName: String, block: Boolean) -> Unit,
) {
    val info = rememberAppInfo(app.packageName)
    val last = app.lastSentWall
    val sent =
        if (last != null) {
            pluralStringResource(R.plurals.settings_sending_apps_sent, app.count, app.count, UiFormat.dateTime(last))
        } else {
            stringResource(R.string.settings_sending_apps_none_stored)
        }
    val blocked = stringResource(R.string.settings_sending_apps_blocked).takeIf { app.blocked }
    NjListRow(
        title = info.label,
        supporting = listOfNotNull(sent, blocked).joinToString("\n"),
        leading = { AppIcon(info.icon) },
        trailing = { Switch(checked = app.blocked, onCheckedChange = null) },
        modifier =
            Modifier.toggleable(
                value = app.blocked,
                role = Role.Switch,
                onValueChange = { block -> onSwitch(app.packageName, block) },
            ),
    )
}

@Composable
private fun AppIcon(icon: ImageBitmap?) {
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
    } else {
        Icon(NjIcons.Send, contentDescription = null, tint = NjTheme.roles.mu, modifier = Modifier.size(ICON_SIZE))
    }
}

/** The label and the icon of an app. An app that is not installed any more shows its package name and no icon. */
private class AppInfo(
    val label: String,
    val icon: ImageBitmap?,
)

/** PackageManager reads are binder calls, so they run on [Dispatchers.IO]; the package name shows until they return. */
@Composable
private fun rememberAppInfo(packageName: String): AppInfo {
    val context = LocalContext.current
    return produceState(AppInfo(packageName, null), packageName) {
        value = withContext(Dispatchers.IO) { loadAppInfo(context.packageManager, packageName) }
    }.value
}

private fun loadAppInfo(
    pm: PackageManager,
    packageName: String,
): AppInfo =
    try {
        val info = pm.getApplicationInfo(packageName, 0)
        val icon = runCatching { pm.getApplicationIcon(info).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull()
        AppInfo(pm.getApplicationLabel(info).toString(), icon)
    } catch (_: PackageManager.NameNotFoundException) {
        AppInfo(packageName, null)
    }

private val ICON_SIZE = 40.dp

/** The bitmap size of an app icon: 40 dp at the highest common density (xxxhdpi, 4x). */
private const val ICON_PX = 160

// ------------------------------------------------------------------ previews

@Preview(name = "sending apps dark")
@Composable
private fun SendingAppsDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        SendingAppsContent(
            state =
                SendingAppsUiState(
                    loading = false,
                    apps =
                        listOf(
                            SendingApp("com.example.messages", lastSentWall = 1_762_300_000_000L, count = 12, blocked = false),
                            SendingApp("com.example.tracker", lastSentWall = 1_762_200_000_000L, count = 1, blocked = true),
                            SendingApp("com.example.removed", lastSentWall = null, count = 0, blocked = true),
                        ),
                ),
            snackbar = null,
            onBack = {},
            onSwitch = { _, _ -> },
        )
    }
}

@Preview(name = "sending apps empty light")
@Composable
private fun SendingAppsEmptyPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) {
        SendingAppsContent(state = SendingAppsUiState(loading = false), snackbar = null, onBack = {}, onSwitch = { _, _ -> })
    }
}
