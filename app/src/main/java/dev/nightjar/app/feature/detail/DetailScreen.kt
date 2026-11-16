package dev.nightjar.app.feature.detail

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.domain.ClassFamily
import dev.nightjar.app.domain.PrimaryAction
import dev.nightjar.app.domain.text.Templates
import dev.nightjar.app.ui.components.LocalNjBottomInset
import dev.nightjar.app.ui.components.NjAction
import dev.nightjar.app.ui.components.NjBadge
import dev.nightjar.app.ui.components.NjCaption
import dev.nightjar.app.ui.components.NjCard
import dev.nightjar.app.ui.components.NjCollapsedTitle
import dev.nightjar.app.ui.components.NjDialog
import dev.nightjar.app.ui.components.NjEmptyState
import dev.nightjar.app.ui.components.NjGroup
import dev.nightjar.app.ui.components.NjIcons
import dev.nightjar.app.ui.components.NjLineIcons
import dev.nightjar.app.ui.components.NjListRow
import dev.nightjar.app.ui.components.NjMenuItem
import dev.nightjar.app.ui.components.NjOption
import dev.nightjar.app.ui.components.NjOverflowMenu
import dev.nightjar.app.ui.components.NjRowDivider
import dev.nightjar.app.ui.components.NjSectionHeader
import dev.nightjar.app.ui.components.NjSegmented
import dev.nightjar.app.ui.components.NjTextButton
import dev.nightjar.app.ui.components.NjTopBarHeight
import dev.nightjar.app.ui.components.NjTypeIcon
import dev.nightjar.app.ui.components.SkeletonRow
import dev.nightjar.app.ui.components.TraceList
import dev.nightjar.app.ui.components.TraceRow
import dev.nightjar.app.ui.components.njActionColor
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.components.rememberNjTitleCollapsed
import dev.nightjar.app.ui.components.severityColor
import dev.nightjar.app.ui.mode.LocalIsResearcher
import dev.nightjar.app.ui.theme.NjRoles
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme
import dev.nightjar.contract.AnomalyCode
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MessageDetailScreen(
    messageId: Long,
    onBack: () -> Unit,
    onOpenMessage: (Long) -> Unit,
    onOpenRule: (Long) -> Unit,
    onExport: () -> Unit,
    // One ViewModel per host entry: the two-pane detail switches it with open(), so a new selection does not leave the
    // flows of the old one running until Messages is popped.
    viewModel: DetailViewModel = hiltViewModel(key = "detail"),
) {
    LaunchedEffect(messageId) { viewModel.open(messageId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    // Snackbars get their own coroutine: `showSnackbar` suspends until it is dismissed, and the collector used to
    // wait for it, so a linked record tapped behind a snackbar was queued for seconds.
    val scope = rememberCoroutineScope()
    // Back and the post-delete timer share one exit, so a user Back inside the 900 ms window is not popped again
    // and does not take the Messages screen with it.
    var popped by remember(messageId) { mutableStateOf(false) }
    val exitOnce: () -> Unit =
        remember(onBack, messageId) {
            {
                if (!popped) {
                    popped = true
                    onBack()
                }
            }
        }
    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is DetailEvent.Snack -> {
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(event.text)
                    }
                }

                is DetailEvent.SnackUndo -> {
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        val result =
                            snackbar.showSnackbar(
                                message = event.text,
                                actionLabel = resources.getString(R.string.action_undo),
                            )
                        if (result == SnackbarResult.ActionPerformed) {
                            viewModel.onAction(DetailAction.UndoMarkExpected(event.knownOriginatorId))
                        }
                    }
                }

                is DetailEvent.Copy -> {
                    copy(context, event.text)
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(resources.getString(R.string.detail_shared))
                    }
                }

                is DetailEvent.OpenMessage -> {
                    onOpenMessage(event.messageId)
                }

                is DetailEvent.OpenRule -> {
                    onOpenRule(event.messageId)
                }

                DetailEvent.Deleted -> {
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(resources.getString(R.string.detail_deleted))
                        delay(900)
                        exitOnce()
                    }
                }
            }
        }
    }
    // A message purged by retention, deleted from another screen or a stale notification id: say so and leave
    // instead of showing a blank Detail.
    val deletedElsewhere = state.deleted && !state.deletedHere
    LaunchedEffect(deletedElsewhere) {
        if (!deletedElsewhere) return@LaunchedEffect
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(resources.getString(R.string.snk_09))
        }
        delay(900)
        exitOnce()
    }
    DetailContent(
        state = state,
        researcher = LocalIsResearcher,
        snackbar = snackbar,
        onBack = exitOnce,
        onAction = viewModel::onAction,
        onOpenMessage = onOpenMessage,
        onExport = onExport,
    )
}

@Composable
fun DetailContent(
    state: DetailUiState,
    researcher: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onAction: (DetailAction) -> Unit,
    onOpenMessage: (Long) -> Unit,
    onExport: () -> Unit,
) {
    val view = state.view

    // "2 s visible", not "2 s composed": the timer starts when the destination resumes and is dropped on pause, so
    // a detail behind the lock or another destination never marks the message reviewed.
    val timerScope = rememberCoroutineScope()
    LifecycleResumeEffect(view?.id) {
        val job =
            if (view != null) {
                timerScope.launch {
                    delay(2000)
                    onAction(DetailAction.AutoReview)
                }
            } else {
                null
            }
        onPauseOrDispose { job?.cancel() }
    }

    val listState = rememberLazyListState()
    val collapsed by rememberNjTitleCollapsed(listState)
    Box(Modifier.fillMaxSize().background(NjTheme.roles.bg)) {
        when {
            state.loading -> {
                // No status bar padding: the shell consumes safeDrawing, so that inset is zero here.
                Column(Modifier.padding(top = NjTopBarHeight)) { repeat(5) { SkeletonRow() } }
            }

            state.deleted || view == null -> {
                DeletedState()
            }

            else -> {
                DetailBody(
                    detail = view,
                    state = state,
                    researcher = researcher,
                    listState = listState,
                    onAction = onAction,
                    onOpenMessage = onOpenMessage,
                )
            }
        }
        // The small bar sits over the list. Its title fades in when the type name scrolls out of view.
        NjCollapsedTitle(
            title = view?.title ?: stringResource(R.string.screen_s05),
            collapsed = collapsed,
            modifier = Modifier.align(Alignment.TopStart),
            onBack = onBack,
            actions = {
                if (view != null) {
                    IconButton(onClick = { onAction(DetailAction.ToggleStar) }) {
                        Icon(
                            ActionIcons.Star,
                            contentDescription =
                                stringResource(if (view.isStarred) R.string.detail_unstar else R.string.detail_star),
                            tint = if (view.isStarred) NjTheme.roles.acc else NjTheme.roles.mu,
                        )
                    }
                    // The same menu component and look as every other top-right menu. The items do not change.
                    NjOverflowMenu(contentDescription = stringResource(R.string.action_more)) { close ->
                        NjMenuItem(
                            text = stringResource(R.string.detail_copy_text),
                            icon = NjLineIcons.Copy,
                            enabled = view.text != null,
                            onClick = {
                                close()
                                onAction(DetailAction.CopyText)
                            },
                        )
                        if (PrimaryAction.MARK_EXPECTED in listOf(view.primary, view.secondary)) {
                            NjMenuItem(
                                text = stringResource(R.string.detail_mark_expected),
                                icon = NjLineIcons.Check,
                                onClick = {
                                    close()
                                    onAction(DetailAction.MarkExpected)
                                },
                            )
                        }
                        if (PrimaryAction.TRUST_APP in listOf(view.primary, view.secondary)) {
                            NjMenuItem(
                                text = stringResource(R.string.detail_trust_app),
                                icon = NjLineIcons.TrustApp,
                                onClick = {
                                    close()
                                    onAction(DetailAction.TrustApp)
                                },
                            )
                        }
                        if (researcher) {
                            NjMenuItem(
                                text = stringResource(R.string.detail_copy_hex),
                                icon = NjLineIcons.Code,
                                onClick = {
                                    close()
                                    onAction(DetailAction.CopyHex)
                                },
                            )
                        }
                        NjMenuItem(
                            text = stringResource(R.string.detail_export),
                            icon = NjLineIcons.Export,
                            onClick = {
                                close()
                                onExport()
                            },
                        )
                        NjMenuItem(
                            text = stringResource(R.string.detail_mark_unreviewed),
                            icon = NjLineIcons.Unreviewed,
                            onClick = {
                                close()
                                onAction(DetailAction.MarkReviewed(false))
                            },
                        )
                    }
                }
            },
        )
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LocalNjBottomInset.current),
        )
    }

    if (state.showDeleteDialog && view != null) {
        NjDialog(
            title = pluralStringResource(R.plurals.dlg_09_title, 1, 1),
            text = stringResource(R.string.dlg_09_body),
            confirmText = stringResource(R.string.dlg_09_confirm),
            dismissText = stringResource(R.string.dlg_09_cancel),
            destructive = true,
            onDismiss = { onAction(DetailAction.CancelDelete) },
            onConfirm = { onAction(DetailAction.ConfirmDelete) },
        )
    }

    // Detail confirms a release before it hands the message back to the system. The Home and Messages rows and the
    // notification action release in one tap by design.
    if (state.showReleaseDialog) {
        NjDialog(
            title = stringResource(R.string.dlg_08_title),
            text = stringResource(R.string.dlg_08_body),
            confirmText = stringResource(R.string.dlg_08_confirm),
            dismissText = stringResource(R.string.dlg_08_cancel),
            onDismiss = { onAction(DetailAction.CancelRelease) },
            onConfirm = { onAction(DetailAction.ConfirmRelease) },
        )
    }

    // Trusting an app silences its background SMS, so the package is named before it is stored.
    val trustPackage = view?.packageName
    if (state.showTrustDialog && trustPackage != null) {
        NjDialog(
            title = Templates.format(stringResource(R.string.dlg_21_title), mapOf("app" to trustPackage)),
            text = stringResource(R.string.dlg_21_body),
            confirmText = stringResource(R.string.dlg_21_confirm),
            dismissText = stringResource(R.string.dlg_21_cancel),
            onDismiss = { onAction(DetailAction.CancelTrustApp) },
            onConfirm = { onAction(DetailAction.ConfirmTrustApp) },
        )
    }

    // Exact number or prefix; the snackbar after the write offers Undo.
    val expectedFor = if (state.showExpectedDialog) view else null
    if (expectedFor != null) {
        var prefix by remember { mutableStateOf(false) }
        NjDialog(
            title =
                Templates.format(
                    stringResource(R.string.dlg_22_title),
                    mapOf("number" to expectedFor.originatorText.orEmpty()),
                ),
            text = stringResource(R.string.dlg_22_body),
            confirmText = stringResource(R.string.dlg_22_confirm),
            dismissText = stringResource(R.string.dlg_22_cancel),
            onDismiss = { onAction(DetailAction.CancelMarkExpected) },
            onConfirm = { onAction(DetailAction.ConfirmMarkExpected(prefix)) },
        ) {
            NjSegmented(
                options =
                    listOf(
                        NjOption(false, stringResource(R.string.dlg_22_exact)),
                        NjOption(true, stringResource(R.string.dlg_22_prefix)),
                    ),
                selected = prefix,
                onSelect = { prefix = it },
            )
        }
    }
}

/** The "purged or never existed" body of Detail; the screen pops itself right after. */
@Composable
private fun DeletedState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        NjEmptyState(
            title = stringResource(R.string.snk_09),
            body = stringResource(R.string.detail_deleted_body),
        )
    }
}

/** The height of the small top bar. The first item of the list starts below it. */

@Composable
private fun DetailBody(
    detail: DetailView,
    state: DetailUiState,
    researcher: Boolean,
    listState: LazyListState,
    onAction: (DetailAction) -> Unit,
    onOpenMessage: (Long) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding =
            PaddingValues(
                start = NjSpacing.m,
                end = NjSpacing.m,
                // The shell already pads and consumes the status bar inset (NightjarShell), so only the bar height is
                // added here. `WindowInsets.statusBars` ignores that consumption and gave a double top gap.
                top = NjTopBarHeight,
                bottom = LocalNjBottomInset.current + NjSpacing.l,
            ),
        verticalArrangement = Arrangement.spacedBy(NjSpacing.m),
    ) {
        item(key = "header") { Header(detail, Modifier.njEnter(0)) }
        if (detail.explanation.isNotBlank()) {
            item(key = "what") { ExplanationCard(detail.explanation, Modifier.njEnter(1)) }
        }
        detail.text?.takeIf { it.isNotBlank() }?.let { text ->
            item(key = "text") { MessageTextCard(text, Modifier.njEnter(1)) }
        }
        // No Evidence card when no line has a real value.
        if (hasEvidence(detail.evidence, detail.formatErrors, detail.anomalies, detail.extraMatches)) {
            item(key = "evidence") { EvidenceCard(detail, researcher, Modifier.njEnter(2)) }
        }
        if (detail.detections.isNotEmpty()) {
            item(key = "detections") {
                Column(Modifier.njEnter(3)) {
                    // The reasons explain a Suspicious or High message. A lower one keeps the neutral title.
                    val why =
                        if (detail.severity.ordinal >= Severity.SUSPICIOUS.ordinal) R.string.pat_detail_why else R.string.detail_detection
                    NjSectionHeader(stringResource(why))
                    Spacer(Modifier.height(NjSpacing.s))
                    NjGroup {
                        detail.detections.forEachIndexed { index, summary ->
                            if (index > 0) NjRowDivider()
                            NjListRow(title = summary, leadingIcon = NjIcons.Warning)
                        }
                    }
                }
            }
        }
        item(key = "timeline") { TimelineCard(detail.timeline, onOpenMessage, Modifier.njEnter(3)) }
        if (detail.parts.size > 1) {
            item(key = "parts") { PartsCard(detail, Modifier.njEnter(4)) }
        }
        // Simple mode has no Raw PDU card. Researcher mode shows it, collapsed until the user taps Show.
        if (researcher) {
            item(key = "raw") {
                RawCard(
                    detail = detail,
                    expanded = state.technicalExpanded,
                    selected = state.selectedField,
                    onAction = onAction,
                    modifier = Modifier.njEnter(4),
                )
            }
        }
        item(key = "actions") { Actions(detail, onAction, Modifier.njEnter(5)) }
        if (detail.links.isNotEmpty() || detail.siblings.isNotEmpty()) {
            item(key = "linked") {
                Column(Modifier.njEnter(6), verticalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
                    LinkedRecords(detail, onOpenMessage)
                }
            }
        }
    }
}

/** The type icon, the type name, the badges (severity, action, SIM) and the sender line. */
@Composable
private fun Header(
    view: DetailView,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NjSpacing.s),
    ) {
        NjTypeIcon(view.family, size = 72.dp, tint = severityColor(roles, view.severity), decorative = true)
        Text(
            view.title,
            style = MaterialTheme.typography.headlineSmall,
            color = roles.tx,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(NjSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            NjBadge(view.severity)
            view.actionBadge?.let { action ->
                NjBadge(text = stringResource(actionBadgeLabel(action)), color = njActionColor(roles, action))
            }
            view.simLabel?.let { NjBadge(text = it, color = roles.mu) }
            if (view.debugTest) NjBadge(text = stringResource(R.string.detail_debug_test), color = roles.amb)
        }
        if (view.senderLine.isNotBlank()) {
            Text(view.senderLine, style = MaterialTheme.typography.bodyMedium, color = roles.mu, textAlign = TextAlign.Center)
        }
    }
}

/** The header badge text. The badge says "On hold"; the Allow / Hold / Block control has other words. */
private fun actionBadgeLabel(action: NjAction): Int =
    when (action) {
        NjAction.HOLD -> R.string.detail_on_hold
        NjAction.BLOCK -> R.string.detail_x_badge_blocked
        NjAction.ALLOW -> R.string.detail_x_badge_allowed
    }

@Composable
private fun ExplanationCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    NjCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(NjSpacing.xs)) {
            Text(stringResource(R.string.detail_what_is_this), style = MaterialTheme.typography.titleMedium, color = NjTheme.roles.tx)
            Text(text, style = MaterialTheme.typography.bodyLarge, color = NjTheme.roles.mu)
        }
    }
}

/** The text of the message. It is selectable, so the user can copy it without the menu. */
@Composable
private fun MessageTextCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    NjCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(NjSpacing.xs)) {
            Text(stringResource(R.string.detail_message_text), style = MaterialTheme.typography.titleMedium, color = NjTheme.roles.tx)
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = NjTheme.roles.tx)
            }
        }
    }
}

/**
 * The Evidence card: the decoded facts with a real value, then the format errors, then the anomaly rows, then the other
 * types that match. Researcher mode adds the wire code after each anomaly name and each other type.
 */
@Composable
private fun EvidenceCard(
    view: DetailView,
    researcher: Boolean,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    NjCard(modifier) {
        Text(stringResource(R.string.detail_evidence), style = MaterialTheme.typography.titleMedium, color = roles.tx)
        factsWithValue(view.evidence).forEach { fact ->
            NjRowDivider()
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = NjSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(NjSpacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fact.label, style = MaterialTheme.typography.bodyMedium, color = roles.mu, modifier = Modifier.weight(1f))
                SelectionContainer(Modifier.weight(1f, fill = false)) {
                    Text(
                        fact.value,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = roles.tx,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        // No "No format errors found" line. A real format error still shows.
        if (view.formatErrors.isNotEmpty()) {
            Spacer(Modifier.height(NjSpacing.s))
            view.formatErrors.forEach { EvidenceNote(NjIcons.Warning, roles.hi, it) }
        }
        if (view.anomalies.isNotEmpty()) {
            Spacer(Modifier.height(NjSpacing.s))
            Text(stringResource(R.string.detail_x_anomalies), style = MaterialTheme.typography.titleSmall, color = roles.tx)
            view.anomalies.forEach {
                EvidenceNote(NjIcons.Warning, severityColor(roles, it.severity), withCode(it.text, it.code, researcher))
            }
        }
        if (view.extraMatches.isNotEmpty()) {
            Spacer(Modifier.height(NjSpacing.s))
            Text(stringResource(R.string.detail_x_extra_subtypes), style = MaterialTheme.typography.titleSmall, color = roles.tx)
            view.extraMatches.forEach { EvidenceNote(NjIcons.Info, roles.mu, withCode(it.text, it.code, researcher)) }
        }
    }
}

/** Researcher mode shows the wire code after the plain words, in the same " · " form as the PID and DCS rows. */
private fun withCode(
    text: String,
    code: String,
    researcher: Boolean,
): String = if (researcher && code.isNotBlank() && code != text) "$text · $code" else text

/** One line of the Evidence card: a small icon in a colour, then text. */
@Composable
private fun EvidenceNote(
    icon: ImageVector,
    color: Color,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = NjSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(NjSpacing.s + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = NjTheme.roles.tx)
    }
}

/**
 * The "What happened" timeline: one dot, one text and one time for each step. A step with a linked message (a delivery report, or
 * the sent message of a report) opens that message when the user taps it.
 */
@Composable
private fun TimelineCard(
    steps: List<TimelineStep>,
    onOpenMessage: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    NjCard(modifier) {
        Text(stringResource(R.string.detail_timeline_title), style = MaterialTheme.typography.titleMedium, color = roles.tx)
        Spacer(Modifier.height(NjSpacing.s))
        steps.forEachIndexed { index, step ->
            val last = index == steps.lastIndex
            val link = step.linkId
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .then(
                            if (link != null) {
                                Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { onOpenMessage(link) }
                            } else {
                                Modifier
                            },
                        ),
                horizontalArrangement = Arrangement.spacedBy(NjSpacing.s + 4.dp),
            ) {
                Column(Modifier.fillMaxHeight().width(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(toneColor(roles, step.tone)),
                    )
                    if (!last) Box(Modifier.width(2.dp).weight(1f).background(roles.line))
                }
                Row(
                    modifier = Modifier.weight(1f).padding(bottom = if (last) 0.dp else NjSpacing.s + 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(NjSpacing.s),
                ) {
                    Text(
                        step.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = roles.tx,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        step.time,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = roles.mu,
                    )
                    // A step that opens a linked message shows the chevron of a list row.
                    if (link != null) {
                        Icon(NjIcons.Chevron, contentDescription = null, tint = roles.mu, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

private fun toneColor(
    roles: NjRoles,
    tone: TimelineTone,
): Color =
    when (tone) {
        TimelineTone.INFO -> roles.acc
        TimelineTone.HOLD -> roles.amb
        TimelineTone.BLOCK, TimelineTone.FAIL -> roles.hi
        TimelineTone.OK -> roles.ok
    }

@Composable
private fun PartsCard(
    view: DetailView,
    modifier: Modifier = Modifier,
) {
    NjCard(modifier) {
        Text(stringResource(R.string.detail_parts), style = MaterialTheme.typography.titleMedium, color = NjTheme.roles.tx)
        Spacer(Modifier.height(NjSpacing.xs))
        view.parts.forEach { part ->
            Text(
                stringResource(R.string.detail_part_arrival, part.index, part.at),
                style = MaterialTheme.typography.bodySmall,
                color = NjTheme.roles.mu,
            )
        }
        val purge = view.purgeText
        when {
            // The plural owns the "Waiting for n parts" sentence and its purge date.
            view.partsWaiting > 0 && purge != null -> {
                NjCaption(pluralStringResource(R.plurals.msgs_parts_waiting, view.partsWaiting, view.partsWaiting, purge))
            }

            purge != null -> {
                NjCaption(stringResource(R.string.detail_purge, purge))
            }
        }
    }
}

/** The Raw PDU card (Researcher mode): hex bytes, decoded fields and the pipeline trace, behind Show / Hide. */
@Composable
private fun RawCard(
    detail: DetailView,
    expanded: Boolean,
    selected: DecodedNode?,
    onAction: (DetailAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    NjCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.detail_raw_pdu), style = MaterialTheme.typography.titleMedium, color = roles.tx)
                Text(stringResource(R.string.detail_raw_pdu_sub), style = MaterialTheme.typography.bodyMedium, color = roles.mu)
            }
            NjTextButton(
                text = stringResource(if (expanded) R.string.detail_x_raw_hide else R.string.detail_raw_show),
                onClick = { onAction(DetailAction.ToggleTechnical) },
            )
        }
        if (expanded) {
            Column(Modifier.padding(top = NjSpacing.s), verticalArrangement = Arrangement.spacedBy(NjSpacing.m)) {
                NjCaption(stringResource(R.string.detail_select_hint))
                LinkedHexView(
                    bytes = detail.raw,
                    selected = selected,
                    onByte = { offset -> onAction(DetailAction.SelectField(deepestNodeAt(detail.decoded, offset))) },
                )
                NjSectionHeader(stringResource(R.string.detail_decoded))
                DecodedTree(
                    nodes = detail.decoded,
                    selected = selected,
                    onSelect = { node -> onAction(DetailAction.SelectField(node)) },
                )
                NjSectionHeader(stringResource(R.string.detail_trace_title))
                TraceList(detail.trace)
            }
        }
    }
}

/** Release, Block similar and Delete as equal tiles. [DetailView.buttons] says which of them show. */
@Composable
private fun Actions(
    view: DetailView,
    onAction: (DetailAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
            view.buttons.forEach { button ->
                when (button) {
                    DetailButton.RELEASE -> {
                        ActionTile(
                            label = stringResource(R.string.detail_release),
                            icon = DetailIcons.Release,
                            container = NjTheme.roles.acc,
                            content = NjTheme.roles.bg,
                            onClick = { onAction(DetailAction.Release) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }

                    DetailButton.BLOCK_SIMILAR -> {
                        ActionTile(
                            label = stringResource(R.string.detail_block_similar),
                            icon = DetailIcons.Block,
                            container = NjTheme.roles.s1,
                            content = NjTheme.roles.hi,
                            onClick = { onAction(DetailAction.OpenRule) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }

                    DetailButton.DELETE -> {
                        ActionTile(
                            label = stringResource(R.string.detail_delete),
                            icon = NjIcons.Delete,
                            container = NjTheme.roles.s1,
                            content = NjTheme.roles.tx,
                            onClick = { onAction(DetailAction.AskDelete) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionTile(
    label: String,
    icon: ImageVector,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 76.dp), shape = NjShape.card, color = container) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = NjSpacing.s, vertical = NjSpacing.m),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(NjSpacing.xs))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = content,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun LinkedRecords(
    view: DetailView,
    onOpenMessage: (Long) -> Unit,
) {
    if (view.links.isNotEmpty()) {
        NjSectionHeader(stringResource(R.string.detail_links))
        NjGroup {
            view.links.forEachIndexed { index, link ->
                if (index > 0) NjRowDivider()
                LinkedRowItem(link, onOpenMessage)
            }
        }
    }
    if (view.siblings.isNotEmpty()) {
        NjSectionHeader(stringResource(R.string.detail_siblings))
        NjGroup {
            view.siblings.forEachIndexed { index, link ->
                if (index > 0) NjRowDivider()
                LinkedRowItem(link, onOpenMessage)
            }
        }
    }
}

@Composable
private fun LinkedRowItem(
    row: LinkedRow,
    onOpenMessage: (Long) -> Unit,
) {
    NjListRow(title = row.title, supporting = row.caption, onClick = { onOpenMessage(row.id) })
}

/** Bytes are labelled so TalkBack reads the offset rather than a wall of hex. */
@Composable
private fun LinkedHexView(
    bytes: ByteArray,
    selected: DecodedNode?,
    onByte: (Int) -> Unit,
) {
    if (bytes.isEmpty()) return
    val scroll = rememberScrollState()
    val byteFormat = stringResource(R.string.detail_hex_byte)
    val byteDescription = { offsetText: String, byteText: String -> byteFormat.format(offsetText, byteText) }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(NjShape.card)
                .background(NjTheme.roles.s2)
                .padding(horizontal = NjSpacing.s, vertical = NjSpacing.s)
                .horizontalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + 8, bytes.size)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%04X".format(offset),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.width(48.dp),
                )
                for (i in offset until end) {
                    val inRange = selected != null && i >= selected.offset && i < selected.offset + selected.length
                    Box(
                        modifier =
                            Modifier
                                // 48 dp is the minimum touch target; the glyph stays 12 sp in the centre.
                                .size(48.dp)
                                .background(if (inRange) NjTheme.roles.acc.copy(alpha = SELECTED_ALPHA) else Color.Transparent)
                                .clickable(role = Role.Button) { onByte(i) }
                                .semantics {
                                    contentDescription = byteDescription("%04X".format(i), "%02X".format(bytes[i]))
                                },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "%02X".format(bytes[i]),
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
            offset = end
        }
    }
}

@Composable
private fun DecodedTree(
    nodes: List<DecodedNode>,
    selected: DecodedNode?,
    onSelect: (DecodedNode) -> Unit,
) {
    if (nodes.isEmpty()) return
    NjCard(padding = NjSpacing.s) {
        nodes.forEachIndexed { index, node ->
            val isGroup = node.depth == 0 && node.value == null
            if (index > 0 && node.depth == 0) NjRowDivider(Modifier.padding(vertical = NjSpacing.xs))
            val isSelected = selected === node
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(NjShape.chip)
                        .background(if (isSelected) NjTheme.roles.acc.copy(alpha = SELECTED_ALPHA) else Color.Transparent)
                        // Every field is a tap target, so it reaches 48 dp.
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onSelect(node) }
                        .padding(start = (node.depth * 16).dp + NjSpacing.s, end = NjSpacing.s, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = prettyName(node.name),
                        style =
                            if (isGroup) {
                                MaterialTheme.typography.titleSmall
                            } else {
                                MaterialTheme.typography.labelMedium
                            },
                        color =
                            if (node.error != null) {
                                NjTheme.roles.hi
                            } else if (isGroup) {
                                NjTheme.roles.tx
                            } else {
                                NjTheme.roles.mu
                            },
                    )
                    node.value?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = NjTheme.roles.tx,
                        )
                    }
                    node.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = NjTheme.roles.hi)
                    }
                }
                if (node.length > 0) {
                    Spacer(Modifier.width(NjSpacing.s))
                    Text(
                        text =
                            if (node.length == 1) "${node.offset}" else "${node.offset}\u2013${node.offset + node.length - 1}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = NjTheme.roles.mu,
                    )
                }
            }
        }
    }
}

/** The fill of a selected byte or field: the accent, light enough for the text on it. */
private const val SELECTED_ALPHA = 0.24f

/** `user_data_header` / `userDataHeader` -> "User data header". */
private fun prettyName(name: String): String {
    val spaced = name.replace('_', ' ').replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").trim()
    return spaced.replaceFirstChar { it.uppercase() }
}

private fun copy(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val clip = ClipData.newPlainText(context.getString(R.string.app_name), text)
    // Message text and hex are the user's private data: keep them out of the Android 13+ clipboard preview and
    // keyboard suggestions.
    clip.description.extras =
        PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    clipboard.setPrimaryClip(clip)
}

/**
 * Action glyphs bundled with the screen: a text arrow and a vertical ellipsis are read as punctuation by
 * TalkBack and do not mirror in RTL. The star is not a severity mark, so it is drawn here instead of borrowing
 * [dev.nightjar.app.ui.theme.SeverityTokens]. The overflow menu uses [NjOverflowMenu].
 */
private object ActionIcons {
    val Star: ImageVector by lazy {
        icon("Star") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 17.27f)
                lineTo(18.18f, 21f)
                lineTo(16.54f, 13.97f)
                lineTo(22f, 9.24f)
                lineTo(14.81f, 8.63f)
                lineTo(12f, 2f)
                lineTo(9.19f, 8.63f)
                lineTo(2f, 9.24f)
                lineTo(7.46f, 13.97f)
                lineTo(5.82f, 21f)
                close()
            }
        }
    }

    private fun icon(
        name: String,
        autoMirror: Boolean = false,
        block: ImageVector.Builder.() -> Unit,
    ): ImageVector =
        ImageVector
            .Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
                autoMirror = autoMirror,
            ).apply(block)
            .build()
}

// ------------------------------------------------------------------ previews

@Preview(name = "detail light, on hold", heightDp = 1100)
@Composable
private fun DetailLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) {
        DetailContent(
            state = DetailUiState(loading = false, view = previewView()),
            researcher = false,
            snackbar = SnackbarHostState(),
            onBack = {},
            onAction = {},
            onOpenMessage = {},
            onExport = {},
        )
    }
}

@Preview(
    name = "detail dark, on hold, researcher",
    heightDp = 1100,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DetailDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        DetailContent(
            state = DetailUiState(loading = false, view = previewView()),
            researcher = true,
            snackbar = SnackbarHostState(),
            onBack = {},
            onAction = {},
            onOpenMessage = {},
            onExport = {},
        )
    }
}

@Preview(name = "detail dark, blocked sent message", heightDp = 1100, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DetailDarkSentPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        DetailContent(
            state = DetailUiState(loading = false, view = previewSentView()),
            researcher = false,
            snackbar = SnackbarHostState(),
            onBack = {},
            onAction = {},
            onOpenMessage = {},
            onExport = {},
        )
    }
}

private fun previewView(): DetailView =
    DetailView(
        id = 1,
        eventId = "e1",
        title = "Malformed WAP push: WSP header length is wrong",
        classId = ClassId.WAP_UNREADABLE,
        severity = Severity.HIGH,
        isQuarantined = false,
        isStarred = false,
        isReviewed = true,
        explanation =
            "NightJar cannot read this push message, because its header is wrong: WSP header length is wrong. " +
                "Attackers can use damaged push messages to crash a phone. By default, NightJar blocks this type of message.",
        detections = emptyList(),
        facts = emptyList(),
        fateText = "NightJar blocked it",
        // Synthetic sender +10000004821 (TP-OA BCD 01 00 00 40 28 F1), never a real number.
        raw = byteArrayOf(0x00, 0x04, 0x0B, 0x91.toByte(), 0x01, 0x00, 0x00, 0x40, 0x28, 0xF1.toByte()),
        hex = "00040B910100004028F1",
        decoded =
            listOf(
                DecodedNode("tpdu", null, 0, 10, 0, null),
                DecodedNode("mt", "1", 0, 1, 1, null),
            ),
        trace = listOf(TraceRow("radio", "+0.000 ms", "newSms")),
        parts = emptyList(),
        partsWaiting = 0,
        purgeText = null,
        links = emptyList(),
        siblings = emptyList(),
        primary = PrimaryAction.BLOCK_LIKE_THIS,
        secondary = PrimaryAction.MARK_EXPECTED,
        canRelease = false,
        originator = "+10000004821",
        originatorText = "+1…821",
        packageName = null,
        text = null,
        family = ClassFamily.WAP,
        simLabel = "SIM 1",
        actionBadge = NjAction.BLOCK,
        senderLine = "From +1…821 · Fri 13 Nov, 21:14",
        evidence =
            listOf(
                DetailFact("Protocol ID (TP-PID)", "0x00 · Normal", mono = true),
                DetailFact("Data coding (TP-DCS)", "0x04 · 8-bit data", mono = true),
                DetailFact("User data length", "140", mono = true),
                DetailFact("Destination port", "2948 · WAP push", mono = true),
                DetailFact("Service centre", "+1…001", mono = true),
            ),
        anomalies = listOf(AnomalyRow("WSP header length is wrong", Severity.HIGH, AnomalyCode.WSP_HLEN.wire())),
        extraMatches = emptyList(),
        timeline =
            listOf(
                TimelineStep("Phone service received it", "21:14:03", TimelineTone.INFO),
                TimelineStep("Classified as Malformed WAP push", "21:14:03", TimelineTone.INFO),
                TimelineStep("NightJar blocked it", "21:14:03", TimelineTone.BLOCK),
            ),
        buttons = detailButtonsFor(isQuarantined = false, releasable = false, direction = Direction.IN, fate = Fate.BLOCKED),
    )

/** A sent message with two delivery reports. A sent message has no action badge and no Block similar tile. */
private fun previewSentView(): DetailView =
    previewView().copy(
        title = "Sent message",
        classId = ClassId.NORMAL,
        severity = Severity.INFO,
        isQuarantined = false,
        explanation = "You sent this message.",
        fateText = "Sent",
        anomalies = emptyList(),
        evidence = listOf(DetailFact("Service centre", "+1…001", mono = true)),
        family = ClassFamily.OUTBOUND,
        actionBadge = null,
        senderLine = "Fri 13 Nov, 21:20",
        timeline =
            listOf(
                TimelineStep("Phone service sent it", "21:20:01", TimelineTone.INFO),
                TimelineStep("Delivery report: pending", "21:20:02", TimelineTone.INFO, linkId = 2),
                TimelineStep("Delivery report: delivered", "21:20:09", TimelineTone.OK, linkId = 3),
            ),
        buttons = detailButtonsFor(isQuarantined = false, releasable = false, direction = Direction.OUT, fate = Fate.SENT),
    )
