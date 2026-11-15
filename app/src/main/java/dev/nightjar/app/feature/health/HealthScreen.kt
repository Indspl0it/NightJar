package dev.nightjar.app.feature.health

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.domain.protection.ModuleInstallState
import dev.nightjar.app.domain.protection.ProtectionReason
import dev.nightjar.app.domain.protection.ProtectionReport
import dev.nightjar.app.feature.settings.moduleFailureText
import dev.nightjar.app.feature.settings.restartBlockText
import dev.nightjar.app.ui.components.LocalNjBottomInset
import dev.nightjar.app.ui.components.NjBadge
import dev.nightjar.app.ui.components.NjBird
import dev.nightjar.app.ui.components.NjCard
import dev.nightjar.app.ui.components.NjCollapsedTitle
import dev.nightjar.app.ui.components.NjDialog
import dev.nightjar.app.ui.components.NjGroup
import dev.nightjar.app.ui.components.NjIcons
import dev.nightjar.app.ui.components.NjLargeTitle
import dev.nightjar.app.ui.components.NjListRow
import dev.nightjar.app.ui.components.NjNavRow
import dev.nightjar.app.ui.components.NjRowDivider
import dev.nightjar.app.ui.components.NjTonalButton
import dev.nightjar.app.ui.components.NjTopBarHeight
import dev.nightjar.app.ui.components.SkeletonRow
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.components.rememberNjTitleCollapsed
import dev.nightjar.app.ui.mode.LocalIsResearcher
import dev.nightjar.app.ui.theme.NjRoles
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.ProtectionState
import dev.nightjar.contract.Severity
import kotlinx.coroutines.launch

@Composable
fun HealthScreen(
    onBack: () -> Unit,
    onOpenBlindSpots: () -> Unit,
    onOpenLogs: () -> Unit,
    viewModel: HealthViewModel = hiltViewModel(),
    selfTestViewModel: SelfTestViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selfTest by selfTestViewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val copied = stringResource(R.string.snk_13)
    val restarted = stringResource(R.string.settings_restart_done)
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                HealthEvent.PhoneRestarted -> {
                    snackbar.showSnackbar(restarted)
                }

                is HealthEvent.RestartFailed -> {
                    snackbar.showSnackbar(resources.getString(R.string.settings_restart_failed, moduleFailureText(resources, event.reason)))
                }
            }
        }
    }
    HealthContent(
        state = state,
        researcher = LocalIsResearcher,
        snackbar = snackbar,
        selfTest = selfTest,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onCopyFingerprint = {
            copy(context, state.buildFull)
            scope.launch { snackbar.showSnackbar(copied) }
        },
        onOpenBlindSpots = onOpenBlindSpots,
        onOpenLogs = onOpenLogs,
        onOpenSelfTest = selfTestViewModel::open,
        onCancelSelfTest = selfTestViewModel::cancel,
        onRestartPhone = viewModel::requestRestart,
    )
    if (state.confirmRestart) {
        NjDialog(
            title = stringResource(R.string.dlg_14_title),
            text = stringResource(R.string.dlg_14_body),
            confirmText = stringResource(R.string.dlg_14_confirm),
            dismissText = stringResource(R.string.dlg_14_cancel),
            destructive = true,
            confirmEnabled = !state.restarting,
            onDismiss = viewModel::dismissRestart,
            onConfirm = viewModel::confirmRestart,
        )
    }
    SelfTestSheet(
        state = selfTest,
        onDismiss = selfTestViewModel::close,
        onToggle = selfTestViewModel::toggle,
        onSetAll = selfTestViewModel::setAllSelected,
        onStart = selfTestViewModel::start,
        onConfirm = selfTestViewModel::confirmStart,
        onDismissConfirm = selfTestViewModel::dismissConfirm,
        onCancel = selfTestViewModel::cancel,
        onRunAgain = selfTestViewModel::open,
        onCloseReport = selfTestViewModel::dismissReport,
        onDismissError = selfTestViewModel::dismissError,
    )
}

/**
 * Protection status: large title, status card, the checklist (one card per check, "How to fix" on each check
 * that is not OK), build and versions, the optional self-test, "Seen from the network" and the links. The list ends
 * above the floating bar ([LocalNjBottomInset]). The sections enter one after the other ([njEnter]).
 */
@Composable
fun HealthContent(
    state: HealthUiState,
    researcher: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCopyFingerprint: () -> Unit,
    onOpenBlindSpots: () -> Unit,
    onOpenLogs: () -> Unit,
    selfTest: SelfTestUiState = SelfTestUiState(),
    onOpenSelfTest: () -> Unit = {},
    onCancelSelfTest: () -> Unit = {},
    onRestartPhone: () -> Unit = {},
) {
    var showNeverInfo by remember { mutableStateOf(false) }
    var fixFor by remember { mutableStateOf<HealthCheckId?>(null) }
    val listState = rememberLazyListState()
    val collapsed by rememberNjTitleCollapsed(listState)
    val title = stringResource(R.string.screen_s09)
    val gutter = Modifier.padding(horizontal = NjSpacing.m)
    val checks = remember(state, researcher) { HealthChecklist.evaluate(state, researcher) }
    val refresh: @Composable RowScope.() -> Unit = {
        IconButton(onClick = onRefresh) {
            Icon(HealthIcons.Refresh, contentDescription = stringResource(R.string.health_refresh), tint = NjTheme.roles.tx)
        }
    }
    Box(Modifier.fillMaxSize().background(NjTheme.roles.bg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding =
                PaddingValues(
                    // The shell already pads and consumes the status bar inset (NightjarShell). `WindowInsets.statusBars`
                    // ignores that consumption and gave a double top gap.
                    top = NjTopBarHeight,
                    bottom = LocalNjBottomInset.current + NjSpacing.l,
                ),
            verticalArrangement = Arrangement.spacedBy(NjSpacing.s + 2.dp),
        ) {
            item(key = "title") { NjLargeTitle(title = title, modifier = Modifier.njEnter(0)) }
            if (state.loading) {
                item(key = "loading") { Column(gutter) { repeat(4) { SkeletonRow() } } }
            } else {
                if (state.report != null) {
                    item(key = "status") { StatusCard(state, HealthChecklist.issueCount(checks), gutter.njEnter(1)) }
                }
                if (state.phoneProblem) {
                    item(key = "phone") { PhoneRestartCard(state, onRestartPhone, gutter.njEnter(1)) }
                }
                item(key = "checks-header") {
                    StatusSectionTitle(stringResource(R.string.onb_step_2_title), gutter.njEnter(2))
                }
                items(checks, key = { "check-" + it.id.name }) { check ->
                    CheckCard(
                        check = check,
                        value = checkValue(check.id, state),
                        onFix = { fixFor = check.id },
                        modifier = gutter.njEnter(3 + check.id.ordinal),
                    )
                }
                item(key = "details-header") {
                    StatusSectionTitle(stringResource(R.string.health_x_details), gutter.njEnter(STAGGER_LAST))
                }
                item(key = "details") { DetailsGroup(state, onCopyFingerprint, gutter.njEnter(STAGGER_LAST)) }
                item(key = "selftest") {
                    SelfTestSection(
                        state = selfTest,
                        onOpen = onOpenSelfTest,
                        onCancel = onCancelSelfTest,
                        modifier = gutter.njEnter(STAGGER_LAST),
                    )
                }
                item(key = "coverage-header") {
                    StatusSectionTitle(stringResource(R.string.health_coverage), gutter.njEnter(STAGGER_LAST))
                }
                item(key = "coverage") {
                    NjGroup(modifier = gutter.njEnter(STAGGER_LAST)) {
                        state.coverage.forEachIndexed { index, row ->
                            if (index > 0) NjRowDivider()
                            NjListRow(
                                title = row.title,
                                trailingValue = row.lastSeen ?: stringResource(R.string.health_never),
                                onClick = if (row.lastSeen == null) ({ showNeverInfo = true }) else null,
                                showChevron = false,
                            )
                        }
                    }
                }
                item(key = "links-header") {
                    StatusSectionTitle(stringResource(R.string.health_links), gutter.njEnter(STAGGER_LAST))
                }
                item(key = "links") {
                    NjGroup(modifier = gutter.njEnter(STAGGER_LAST)) {
                        NjNavRow(
                            title = stringResource(R.string.health_blind_spots),
                            leadingIcon = NjIcons.Info,
                            onClick = onOpenBlindSpots,
                        )
                        if (researcher) {
                            NjRowDivider()
                            NjNavRow(
                                title = stringResource(R.string.health_logs),
                                leadingIcon = NjIcons.Tools,
                                onClick = onOpenLogs,
                            )
                        }
                    }
                }
            }
        }
        NjCollapsedTitle(
            title = title,
            collapsed = collapsed,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding(),
            onBack = onBack,
            actions = refresh,
        )
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LocalNjBottomInset.current),
        )
    }

    fixFor?.let { id ->
        FixDialog(
            id = id,
            state = state,
            onDismiss = { fixFor = null },
            onOpenLogs = {
                fixFor = null
                onOpenLogs()
            },
        )
    }

    if (showNeverInfo) {
        NjDialog(
            title = stringResource(R.string.bs_05_title),
            text = stringResource(R.string.msg_never),
            confirmText = stringResource(R.string.action_ok),
            dismissText = null,
            onDismiss = { showNeverInfo = false },
            onConfirm = { showNeverInfo = false },
        )
    }
}

/** The sections after the checklist share one stagger step: [dev.nightjar.app.ui.theme.NjMotion] counts 8 items at most. */
private const val STAGGER_LAST = 7

/** The accent colour of the hero severity: green when protected, amber when degraded, red when protection does not work. */
private fun heroColor(
    roles: NjRoles,
    severity: Severity,
): Color =
    when (severity) {
        Severity.INFO -> roles.ok
        Severity.NOTABLE -> roles.amb
        Severity.SUSPICIOUS -> roles.hi
        Severity.HIGH -> roles.hi
    }

/** Green when every check is OK. Otherwise the hero colour, and at least amber: an open check is never shown in green. */
private fun badgeColor(
    roles: NjRoles,
    issues: Int,
    severity: Severity,
): Color =
    when {
        issues == 0 -> roles.ok
        severity == Severity.INFO -> roles.amb
        else -> heroColor(roles, severity)
    }

/** The status card: a badge with the number of open checks, the hero title and line, and the bird. */
@Composable
private fun StatusCard(
    state: HealthUiState,
    issues: Int,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    val badge =
        if (issues == 0) {
            stringResource(R.string.health_x_badge_ok)
        } else {
            pluralStringResource(R.plurals.health_x_badge_issues, issues, issues)
        }
    NjCard(modifier = modifier, padding = 20.dp) {
        Box(Modifier.fillMaxWidth()) {
            NjBird(Modifier.width(150.dp).align(Alignment.TopEnd).alpha(0.5f))
            Column(verticalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
                NjBadge(
                    text = badge,
                    color = badgeColor(roles, issues, state.heroSeverity),
                    icon = if (issues == 0) NjIcons.Check else NjIcons.Warning,
                )
                Text(
                    text = state.heroTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    color = roles.tx,
                    modifier = Modifier.padding(top = NjSpacing.s).semantics { heading() },
                )
                if (state.heroBody.isNotBlank() && !state.heroBody.equals(state.heroTitle, ignoreCase = true)) {
                    Text(
                        text = state.heroBody,
                        style = MaterialTheme.typography.bodyLarge,
                        color = roles.mu,
                        modifier = Modifier.padding(end = NjSpacing.l),
                    )
                }
            }
        }
    }
}

/**
 * The phone process runs without the module. The user restarts it here, and only here; the button is off during
 * a call and in safe mode, and the card says why.
 */
@Composable
private fun PhoneRestartCard(
    state: HealthUiState,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    NjCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.watchdog_title),
            style = MaterialTheme.typography.titleMedium,
            color = roles.tx,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.health_x_phone_restart_body),
            style = MaterialTheme.typography.bodyMedium,
            color = roles.mu,
            modifier = Modifier.padding(top = NjSpacing.xs),
        )
        state.restartBlock?.let { block ->
            Text(
                text = stringResource(restartBlockText(block)),
                style = MaterialTheme.typography.bodyMedium,
                color = roles.tx,
                modifier = Modifier.padding(top = NjSpacing.s),
            )
        }
        NjTonalButton(
            text = stringResource(R.string.watchdog_restart),
            onClick = onRestart,
            enabled = state.restartBlock == null && !state.restarting,
            modifier = Modifier.fillMaxWidth().padding(top = NjSpacing.m),
        )
    }
}

/** One check: state icon, title, current value, state badge and, when the check is not OK, "How to fix". */
@Composable
private fun CheckCard(
    check: HealthCheck,
    value: String,
    onFix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    val tone = checkTone(roles, check.state)
    NjCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIconSquare(tone.icon, tone.color)
            Spacer(Modifier.width(NjSpacing.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(checkTitle(check.id), style = MaterialTheme.typography.titleMedium, color = roles.tx)
                Text(value, style = MaterialTheme.typography.bodyMedium, color = roles.mu)
            }
            Spacer(Modifier.width(NjSpacing.s))
            NjBadge(text = stringResource(tone.label), color = tone.color)
        }
        if (check.needsFix) {
            NjTonalButton(
                text = stringResource(R.string.onb_how_to_fix),
                onClick = onFix,
                modifier = Modifier.fillMaxWidth().padding(top = NjSpacing.m),
            )
        }
    }
}

/** Build (tap copies the full fingerprint) and versions: facts, not checks. */
@Composable
private fun DetailsGroup(
    state: HealthUiState,
    onCopyFingerprint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NjGroup(modifier = modifier) {
        // Label on top, value below: a long value wraps under its label and can never collide with it.
        NjListRow(
            title = stringResource(R.string.health_build),
            supporting = shortFingerprint(state.buildFull).ifBlank { stringResource(R.string.health_unknown) },
            onClick = onCopyFingerprint,
            showChevron = false,
        )
        NjRowDivider()
        NjListRow(
            title = stringResource(R.string.health_versions),
            supporting =
                stringResource(
                    R.string.health_app_version,
                    state.appVersion,
                    state.payloadVersion ?: stringResource(R.string.health_unknown),
                ),
        )
    }
}

/** The help for one check. The hooks check also offers the logs, the only fix action this screen has. */
@Composable
private fun FixDialog(
    id: HealthCheckId,
    state: HealthUiState,
    onDismiss: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val logs = HealthChecklist.fix(id) == HealthFix.OPEN_LOGS
    NjDialog(
        title = checkTitle(id),
        text = fixText(id, state),
        confirmText = stringResource(if (logs) R.string.health_logs else R.string.action_ok),
        dismissText = if (logs) stringResource(R.string.action_close) else null,
        onDismiss = onDismiss,
        onConfirm = if (logs) onOpenLogs else onDismiss,
    )
}

@Composable
private fun checkTitle(id: HealthCheckId): String =
    stringResource(
        when (id) {
            HealthCheckId.MODULE -> R.string.health_module
            HealthCheckId.MODULE_VERSION -> R.string.health_x_check_module_version
            HealthCheckId.ROOT -> R.string.health_root
            HealthCheckId.FRAMEWORK -> R.string.onb_framework
            HealthCheckId.RULES -> R.string.health_x_check_rules
            HealthCheckId.HOOKS -> R.string.health_hooks
            HealthCheckId.EVENTS -> R.string.health_x_check_events
            HealthCheckId.STORAGE -> R.string.health_x_check_storage
        },
    )

@Composable
private fun checkValue(
    id: HealthCheckId,
    state: HealthUiState,
): String =
    when (id) {
        HealthCheckId.MODULE -> {
            moduleLabel(state.moduleState)
        }

        HealthCheckId.MODULE_VERSION -> {
            stringResource(
                if (state.payloadNewer == true) R.string.health_x_module_version_newer else R.string.health_x_module_version_ok,
            )
        }

        HealthCheckId.ROOT -> {
            listOfNotNull(state.rootManager.takeIf { it.isNotBlank() }, state.rootVersion)
                .joinToString(" · ")
                .ifBlank { stringResource(R.string.health_unknown) }
        }

        HealthCheckId.FRAMEWORK -> {
            stringResource(if (state.framework) R.string.health_x_framework_found else R.string.health_module_not_installed)
        }

        HealthCheckId.RULES -> {
            rulesValue(state.rules)
        }

        HealthCheckId.HOOKS -> {
            pluralStringResource(R.plurals.health_x_hooks_value, state.hooksLive, state.hooksLive, state.hooksTotal)
        }

        HealthCheckId.EVENTS -> {
            if (state.lostEvents > 0) {
                pluralStringResource(R.plurals.msg_lost_01, state.lostEvents, state.lostEvents)
            } else {
                stringResource(R.string.health_x_events_ok)
            }
        }

        HealthCheckId.STORAGE -> {
            humanBytes(LocalContext.current, state.databaseBytes)
        }
    }

@Composable
private fun fixText(
    id: HealthCheckId,
    state: HealthUiState,
): String =
    when (id) {
        // No Xposed framework is a framework problem; a framework without a handshake needs the module turned on.
        HealthCheckId.MODULE -> {
            stringResource(
                if (state.moduleState == ModuleInstallState.NOT_INSTALLED) R.string.onb_no_framework else R.string.onb_module_missing,
            )
        }

        HealthCheckId.ROOT -> {
            stringResource(if (state.rooted == false) R.string.onb_root_denied else R.string.health_x_fix_root_unknown)
        }

        HealthCheckId.FRAMEWORK -> {
            stringResource(R.string.onb_no_framework)
        }

        HealthCheckId.MODULE_VERSION -> {
            stringResource(R.string.msg_upd_01)
        }

        HealthCheckId.RULES -> {
            rulesFixText(state.rules)
        }

        HealthCheckId.HOOKS -> {
            stringResource(R.string.health_x_fix_hooks)
        }

        HealthCheckId.EVENTS -> {
            stringResource(R.string.health_x_fix_events)
        }

        HealthCheckId.STORAGE -> {
            val size = humanBytes(LocalContext.current, state.databaseBytes)
            stringResource(R.string.health_db_size, size) + " " + stringResource(R.string.health_db_hint)
        }
    }

/** The most serious rules problem: an error, then old rules, then skipped rules. */
@Composable
private fun rulesValue(rules: RulesHealth?): String =
    when {
        rules?.error != null -> stringResource(R.string.health_x_rules_error, rules.error)
        rules?.behind == true -> stringResource(R.string.health_x_rules_behind)
        rules != null && rules.skipped > 0 -> pluralStringResource(R.plurals.health_x_rules_skipped, rules.skipped, rules.skipped)
        else -> stringResource(R.string.health_x_rules_ok)
    }

/** One help paragraph for each rules problem that applies. */
@Composable
private fun rulesFixText(rules: RulesHealth?): String =
    listOfNotNull(
        rules?.error?.let { stringResource(R.string.health_x_fix_rules_error) },
        stringResource(R.string.health_x_fix_rules_behind).takeIf { rules?.behind == true },
        stringResource(R.string.health_x_fix_rules_skipped).takeIf { rules != null && rules.skipped > 0 },
    ).joinToString("\n\n")

@Composable
private fun moduleLabel(state: ModuleInstallState): String =
    stringResource(
        when (state) {
            ModuleInstallState.NOT_INSTALLED -> R.string.health_module_not_installed
            ModuleInstallState.INSTALLED -> R.string.health_module_installed
            ModuleInstallState.UPDATE_AVAILABLE -> R.string.health_module_update
            ModuleInstallState.PENDING_REBOOT -> R.string.health_module_pending
            ModuleInstallState.DISABLED -> R.string.health_module_disabled
            ModuleInstallState.REMOVAL_PENDING -> R.string.health_module_removing
        },
    )

/** The size in the units and the number format of the user's locale ("12 MB"). */
private fun humanBytes(
    context: Context,
    bytes: Long,
): String = Formatter.formatShortFileSize(context, bytes)

private fun copy(
    context: Context,
    text: String,
) {
    if (text.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("NightJar", text))
}

// ------------------------------------------------------------------ previews

@Preview(name = "status light", heightDp = 1600)
@Composable
private fun HealthLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) {
        HealthContent(
            state = previewHealth(),
            researcher = true,
            snackbar = SnackbarHostState(),
            onBack = {},
            onRefresh = {},
            onCopyFingerprint = {},
            onOpenBlindSpots = {},
            onOpenLogs = {},
        )
    }
}

@Preview(name = "status dark", heightDp = 1600, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HealthDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        HealthContent(
            state = previewHealth(),
            researcher = false,
            snackbar = SnackbarHostState(),
            onBack = {},
            onRefresh = {},
            onCopyFingerprint = {},
            onOpenBlindSpots = {},
            onOpenLogs = {},
        )
    }
}

@Preview(name = "status problems dark", heightDp = 1600, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HealthProblemsDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        HealthContent(
            state =
                previewHealth().copy(
                    heroTitle = "Not active",
                    heroBody = "The module is not loaded.",
                    heroSeverity = Severity.SUSPICIOUS,
                    moduleState = ModuleInstallState.PENDING_REBOOT,
                    rooted = null,
                    payloadVersion = null,
                    hooksLive = 4,
                    lostEvents = 3,
                    rules = RulesHealth(behind = true),
                    payloadNewer = true,
                ),
            researcher = true,
            snackbar = SnackbarHostState(),
            onBack = {},
            onRefresh = {},
            onCopyFingerprint = {},
            onOpenBlindSpots = {},
            onOpenLogs = {},
        )
    }
}

private fun previewHealth(): HealthUiState =
    HealthUiState(
        loading = false,
        report = previewReport(),
        heroTitle = "Protected",
        heroBody = "NightJar monitors SIM 1 and SIM 2.",
        heroSeverity = Severity.INFO,
        moduleState = ModuleInstallState.INSTALLED,
        rootManager = "Magisk",
        rootVersion = "28.1",
        rooted = true,
        framework = true,
        buildFull = "google/akita/akita:17/CP3A/261005:user/release-keys",
        appVersion = "0.3.2",
        payloadVersion = "0.3.1",
        hooksLive = 6,
        hooksTotal = 6,
        coverage = listOf(CoverageRow(ClassId.SILENT, "Silent ping", null)),
    )

private fun previewReport(): ProtectionReport =
    ProtectionReport(
        state = ProtectionState.PROTECTED,
        reason = ProtectionReason.NONE,
        blockingPaused = false,
        firewallAvailable = true,
        firewallEnabled = true,
    )
