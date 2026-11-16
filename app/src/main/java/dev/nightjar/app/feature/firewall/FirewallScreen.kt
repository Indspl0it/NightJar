package dev.nightjar.app.feature.firewall

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.domain.ClassFamily
import dev.nightjar.app.feature.rules.classLabelRes
import dev.nightjar.app.feature.rules.showLatestSnackbar
import dev.nightjar.app.ui.components.LocalNjBottomInset
import dev.nightjar.app.ui.components.NjAction
import dev.nightjar.app.ui.components.NjActionSegmented
import dev.nightjar.app.ui.components.NjBannerKind
import dev.nightjar.app.ui.components.NjChoiceSheet
import dev.nightjar.app.ui.components.NjCollapsedTitle
import dev.nightjar.app.ui.components.NjDialog
import dev.nightjar.app.ui.components.NjGroup
import dev.nightjar.app.ui.components.NjIcons
import dev.nightjar.app.ui.components.NjInfoBanner
import dev.nightjar.app.ui.components.NjLargeTitle
import dev.nightjar.app.ui.components.NjNavRow
import dev.nightjar.app.ui.components.NjOption
import dev.nightjar.app.ui.components.NjRowDivider
import dev.nightjar.app.ui.components.SkeletonRow
import dev.nightjar.app.ui.components.familyLabel
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.components.rememberNjTitleCollapsed
import dev.nightjar.app.ui.components.typeIconFor
import dev.nightjar.app.ui.nav.NavActions
import dev.nightjar.app.ui.nav.Route
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme
import dev.nightjar.contract.ClassId
import dev.nightjar.rules.Preset
import dev.nightjar.rules.Presets

/**
 * Protection (formerly Firewall): large title, the Blocking card with the preset, one group with one compact
 * row for each type, and the rows for Custom rules and Known senders.
 */
@Composable
fun FirewallScreen(actions: NavActions) {
    val viewModel: FirewallViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.onShown() }
    val snackbar = remember { SnackbarHostState() }

    val scope = rememberCoroutineScope()
    val texts by rememberUpdatedState(
        FirewallTexts(
            saved = stringResource(R.string.settings_saved),
            failed = stringResource(R.string.change_failed),
            notSent = stringResource(R.string.firewall_not_sent),
        ),
    )

    // Each event replaces the snackbar on screen, so a new result never waits behind an old one.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is FirewallEvent.ClassActionChanged, is FirewallEvent.PresetChanged -> {
                    scope.showLatestSnackbar(snackbar, texts.saved)
                }

                FirewallEvent.Failed -> {
                    scope.showLatestSnackbar(snackbar, texts.failed)
                }

                FirewallEvent.BlockingNotSent -> {
                    scope.showLatestSnackbar(snackbar, texts.notSent)
                }
            }
        }
    }

    ProtectionContent(
        state = state,
        snackbar = snackbar,
        onBlocking = viewModel::requestBlocking,
        onSelect = { classId, tapped ->
            val row = state.rows.firstOrNull { it.classId == classId }
            val verdict = row?.let { verdictForTap(it, tapped) }
            if (verdict != null) viewModel.requestClassAction(classId, verdict)
        },
        onNotify = { classId, notify ->
            val row = state.rows.firstOrNull { it.classId == classId }
            val verdict = row?.let { verdictForNotify(it, notify) }
            if (verdict != null) viewModel.requestClassAction(classId, verdict)
        },
        onPreset = viewModel::requestPreset,
        onEmergencyInfo = viewModel::showEmergencyInfo,
        onRules = { actions.open(Route.CustomRules) },
        onSenders = { actions.open(Route.KnownSenders()) },
        onDialogConfirm = viewModel::confirmDialog,
        onDialogDismiss = viewModel::dismissDialog,
    )
}

/** The snackbar texts, read in composition and used by the event collector. */
private data class FirewallTexts(
    val saved: String,
    val failed: String,
    val notSent: String,
)

@Composable
private fun ProtectionContent(
    state: FirewallUiState,
    snackbar: SnackbarHostState,
    onBlocking: (Boolean) -> Unit,
    onSelect: (ClassId, NjAction) -> Unit,
    onNotify: (ClassId, Boolean) -> Unit,
    onPreset: (PresetId) -> Unit,
    onEmergencyInfo: () -> Unit,
    onRules: () -> Unit,
    onSenders: () -> Unit,
    onDialogConfirm: () -> Unit,
    onDialogDismiss: () -> Unit,
) {
    var presetSheet by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val collapsed by rememberNjTitleCollapsed(listState)
    val gutter = Modifier.padding(horizontal = NjSpacing.m)
    val reasons = state.reasons
    val cards = remember(state.rows) { state.rows.map { it.toCard() } }
    val controlsOn = !state.readOnly && !state.saving
    val title = stringResource(R.string.prot_x_title)
    Box(Modifier.fillMaxSize().background(NjTheme.roles.bg)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding =
                PaddingValues(
                    // The shell already pads and consumes the status bar inset (NightjarShell). `WindowInsets.statusBars`
                    // ignores that consumption and gave a double top gap.
                    top = NjSpacing.s,
                    bottom = LocalNjBottomInset.current + NjSpacing.l,
                ),
            verticalArrangement = Arrangement.spacedBy(NjSpacing.s + 2.dp),
        ) {
            item(key = "title") {
                NjLargeTitle(title = title, modifier = Modifier.njEnter(0))
            }
            item(key = "intro") {
                Text(
                    text = stringResource(R.string.prot_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NjTheme.roles.mu,
                    modifier = gutter.njEnter(1),
                )
            }
            if (!state.loading && reasons.isNotEmpty()) {
                item(key = "banner") {
                    NjInfoBanner(
                        text = reasonText(reasons),
                        kind = if (FirewallReason.HOOKS_FAILED in reasons) NjBannerKind.ERROR else NjBannerKind.WARN,
                        modifier = gutter,
                    )
                }
            }
            item(key = "master") {
                BlockingCard(
                    blockingOn = state.blockingOn,
                    preset = state.preset,
                    enabled = controlsOn,
                    onBlocking = onBlocking,
                    onPreset = { presetSheet = true },
                    modifier = gutter.njEnter(2),
                )
            }
            if (state.loading) {
                item(key = "loading") { Column(gutter) { repeat(4) { SkeletonRow() } } }
            } else {
                item(key = "types") {
                    NjGroup(modifier = gutter.njEnter(3)) {
                        cards.forEach { card ->
                            TypeRow(
                                card = card,
                                enabled = controlsOn,
                                onSelect = { onSelect(card.classId, it) },
                                onNotify = { onNotify(card.classId, it) },
                            )
                            NjRowDivider()
                        }
                        // The row only opens information, so it stays enabled when the screen is read-only.
                        EmergencyRow(onInfo = onEmergencyInfo)
                    }
                }
            }
            item(key = "links") {
                NjGroup(modifier = gutter.njEnter(4)) {
                    NjNavRow(
                        title = stringResource(R.string.prot_custom_rules),
                        leadingIcon = NjIcons.Tune,
                        value = state.userRuleCount.toString(),
                        onClick = onRules,
                    )
                    NjRowDivider()
                    NjNavRow(
                        title = stringResource(R.string.prot_known_senders),
                        leadingIcon = NjIcons.Bell,
                        value = state.knownSenderCount.toString(),
                        onClick = onSenders,
                    )
                }
            }
        }
        NjCollapsedTitle(
            title = title,
            collapsed = collapsed,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding(),
        )
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LocalNjBottomInset.current),
        )
    }

    if (presetSheet) {
        PresetSheet(
            preset = state.preset,
            onSelect = { preset ->
                presetSheet = false
                onPreset(preset)
            },
            onDismiss = { presetSheet = false },
        )
    }

    state.dialog?.let { dialog ->
        FirewallDialogHost(dialog = dialog, onConfirm = onDialogConfirm, onDismiss = onDialogDismiss)
    }
}

/**
 * The Blocking card: the shield, "Blocking", the active preset as the supporting line, and the
 * Blocking switch. A tap on the row opens the preset choice ([PresetSheet]). The row is not a button while the
 * screen is read-only or saving, because the view model then ignores a choice. Safe mode is a separate switch in
 * Settings. While it is on, the switch keeps its own state and the banner says that safe mode paused blocking.
 */
@Composable
private fun BlockingCard(
    blockingOn: Boolean,
    preset: PresetId,
    enabled: Boolean,
    onBlocking: (Boolean) -> Unit,
    onPreset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    val blocking = stringResource(R.string.prot_blocking)
    NjGroup(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, role = Role.Button, onClick = onPreset)
                    .heightIn(min = 56.dp)
                    .padding(horizontal = NjSpacing.m, vertical = NjSpacing.s + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                NjIcons.Shield,
                contentDescription = null,
                tint = if (blockingOn) roles.ok else roles.mu,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(NjSpacing.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(blocking, style = MaterialTheme.typography.bodyLarge, color = roles.tx)
                Text(
                    stringResource(R.string.preset_label, stringResource(presetNameRes(preset))),
                    style = MaterialTheme.typography.bodyMedium,
                    color = roles.mu,
                )
            }
            Spacer(Modifier.width(NjSpacing.s))
            Switch(
                checked = blockingOn,
                onCheckedChange = onBlocking,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = blocking },
            )
        }
    }
}

/**
 * The presets as radio rows, the active one selected. Custom shows only when it is active: the user cannot select it,
 * because a change to one type makes it.
 */
@Composable
private fun PresetSheet(
    preset: PresetId,
    onSelect: (PresetId) -> Unit,
    onDismiss: () -> Unit,
) {
    val ids = PresetId.entries.filter { it != PresetId.CUSTOM || preset == PresetId.CUSTOM }
    NjChoiceSheet(
        title = stringResource(R.string.preset),
        options =
            ids.map { id ->
                NjOption(
                    value = id,
                    label = stringResource(presetNameRes(id)),
                    supporting = stringResource(presetDescriptionRes(id)),
                )
            },
        selected = preset,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

@StringRes
private fun presetNameRes(preset: PresetId): Int =
    when (preset) {
        PresetId.MONITOR -> R.string.preset_monitor
        PresetId.RECOMMENDED -> R.string.preset_recommended
        PresetId.STRICT -> R.string.preset_strict
        PresetId.QUARANTINE -> R.string.preset_quarantine
        PresetId.CUSTOM -> R.string.preset_custom
    }

@StringRes
private fun presetDescriptionRes(preset: PresetId): Int =
    when (preset) {
        PresetId.MONITOR -> R.string.preset_desc_monitor
        PresetId.RECOMMENDED -> R.string.preset_desc_recommended
        PresetId.STRICT -> R.string.preset_desc_strict
        PresetId.QUARANTINE -> R.string.preset_desc_quarantine
        PresetId.CUSTOM -> R.string.preset_desc_custom
    }

/**
 * One compact type row: the type icon, the type name, the bell when the type can notify, and the Allow /
 * Hold / Block control under the name. A tap on the name shows or hides one line that explains the type and, when an
 * action is locked, why.
 */
@Composable
private fun TypeRow(
    card: ProtectionTypeCard,
    enabled: Boolean,
    onSelect: (NjAction) -> Unit,
    onNotify: (Boolean) -> Unit,
) {
    val description = typeDescriptionRes(card.classId)?.let { stringResource(it) } ?: familyLabel(card.family)
    val why = card.reason?.let { stringResource(whyRes(it)) }
    TypeRowBody(
        family = card.family,
        name = stringResource(protectionLabelRes(card.classId)),
        info = listOfNotNull(description, why).joinToString(" "),
        selected = card.selected,
        enabledActions = card.enabled,
        controlEnabled = enabled,
        onSelect = onSelect,
        bell =
            if (card.showBell) {
                { NotifyBell(on = card.notify, enabled = enabled && card.showNotify, onToggle = { onNotify(!card.notify) }) }
            } else {
                null
            },
    )
}

/** Emergency alerts: the matrix leaves them out (clamp), so the row shows Allow only. A tap on Allow explains why. */
@Composable
private fun EmergencyRow(onInfo: () -> Unit) {
    TypeRowBody(
        family = ClassFamily.BROADCAST,
        name = stringResource(R.string.firewall_emergency),
        info = stringResource(R.string.prot_x_emergency_reason),
        selected = NjAction.ALLOW,
        enabledActions = setOf(NjAction.ALLOW),
        controlEnabled = true,
        onSelect = { onInfo() },
        bell = null,
    )
}

@Composable
private fun TypeRowBody(
    family: ClassFamily,
    name: String,
    info: String,
    selected: NjAction,
    enabledActions: Set<NjAction>,
    controlEnabled: Boolean,
    onSelect: (NjAction) -> Unit,
    bell: (@Composable () -> Unit)?,
) {
    val roles = NjTheme.roles
    val groupLabel = stringResource(R.string.prot_x_cd_actions, name)
    var infoOpen by rememberSaveable(name) { mutableStateOf(false) }
    // TalkBack says whether the information line is open (review screens-trust L7).
    val infoState = stringResource(if (infoOpen) R.string.cd_row_open else R.string.cd_row_closed)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NjSpacing.m, vertical = NjSpacing.s + 2.dp),
        verticalArrangement = Arrangement.spacedBy(NjSpacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(typeIconFor(family), contentDescription = null, tint = roles.acc, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(NjSpacing.s))
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .clickable(role = Role.Button) { infoOpen = !infoOpen }
                        .semantics { stateDescription = infoState },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(text = name, style = MaterialTheme.typography.bodyLarge, color = roles.tx)
            }
            bell?.invoke()
        }
        if (infoOpen) {
            Text(
                text = info,
                style = MaterialTheme.typography.bodySmall,
                color = roles.mu,
                modifier = Modifier.padding(start = 20.dp + NjSpacing.s),
            )
        }
        NjActionSegmented(
            selected = selected,
            onSelect = onSelect,
            enabled = controlEnabled,
            enabledActions = enabledActions,
            modifier = Modifier.semantics { contentDescription = groupLabel },
        )
    }
}

/**
 * The bell of a type that can notify ("Notify me"). On = "Allow and notify" (NOTIFY), off = Allow (ALLOW). On Hold or
 * Block the bell shows off and cannot change.
 */
@Composable
private fun NotifyBell(
    on: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val roles = NjTheme.roles
    val label = stringResource(R.string.prot_x_notify_me)
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
                .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (on) NjIcons.Bell else FirewallIcons.BellOff,
            contentDescription = null,
            tint = if (on) roles.acc else roles.mu,
            modifier = Modifier.size(20.dp).alpha(if (enabled || on) 1f else 0.4f),
        )
    }
}

/** The "why" sentence of a type with a locked action. It follows the type description in the info line. */
@StringRes
private fun whyRes(reason: ActionReason): Int =
    when (reason) {
        ActionReason.LOCKED -> R.string.prot_x_reason_locked
        ActionReason.ALLOW_OR_NOTIFY -> R.string.prot_why_allow_notify
        ActionReason.NO_HOLD -> R.string.prot_why_no_hold
        ActionReason.NO_BLOCK -> R.string.prot_x_reason_no_block
        ActionReason.NOT_ENFORCED -> R.string.prot_x_reason_not_enforced
    }

/**
 * One line of text for each incoming type. The outgoing types never get a card, so they have no entry: a type that is
 * not in the map shows its family name.
 */
private val TypeDescriptions: Map<ClassId, Int> =
    mapOf(
        ClassId.NORMAL to R.string.prot_desc_normal,
        ClassId.SILENT to R.string.prot_x_desc_silent,
        ClassId.FLASH to R.string.prot_x_desc_flash,
        ClassId.REPLACE to R.string.prot_x_desc_replace,
        ClassId.MWI to R.string.prot_x_desc_mwi,
        ClassId.VVM to R.string.prot_x_desc_vvm,
        ClassId.MISSED_CALL to R.string.prot_x_desc_missed_call,
        ClassId.CARRIER to R.string.prot_x_desc_carrier,
        ClassId.RCS_PROV to R.string.prot_x_desc_rcs_prov,
        ClassId.APP_TOKEN to R.string.prot_x_desc_app_token,
        ClassId.DATA_PORT to R.string.prot_x_desc_data_port,
        ClassId.WAP_MMS to R.string.prot_x_desc_wap_mms,
        ClassId.WAP_SISL to R.string.prot_x_desc_wap_sisl,
        ClassId.WAP_CP to R.string.prot_x_desc_wap_cp,
        ClassId.WAP_DM to R.string.prot_x_desc_wap_dm,
        ClassId.WAP_SUPL to R.string.prot_x_desc_wap_supl,
        ClassId.WAP_OTHER to R.string.prot_x_desc_wap_other,
        ClassId.SIM_DD to R.string.prot_x_desc_sim_dd,
        ClassId.SIM_STORED to R.string.prot_desc_sim_stored,
        ClassId.STATUS to R.string.prot_desc_status,
        ClassId.CB to R.string.prot_x_desc_cb,
        ClassId.OEM to R.string.prot_x_desc_oem,
        ClassId.INJECTED to R.string.prot_x_desc_injected,
        ClassId.RETURN_CALL to R.string.prot_desc_return_call,
        ClassId.ME_DOWNLOAD to R.string.prot_desc_me_download,
        ClassId.ME_DEPERSO to R.string.prot_desc_me_deperso,
        ClassId.CLASS3 to R.string.prot_desc_class3,
        ClassId.TELEMATIC to R.string.prot_desc_telematic,
        ClassId.EMPTY to R.string.prot_desc_empty,
        ClassId.BINARY to R.string.prot_desc_binary,
        ClassId.COMPRESSED to R.string.prot_desc_compressed,
        ClassId.MULTIPART_INCOMPLETE to R.string.prot_desc_multipart_incomplete,
        ClassId.MALFORMED_SMS to R.string.prot_desc_malformed_sms,
        ClassId.SMART to R.string.prot_desc_smart,
        ClassId.LOC_SUPL to R.string.prot_desc_loc_supl,
        ClassId.WAP_CO to R.string.prot_desc_wap_co,
        ClassId.WAP_DS to R.string.prot_desc_wap_ds,
        ClassId.WAP_EMN to R.string.prot_desc_wap_emn,
        ClassId.WAP_DRM to R.string.prot_desc_wap_drm,
        ClassId.WAP_VCARD to R.string.prot_desc_wap_vcard,
        ClassId.WAP_IMPS to R.string.prot_desc_wap_imps,
        ClassId.WAP_UNREADABLE to R.string.prot_desc_wap_unreadable,
        ClassId.CB_SIM to R.string.prot_desc_cb_sim,
        ClassId.UNREADABLE_SMS to R.string.firewall_prot_desc_unreadable_sms,
    )

/** One line of text for a type, or null for a type that has no card (the card then shows its family name). */
@StringRes
internal fun typeDescriptionRes(classId: ClassId): Int? = TypeDescriptions[classId]

@Composable
private fun reasonText(reasons: List<FirewallReason>): String =
    reasons
        .map { reason ->
            stringResource(
                when (reason) {
                    FirewallReason.HOOKS_FAILED -> R.string.firewall_reason_hooks
                    FirewallReason.NOT_ACTIVE -> R.string.firewall_reason_not_active
                    FirewallReason.SAFE_MODE_FLAG -> R.string.firewall_reason_safe_mode
                    FirewallReason.NOT_ENABLED -> R.string.firewall_reason_not_enabled
                },
            )
        }.joinToString(" ")

@Composable
private fun FirewallDialogHost(
    dialog: FirewallDialog,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        FirewallDialog.BlockingOffConfirm -> {
            NjDialog(
                title = stringResource(R.string.firewall_off_title),
                text = stringResource(R.string.firewall_off_body),
                confirmText = stringResource(R.string.firewall_off_confirm),
                dismissText = stringResource(R.string.firewall_off_cancel),
                onConfirm = onConfirm,
                onDismiss = onDismiss,
            )
        }

        is FirewallDialog.PresetReplace -> {
            NjDialog(
                title = stringResource(R.string.dlg_18_title),
                text = stringResource(R.string.dlg_18_body),
                confirmText = stringResource(R.string.dlg_18_confirm),
                dismissText = stringResource(R.string.dlg_18_cancel),
                onConfirm = onConfirm,
                onDismiss = onDismiss,
            )
        }

        is FirewallDialog.GuardSimUpdate -> {
            NjDialog(
                title = stringResource(R.string.dlg_05_title),
                text = stringResource(R.string.dlg_05_body),
                confirmText = stringResource(R.string.dlg_05_confirm),
                dismissText = stringResource(R.string.dlg_05_cancel),
                onConfirm = onConfirm,
                onDismiss = onDismiss,
            )
        }

        is FirewallDialog.GuardConsumerClass -> {
            val label = stringResource(classLabelRes(dialog.classId))
            NjDialog(
                title = stringResource(R.string.guard_consumer_title, label),
                text = stringResource(R.string.guard_consumer_body, label),
                confirmText = stringResource(R.string.dlg_06_confirm),
                dismissText = stringResource(R.string.dlg_06_cancel),
                onConfirm = onConfirm,
                onDismiss = onDismiss,
            )
        }

        FirewallDialog.EmergencyInfo -> {
            NjDialog(
                title = stringResource(R.string.firewall_emergency),
                text = stringResource(R.string.firewall_emergency_explained),
                confirmText = stringResource(R.string.action_ok),
                dismissText = null,
                onConfirm = onConfirm,
                onDismiss = onDismiss,
            )
        }
    }
}

// ------------------------------------------------------------------ previews

/** The Recommended preset, the preset of a new install. */
private val PreviewActions = Presets.classActions(Preset.RECOMMENDED)

@Composable
private fun ProtectionPreview(themeMode: ThemeMode) {
    PreviewTheme(themeMode = themeMode) {
        ProtectionContent(
            state =
                FirewallUiState(
                    loading = false,
                    preset = PresetId.RECOMMENDED,
                    rows = firewallRows(PreviewActions),
                    userRuleCount = 2,
                    knownSenderCount = 5,
                    firewallAvailable = true,
                ),
            snackbar = remember { SnackbarHostState() },
            onBlocking = {},
            onSelect = { _, _ -> },
            onNotify = { _, _ -> },
            onPreset = {},
            onEmergencyInfo = {},
            onRules = {},
            onSenders = {},
            onDialogConfirm = {},
            onDialogDismiss = {},
        )
    }
}

@Preview(name = "Protection dark", widthDp = 400, heightDp = 1200)
@Composable
private fun ProtectionDarkPreview() {
    ProtectionPreview(ThemeMode.DARK)
}

@Preview(name = "Protection light", widthDp = 400, heightDp = 1200)
@Composable
private fun ProtectionLightPreview() {
    ProtectionPreview(ThemeMode.LIGHT)
}
