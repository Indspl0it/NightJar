package dev.nightjar.app.feature.known

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.data.model.KnownOriginator
import dev.nightjar.app.data.model.KnownOriginatorKind
import dev.nightjar.app.data.model.Mute
import dev.nightjar.app.data.model.MuteScope
import dev.nightjar.app.data.model.TrustedPackage
import dev.nightjar.app.domain.known.KnownOriginator.Companion.LABEL_CARRIER
import dev.nightjar.app.domain.rules.KnownOriginatorPolicy
import dev.nightjar.app.feature.detail.DetailIcons
import dev.nightjar.app.feature.rules.ToolGroupRow
import dev.nightjar.app.feature.rules.ToolRowPosition
import dev.nightjar.app.feature.rules.ToolScreen
import dev.nightjar.app.feature.rules.classLabelRes
import dev.nightjar.app.feature.rules.joinWith
import dev.nightjar.app.feature.rules.showLatestSnackbar
import dev.nightjar.app.feature.rules.toolCard
import dev.nightjar.app.feature.rules.toolLoading
import dev.nightjar.app.feature.settings.UiFormat
import dev.nightjar.app.ui.components.NjBottomSheet
import dev.nightjar.app.ui.components.NjCaption
import dev.nightjar.app.ui.components.NjEmptyState
import dev.nightjar.app.ui.components.NjIcons
import dev.nightjar.app.ui.components.NjListRow
import dev.nightjar.app.ui.components.NjOption
import dev.nightjar.app.ui.components.NjPrimaryButton
import dev.nightjar.app.ui.components.NjSegmented
import dev.nightjar.app.ui.components.NjTextButton
import dev.nightjar.app.ui.components.NjTextField
import dev.nightjar.app.ui.components.NjTonalButton
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.nav.NavActions
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.PreviewTheme
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Wire

/**
 * Known senders and trusted apps (-01, 02). [initialTab] is the tab the screen opens on, for
 * example Trusted apps from Settings.
 */
@Composable
fun KnownSendersScreen(
    actions: NavActions,
    initialTab: KnownTab = KnownTab.SENDERS,
) {
    val viewModel: KnownSendersViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // Applied once per screen: a rotation keeps the tab the user chose.
    var tabApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!tabApplied) {
            viewModel.selectTab(initialTab)
            tabApplied = true
        }
    }

    val deletedText =
        mapOf(
            KnownEntryKind.SENDER to stringResource(R.string.known_deleted),
            KnownEntryKind.APP to stringResource(R.string.known_trust_deleted),
            KnownEntryKind.MUTE to stringResource(R.string.known_mute_deleted),
        )
    val restoredText =
        mapOf(
            KnownEntryKind.SENDER to stringResource(R.string.known_restored),
            KnownEntryKind.APP to stringResource(R.string.known_trust_restored),
            KnownEntryKind.MUTE to stringResource(R.string.known_mute_restored),
        )
    val undo = stringResource(R.string.action_undo)
    val saved = stringResource(R.string.known_saved)
    val trustSaved = stringResource(R.string.trusted_saved)
    val failed = stringResource(R.string.change_failed)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                KnownSendersEvent.Saved -> {
                    showLatestSnackbar(snackbar, saved)
                }

                KnownSendersEvent.TrustSaved -> {
                    showLatestSnackbar(snackbar, trustSaved)
                }

                is KnownSendersEvent.Deleted -> {
                    showLatestSnackbar(snackbar, deletedText.getValue(event.kind), actionLabel = undo) {
                        viewModel.undoDelete(event.token)
                    }
                }

                is KnownSendersEvent.Restored -> {
                    showLatestSnackbar(snackbar, restoredText.getValue(event.kind))
                }

                KnownSendersEvent.Failed -> {
                    showLatestSnackbar(snackbar, failed)
                }
            }
        }
    }

    // The picker covers the screen. It has no route of its own, so the shell and the graph do not change.
    if (state.pickerOpen) {
        AppPickerScreen(onClose = viewModel::closePicker)
        return
    }

    KnownSendersContent(
        state = state,
        snackbar = snackbar,
        onBack = { actions.back() },
        onTab = viewModel::selectTab,
        onAdd = viewModel::openAdd,
        onEdit = viewModel::openEdit,
        onDeleteSender = viewModel::deleteSender,
        onAddApps = viewModel::openPicker,
        onRemoveApp = viewModel::removeTrustedApp,
        onUnmute = viewModel::clearMute,
    )

    state.form?.let { form ->
        KnownFormSheet(
            form = form,
            validation = state.validation,
            busy = state.busy,
            onForm = viewModel::updateForm,
            onSave = viewModel::saveForm,
            onDismiss = viewModel::closeForm,
        )
    }
}

@Composable
private fun KnownSendersContent(
    state: KnownSendersUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onTab: (KnownTab) -> Unit,
    onAdd: () -> Unit,
    onEdit: (KnownOriginator) -> Unit,
    onDeleteSender: (KnownOriginator) -> Unit,
    onAddApps: () -> Unit,
    onRemoveApp: (TrustedPackage) -> Unit,
    onUnmute: (Mute) -> Unit,
) {
    val tabOptions = KnownTab.entries.map { NjOption(it, tabLabel(it)) }
    ToolScreen(
        title = stringResource(R.string.screen_s13),
        onBack = onBack,
        snackbar = snackbar,
    ) {
        item(key = "tabs") {
            NjSegmented(
                options = tabOptions,
                selected = state.tab,
                onSelect = onTab,
                modifier = Modifier.toolCard().njEnter(1),
            )
        }
        when {
            state.loading -> toolLoading(3)
            state.tab == KnownTab.SENDERS -> senderItems(state, onAdd, onEdit, onDeleteSender)
            state.tab == KnownTab.APPS -> trustedAppItems(state, onAddApps, onRemoveApp)
            else -> muteItems(state, onUnmute)
        }
    }
}

/** The stagger index of the first entry: after the title, the tabs and the first control. */
private const val FIRST_ENTRY_INDEX = 3

private fun LazyListScope.senderItems(
    state: KnownSendersUiState,
    onAdd: () -> Unit,
    onEdit: (KnownOriginator) -> Unit,
    onDelete: (KnownOriginator) -> Unit,
) {
    item(key = "add") {
        NjTonalButton(
            text = stringResource(R.string.known_add),
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().toolCard().njEnter(2),
        )
    }
    if (state.senders.isEmpty()) {
        item(key = "empty-senders") {
            NjEmptyState(
                title = stringResource(R.string.known_tab_senders),
                body = stringResource(R.string.known_empty_senders),
                icon = DetailIcons.Person,
                modifier = Modifier.njEnter(FIRST_ENTRY_INDEX),
            )
        }
        return
    }
    itemsIndexed(state.senders, key = { _, sender -> "sender-" + sender.id }) { index, sender ->
        val kindLabel =
            stringResource(
                if (sender.kind == KnownOriginatorKind.PREFIX) {
                    R.string.known_kind_prefix
                } else {
                    R.string.known_kind_exact
                },
            )
        val added = stringResource(R.string.known_added, UiFormat.date(sender.addedAt))
        val label = sender.label?.let { labelText(it) }
        KnownRow(
            position = ToolRowPosition.of(index, state.senders.size),
            title = sender.value,
            subtitle = joinWith(listOfNotNull(kindLabel, label, added), R.string.known_sender_row),
            onEdit = { onEdit(sender) },
            onRemove = { onDelete(sender) },
            modifier = Modifier.njEnter(FIRST_ENTRY_INDEX + index),
        )
    }
}

/**
 * The Apps tab. With no trusted app it shows the empty state with one line about trust and the "Add apps"
 * button. With trusted apps the "Add apps" button stays above the list, like the add button of the Senders tab.
 */
private fun LazyListScope.trustedAppItems(
    state: KnownSendersUiState,
    onAddApps: () -> Unit,
    onRemove: (TrustedPackage) -> Unit,
) {
    if (state.apps.isEmpty()) {
        item(key = "empty-apps") {
            NjEmptyState(
                title = stringResource(R.string.trusted_empty_title),
                body = stringResource(R.string.trusted_empty_body),
                icon = NjIcons.Shield,
                modifier = Modifier.njEnter(2),
                action = {
                    NjPrimaryButton(text = stringResource(R.string.trusted_add_apps), onClick = onAddApps)
                },
            )
        }
        return
    }
    item(key = "add-apps") {
        NjTonalButton(
            text = stringResource(R.string.trusted_add_apps),
            onClick = onAddApps,
            modifier = Modifier.fillMaxWidth().toolCard().njEnter(2),
        )
    }
    itemsIndexed(state.apps, key = { _, app -> "app-" + app.packageName }) { index, app ->
        KnownRow(
            position = ToolRowPosition.of(index, state.apps.size),
            title = app.label ?: app.packageName,
            subtitle =
                stringResource(
                    R.string.known_sender_row,
                    app.packageName,
                    stringResource(R.string.known_added, UiFormat.date(app.addedAt)),
                ),
            onEdit = null,
            onRemove = { onRemove(app) },
            removeLabel = R.string.known_remove_trust,
            modifier = Modifier.njEnter(FIRST_ENTRY_INDEX + index),
        )
    }
}

private fun LazyListScope.muteItems(
    state: KnownSendersUiState,
    onUnmute: (Mute) -> Unit,
) {
    if (state.mutes.isEmpty()) {
        item(key = "empty-mutes") {
            NjEmptyState(
                title = stringResource(R.string.known_tab_mutes),
                body = stringResource(R.string.known_empty_mutes),
                icon = NjIcons.Bell,
                modifier = Modifier.njEnter(2),
            )
        }
        return
    }
    itemsIndexed(state.mutes, key = { _, mute -> "mute-" + mute.id }) { index, mute ->
        KnownRow(
            position = ToolRowPosition.of(index, state.mutes.size),
            title =
                when (mute.scope) {
                    MuteScope.CLASS -> classMuteLabel(mute.value)
                    MuteScope.ORIGINATOR -> mute.value
                },
            subtitle =
                stringResource(
                    R.string.known_sender_row,
                    when (mute.scope) {
                        MuteScope.CLASS -> stringResource(R.string.known_scope_class)
                        MuteScope.ORIGINATOR -> stringResource(R.string.known_scope_originator)
                    },
                    if (mute.until == Long.MAX_VALUE) {
                        stringResource(R.string.known_until_always)
                    } else {
                        stringResource(R.string.known_until, UiFormat.dateTime(mute.until))
                    },
                ),
            onEdit = null,
            onRemove = { onUnmute(mute) },
            removeLabel = R.string.known_unmute,
            modifier = Modifier.njEnter(2 + index),
        )
    }
}

@Composable
private fun classMuteLabel(wire: String): String {
    val classId = Wire.parse(ClassId::class.java, wire, null)
    return if (classId != null) stringResource(classLabelRes(classId)) else wire
}

/** The shown text of a stored label: a [KnownOriginatorPolicy.LABELS] value is translated, an older free text is not. */
@Composable
private fun labelText(label: String): String =
    when (label) {
        LABEL_CARRIER -> stringResource(R.string.known_label_carrier)
        LABEL_BANK -> stringResource(R.string.known_label_bank)
        LABEL_OTHER -> stringResource(R.string.known_label_other)
        else -> label
    }

private const val LABEL_BANK = "bank"
private const val LABEL_OTHER = "other"

/**
 * One entry as a row of the list group: tap the row to edit (when editable), the trailing button removes it. The
 * button names its entry for TalkBack, and the edit tap carries a named action, because [NjListRow] has no click label.
 */
@Composable
private fun KnownRow(
    position: ToolRowPosition,
    title: String,
    subtitle: String,
    onEdit: (() -> Unit)?,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    removeLabel: Int = R.string.known_remove,
) {
    val editLabel = stringResource(R.string.known_edit_entry, title)
    val removeText = stringResource(removeLabel)
    val removeDescription = stringResource(R.string.known_remove_entry, removeText, title)
    val rowModifier =
        if (onEdit == null) {
            modifier
        } else {
            modifier.semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(editLabel) {
                            onEdit()
                            true
                        },
                    )
            }
        }
    ToolGroupRow(position = position, modifier = rowModifier) {
        NjListRow(
            title = title,
            supporting = subtitle,
            onClick = onEdit,
            showChevron = false,
            trailing = {
                NjTextButton(
                    text = removeText,
                    onClick = onRemove,
                    destructive = true,
                    modifier = Modifier.semantics { contentDescription = removeDescription },
                )
            },
        )
    }
}

@Composable
private fun KnownFormSheet(
    form: KnownForm,
    validation: KnownValidation?,
    busy: Boolean,
    onForm: (KnownForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    NjBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(if (form.id == null) R.string.bs_06_title else R.string.known_edit_title),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = NjSpacing.m),
            verticalArrangement = Arrangement.spacedBy(NjSpacing.m),
        ) {
            NjTextField(
                value = form.value,
                onValueChange = { onForm(form.copy(value = it)) },
                label = stringResource(R.string.known_value),
                isError = validation != null,
                supporting = validation?.let { validationText(it) },
            )
            NjSegmented(
                options =
                    listOf(
                        NjOption(KnownOriginatorKind.EXACT, stringResource(R.string.known_kind_exact)),
                        NjOption(KnownOriginatorKind.PREFIX, stringResource(R.string.known_kind_prefix)),
                    ),
                selected = form.kind,
                onSelect = { onForm(form.copy(kind = it)) },
            )
            NjCaption(stringResource(R.string.known_label))
            NjSegmented(
                options = labelOptions(form.label),
                selected = form.label,
                onSelect = { onForm(form.copy(label = it)) },
            )
            NjPrimaryButton(
                text = stringResource(if (form.id == null) R.string.known_add_confirm else R.string.action_save),
                onClick = onSave,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            NjTextButton(
                text = stringResource(R.string.known_cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The label choices: no label plus [KnownOriginatorPolicy.LABELS]. The OMA CP detector compares the label exactly, so
 * free text cannot be typed any more; an older free-text label stays as a choice so that an edit does not drop it.
 */
@Composable
private fun labelOptions(current: String): List<NjOption<String>> {
    val options = ArrayList<NjOption<String>>(KnownOriginatorPolicy.LABELS.size + 2)
    options += NjOption("", stringResource(R.string.known_label_none))
    KnownOriginatorPolicy.LABELS.forEach { options += NjOption(it, labelText(it)) }
    if (current.isNotBlank() && current !in KnownOriginatorPolicy.LABELS) options += NjOption(current, current)
    return options
}

@Composable
private fun validationText(validation: KnownValidation): String =
    stringResource(
        when (validation) {
            KnownValidation.EMPTY -> R.string.known_error_empty
            KnownValidation.TOO_LONG -> R.string.known_error_long
            KnownValidation.NOT_A_NUMBER -> R.string.known_error_invalid
            KnownValidation.DUPLICATE -> R.string.known_error_duplicate
        },
    )

@Composable
private fun tabLabel(tab: KnownTab): String =
    stringResource(
        when (tab) {
            KnownTab.SENDERS -> R.string.trusted_tab_senders
            KnownTab.APPS -> R.string.trusted_tab_apps
            KnownTab.MUTES -> R.string.known_tab_mutes
        },
    )

// ------------------------------------------------------------------ previews

/** Synthetic senders only: an alphanumeric sender ID and a short code, never a phone number. */
private val PreviewKnownState =
    KnownSendersUiState(
        loading = false,
        senders =
            listOf(
                KnownOriginator(
                    id = 1,
                    value = "NJ-TEST",
                    kind = KnownOriginatorKind.EXACT,
                    label = "carrier",
                    addedAt = 1_760_000_000_000,
                ),
                KnownOriginator(
                    id = 2,
                    value = "12345",
                    kind = KnownOriginatorKind.PREFIX,
                    label = null,
                    addedAt = 1_760_000_000_000,
                ),
            ),
    )

@Composable
private fun PreviewKnownSenders(state: KnownSendersUiState) {
    KnownSendersContent(
        state = state,
        snackbar = remember { SnackbarHostState() },
        onBack = {},
        onTab = {},
        onAdd = {},
        onEdit = {},
        onDeleteSender = {},
        onAddApps = {},
        onRemoveApp = {},
        onUnmute = {},
    )
}

@Preview(name = "Known senders dark", widthDp = 400, heightDp = 700)
@Composable
private fun KnownSendersDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewKnownSenders(PreviewKnownState) }
}

@Preview(name = "Known senders light", widthDp = 400, heightDp = 700)
@Composable
private fun KnownSendersLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { PreviewKnownSenders(PreviewKnownState) }
}

@Preview(name = "Known senders apps empty dark", widthDp = 400, heightDp = 640)
@Composable
private fun KnownSendersAppsEmptyDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        PreviewKnownSenders(KnownSendersUiState(loading = false, tab = KnownTab.APPS))
    }
}

@Preview(name = "Known senders mutes empty dark", widthDp = 400, heightDp = 640)
@Composable
private fun KnownSendersEmptyDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        PreviewKnownSenders(KnownSendersUiState(loading = false, tab = KnownTab.MUTES))
    }
}
