package dev.nightjar.app.feature.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.domain.protection.InstallCheck
import dev.nightjar.app.domain.protection.InstallCheckId
import dev.nightjar.app.domain.protection.ModuleFailure
import dev.nightjar.app.feature.protection.InstallUiState
import dev.nightjar.app.ui.components.LocalNjBottomInset
import dev.nightjar.app.ui.components.NjDialog
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme
import dev.nightjar.app.ui.theme.findActivity

/** The screen is a thin renderer over [OnboardingViewModel]. */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onEvent(OnboardingEvent.NotificationsResult(granted))
        }
    LaunchedEffect(state.finished) {
        if (state.finished) onFinished()
    }
    // Back from Vector, LSPosed, the root manager or the system notification settings: the checks and the
    // notification switch read the phone state again.
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(OnboardingEvent.Resume)
        onPauseOrDispose { }
    }
    BackHandler {
        if (state.step == OnboardingStep.WELCOME) {
            context.findActivity()?.finish()
        } else {
            viewModel.onEvent(OnboardingEvent.Back)
        }
    }
    OnboardingContent(
        state = state,
        onEvent = viewModel::onEvent,
        onRequestNotifications = {
            viewModel.onEvent(OnboardingEvent.RequestNotifications)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        },
        onOpenNotificationSettings = { openNotificationSettings(context) },
        onOpenFramework = { openFramework(context) },
    )
}

/**
 * Onboarding in three screens: a "STEP n OF 3" label at the top, the screen of the step, and
 * three dots above the bottom button. Welcome and Ready have a soft glow behind the top.
 * 1. Welcome: the logo, the question, three point cards and "Start".
 * 2. Setup ([OnboardingStep.CHECKS]): one live stepper (root, Vector or LSPosed, module, reboot). Only the current step
 *    opens as a card with its own button.
 * 3. Ready ([OnboardingStep.DONE]): the notification switch, the Researcher mode switch and "Open NightJar".
 *
 * [onOpenFramework] opens the Vector or LSPosed manager app and returns false when the phone has no manager app that
 * NightJar can open. The screen then shows the manual steps in a dialog, and "Done" there continues to the reboot step.
 */
@Composable
fun OnboardingContent(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit = {},
    onOpenFramework: () -> Boolean = { false },
) {
    var showManualSteps by rememberSaveable { mutableStateOf(false) }
    val roles = NjTheme.roles
    Box(Modifier.fillMaxSize().background(roles.bg)) {
        when (state.step) {
            OnboardingStep.WELCOME -> OnboardingGlow(roles.acc)
            OnboardingStep.DONE -> OnboardingGlow(roles.ok)
            OnboardingStep.CHECKS -> Unit
        }
        OnboardingColumn(state, onEvent, onRequestNotifications, onOpenNotificationSettings) {
            if (onOpenFramework()) {
                onEvent(OnboardingEvent.OpenFramework)
            } else {
                showManualSteps = true
            }
        }
    }
    OnboardingDialogs(state, onEvent, showManualSteps) { showManualSteps = false }
}

/** The step label at the top, then the screen of the current step. [onOpenFramework] is the "Open LSPosed" action. */
@Composable
private fun OnboardingColumn(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenFramework: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            // The shell pads and consumes the safe-drawing insets (status bar, navigation bar, cutouts) also for this
            // flow, so only the gutter and the bottom inset of the shell are added here.
            .padding(horizontal = SetupGutter)
            .padding(top = NjSpacing.m, bottom = LocalNjBottomInset.current + NjSpacing.m),
    ) {
        StepLabel(OnboardingPhase.of(state.step))
        // One key per step: a new step plays the enter motion again.
        key(state.step) {
            when (state.step) {
                OnboardingStep.WELCOME -> {
                    WelcomeStep(onEvent)
                }

                OnboardingStep.CHECKS -> {
                    SetupStep(state = state, onEvent = onEvent, onOpenFramework = onOpenFramework)
                }

                OnboardingStep.DONE -> {
                    ReadyStep(state, onEvent, onRequestNotifications, onOpenNotificationSettings)
                }
            }
        }
    }
}

/** The manual framework steps dialog and the reboot dialog. Root access is a card, not a dialog. */
@Composable
private fun OnboardingDialogs(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    showManualSteps: Boolean,
    onCloseManualSteps: () -> Unit,
) {
    if (showManualSteps) {
        NjDialog(
            title = stringResource(R.string.dlg_02_title),
            text = stringResource(R.string.dlg_02_body),
            confirmText = stringResource(R.string.dlg_02_confirm),
            dismissText = stringResource(R.string.dlg_02_cancel),
            onDismiss = onCloseManualSteps,
            onConfirm = {
                onCloseManualSteps()
                onEvent(OnboardingEvent.OpenFramework)
            },
        )
    }

    if (state.showRebootDialog) {
        val failure = state.rebootFailure
        NjDialog(
            title = stringResource(R.string.dlg_03_title),
            text =
                if (failure == null) {
                    stringResource(R.string.dlg_03_body)
                } else {
                    stringResource(R.string.onb_x_reboot_failed, stringResource(failureText(failure)))
                },
            confirmText = stringResource(R.string.dlg_03_confirm),
            confirmEnabled = !state.rebooting,
            // A reboot stops every app on the phone: the same red confirm button as "Restart phone" in Settings.
            destructive = true,
            onDismiss = { onEvent(OnboardingEvent.DismissReboot) },
            onConfirm = { onEvent(OnboardingEvent.RebootNow) },
        )
    }
}

/** The Vector and LSPosed manager apps that NightJar can open, in this order. */
private val FrameworkManagerPackages = listOf("org.lsposed.manager", "org.matrix.vector")

/** Opens the first installed Vector or LSPosed manager app. False when there is none that NightJar can open. */
private fun openFramework(context: Context): Boolean {
    val intent =
        FrameworkManagerPackages.firstNotNullOfOrNull { runCatching { context.packageManager.getLaunchIntentForPackage(it) }.getOrNull() }
            ?: return false
    return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

/** The system notification settings of NightJar: the only place where the user can turn notifications off. */
private fun openNotificationSettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** The reason of a failed root operation, as the end of a sentence (the same strings as Settings). */
@StringRes
private fun failureText(failure: ModuleFailure): Int =
    when (failure) {
        ModuleFailure.ROOT_DENIED -> R.string.module_failure_root_denied
        ModuleFailure.CLI_FAILED -> R.string.module_failure_cli_failed
        ModuleFailure.CHECKSUM_MISMATCH -> R.string.module_failure_checksum
        ModuleFailure.FILE_UNREADABLE -> R.string.module_failure_unreadable
    }

// ------------------------------------------------------------------ previews

@Preview(name = "welcome light")
@Composable
private fun OnboardingWelcomeLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) {
        OnboardingContent(OnboardingUiState(), {}, {})
    }
}

@Preview(name = "welcome dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OnboardingWelcomeDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        OnboardingContent(OnboardingUiState(), {}, {})
    }
}

private fun previewChecksState(rebootPending: Boolean): OnboardingUiState =
    OnboardingUiState(
        step = OnboardingStep.CHECKS,
        checks =
            listOf(
                InstallCheck(InstallCheckId.ROOT, InstallCheck.State.OK, "30700"),
                InstallCheck(InstallCheckId.FRAMEWORK, InstallCheck.State.OK, "VECTOR"),
                InstallCheck(InstallCheckId.MODULE, InstallCheck.State.WARNING, null),
            ),
        checksRan = true,
        rootLabel = "Magisk 30700",
        install = if (rebootPending) InstallUiState.Success(needsReboot = true) else InstallUiState.Idle,
    )

@Preview(name = "setup dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OnboardingSetupDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        OnboardingContent(previewChecksState(rebootPending = false), {}, {})
    }
}

@Preview(name = "setup root light")
@Composable
private fun OnboardingSetupRootLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) {
        OnboardingContent(OnboardingUiState(step = OnboardingStep.CHECKS), {}, {})
    }
}

@Preview(name = "setup large font dark", fontScale = 1.5f, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OnboardingSetupLargeFontPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        OnboardingContent(previewChecksState(rebootPending = false), {}, {})
    }
}

@Preview(name = "setup reboot light")
@Composable
private fun OnboardingSetupRebootLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) {
        OnboardingContent(previewChecksState(rebootPending = true), {}, {})
    }
}

@Preview(name = "ready dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OnboardingReadyDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        OnboardingContent(
            OnboardingUiState(
                step = OnboardingStep.DONE,
                notificationsAllowed = true,
                simNames = "SIM 1 and SIM 2",
                recommendedPreset = true,
            ),
            {},
            {},
        )
    }
}
