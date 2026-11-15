package dev.nightjar.app.feature.logs

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nightjar.app.R
import dev.nightjar.app.core.log.LogLevel
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.feature.rules.ToolGroupRow
import dev.nightjar.app.feature.rules.ToolRowPosition
import dev.nightjar.app.feature.rules.ToolScreen
import dev.nightjar.app.feature.rules.toolCard
import dev.nightjar.app.feature.rules.toolLoading
import dev.nightjar.app.ui.components.NjCaption
import dev.nightjar.app.ui.components.NjEmptyState
import dev.nightjar.app.ui.components.NjFilterChips
import dev.nightjar.app.ui.components.NjIcons
import dev.nightjar.app.ui.components.NjOption
import dev.nightjar.app.ui.components.NjSearchField
import dev.nightjar.app.ui.components.NjSegmented
import dev.nightjar.app.ui.components.NjTextButton
import dev.nightjar.app.ui.components.NjTonalButton
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.nav.NavActions
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme
import kotlinx.coroutines.launch

/** Logs (-01, 03) and the audit tab of Audit history. Tabs: Payload, App, Framework, Audit. */
@Composable
fun LogsScreen(actions: NavActions) {
    val viewModel: LogsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exported = stringResource(R.string.log_exported)
    val exportFailed = stringResource(R.string.log_export_failed)
    val clipLabel = stringResource(R.string.log_clip_label)
    val copied = stringResource(R.string.log_copied)
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) viewModel.exportTo(uri)
        }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                LogsEvent.Exported -> snackbar.showSnackbar(exported)
                LogsEvent.Failed -> snackbar.showSnackbar(exportFailed)
            }
        }
    }

    // The audit list loads only when its tab is open.
    val audit = if (state.tab == LogTab.AUDIT) auditBinding() else null

    LogsContent(
        state = state,
        audit = audit,
        snackbar = snackbar,
        onBack = { actions.back() },
        onTab = viewModel::selectTab,
        onLevel = viewModel::toggleLevel,
        onQuery = viewModel::setQuery,
        onRefresh = viewModel::reload,
        onCopy = { lines ->
            val text = lines.joinToString("\n") { it.raw }
            val manager = context.getSystemService(ClipboardManager::class.java)
            // Masked, but still private: Android 13+ then hides the text in the clipboard preview.
            val clip = ClipData.newPlainText(clipLabel, text)
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            manager?.setPrimaryClip(clip)
            scope.launch { snackbar.showSnackbar(copied) }
        },
        onExport = { exportLauncher.launch("nightjar-logs.txt") },
    )
}

@Composable
private fun LogsContent(
    state: LogsUiState,
    audit: AuditBinding?,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onTab: (LogTab) -> Unit,
    onLevel: (LogLevel, Boolean) -> Unit,
    onQuery: (String) -> Unit,
    onRefresh: () -> Unit,
    onCopy: (List<LogLine>) -> Unit,
    onExport: () -> Unit,
) {
    // visibleLines filters on each read, so read it one time for each change of lines, levels or query.
    val visible = remember(state.lines, state.levels, state.query) { state.visibleLines }
    val tabOptions = LogTab.entries.map { NjOption(it, tabLabel(it)) }
    val companionLabel = tabLabel(LogTab.COMPANION)
    ToolScreen(
        title = stringResource(R.string.screen_s12),
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
            state.tab == LogTab.AUDIT -> {
                if (audit != null) auditItems(state = audit.state, onSelect = audit.onSelect, firstIndex = 2)
            }

            state.tab == LogTab.PAYLOAD && state.payloadUnavailable && !state.loading -> {
                item(key = "payload-missing") {
                    NjEmptyState(
                        title = stringResource(R.string.log_payload_title),
                        body = stringResource(R.string.log_payload_missing),
                        icon = NjIcons.Database,
                        action = {
                            NjTonalButton(
                                text = stringResource(R.string.log_refresh),
                                onClick = onRefresh,
                            )
                        },
                        modifier = Modifier.njEnter(2),
                    )
                }
            }

            else -> {
                logItems(
                    state = state,
                    visible = visible,
                    companionLabel = companionLabel,
                    onLevel = onLevel,
                    onQuery = onQuery,
                    onRefresh = onRefresh,
                    onCopy = { onCopy(visible) },
                    onExport = onExport,
                )
            }
        }
    }
}

/** The level chips, the search field, the actions and the log lines as one card group. */
private fun LazyListScope.logItems(
    state: LogsUiState,
    visible: List<LogLine>,
    companionLabel: String,
    onLevel: (LogLevel, Boolean) -> Unit,
    onQuery: (String) -> Unit,
    onRefresh: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
) {
    item(key = "levels") {
        NjFilterChips(
            options = LogLevel.entries.map { NjOption(it, levelLabel(it)) },
            selected = state.levels,
            onToggle = { level -> onLevel(level, level !in state.levels) },
            modifier = Modifier.toolCard().njEnter(2),
        )
    }
    item(key = "search") {
        NjSearchField(
            value = state.query,
            onValueChange = onQuery,
            placeholder = stringResource(R.string.log_search),
            modifier = Modifier.toolCard().njEnter(3),
        )
    }
    item(key = "actions") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = NjSpacing.m + NjSpacing.xs, end = NjSpacing.xs, top = NjSpacing.xs)
                .njEnter(4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NjCaption(
                text = pluralStringResource(R.plurals.log_lines, visible.size, visible.size),
                modifier = Modifier.weight(1f),
            )
            NjTextButton(text = stringResource(R.string.log_refresh), onClick = onRefresh)
            NjTextButton(text = stringResource(R.string.log_copy_all), onClick = onCopy)
            NjTextButton(text = stringResource(R.string.log_export), onClick = onExport)
        }
    }
    when {
        state.companionNeedsRoot -> {
            item(key = "companion-root") {
                NjEmptyState(
                    title = companionLabel,
                    body = stringResource(R.string.log_companion_root),
                    icon = NjIcons.Lock,
                    action = { NjTonalButton(text = stringResource(R.string.log_refresh), onClick = onRefresh) },
                    modifier = Modifier.njEnter(5),
                )
            }
        }

        state.loading && state.lines.isEmpty() -> {
            toolLoading(3)
        }

        visible.isEmpty() -> {
            item(key = "log-empty") {
                NjEmptyState(
                    title = stringResource(R.string.screen_s12),
                    body = stringResource(if (state.lines.isEmpty()) R.string.log_empty_file else R.string.log_empty),
                    action = { NjTonalButton(text = stringResource(R.string.log_refresh), onClick = onRefresh) },
                    modifier = Modifier.njEnter(5),
                )
            }
        }

        else -> {
            // One list item for each line, so a long log stays lazy. The lines share one card, without dividers.
            items(visible.size, contentType = { "log-line" }) { index ->
                val position = ToolRowPosition.of(index, visible.size)
                ToolGroupRow(position = position, divider = false) {
                    LogRow(line = visible[index], position = position)
                }
            }
        }
    }
}

/** One compact monospace line: time, coloured level letter, tag, message. */
@Composable
private fun LogRow(
    line: LogLine,
    position: ToolRowPosition = ToolRowPosition.MIDDLE,
) {
    val roles = NjTheme.roles
    val muted = roles.mu
    val levelColor: Color =
        when (line.level) {
            LogLevel.E -> roles.sus
            LogLevel.W -> roles.amb
            LogLevel.I -> roles.ok
            else -> muted
        }
    val text =
        buildAnnotatedString {
            val time = line.at.substringAfter('T', line.at).take(TIME_CHARS)
            if (time.isNotEmpty()) {
                withStyle(SpanStyle(color = muted)) {
                    append(time)
                    append(' ')
                }
            }
            line.level?.let { level ->
                withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Bold)) {
                    append(level.letter)
                    append(' ')
                }
            }
            if (line.tag.isNotEmpty()) {
                withStyle(SpanStyle(color = muted)) {
                    append(line.tag)
                    append(' ')
                }
            }
            append(line.message)
        }
    // The first and the last line get more space, so the text does not touch the card corners.
    val top = if (position == ToolRowPosition.FIRST || position == ToolRowPosition.ONLY) NjSpacing.s + 2.dp else 3.dp
    val bottom = if (position == ToolRowPosition.LAST || position == ToolRowPosition.ONLY) NjSpacing.s + 2.dp else 3.dp
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = roles.tx,
        modifier = Modifier.fillMaxWidth().padding(start = NjSpacing.m - 4.dp, end = NjSpacing.m - 4.dp, top = top, bottom = bottom),
    )
}

private const val TIME_CHARS = 12

@Composable
private fun tabLabel(tab: LogTab): String =
    stringResource(
        when (tab) {
            LogTab.PAYLOAD -> R.string.log_tab_payload
            LogTab.APP -> R.string.log_tab_app
            LogTab.COMPANION -> R.string.log_tab_companion
            LogTab.AUDIT -> R.string.log_tab_audit
        },
    )

// ------------------------------------------------------------------ previews

private val PreviewLogsState =
    LogsUiState(
        loading = false,
        lines =
            listOf(
                LogLine(
                    at = "2026-11-05T12:00:00.000+00:00",
                    level = LogLevel.I,
                    tag = "ingest",
                    message = "batch committed count=3",
                    raw = "2026-11-05T12:00:00.000+00:00  I  ingest  batch committed count=3",
                ),
                LogLine(
                    at = "2026-11-05T12:00:01.000+00:00",
                    level = LogLevel.W,
                    tag = "ipc",
                    message = "session_stale age_s=40",
                    raw = "2026-11-05T12:00:01.000+00:00  W  ipc  session_stale age_s=40",
                ),
                LogLine(
                    at = "2026-11-05T12:00:02.000+00:00",
                    level = LogLevel.E,
                    tag = "rules", // contract-ok: preview log tag, not an IPC key
                    message = "snapshot_push_failed reason=timeout",
                    raw = "2026-11-05T12:00:02.000+00:00  E  rules  snapshot_push_failed reason=timeout",
                ),
            ),
    )

@Composable
private fun PreviewLogs(state: LogsUiState) {
    LogsContent(
        state = state,
        audit = null,
        snackbar = remember { SnackbarHostState() },
        onBack = {},
        onTab = {},
        onLevel = { _, _ -> },
        onQuery = {},
        onRefresh = {},
        onCopy = {},
        onExport = {},
    )
}

@Preview(name = "Logs dark", widthDp = 400, heightDp = 700)
@Composable
private fun LogsDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewLogs(PreviewLogsState) }
}

@Preview(name = "Logs light", widthDp = 400, heightDp = 700)
@Composable
private fun LogsLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { PreviewLogs(PreviewLogsState) }
}

@Preview(name = "Logs empty dark", widthDp = 400, heightDp = 640)
@Composable
private fun LogsEmptyDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewLogs(LogsUiState(loading = false)) }
}

/** The level chip text: the full level name, so TalkBack reads "Warnings", not "W". */
@Composable
private fun levelLabel(level: LogLevel): String =
    stringResource(
        when (level) {
            LogLevel.E -> R.string.log_level_errors
            LogLevel.W -> R.string.log_level_warnings
            LogLevel.I -> R.string.log_level_info
            LogLevel.D -> R.string.log_level_debug
        },
    )
