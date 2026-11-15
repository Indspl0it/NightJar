package dev.nightjar.app.feature.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.DigestMode
import dev.nightjar.app.core.settings.LockTimeout
import dev.nightjar.app.core.settings.Retention
import dev.nightjar.app.core.settings.Settings
import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.notify.ChannelId
import dev.nightjar.app.domain.protection.ModuleFailure
import dev.nightjar.app.feature.diagnostics.SendDiagnosticsDialog
import dev.nightjar.app.feature.known.KnownTab
import dev.nightjar.app.feature.protection.RestartBlock
import dev.nightjar.app.feature.rules.classLabelRes
import dev.nightjar.app.ui.components.NjCaption
import dev.nightjar.app.ui.components.NjChoiceSheet
import dev.nightjar.app.ui.components.NjDialog
import dev.nightjar.app.ui.components.NjGroup
import dev.nightjar.app.ui.components.NjListRow
import dev.nightjar.app.ui.components.NjOption
import dev.nightjar.app.ui.components.NjRowDivider
import dev.nightjar.app.ui.components.NjSectionHeader
import dev.nightjar.app.ui.components.NjSwitchRow
import dev.nightjar.app.ui.components.NjTextField
import dev.nightjar.app.ui.components.NjVerdictChip
import dev.nightjar.app.ui.components.familyLabel
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.mode.LocalIsResearcher
import dev.nightjar.app.ui.nav.NavActions
import dev.nightjar.app.ui.nav.Route
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.contract.Severity
import android.provider.Settings as AndroidSettings

/** A sheet or dialog built at click time with its concrete type; `dismiss` closes the host. */
internal class DialogSpec(
    val content: @Composable (dismiss: () -> Unit) -> Unit,
)

/** Everything a settings page needs, so page functions take one parameter instead of seven. */
internal class SettingsEnv(
    val settings: Settings,
    val viewModel: SettingsViewModel,
    val actions: NavActions,
    val researcher: Boolean,
    val navigate: (SettingsPage) -> Unit,
    val show: (DialogSpec) -> Unit,
    val onOpenChannel: (ChannelId) -> Unit,
    val onSendDiagnostics: () -> Unit,
)

/** Opens a bottom-sheet picker for [options]; the sheet closes after a choice. */
internal fun <T> SettingsEnv.pick(
    @StringRes title: Int,
    options: List<NjOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    show(
        DialogSpec { dismiss ->
            NjChoiceSheet(
                title = stringResource(title),
                options = options,
                selected = selected,
                onSelect = {
                    onSelect(it)
                    dismiss()
                },
                onDismiss = dismiss,
            )
        },
    )
}

/** Opens a small number-entry dialog constrained to [range]. */
private fun SettingsEnv.askNumber(
    @StringRes title: Int,
    initial: Int,
    range: IntRange,
    onConfirm: (Int) -> Unit,
) {
    show(
        DialogSpec { dismiss ->
            NumberDialog(
                title = stringResource(title),
                initial = initial,
                range = range,
                onConfirm = {
                    onConfirm(it)
                    dismiss()
                },
                onDismiss = dismiss,
            )
        },
    )
}

/**
 * Shows one snackbar per [SettingsEvent] from [viewModel]. A safe-mode change offers an undo; the undo itself does not
 * offer another one.
 */
@Composable
private fun SettingsEventEffects(
    viewModel: SettingsViewModel,
    snackbar: SnackbarHostState,
) {
    val resources = LocalResources.current
    val dataDeleted = stringResource(R.string.settings_delete_all_done)
    val deleteFailed = stringResource(R.string.settings_delete_all_failed)
    val restarted = stringResource(R.string.settings_restart_done)
    val saveFailed = stringResource(R.string.settings_save_failed)
    val safeOn = stringResource(R.string.snk_11)
    val safeOff = stringResource(R.string.snk_11_off)
    val undo = stringResource(R.string.action_undo)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                SettingsEvent.DataDeleted -> {
                    snackbar.showSnackbar(dataDeleted)
                }

                SettingsEvent.DeleteAllFailed -> {
                    snackbar.showSnackbar(deleteFailed)
                }

                is SettingsEvent.RetentionDeleted -> {
                    if (event.count > 0) {
                        snackbar.showSnackbar(resources.getQuantityString(R.plurals.snk_04, event.count, event.count))
                    }
                }

                SettingsEvent.PhoneRestarted -> {
                    snackbar.showSnackbar(restarted)
                }

                is SettingsEvent.RestartFailed -> {
                    snackbar.showSnackbar(resources.getString(R.string.settings_restart_failed, moduleFailureText(resources, event.reason)))
                }

                is SettingsEvent.RestartBlocked -> {
                    snackbar.showSnackbar(resources.getString(restartBlockText(event.block)))
                }

                is SettingsEvent.SafeModeChanged -> {
                    val message = if (event.enabled) safeOn else safeOff
                    if (event.undoable) {
                        val result = snackbar.showSnackbar(message, undo, duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) viewModel.setSafeMode(!event.enabled, undoable = false)
                    } else {
                        snackbar.showSnackbar(message)
                    }
                }

                SettingsEvent.SaveFailed -> {
                    snackbar.showSnackbar(saveFailed)
                }
            }
        }
    }
}

/**
 * Settings (-01, 03): a home with a search field and the groups Appearance, Notifications,
 * Protection, Data and storage, Diagnostics and About. Some rows open a sub-screen. The search finds rows on the
 * sub-screens too ([SettingsRow]).
 */
@Composable
fun SettingsScreen(actions: NavActions) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    var query by rememberSaveable { mutableStateOf("") }
    var openDialog by remember { mutableStateOf<DialogSpec?>(null) }
    var showDiagnostics by remember { mutableStateOf(false) }

    SettingsEventEffects(viewModel, snackbar)

    BackHandler(enabled = page != SettingsPage.HOME) { page = SettingsPage.HOME }

    val env =
        SettingsEnv(
            settings = state.settings,
            viewModel = viewModel,
            actions = actions,
            researcher = LocalIsResearcher,
            navigate = { page = it },
            show = { openDialog = it },
            onOpenChannel = { channel ->
                val intent =
                    Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(AndroidSettings.EXTRA_CHANNEL_ID, channel.channelId)
                runCatching { context.startActivity(intent) }
                Unit
            },
            onSendDiagnostics = { showDiagnostics = true },
        )

    val visible = visibleSettingsRows(env.researcher, state.settings.appLock)
    val searching = page == SettingsPage.HOME && settingsSearchTerms(query).isNotEmpty()
    val results = if (searching) searchSettingsRows(visible, query) else emptyList()

    // A new list for each page, so a sub-screen opens at its top and its blocks enter again.
    key(page) {
        SettingsFrame(
            title = pageTitle(page),
            snackbar = snackbar,
            onBack = if (page == SettingsPage.HOME) null else ({ page = SettingsPage.HOME }),
        ) {
            if (page == SettingsPage.HOME) {
                settingsHomeItems(
                    query = query,
                    onQuery = { query = it },
                    visible = visible,
                    results = results,
                    env = env,
                )
            } else {
                item(key = "page", contentType = "page") {
                    Column(
                        modifier = Modifier.padding(horizontal = NjSpacing.m).njEnter(1),
                        verticalArrangement = Arrangement.spacedBy(NjSpacing.m),
                    ) {
                        SettingsPageContent(page, env)
                    }
                }
            }
        }
    }

    openDialog?.let { spec -> spec.content { openDialog = null } }

    if (showDiagnostics) {
        SendDiagnosticsDialog(onDismiss = { showDiagnostics = false })
    }

    state.dialog?.let { dialog ->
        SettingsDialogHost(
            dialog = dialog,
            state = state,
            viewModel = viewModel,
            onOpenSecuritySettings = {
                runCatching { context.startActivity(Intent(AndroidSettings.ACTION_SECURITY_SETTINGS)) }
                Unit
            },
        )
    }
}

@Composable
private fun ColumnScope.SettingsPageContent(
    page: SettingsPage,
    env: SettingsEnv,
) {
    when (page) {
        // The home is a list of groups, not one block (SettingsHome.kt).
        SettingsPage.HOME -> Unit

        SettingsPage.NOTIFICATIONS -> NotificationsPage(env)

        SettingsPage.DETECTION -> DetectionPage(env)

        SettingsPage.SEVERITY -> SeverityPage(env)

        SettingsPage.CELL -> CellPage(env)

        SettingsPage.OUTGOING -> OutgoingPage(env)

        SettingsPage.PRIVACY -> PrivacyPage(env)

        SettingsPage.APPEARANCE -> AppearancePage(env)
    }
}

@Composable
internal fun pageTitle(page: SettingsPage): String =
    stringResource(
        when (page) {
            SettingsPage.HOME -> R.string.screen_s10
            SettingsPage.NOTIFICATIONS -> R.string.nj_set_row_notify_more
            SettingsPage.DETECTION -> R.string.settings_cat_detection
            SettingsPage.SEVERITY -> R.string.settings_severity_per_type
            SettingsPage.CELL -> R.string.settings_group_cb
            SettingsPage.OUTGOING -> R.string.settings_cat_outgoing
            SettingsPage.PRIVACY -> R.string.settings_cat_privacy
            SettingsPage.APPEARANCE -> R.string.settings_group_appearance
        },
    )

// ------------------------------------------------------------------ pages

/** Digest, notification limit and system channels. The four notification switches are on the home. */
@Composable
private fun ColumnScope.NotificationsPage(env: SettingsEnv) {
    val s = env.settings
    val vm = env.viewModel
    val minuteSuffix = stringResource(R.string.settings_minutes_suffix)
    val digestOptions = DigestMode.entries.map { NjOption(it, digestLabel(it)) }
    val rateOptions = Settings.RATE_LIMIT_CHOICES.map { NjOption(it, "$it $minuteSuffix") }
    NjSectionHeader(stringResource(R.string.settings_notify_frequency))
    NjGroup {
        NjListRow(
            title = stringResource(R.string.settings_notify_digest),
            trailingValue = digestLabel(s.notifyDigest),
            onClick = {
                env.pick(
                    title = R.string.settings_notify_digest,
                    options = digestOptions,
                    selected = s.notifyDigest,
                    onSelect = vm::setDigest,
                )
            },
        )
        NjRowDivider()
        NjListRow(
            title = stringResource(R.string.settings_notify_ratelimitmin),
            trailingValue = stringResource(R.string.settings_rate_limit, s.notifyRateLimitMin),
            onClick = {
                env.pick(
                    title = R.string.settings_notify_ratelimitmin,
                    options = rateOptions,
                    selected = s.notifyRateLimitMin,
                    onSelect = vm::setRateLimit,
                )
            },
        )
    }
    NjSectionHeader(stringResource(R.string.settings_notify_channels))
    NjGroup {
        val channels =
            listOf(
                ChannelId.SUSPICIOUS to R.string.settings_notify_suspicious,
                ChannelId.HIDDEN to R.string.settings_notify_notable,
                ChannelId.FIREWALL to R.string.settings_notify_firewall,
                ChannelId.BROADCAST to R.string.settings_notify_cb,
                ChannelId.DIGEST to R.string.settings_notify_digest,
            )
        channels.forEachIndexed { index, (channel, label) ->
            if (index > 0) NjRowDivider()
            NjListRow(
                title = stringResource(label),
                supporting = stringResource(R.string.settings_open_channel),
                onClick = { env.onOpenChannel(channel) },
            )
        }
    }
}

@Composable
private fun ColumnScope.DetectionPage(env: SettingsEnv) {
    val s = env.settings
    val vm = env.viewModel
    val minuteSuffix = stringResource(R.string.settings_minutes_suffix)
    val windowOptions = Settings.SILENT_WINDOW_CHOICES.map { NjOption(it, "$it $minuteSuffix") }
    NjSectionHeader(stringResource(R.string.settings_group_detectors))
    NjGroup {
        NjListRow(
            title = stringResource(R.string.settings_detect_silent_count),
            trailingValue = s.detectSilentCount.toString(),
            onClick = {
                env.askNumber(
                    title = R.string.settings_detect_silent_count,
                    initial = s.detectSilentCount,
                    range = Settings.SILENT_COUNT_RANGE,
                    onConfirm = vm::setSilentCount,
                )
            },
        )
        NjRowDivider()
        NjListRow(
            title = stringResource(R.string.settings_detect_silent_windowmin),
            trailingValue = stringResource(R.string.settings_minutes, s.detectSilentWindowMin),
            onClick = {
                env.pick(
                    title = R.string.settings_detect_silent_windowmin,
                    options = windowOptions,
                    selected = s.detectSilentWindowMin,
                    onSelect = vm::setSilentWindow,
                )
            },
        )
        NjRowDivider()
        NjSwitchRow(stringResource(R.string.settings_detect_silent_newsender), s.detectSilentNewSender, vm::setSilentNewSender)
        NjRowDivider()
        NjSwitchRow(stringResource(R.string.settings_detect_silent_roaming), s.detectSilentRoaming, vm::setSilentRoaming)
        NjRowDivider()
        NjListRow(
            title = stringResource(R.string.settings_detect_supl_gracemin),
            trailingValue = stringResource(R.string.settings_minutes, s.detectSuplGraceMin),
            onClick = {
                env.askNumber(
                    title = R.string.settings_detect_supl_gracemin,
                    initial = s.detectSuplGraceMin,
                    range = Settings.SUPL_GRACE_RANGE,
                    onConfirm = vm::setSuplGrace,
                )
            },
        )
    }
    NjGroup {
        NjListRow(
            title = stringResource(R.string.settings_known_senders),
            onClick = { env.actions.open(Route.KnownSenders()) },
        )
    }
}

@Composable
private fun ColumnScope.SeverityPage(env: SettingsEnv) {
    val overrides = env.settings.severityOverrides
    NjCaption(stringResource(R.string.settings_severity_intro))
    val byFamily = ClassRegistry.all().groupBy { it.family }
    byFamily.forEach { (family, profiles) ->
        NjSectionHeader(familyLabel(family))
        NjGroup {
            profiles.forEachIndexed { index, profile ->
                if (index > 0) NjRowDivider()
                val override = overrides[profile.classId]
                NjListRow(
                    title = stringResource(classLabelRes(profile.classId)),
                    trailingValue = if (override == null) stringResource(R.string.settings_severity_default) else null,
                    trailing =
                        if (override != null) {
                            { NjVerdictChip(severity = override) }
                        } else {
                            null
                        },
                    onClick = { env.viewModel.requestSeverity(profile.classId) },
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.CellPage(env: SettingsEnv) {
    val s = env.settings
    val vm = env.viewModel
    NjGroup {
        NjSwitchRow(stringResource(R.string.settings_cb_log), s.cbLog, vm::setCbLog)
        if (env.researcher) {
            NjRowDivider()
            NjSwitchRow(stringResource(R.string.settings_cb_allchannels), s.cbAllChannels, vm::setCbAllChannels)
        }
    }
}

@Composable
private fun ColumnScope.OutgoingPage(env: SettingsEnv) {
    val s = env.settings
    val vm = env.viewModel
    NjGroup {
        NjSwitchRow(stringResource(R.string.settings_outgoing_enabled), s.outgoingEnabled, vm::setOutgoingEnabled)
        NjRowDivider()
        NjSwitchRow(stringResource(R.string.settings_outgoing_logvisible), s.outgoingLogVisible, vm::setOutgoingLogVisible)
    }
    NjGroup {
        NjListRow(
            title = stringResource(R.string.settings_trusted_apps),
            onClick = { env.actions.open(Route.KnownSenders(tab = KnownTab.APPS.name)) },
        )
        NjRowDivider()
        NjListRow(
            title = stringResource(R.string.settings_sending_apps),
            onClick = { env.actions.open(Route.SendingApps) },
        )
    }
}

@Composable
private fun ColumnScope.PrivacyPage(env: SettingsEnv) {
    val s = env.settings
    val vm = env.viewModel
    val lockOptions =
        listOf(
            NjOption(LockTimeout.IMMEDIATELY, stringResource(R.string.settings_lock_immediately)),
            NjOption(LockTimeout.ONE_MIN, stringResource(R.string.settings_minutes, 1)),
            NjOption(LockTimeout.FIVE_MIN, stringResource(R.string.settings_minutes, 5)),
            NjOption(LockTimeout.FIFTEEN_MIN, stringResource(R.string.settings_minutes, 15)),
        )
    NjGroup {
        NjSwitchRow(stringResource(R.string.settings_privacy_applock), s.appLock, vm::setAppLock)
        if (s.appLock) {
            NjRowDivider()
            NjListRow(
                title = stringResource(R.string.settings_privacy_locktimeout),
                trailingValue = lockTimeoutLabel(s.lockTimeout),
                onClick = {
                    env.pick(
                        title = R.string.settings_privacy_locktimeout,
                        options = lockOptions,
                        selected = s.lockTimeout,
                        onSelect = vm::setLockTimeout,
                    )
                },
            )
        }
    }
    NjGroup {
        NjSwitchRow(stringResource(R.string.settings_privacy_masknumbers), s.maskNumbers, vm::setMaskNumbers)
        NjRowDivider()
        NjSwitchRow(stringResource(R.string.settings_privacy_hideinnotifications), s.hideInNotifications, vm::setHideInNotifications)
        NjRowDivider()
        NjSwitchRow(stringResource(R.string.settings_privacy_blockscreenshots), s.blockScreenshots, vm::setBlockScreenshots)
    }
}

@Composable
private fun ColumnScope.AppearancePage(env: SettingsEnv) {
    val s = env.settings
    val vm = env.viewModel
    AppearanceContent(
        settings = s,
        colour =
            AppearanceColourActions(
                onTheme = vm::setTheme,
                onAccent = vm::setAccent,
                onDynamicColor = vm::setDynamicColor,
                onTrueBlack = vm::setTrueBlack,
            ),
        motion =
            AppearanceMotionActions(
                onFloatingBar = vm::setFloatingBar,
                onListAnimations = vm::setListAnimations,
                onListBounce = vm::setListBounce,
                onReduceMotion = vm::setReduceMotion,
                onHaptics = vm::setHaptics,
            ),
    )
    NjSectionHeader(stringResource(R.string.group_messages))
    NjGroup {
        NjSwitchRow(stringResource(R.string.settings_ui_autoreview), s.autoReview, vm::setAutoReview)
    }
}

// ------------------------------------------------------------------ labels

@Composable
private fun digestLabel(mode: DigestMode): String =
    stringResource(
        when (mode) {
            DigestMode.OFF -> R.string.settings_digest_off
            DigestMode.WEEKLY -> R.string.settings_digest_weekly
        },
    )

@Composable
private fun lockTimeoutLabel(timeout: LockTimeout): String =
    when (timeout) {
        LockTimeout.IMMEDIATELY -> stringResource(R.string.settings_lock_immediately)
        else -> stringResource(R.string.settings_minutes, (timeout.millis / 60_000L).toInt())
    }

@Composable
internal fun retentionLabel(retention: Retention): String =
    stringResource(
        when (retention) {
            Retention.DAYS_30 -> R.string.settings_retention_30
            Retention.DAYS_90 -> R.string.settings_retention_90
            Retention.DAYS_365 -> R.string.settings_retention_365
            Retention.FOREVER -> R.string.settings_retention_forever
        },
    )

internal fun moduleFailureText(
    resources: android.content.res.Resources,
    failure: ModuleFailure,
): String =
    resources.getString(
        when (failure) {
            ModuleFailure.ROOT_DENIED -> R.string.module_failure_root_denied
            ModuleFailure.CLI_FAILED -> R.string.module_failure_cli_failed
            ModuleFailure.CHECKSUM_MISMATCH -> R.string.module_failure_checksum
            ModuleFailure.FILE_UNREADABLE -> R.string.module_failure_unreadable
        },
    )

/** Why the phone service restart is off now. Settings and Health use the same text. */
@StringRes
internal fun restartBlockText(block: RestartBlock): Int =
    when (block) {
        RestartBlock.ACTIVE_CALL -> R.string.watchdog_blocked_call
        RestartBlock.SAFE_MODE -> R.string.watchdog_blocked_safe_mode
    }

// ------------------------------------------------------------------ dialogs

@Composable
private fun SettingsDialogHost(
    dialog: SettingsDialog,
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenSecuritySettings: () -> Unit,
) {
    when (dialog) {
        SettingsDialog.DeleteAll -> {
            var typed by remember { mutableStateOf("") }
            NjDialog(
                title = stringResource(R.string.dlg_10_title),
                text = stringResource(R.string.settings_delete_all_body),
                confirmText = stringResource(R.string.settings_delete_all_confirm),
                dismissText = stringResource(R.string.dlg_10_cancel),
                destructive = true,
                confirmEnabled = !state.busy,
                onDismiss = viewModel::dismissDialog,
                onConfirm = { viewModel.deleteAll(typed) },
            ) {
                NjTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = stringResource(R.string.settings_delete_all_hint),
                    isError = state.deleteAllError,
                    supporting = if (state.deleteAllError) stringResource(R.string.settings_delete_all_typing_error) else null,
                )
            }
        }

        is SettingsDialog.RetentionChange -> {
            NjDialog(
                title = pluralStringResource(R.plurals.dlg_16_title, dialog.count, dialog.count),
                text = stringResource(R.string.settings_retention_confirm, retentionLabel(dialog.target)),
                confirmText = stringResource(R.string.dlg_16_confirm),
                dismissText = stringResource(R.string.dlg_16_cancel),
                destructive = true,
                confirmEnabled = !state.busy,
                onDismiss = viewModel::dismissDialog,
                onConfirm = viewModel::confirmDialog,
            )
        }

        SettingsDialog.AppLockNoCredential -> {
            NjDialog(
                title = stringResource(R.string.dlg_23_title),
                text = stringResource(R.string.dlg_23_body),
                confirmText = stringResource(R.string.dlg_23_confirm),
                dismissText = stringResource(R.string.dlg_23_cancel),
                onDismiss = viewModel::dismissDialog,
                onConfirm = {
                    viewModel.dismissDialog()
                    onOpenSecuritySettings()
                },
            )
        }

        // Information only: the user turns off the module in Vector or LSPosed. OK closes it.
        SettingsDialog.RemoveModule -> {
            NjDialog(
                title = stringResource(R.string.dlg_29_title),
                text = stringResource(R.string.dlg_29_body),
                confirmText = stringResource(R.string.dlg_29_confirm),
                dismissText = null,
                onDismiss = viewModel::dismissDialog,
                onConfirm = viewModel::confirmDialog,
            )
        }

        SettingsDialog.RestartPhone -> {
            NjDialog(
                title = stringResource(R.string.dlg_14_title),
                text = stringResource(R.string.dlg_14_body),
                confirmText = stringResource(R.string.dlg_14_confirm),
                dismissText = stringResource(R.string.dlg_14_cancel),
                destructive = true,
                confirmEnabled = !state.busy,
                onDismiss = viewModel::dismissDialog,
                onConfirm = viewModel::confirmDialog,
            )
        }

        SettingsDialog.ResearcherIntro -> {
            NjDialog(
                title = stringResource(R.string.dlg_15_title),
                text = stringResource(R.string.dlg_15_body),
                confirmText = stringResource(R.string.dlg_15_confirm),
                dismissText = null,
                onDismiss = viewModel::dismissDialog,
                onConfirm = viewModel::confirmDialog,
            )
        }

        is SettingsDialog.Severity -> {
            NjChoiceSheet<Severity?>(
                title = stringResource(R.string.settings_severity_title, stringResource(classLabelRes(dialog.classId))),
                options =
                    listOf(
                        NjOption(null, stringResource(R.string.settings_severity_default)),
                        NjOption(Severity.INFO, stringResource(R.string.severity_info)),
                        NjOption(Severity.NOTABLE, stringResource(R.string.severity_notable)),
                        NjOption(Severity.SUSPICIOUS, stringResource(R.string.severity_suspicious)),
                        NjOption(Severity.HIGH, stringResource(R.string.severity_high)),
                    ),
                selected = state.settings.severityOverrides[dialog.classId],
                onSelect = { viewModel.setSeverity(dialog.classId, it) },
                onDismiss = viewModel::dismissDialog,
            )
        }
    }
}

@Composable
private fun NumberDialog(
    title: String,
    initial: Int,
    range: IntRange,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in range
    NjDialog(
        title = title,
        confirmText = stringResource(R.string.action_done),
        confirmEnabled = valid,
        onDismiss = onDismiss,
        onConfirm = {
            val value = parsed
            if (value != null) onConfirm(value)
        },
    ) {
        NjTextField(
            value = text,
            onValueChange = { input -> text = input.filter { it.isDigit() }.take(3) },
            label = title,
            isError = !valid,
            supporting = stringResource(R.string.settings_number_range, range.first, range.last),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}
