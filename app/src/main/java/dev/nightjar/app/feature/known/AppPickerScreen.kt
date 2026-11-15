package dev.nightjar.app.feature.known

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.domain.known.AppFilter
import dev.nightjar.app.domain.known.InstalledApp
import dev.nightjar.app.feature.rules.showLatestSnackbar
import dev.nightjar.app.feature.rules.toolLoading
import dev.nightjar.app.ui.components.LocalNjBottomInset
import dev.nightjar.app.ui.components.NjEmptyState
import dev.nightjar.app.ui.components.NjFilterChips
import dev.nightjar.app.ui.components.NjIcons
import dev.nightjar.app.ui.components.NjLineIcons
import dev.nightjar.app.ui.components.NjListRow
import dev.nightjar.app.ui.components.NjMenuItem
import dev.nightjar.app.ui.components.NjOption
import dev.nightjar.app.ui.components.NjOverflowMenu
import dev.nightjar.app.ui.components.NjPrimaryButton
import dev.nightjar.app.ui.components.NjRowDivider
import dev.nightjar.app.ui.components.NjSearchField
import dev.nightjar.app.ui.components.NjTopBarHeight
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme

/**
 * The trusted apps picker.
 *
 * The bar has Close, the title "Add trusted apps" and a menu with Select all, Invert selection and Clear selection.
 * Under the bar: the search field and the User / System / All chips. Each row has the app icon, the label, the package
 * name and a check box. The bottom bar shows how many apps have a check, and Save. The trusted apps start with a check,
 * so Save trusts the new checks and removes the trust of an app without a check. Close and Back make no change.
 */
@Composable
fun AppPickerScreen(
    onClose: (saved: Boolean) -> Unit,
    viewModel: AppPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val failed = stringResource(R.string.change_failed)
    // A new pick starts once for each opening. A rotation keeps the search and the checks.
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!started) {
            viewModel.start()
            started = true
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AppPickerEvent.Saved -> onClose(event.added + event.removed > 0)
                AppPickerEvent.Failed -> showLatestSnackbar(snackbar, failed)
            }
        }
    }
    BackHandler { onClose(false) }
    AppPickerContent(
        // The ViewModel outlives the picker: until start() runs for this opening, show the empty loading state, not the
        // rows, checks and search of the last opening (review L4).
        state = if (started) state else AppPickerUiState(),
        snackbar = snackbar,
        onClose = { onClose(false) },
        onQuery = viewModel::setQuery,
        onFilter = viewModel::setFilter,
        onToggle = viewModel::toggle,
        onSelectAll = viewModel::selectAll,
        onInvert = viewModel::invert,
        onClear = viewModel::clear,
        onSave = viewModel::save,
        onRetry = viewModel::start,
    )
}

@Composable
private fun AppPickerContent(
    state: AppPickerUiState,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (AppFilter) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
) {
    val ready = !state.loading && !state.failed
    val bottomInset = LocalNjBottomInset.current
    // The shell consumes the status bar inset, so this padding is 0 inside the shell and correct outside it.
    Box(Modifier.fillMaxSize().background(NjTheme.roles.bg).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            PickerBar(
                menuEnabled = ready && state.rows.isNotEmpty(),
                onClose = onClose,
                onSelectAll = onSelectAll,
                onInvert = onInvert,
                onClear = onClear,
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = NjSpacing.m, vertical = NjSpacing.s),
                verticalArrangement = Arrangement.spacedBy(NjSpacing.s),
            ) {
                NjSearchField(
                    value = state.query,
                    onValueChange = onQuery,
                    placeholder = stringResource(R.string.trusted_search),
                )
                NjFilterChips(
                    options =
                        listOf(
                            NjOption(AppFilter.USER, stringResource(R.string.trusted_filter_user)),
                            NjOption(AppFilter.SYSTEM, stringResource(R.string.trusted_filter_system)),
                            NjOption(AppFilter.ALL, stringResource(R.string.trusted_filter_all)),
                        ),
                    selected = setOf(state.filter),
                    onToggle = onFilter,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PickerBody(state = state, onToggle = onToggle, onRetry = onRetry)
                SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
            }
            if (ready) {
                PickerSaveBar(
                    count = state.selected.size,
                    saving = state.saving,
                    onSave = onSave,
                    modifier = Modifier.padding(bottom = bottomInset),
                )
            }
        }
    }
}

/** The small bar of the picker: Close, the title and the selection menu. */
@Composable
private fun PickerBar(
    menuEnabled: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onClear: () -> Unit,
) {
    val roles = NjTheme.roles
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = NjTopBarHeight).padding(horizontal = NjSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(NjIcons.Close, contentDescription = stringResource(R.string.action_close), tint = roles.tx)
        }
        Text(
            stringResource(R.string.trusted_picker_title),
            style = MaterialTheme.typography.titleLarge,
            color = roles.tx,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = NjSpacing.s).semantics { heading() },
        )
        // The shared top-right menu (the same menu style on every screen), with a line icon per item.
        NjOverflowMenu(contentDescription = stringResource(R.string.action_more), enabled = menuEnabled) { close ->
            PickerMenuItem(R.string.trusted_select_all, NjLineIcons.SelectAll, onSelectAll, close)
            PickerMenuItem(R.string.trusted_invert, NjLineIcons.Invert, onInvert, close)
            PickerMenuItem(R.string.trusted_clear, NjLineIcons.ClearAll, onClear, close)
        }
    }
}

@Composable
private fun PickerMenuItem(
    label: Int,
    icon: ImageVector,
    onClick: () -> Unit,
    close: () -> Unit,
) {
    NjMenuItem(
        text = stringResource(label),
        icon = icon,
        onClick = {
            close()
            onClick()
        },
    )
}

@Composable
private fun PickerBody(
    state: AppPickerUiState,
    onToggle: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.loading -> {
            LazyColumn(Modifier.fillMaxSize()) { toolLoading(4) }
        }

        state.failed -> {
            NjEmptyState(
                title = stringResource(R.string.trusted_load_failed),
                icon = NjIcons.Warning,
                actionLabel = stringResource(R.string.action_retry),
                onAction = onRetry,
            )
        }

        state.rows.isEmpty() -> {
            NjEmptyState(
                title = stringResource(R.string.trusted_no_match),
                body = stringResource(R.string.trusted_no_match_body),
                icon = NjIcons.Search,
            )
        }

        else -> {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = NjSpacing.m)) {
                itemsIndexed(state.rows, key = { _, app -> app.packageName }) { index, app ->
                    if (index > 0) NjRowDivider()
                    PickerRow(app = app, checked = app.packageName in state.selected, onToggle = { onToggle(app.packageName) })
                }
            }
        }
    }
}

/** One app: icon, label, package name and the check box. The whole row toggles the check. */
@Composable
private fun PickerRow(
    app: InstalledApp,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val visual = rememberAppVisual(app.packageName)
    NjListRow(
        title = app.label,
        supporting = app.packageName,
        leading = { AppIcon(visual.icon) },
        trailing = { Checkbox(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }),
    )
}

/** The bottom bar: how many apps have a check, and Save. */
@Composable
private fun PickerSaveBar(
    count: Int,
    saving: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    Column(modifier.fillMaxWidth().background(roles.bg)) {
        HorizontalDivider(color = roles.line)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NjSpacing.m, vertical = NjSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                pluralStringResource(R.plurals.trusted_selected, count, count),
                style = MaterialTheme.typography.bodyLarge,
                color = roles.tx,
                modifier = Modifier.weight(1f),
            )
            NjPrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = onSave,
                enabled = !saving,
            )
        }
    }
}

// ------------------------------------------------------------------ previews

/** Synthetic apps only. */
private val PreviewPickerState =
    AppPickerUiState(
        loading = false,
        rows =
            listOf(
                InstalledApp("dev.example.notes", "Example notes", system = false),
                InstalledApp("dev.example.sync", "Example sync with a long app name that does not fit", system = false),
                InstalledApp("dev.example.tester", "Tester", system = false),
            ),
        selected = setOf("dev.example.tester"),
    )

@Composable
private fun PreviewPicker(state: AppPickerUiState) {
    AppPickerContent(
        state = state,
        snackbar = remember { SnackbarHostState() },
        onClose = {},
        onQuery = {},
        onFilter = {},
        onToggle = {},
        onSelectAll = {},
        onInvert = {},
        onClear = {},
        onSave = {},
        onRetry = {},
    )
}

@Preview(name = "Trusted apps picker dark", widthDp = 400, heightDp = 700)
@Composable
private fun AppPickerDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewPicker(PreviewPickerState) }
}

@Preview(name = "Trusted apps picker light", widthDp = 400, heightDp = 700)
@Composable
private fun AppPickerLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { PreviewPicker(PreviewPickerState) }
}
