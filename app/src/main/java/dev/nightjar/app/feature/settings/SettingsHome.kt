package dev.nightjar.app.feature.settings

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.nightjar.app.BuildConfig
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.Retention
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.core.settings.UiMode
import dev.nightjar.app.ui.components.LocalNjBottomInset
import dev.nightjar.app.ui.components.NjCollapsedTitle
import dev.nightjar.app.ui.components.NjEmptyState
import dev.nightjar.app.ui.components.NjGroup
import dev.nightjar.app.ui.components.NjIcons
import dev.nightjar.app.ui.components.NjLargeTitle
import dev.nightjar.app.ui.components.NjListRow
import dev.nightjar.app.ui.components.NjNavRow
import dev.nightjar.app.ui.components.NjOption
import dev.nightjar.app.ui.components.NjRowDivider
import dev.nightjar.app.ui.components.NjSearchField
import dev.nightjar.app.ui.components.NjSectionHeader
import dev.nightjar.app.ui.components.NjSwitchRow
import dev.nightjar.app.ui.components.NjTopBarHeight
import dev.nightjar.app.ui.components.currentNjMotion
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.components.rememberNjTitleCollapsed
import dev.nightjar.app.ui.nav.Route
import dev.nightjar.app.ui.theme.LocalNjMotionPrefs
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme

/**
 * The frame of every settings screen: the large title as the first list item, [NjCollapsedTitle] on top when
 * the list scrolls, and the end of the list above the floating bar ([LocalNjBottomInset]). A sub-screen gives
 * [onBack], and its back bar always shows. The list bounces at its end only when "Bounce at list end" is on.
 */
@Composable
internal fun SettingsFrame(
    title: String,
    snackbar: SnackbarHostState,
    onBack: (() -> Unit)?,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    val collapsed by rememberNjTitleCollapsed(listState)
    val bounce = currentNjMotion().bounceAllowed(LocalNjMotionPrefs.current.bounce)
    Box(Modifier.fillMaxSize().background(NjTheme.roles.bg)) {
        // "Bounce at list end" off, or animations off: no overscroll effect at all (redesign 7.4).
        CompositionLocalProvider(LocalOverscrollFactory provides (if (bounce) LocalOverscrollFactory.current else null)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        top = if (onBack != null) NjTopBarHeight else NjSpacing.s,
                        bottom = LocalNjBottomInset.current + NjSpacing.l,
                    ),
                verticalArrangement = Arrangement.spacedBy(NjSpacing.s + 2.dp),
            ) {
                item(key = "title", contentType = "title") {
                    NjLargeTitle(title = title, modifier = Modifier.njEnter(0))
                }
                content()
            }
        }
        if (onBack != null || collapsed) {
            NjCollapsedTitle(
                title = title,
                collapsed = collapsed,
                onBack = onBack,
                modifier = Modifier.align(Alignment.TopStart).background(NjTheme.roles.bg),
            )
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LocalNjBottomInset.current),
        )
    }
}

/**
 * The items of the Settings home: the search field, then one card for each [SettingsGroup]. When [query] has a word,
 * the cards hold the matching [results] instead, and a row from a sub-screen opens that sub-screen.
 */
internal fun LazyListScope.settingsHomeItems(
    query: String,
    onQuery: (String) -> Unit,
    visible: List<SettingsRow>,
    results: List<SettingsRow>,
    env: SettingsEnv,
) {
    item(key = "search", contentType = "search") {
        NjSearchField(
            value = query,
            onValueChange = onQuery,
            placeholder = stringResource(R.string.nj_set_search_hint),
            modifier = Modifier.padding(horizontal = NjSpacing.m).njEnter(1),
        )
    }
    val searching = settingsSearchTerms(query).isNotEmpty()
    val rows = if (searching) results else visible.filter { it.page == SettingsPage.HOME }
    if (searching && rows.isEmpty()) {
        item(key = "no-results", contentType = "state") {
            NjEmptyState(
                title = stringResource(R.string.nj_set_search_empty_title),
                body = stringResource(R.string.nj_set_search_empty_body),
                icon = NjIcons.Search,
            )
        }
    }
    SettingsGroup.entries.forEachIndexed { index, group ->
        val groupRows = rows.filter { it.group == group }
        if (groupRows.isNotEmpty()) {
            item(key = "group-${group.name}", contentType = "group") {
                SettingsGroupCard(
                    group = group,
                    rows = groupRows,
                    env = env,
                    modifier = Modifier.padding(horizontal = NjSpacing.m).njEnter(index + 2),
                )
            }
        }
    }
}

/** The [rows] that match [query], by the title and the description the user sees. */
@Composable
internal fun searchSettingsRows(
    rows: List<SettingsRow>,
    query: String,
): List<SettingsRow> {
    val titles = rows.map { rowTitle(it) }
    val descriptions = rows.map { row -> row.supporting?.let { stringResource(it) } }
    return filterSettingsRows(rows.indices.toList(), query, { titles[it] }, { descriptions[it] }).map { rows[it] }
}

@Composable
private fun SettingsGroupCard(
    group: SettingsGroup,
    rows: List<SettingsRow>,
    env: SettingsEnv,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
        NjSectionHeader(stringResource(group.title))
        NjGroup {
            rows.forEachIndexed { index, row ->
                if (index > 0) NjRowDivider()
                if (row.page == SettingsPage.HOME) HomeRow(row, env) else FoundRow(row, env)
            }
        }
    }
}

/** A search result from a sub-screen: the row title, the sub-screen name under it, and a tap opens the sub-screen. */
@Composable
private fun FoundRow(
    row: SettingsRow,
    env: SettingsEnv,
) {
    NjNavRow(
        title = rowTitle(row),
        supporting = pageTitle(row.page),
        onClick = { env.navigate(row.page) },
    )
}

/** One row of the home. Switches change the setting at once; other rows open a picker, a dialog or a screen. */
@Composable
private fun HomeRow(
    row: SettingsRow,
    env: SettingsEnv,
) {
    val s = env.settings
    val vm = env.viewModel
    val title = rowTitle(row)
    val supporting = row.supporting?.let { stringResource(it) }
    val icon = rowIcon(row)
    when (row) {
        SettingsRow.NOTIFY_SUSPICIOUS -> {
            NjSwitchRow(title, s.notifySuspicious, vm::setNotifySuspicious, supporting = supporting, leadingIcon = icon)
        }

        SettingsRow.NOTIFY_NOTABLE -> {
            NjSwitchRow(title, s.notifyNotable, vm::setNotifyNotable, supporting = supporting, leadingIcon = icon)
        }

        SettingsRow.NOTIFY_CB -> {
            NjSwitchRow(title, s.notifyCb, vm::setNotifyCb, supporting = supporting, leadingIcon = icon)
        }

        SettingsRow.SAFE_MODE -> {
            NjSwitchRow(title, s.safeMode, vm::setSafeMode, supporting = supporting, leadingIcon = icon)
        }

        SettingsRow.DEBUG_LOGGING -> {
            NjSwitchRow(title, s.debugLogging, vm::setDebugLogging, supporting = supporting, leadingIcon = icon)
        }

        SettingsRow.MODE -> {
            ModeRow(title, supporting, icon, env)
        }

        SettingsRow.RETENTION -> {
            RetentionRow(title, icon, env)
        }

        SettingsRow.VERSION -> {
            NjListRow(title = title, leadingIcon = icon)
        }

        SettingsRow.DELETE_ALL -> {
            NjListRow(
                title = title,
                supporting = supporting,
                leadingIcon = icon,
                destructive = true,
                onClick = vm::requestDeleteAll,
            )
        }

        else -> {
            NjNavRow(title = title, onClick = homeRowClick(row, env), supporting = supporting, leadingIcon = icon)
        }
    }
}

/** What a tap on a home row that opens something does. */
private fun homeRowClick(
    row: SettingsRow,
    env: SettingsEnv,
): () -> Unit {
    val open: (Route) -> () -> Unit = { route -> { env.actions.open(route) } }
    return when (row) {
        SettingsRow.EXPORT -> open(Route.Export)
        SettingsRow.VIEW_LOGS -> open(Route.Logs)
        SettingsRow.AUDIT_HISTORY -> open(Route.AuditHistory)
        SettingsRow.BLIND_SPOTS -> open(Route.BlindSpots)
        SettingsRow.REMOVE_MODULE -> env.viewModel::requestRemoveModule
        SettingsRow.RESTART_PHONE -> env.viewModel::requestRestartPhone
        SettingsRow.SHARE_DIAGNOSTICS -> env.onSendDiagnostics
        else -> ({ env.navigate(row.opens ?: row.page) })
    }
}

@Composable
private fun ModeRow(
    title: String,
    supporting: String?,
    icon: ImageVector?,
    env: SettingsEnv,
) {
    val modeOptions =
        listOf(
            NjOption(UiMode.SIMPLE, stringResource(R.string.mode_simple)),
            NjOption(UiMode.RESEARCHER, stringResource(R.string.mode_researcher)),
        )
    NjNavRow(
        title = title,
        supporting = supporting,
        leadingIcon = icon,
        value = modeOptions.first { it.value == env.settings.mode }.label,
        onClick = {
            env.pick(
                title = R.string.settings_group_mode,
                options = modeOptions,
                selected = env.settings.mode,
                onSelect = env.viewModel::requestMode,
            )
        },
    )
}

@Composable
private fun RetentionRow(
    title: String,
    icon: ImageVector?,
    env: SettingsEnv,
) {
    val retentionOptions =
        listOf(
            NjOption(Retention.DAYS_30, stringResource(R.string.settings_retention_30)),
            NjOption(Retention.DAYS_90, stringResource(R.string.settings_retention_90)),
            NjOption(Retention.DAYS_365, stringResource(R.string.settings_retention_365)),
            NjOption(Retention.FOREVER, stringResource(R.string.settings_retention_forever)),
        )
    NjNavRow(
        title = title,
        leadingIcon = icon,
        value = retentionLabel(env.settings.retention),
        onClick = {
            env.pick(
                title = R.string.settings_data_retentiondays,
                options = retentionOptions,
                selected = env.settings.retention,
                onSelect = env.viewModel::requestRetention,
            )
        },
    )
}

/** The title of [row] as the user sees it. The version row has the version name in it. */
@Composable
private fun rowTitle(row: SettingsRow): String =
    if (row == SettingsRow.VERSION) stringResource(row.title, BuildConfig.VERSION_NAME) else stringResource(row.title)

/** The icon of a home row. Rows on a sub-screen have no icon. */
private fun rowIcon(row: SettingsRow): ImageVector? =
    when (row) {
        SettingsRow.APPEARANCE -> NjIcons.Appearance
        SettingsRow.MODE -> NjIcons.Gear
        SettingsRow.NOTIFY_SUSPICIOUS, SettingsRow.SEVERITY -> NjIcons.Warning
        SettingsRow.NOTIFY_NOTABLE -> NjIcons.Diamond
        SettingsRow.SAFE_MODE, SettingsRow.BLIND_SPOTS -> NjIcons.Shield
        SettingsRow.NOTIFY_CB, SettingsRow.CELL -> NjIcons.Broadcast
        SettingsRow.NOTIFY_MORE -> NjIcons.Bell
        SettingsRow.DETECTION -> NjIcons.Tune
        SettingsRow.OUTGOING, SettingsRow.EXPORT, SettingsRow.SHARE_DIAGNOSTICS -> NjIcons.Send
        SettingsRow.PRIVACY -> NjIcons.Lock
        SettingsRow.RETENTION -> NjIcons.Database
        SettingsRow.DELETE_ALL -> NjIcons.Close
        SettingsRow.REMOVE_MODULE, SettingsRow.DEBUG_LOGGING, SettingsRow.RESTART_PHONE -> NjIcons.Tools
        SettingsRow.VIEW_LOGS -> NjIcons.Inbox
        SettingsRow.AUDIT_HISTORY -> NjIcons.Check
        SettingsRow.VERSION -> NjIcons.Info
        else -> null
    }

// ------------------------------------------------------------------ previews

@Composable
private fun SettingsHomePreviewContent() {
    Column(
        Modifier.background(NjTheme.roles.bg).padding(vertical = NjSpacing.m),
        verticalArrangement = Arrangement.spacedBy(NjSpacing.s + 2.dp),
    ) {
        NjLargeTitle(title = stringResource(R.string.screen_s10))
        NjSearchField(
            value = "",
            onValueChange = {},
            placeholder = stringResource(R.string.nj_set_search_hint),
            modifier = Modifier.padding(horizontal = NjSpacing.m),
        )
        Column(Modifier.padding(horizontal = NjSpacing.m), verticalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
            NjSectionHeader(stringResource(R.string.settings_group_appearance))
            NjGroup {
                NjNavRow(
                    title = stringResource(R.string.settings_group_appearance),
                    supporting = stringResource(R.string.nj_set_row_appearance_sub),
                    leadingIcon = NjIcons.Appearance,
                    onClick = {},
                )
                NjRowDivider()
                NjNavRow(
                    title = stringResource(R.string.settings_group_mode),
                    supporting = stringResource(R.string.settings_mode_sub),
                    leadingIcon = NjIcons.Gear,
                    value = stringResource(R.string.mode_simple),
                    onClick = {},
                )
            }
            NjSectionHeader(stringResource(R.string.settings_group_notifications))
            NjGroup {
                NjSwitchRow(
                    title = stringResource(R.string.settings_notify_suspicious),
                    checked = true,
                    onCheckedChange = {},
                    leadingIcon = NjIcons.Warning,
                )
                NjRowDivider()
                NjNavRow(
                    title = stringResource(R.string.nj_set_row_notify_more),
                    supporting = stringResource(R.string.nj_set_row_notify_more_sub),
                    leadingIcon = NjIcons.Bell,
                    onClick = {},
                )
            }
        }
    }
}

@Preview(name = "Settings home dark", widthDp = 400)
@Composable
private fun SettingsHomeDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { SettingsHomePreviewContent() }
}

@Preview(name = "Settings home light", widthDp = 400)
@Composable
private fun SettingsHomeLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { SettingsHomePreviewContent() }
}
