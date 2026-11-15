package dev.nightjar.app.feature.messages

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import dev.nightjar.app.R
import dev.nightjar.app.core.security.NumberDisplay
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.data.model.FeedChip
import dev.nightjar.app.data.model.MessageListItem
import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.feature.detail.DetailIcons
import dev.nightjar.app.feature.detail.releaseFailureRes
import dev.nightjar.app.feature.rules.showLatestSnackbar
import dev.nightjar.app.ui.components.LocalNjBottomInset
import dev.nightjar.app.ui.components.NjCaption
import dev.nightjar.app.ui.components.NjCollapsedTitle
import dev.nightjar.app.ui.components.NjDialog
import dev.nightjar.app.ui.components.NjEmptyState
import dev.nightjar.app.ui.components.NjFilterChips
import dev.nightjar.app.ui.components.NjIcons
import dev.nightjar.app.ui.components.NjLargeTitle
import dev.nightjar.app.ui.components.NjLineIcons
import dev.nightjar.app.ui.components.NjOption
import dev.nightjar.app.ui.components.NjSearchField
import dev.nightjar.app.ui.components.NjShimmerCard
import dev.nightjar.app.ui.components.NjSwipeRow
import dev.nightjar.app.ui.components.NjTonalButton
import dev.nightjar.app.ui.components.NjTopBarHeight
import dev.nightjar.app.ui.components.currentNjMotion
import dev.nightjar.app.ui.components.rememberNjTitleCollapsed
import dev.nightjar.app.ui.components.typeIconFor
import dev.nightjar.app.ui.text.LocalStrings
import dev.nightjar.app.ui.theme.LocalNjMotionPrefs
import dev.nightjar.app.ui.theme.NjMotion
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme
import dev.nightjar.app.ui.theme.SeverityTokens
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Verdict
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The chip key of "Delivery reports". The other chips use the wire name of their [FeedChip]. */
private const val CHIP_REPORTS = "REPORTS"

/** Corner radius of a day group (redesign 7.2: list group 22 dp). */
internal val GroupRadius = 22.dp

/**
 * The filter sheet is presented here, over the feed, not as a navigation destination.
 *
 * Home opens this screen with a filter in two ways: `FeedFilterController.openEntry(family, epochDay)` before it
 * navigates, or the route argument `Route.Messages(filter = "family:blocked,day:20400")` (see [parseEntryArgument]).
 */
@Composable
fun MessagesScreen(
    onOpenMessage: (Long) -> Unit,
    onOpenRule: (Long) -> Unit,
    onExport: () -> Unit,
    focusSearch: Boolean = false,
    viewModel: MessagesViewModel = hiltViewModel(),
    /** The detail of a message in the two-pane layout. `onClose` closes the pane (its Back button). */
    detailPane: @Composable (messageId: Long, onClose: () -> Unit) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val items = viewModel.feed.collectAsLazyPagingItems()
    val heldItems = viewModel.held.collectAsLazyPagingItems()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var showFilter by remember { mutableStateOf(false) }
    // The Home search button asks for the focus in the search field one time. It is not asked again after the
    // user comes back from a message.
    var searchFocusPending by rememberSaveable { mutableStateOf(focusSearch) }
    // The texts are read when an event comes, so a change of language while the screen is open shows the new text.
    val texts by rememberUpdatedState(
        mapOf(
            MessagesMessage.REVIEWED to stringResource(R.string.snk_01),
            MessagesMessage.UNREVIEWED to stringResource(R.string.snk_01_unreviewed),
            MessagesMessage.MUTED to stringResource(R.string.msgs_muted),
            MessagesMessage.SWIPE_DISABLED to stringResource(R.string.msgs_swipe_disabled),
            MessagesMessage.FAILED to stringResource(R.string.change_failed),
            MessagesMessage.RELEASED to stringResource(R.string.detail_released),
            MessagesMessage.RELEASE_UNAVAILABLE to stringResource(R.string.detail_release_unavailable),
            MessagesMessage.HELD_BLOCKED to stringResource(R.string.msgs_held_blocked),
        ),
    )
    val undo by rememberUpdatedState(stringResource(R.string.action_undo))
    val openRule by rememberUpdatedState(onOpenRule)

    BackHandler(enabled = state.selection.isNotEmpty()) {
        viewModel.onAction(MessagesAction.ClearSelection)
    }
    // Snackbars run in their own coroutine: `showSnackbar` suspends until it is dismissed (4 s for Short), and the
    // collector used to wait for it, so a row tap behind a snackbar was queued for seconds.
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is MessagesEvent.Snack -> {
                    val action = event.undo
                    scope.showLatestSnackbar(
                        snackbar = snackbar,
                        message = texts.getValue(event.message),
                        actionLabel = if (action != null) undo else null,
                        duration = SnackbarDuration.Short,
                    ) { action?.invoke() }
                }

                is MessagesEvent.Deleted -> {
                    scope.showLatestSnackbar(
                        snackbar,
                        resources.getQuantityString(R.plurals.snk_04, event.count, event.count),
                    )
                }

                is MessagesEvent.OpenRule -> {
                    openRule(event.messageId)
                }

                is MessagesEvent.ReleaseFailed -> {
                    scope.showLatestSnackbar(snackbar, resources.getString(releaseFailureRes(event.code)))
                }
            }
        }
    }

    MessagesContent(
        state = state,
        // The loaded window of the pager (no placeholders, `maxSize` drops far pages), so grouping works on that window.
        rows = items.itemSnapshotList.items,
        // Reading an index tells the pager which part of the list is on screen, so it loads the next page in time.
        onShowIndex = { index -> if (index >= 0 && index < items.itemCount) items[index] },
        held = heldItems.itemSnapshotList.items,
        onShowHeldIndex = { index -> if (index >= 0 && index < heldItems.itemCount) heldItems[index] },
        onRelease = { row -> viewModel.onAction(MessagesAction.Release(row)) },
        onBlockHeld = { row -> viewModel.onAction(MessagesAction.BlockHeld(row)) },
        refreshState = items.loadState.refresh,
        appendLoading = items.loadState.append is LoadState.Loading,
        snackbar = snackbar,
        onChip = { chip -> viewModel.onAction(MessagesAction.SelectChip(chip)) },
        onToggleReports = { viewModel.onAction(MessagesAction.ToggleReports) },
        onClearFates = { viewModel.onAction(MessagesAction.ClearFates) },
        onClearAttention = { viewModel.onAction(MessagesAction.ClearAttention) },
        onSearch = { query -> viewModel.onAction(MessagesAction.Search(query)) },
        onOpenFilters = { showFilter = true },
        // Navigation is not an event: going through the ViewModel's channel put the tap behind the snackbar queue
        // , and a second tap opened the detail twice.
        onClickRow = { row -> onOpenMessage(row.id) },
        onLongClickRow = { row -> viewModel.toggleSelection(row) },
        onPinRow = { row -> viewModel.onAction(MessagesAction.OpenInPane(row.id)) },
        onClosePane = { viewModel.onAction(MessagesAction.ClosePane) },
        onToggleReviewed = { row -> viewModel.onAction(MessagesAction.SwipeReviewed(row)) },
        onSwipeLeft = { row -> viewModel.onAction(MessagesAction.SwipeLeft(row)) },
        onClearFilters = { viewModel.onAction(MessagesAction.ClearFilters) },
        onRetry = { items.retry() },
        onClearSelection = { viewModel.onAction(MessagesAction.ClearSelection) },
        onSelectAll = { viewModel.onAction(MessagesAction.SelectAll) },
        onInvertSelection = { viewModel.onAction(MessagesAction.InvertSelection) },
        onMarkReviewed = { viewModel.onAction(MessagesAction.MarkSelectionReviewed(true)) },
        onMarkUnreviewed = { viewModel.onAction(MessagesAction.MarkSelectionReviewed(false)) },
        onStar = { viewModel.onAction(MessagesAction.StarSelection(true)) },
        onDelete = { viewModel.onAction(MessagesAction.AskDeleteSelection) },
        // Both slots of the request are reset first, so Export never opens on a previous export's scope.
        onExport = {
            viewModel.exportSelection()
            onExport()
        },
        onExportFilter = {
            viewModel.exportFilter()
            onExport()
        },
        detailPane = detailPane,
        focusSearch = searchFocusPending,
        onSearchFocused = { searchFocusPending = false },
    )

    if (showFilter) {
        FilterScreen(onDismiss = { showFilter = false })
    }

    state.deleteConfirm?.let { ids ->
        NjDialog(
            title = pluralStringResource(R.plurals.dlg_09_title, ids.size, ids.size),
            // "You cannot undo this." The hold warning shows only when a held message is selected.
            text = stringResource(if (state.deleteIncludesHeld) R.string.dlg_09_body else R.string.select_delete_body),
            confirmText = stringResource(R.string.dlg_09_confirm),
            dismissText = stringResource(R.string.dlg_09_cancel),
            destructive = true,
            onDismiss = { viewModel.onAction(MessagesAction.CancelDelete) },
            onConfirm = { viewModel.onAction(MessagesAction.ConfirmDelete) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesContent(
    state: MessagesUiState,
    rows: List<MessageListItem>,
    onShowIndex: (Int) -> Unit,
    refreshState: LoadState,
    appendLoading: Boolean,
    snackbar: SnackbarHostState,
    onChip: (FeedChip) -> Unit,
    onToggleReports: () -> Unit,
    onClearFates: () -> Unit,
    onClearAttention: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenFilters: () -> Unit,
    onClickRow: (MessageListItem) -> Unit,
    onLongClickRow: (MessageListItem) -> Unit,
    onPinRow: (MessageListItem) -> Unit,
    onToggleReviewed: (MessageListItem) -> Unit,
    onSwipeLeft: (MessageListItem) -> Unit,
    onClearFilters: () -> Unit,
    onRetry: () -> Unit,
    onClearSelection: () -> Unit,
    onMarkReviewed: () -> Unit,
    onMarkUnreviewed: () -> Unit,
    onStar: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onExportFilter: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    onInvertSelection: () -> Unit = {},
    focusSearch: Boolean = false,
    onSearchFocused: () -> Unit = {},
    onClosePane: () -> Unit = {},
    held: List<MessageListItem> = emptyList(),
    onShowHeldIndex: (Int) -> Unit = {},
    onRelease: (MessageListItem) -> Unit = {},
    onBlockHeld: (MessageListItem) -> Unit = {},
    detailPane: @Composable (messageId: Long, onClose: () -> Unit) -> Unit,
) {
    val selecting = state.selection.isNotEmpty()
    val heldRows = HeldRows(held, onShowHeldIndex, onRelease, onBlockHeld)
    val selection =
        SelectionCallbacks(
            onMarkReviewed = onMarkReviewed,
            onMarkUnreviewed = onMarkUnreviewed,
            onStar = onStar,
            onExport = onExport,
            onDelete = onDelete,
            onSelectAll = onSelectAll,
            onInvert = onInvertSelection,
            onClear = onClearSelection,
        )
    val headerActions: @Composable RowScope.() -> Unit = {
        if (selecting) {
            SelectionActions(selection)
        } else {
            IconButtonWithBadge(
                count = state.activeFilterCount,
                label = stringResource(R.string.msgs_filters),
                onClick = onOpenFilters,
                icon = NjIcons.Tune,
            )
            IconButton(onClick = onExportFilter) {
                Icon(DetailIcons.Share, contentDescription = stringResource(R.string.msgs_export))
            }
        }
    }
    Scaffold(
        containerColor = NjTheme.roles.bg,
        // Above the floating bar, like every other snackbar of the shell.
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = LocalNjBottomInset.current)) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // The branch is chosen on width alone: the old `selectedRowId != null` guard was never true because the
            // only row clicks that set it were inside the same branch.
            val twoPane = maxWidth >= 840.dp
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(0.38f)) {
                        Feed(
                            state = state,
                            rows = rows,
                            onShowIndex = onShowIndex,
                            refreshState = refreshState,
                            appendLoading = appendLoading,
                            onChip = onChip,
                            onToggleReports = onToggleReports,
                            onClearFates = onClearFates,
                            onClearAttention = onClearAttention,
                            onClickRow = { row -> onPinRow(row) },
                            onSelectRow = { row -> onLongClickRow(row) },
                            onToggleReviewed = onToggleReviewed,
                            onSwipeLeft = onSwipeLeft,
                            onClearFilters = onClearFilters,
                            onRetry = onRetry,
                            onLongClickRow = onLongClickRow,
                            onSearch = onSearch,
                            selection = selection,
                            content = headerActions,
                            focusSearch = focusSearch,
                            onSearchFocused = onSearchFocused,
                            pinnedId = state.selectedRowId,
                            heldRows = heldRows,
                        )
                    }
                    Box(Modifier.weight(0.62f)) {
                        val pinned = state.selectedRowId
                        if (pinned != null) {
                            // System Back closes the pane first; the selection mode has its own BackHandler.
                            BackHandler(enabled = !selecting) { onClosePane() }
                            detailPane(pinned, onClosePane)
                        } else {
                            NjEmptyState(
                                title = stringResource(R.string.msgs_detail_empty_title),
                                body = stringResource(R.string.msgs_detail_empty_body),
                            )
                        }
                    }
                }
            } else {
                Feed(
                    state = state,
                    rows = rows,
                    onShowIndex = onShowIndex,
                    refreshState = refreshState,
                    appendLoading = appendLoading,
                    onChip = onChip,
                    onToggleReports = onToggleReports,
                    onClearFates = onClearFates,
                    onClearAttention = onClearAttention,
                    onClickRow = onClickRow,
                    onSelectRow = onLongClickRow,
                    onToggleReviewed = onToggleReviewed,
                    onSwipeLeft = onSwipeLeft,
                    onClearFilters = onClearFilters,
                    onRetry = onRetry,
                    onLongClickRow = onLongClickRow,
                    onSearch = onSearch,
                    selection = selection,
                    content = headerActions,
                    focusSearch = focusSearch,
                    onSearchFocused = onSearchFocused,
                    heldRows = heldRows,
                )
            }
        }
    }
}

/** The active-filter chips and the count line above the feed rows. */
private fun LazyListScope.feedSummaryItems(
    state: MessagesUiState,
    onClearFates: () -> Unit,
    onClearAttention: () -> Unit,
    onClearFilters: () -> Unit,
) {
    if (state.spec.fates.isNotEmpty() || state.spec.attentionOnly) {
        item(key = "active-filters", contentType = "chips") {
            ActiveFilters(state, onClearFates, onClearAttention)
        }
    }
    countLine(state.total, state.spec)?.let { line ->
        item(key = "count", contentType = "caption") { CountLineRow(line, onClearFilters) }
    }
}

/** "10 messages · 1 filter on" with a Clear button on the right while filters are on. */
@Composable
private fun CountLineRow(
    line: CountLine,
    onClearFilters: () -> Unit,
) {
    val messages = pluralStringResource(R.plurals.msgs_count, line.messages, line.messages)
    val text =
        if (line.canClear) {
            "$messages · ${pluralStringResource(R.plurals.msgs_filters_on, line.filters, line.filters)}"
        } else {
            messages
        }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = NjSpacing.m + NjSpacing.xs, end = NjSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NjCaption(text = text, modifier = Modifier.weight(1f).padding(vertical = NjSpacing.s))
        if (line.canClear) {
            TextButton(onClick = onClearFilters) {
                Text(stringResource(R.string.msgs_filters_clear), color = NjTheme.roles.acc)
            }
        }
    }
}

/** The search field. [focusSearch] asks for the focus one time (: the Home search button). */
private fun LazyListScope.searchItem(
    query: String,
    onSearch: (String) -> Unit,
    focusSearch: Boolean,
    onSearchFocused: () -> Unit,
) {
    item(key = "search", contentType = "search") {
        val searchFocus = remember { FocusRequester() }
        NjSearchField(
            value = query,
            onValueChange = onSearch,
            placeholder = stringResource(R.string.msgs_search_hint),
            modifier = Modifier.padding(horizontal = NjSpacing.m, vertical = NjSpacing.s).focusRequester(searchFocus),
        )
        if (focusSearch) {
            // Opened from the Home search button. The field is on screen now, so it can take the focus.
            LaunchedEffect(Unit) {
                runCatching { searchFocus.requestFocus() }
                onSearchFocused()
            }
        }
    }
}

/**
 * The feed when it shows no rows, or when the load failed: shimmer while loading, the error with Retry, no match for
 * the filter, or the empty inbox. Out of [Feed] to keep its complexity under the detekt limit.
 */
private fun LazyListScope.feedStateItems(
    refreshState: LoadState,
    filtered: Boolean,
    onRetry: () -> Unit,
    onClearFilters: () -> Unit,
) {
    when {
        refreshState is LoadState.Loading -> {
            repeat(SHIMMER_ROWS) { index ->
                item(key = "shimmer-$index", contentType = "shimmer") {
                    NjShimmerCard(Modifier.padding(horizontal = NjSpacing.m, vertical = NjSpacing.xs))
                }
            }
        }

        refreshState is LoadState.Error -> {
            item(key = "load-failed", contentType = "state") {
                NjEmptyState(
                    title = stringResource(R.string.state_error_title),
                    body = stringResource(R.string.state_retry),
                    icon = NjIcons.Warning,
                    action = {
                        NjTonalButton(text = stringResource(R.string.action_retry), onClick = onRetry)
                    },
                )
            }
        }

        filtered -> {
            item(key = "empty-filter", contentType = "state") {
                NjEmptyState(
                    title = stringResource(R.string.state_empty_filter),
                    body =
                        stringResource(
                            R.string.state_empty_filter_body,
                            stringResource(R.string.action_clear_filters),
                        ),
                    action = {
                        NjTonalButton(text = stringResource(R.string.action_clear_filters), onClick = onClearFilters)
                    },
                )
            }
        }

        else -> {
            item(key = "empty", contentType = "state") {
                NjEmptyState(
                    title = stringResource(R.string.msgs_empty_title),
                    body = stringResource(R.string.msg_empty_01),
                    icon = NjIcons.Inbox,
                )
            }
        }
    }
}

/** Shimmer cards while the first page loads. */
private const val SHIMMER_ROWS = 6

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Feed(
    state: MessagesUiState,
    rows: List<MessageListItem>,
    onShowIndex: (Int) -> Unit,
    refreshState: LoadState,
    appendLoading: Boolean,
    onChip: (FeedChip) -> Unit,
    onToggleReports: () -> Unit,
    onClearFates: () -> Unit,
    onClearAttention: () -> Unit,
    onClickRow: (MessageListItem) -> Unit,
    onSelectRow: (MessageListItem) -> Unit,
    onToggleReviewed: (MessageListItem) -> Unit,
    onSwipeLeft: (MessageListItem) -> Unit,
    onClearFilters: () -> Unit,
    onRetry: () -> Unit,
    onLongClickRow: (MessageListItem) -> Unit,
    onSearch: (String) -> Unit,
    selection: SelectionCallbacks,
    pinnedId: Long? = null,
    focusSearch: Boolean = false,
    onSearchFocused: () -> Unit = {},
    heldRows: HeldRows = HeldRows(),
    /** The header actions (filter, export, more), in the large title and in the collapsed bar. */
    content: @Composable RowScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    val titleCollapsed by rememberNjTitleCollapsed(listState)
    val selecting = state.selection.isNotEmpty()
    val prefs = LocalNjMotionPrefs.current
    val motion = currentNjMotion()
    val animate = prefs.listAnimations && motion.enabled
    // Keys of the items that already ran their enter animation, so an item that scrolls back in does not run it again.
    val seen = remember { HashSet<String>() }
    // In the unfiltered view the held rows move to "Waiting for you"; the day groups leave them out.
    val waiting = showsWaitingSection(state.spec, state.quarantineCount) && heldRows.rows.isNotEmpty()
    val entries = remember(rows, waiting) { buildFeedEntries(rows, skip = { row -> waiting && isHeld(row) }) }
    val display = NumberDisplay.forSetting(state.maskNumbers)
    val screenTitle = stringResource(R.string.screen_s03)
    // The collapsed title bar lies over the list; a day header that sticks at the top moves below it.
    val barShown = selecting || titleCollapsed
    val barPx = with(LocalDensity.current) { NjTopBarHeight.roundToPx() }
    val clickLabels =
        RowClickLabels(
            open = stringResource(R.string.msgs_open),
            select = stringResource(R.string.msgs_select),
            selectedState = stringResource(R.string.select_selected),
            notSelectedState = stringResource(R.string.select_not_selected),
        )
    val callbacks = RowCallbacks(onClickRow, onSelectRow, onLongClickRow, onToggleReviewed, onSwipeLeft)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = NjSpacing.l + LocalNjBottomInset.current),
        ) {
            // The large title, search, chips and the count line cannot be used while the user selects. The
            // selection bar and the select-all line replace them.
            if (selecting) {
                selectAllItem(state.selection.size, state.total, selection)
            } else {
                item(key = "title", contentType = "title") {
                    NjLargeTitle(title = screenTitle, actions = content, modifier = Modifier.padding(top = NjSpacing.s))
                }
                searchItem(state.query, onSearch, focusSearch, onSearchFocused)
                item(key = "chips", contentType = "chips") { Chips(state, onChip, onToggleReports) }
                feedSummaryItems(state, onClearFates, onClearAttention, onClearFilters)
            }
            if (waiting) {
                waitingItems(
                    count = state.quarantineCount,
                    held = heldRows,
                    state = state,
                    display = display,
                    haptics = prefs.haptics,
                    clickLabels = clickLabels,
                    callbacks = callbacks,
                )
            }
            when {
                rows.isEmpty() || refreshState is LoadState.Error -> {
                    feedStateItems(refreshState, filtered = !state.spec.isDefault, onRetry, onClearFilters)
                }

                else -> {
                    entries.forEachIndexed { index, entry ->
                        when (entry) {
                            is FeedEntry.Day -> {
                                stickyHeader(key = entry.key, contentType = "day") {
                                    DayHeader(
                                        day = entry.day,
                                        modifier =
                                            Modifier.offset {
                                                // Placement only: no item moves, the header draws below the bar.
                                                val placed =
                                                    listState.layoutInfo.visibleItemsInfo
                                                        .firstOrNull { it.key == entry.key }
                                                        ?.offset ?: barPx
                                                IntOffset(0, if (barShown) (barPx - placed).coerceAtLeast(0) else 0)
                                            },
                                    )
                                }
                            }

                            is FeedEntry.Message -> {
                                item(key = entry.key, contentType = "message") {
                                    onShowIndex(entry.sourceIndex)
                                    val row = entry.row
                                    // Only the first items of the list stagger; later items enter with no delay.
                                    val enterIndex = if (index < NjMotion.STAGGER_MAX_ITEMS) index else 0
                                    val placement = Modifier.feedPlacement(this, animate)
                                    FeedRow(
                                        entry = entry,
                                        position = groupPosition(entries, index),
                                        query = state.spec.search,
                                        selecting = selecting,
                                        selected = row.id in state.selection,
                                        current = row.id == pinnedId,
                                        display = display,
                                        haptics = prefs.haptics,
                                        // No swipe in selection mode; a sent message or a delivery report has no
                                        // "Block" and no review action.
                                        swipeEnabled = canSwipe(row, selecting),
                                        clickLabels = clickLabels,
                                        onOpenReport = { report -> callbacks.openReport(report, selecting) },
                                        onClick = { callbacks.click(row, selecting) },
                                        onLongClick = { callbacks.longClick(row, selecting) },
                                        onToggleReviewed = { onToggleReviewed(row) },
                                        onSwipeLeft = { onSwipeLeft(row) },
                                        heldActions = heldActions(row, selecting, heldRows),
                                        modifier = placement.enterAnimation(entry.key, enterIndex, seen, animate, motion),
                                    )
                                }
                            }
                        }
                    }
                    if (appendLoading) {
                        item(key = "append", contentType = "append") {
                            Box(Modifier.fillMaxWidth().padding(NjSpacing.m), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
        if (selecting || titleCollapsed) {
            NjCollapsedTitle(
                title =
                    if (selecting) {
                        pluralStringResource(R.plurals.msgs_selected, state.selection.size, state.selection.size)
                    } else {
                        screenTitle
                    },
                collapsed = true,
                onBack = if (selecting) selection.onClear else null,
                // A close icon ends the selection. A back arrow means "go to the previous screen".
                backIcon = NjIcons.Close,
                backLabel = stringResource(R.string.trusted_clear),
                actions = content,
                modifier = Modifier.background(NjTheme.roles.bg),
            )
        }
    }
}

/** The item placement animation of a feed row, or none when list animations are off. */
private fun Modifier.feedPlacement(
    scope: LazyItemScope,
    animate: Boolean,
): Modifier =
    with(scope) {
        if (animate) {
            this@feedPlacement.animateItem()
        } else {
            this@feedPlacement.animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
        }
    }

/**
 * Fade in and move up (redesign 7.4: [NjMotion.listEnterOffset], 450 ms, overshoot easing) with a stagger by [index].
 * It runs once for a [key] that is not in [seen]. With [enabled] false the item shows at once. Not `@Composable` (lint
 * ComposableModifierFactory): the state lives in [composed].
 */
private fun Modifier.enterAnimation(
    key: String,
    index: Int,
    seen: MutableSet<String>,
    enabled: Boolean,
    motion: NjMotion,
): Modifier =
    composed {
        val progress = remember(key) { Animatable(if (enabled && key !in seen) 0f else 1f) }
        LaunchedEffect(key) {
            seen.add(key)
            if (progress.value < 1f) progress.animateTo(1f, motion.listEnterSpec(index))
        }
        val offsetPx = with(LocalDensity.current) { motion.listEnterOffset.toPx() }
        Modifier.graphicsLayer {
            alpha = progress.value.coerceIn(0f, 1f)
            translationY = (1f - progress.value) * offsetPx
        }
    }

@Composable
private fun Chips(
    state: MessagesUiState,
    onChip: (FeedChip) -> Unit,
    onToggleReports: () -> Unit,
) {
    val quarantined =
        if (state.quarantineCount > 0) {
            stringResource(R.string.msgs_chip_count, stringResource(R.string.msgs_chip_quarantined), state.quarantineCount)
        } else {
            stringResource(R.string.msgs_chip_quarantined)
        }
    val feedLabels =
        mapOf(
            FeedChip.ALL to stringResource(R.string.msgs_chip_all),
            FeedChip.SUSPICIOUS to stringResource(R.string.msgs_chip_suspicious),
            FeedChip.QUARANTINED to quarantined,
            FeedChip.OUTGOING to stringResource(R.string.msgs_chip_outgoing),
            FeedChip.BROADCASTS to stringResource(R.string.msgs_chip_broadcasts),
            FeedChip.SIM1 to stringResource(R.string.msgs_chip_sim1),
            FeedChip.SIM2 to stringResource(R.string.msgs_chip_sim2),
        )
    val reportsLabel = stringResource(R.string.msg_x_chip_reports)
    val options =
        buildList {
            FEED_CHIPS.forEach { chip ->
                add(NjOption(chip.wire, feedLabels.getValue(chip)))
                // "Delivery reports" is a toggle and sits after "On hold".
                if (chip == FeedChip.QUARANTINED) add(NjOption(CHIP_REPORTS, reportsLabel))
            }
        }
    val selected =
        buildSet {
            add(state.spec.chip.wire)
            if (state.spec.showDeliveryReports) add(CHIP_REPORTS)
        }
    NjFilterChips(
        options = options,
        selected = selected,
        onToggle = { key -> if (key == CHIP_REPORTS) onToggleReports() else onChip(FeedChip.from(key)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = NjSpacing.m, vertical = NjSpacing.xs),
    )
}

/** Removable chips for the conditions that Home sets and the chip row cannot show: "Blocked" and "Needs attention". */
@Composable
private fun ActiveFilters(
    state: MessagesUiState,
    onClearFates: () -> Unit,
    onClearAttention: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = NjSpacing.m, vertical = NjSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(NjSpacing.s),
    ) {
        if (state.spec.fates.isNotEmpty()) {
            RemovableChip(stringResource(R.string.home_stat_blocked), onClearFates)
        }
        if (state.spec.attentionOnly) {
            RemovableChip(stringResource(R.string.home_needs_attention), onClearAttention)
        }
    }
}

/** A filter condition as a chip; a tap removes it. The close icon tells TalkBack what the tap does. */
@Composable
internal fun RemovableChip(
    label: String,
    onRemove: () -> Unit,
) {
    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(label, maxLines = 1) },
        trailingIcon = {
            Icon(NjIcons.Close, contentDescription = stringResource(R.string.msgs_remove_filter), modifier = Modifier.size(18.dp))
        },
        shape = NjShape.chip,
    )
}

/** The day header. It sticks to the top while the rows of its day scroll under it. */
@Composable
private fun DayHeader(
    day: LocalDate,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val label =
        when (day) {
            today -> stringResource(R.string.msg_group_today)
            today.minusDays(1) -> stringResource(R.string.msg_group_yesterday)
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(day)
        }
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = NjTheme.roles.mu,
        modifier =
            modifier
                .fillMaxWidth()
                .background(NjTheme.roles.bg)
                .padding(start = NjSpacing.m + NjSpacing.xs, end = NjSpacing.m, top = NjSpacing.m, bottom = NjSpacing.s)
                .semantics { heading() },
    )
}

/** The filter button, with the number of active conditions as a badge and in the description (05). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IconButtonWithBadge(
    count: Int,
    label: String,
    onClick: () -> Unit,
    icon: ImageVector,
) {
    val description =
        if (count > 0) {
            "$label, ${pluralStringResource(R.plurals.filter_active_count, count, count)}"
        } else {
            label
        }
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        BadgedBox(
            badge = {
                if (count > 0) {
                    // A neutral badge. Amber is only for messages that wait for the user.
                    Badge(containerColor = NjTheme.roles.mu, contentColor = NjTheme.roles.bg) { Text(count.toString()) }
                }
            },
        ) {
            Icon(icon, contentDescription = null)
        }
    }
}

// ------------------------------------------------------------------ previews

private const val PREVIEW_DAY_MINUTES = 24L * 60

/** Synthetic rows for the previews. The number is not real. */
private fun previewRow(
    id: Long,
    minutesAgo: Long,
    classId: ClassId,
    severity: Severity = Severity.INFO,
    direction: Direction = Direction.IN,
    quarantined: Boolean = false,
    fate: Fate = Fate.SYSTEM,
    deliveryStatus: String? = null,
    relatedMessageId: Long? = null,
) = MessageListItem(
    id = id,
    eventId = "preview-$id",
    receivedAtWall = System.currentTimeMillis() - minutesAgo * 60_000,
    classId = classId,
    severity = severity,
    title = "",
    originator = "+15550104821",
    textPreview = "",
    fate = fate,
    verdict = Verdict.ALLOW,
    isQuarantined = quarantined,
    isReviewed = false,
    isStarred = false,
    direction = direction,
    subId = 1,
    slot = 0,
    partsHave = 1,
    partsTotal = 1,
    consumerPackage = null,
    consumerPackageLabel = null,
    isReplay = false,
    deliveryStatus = deliveryStatus,
    relatedMessageId = relatedMessageId,
)

private fun previewRows(): List<MessageListItem> =
    listOf(
        previewRow(1, 5, ClassId.SILENT, Severity.SUSPICIOUS, quarantined = true),
        previewRow(2, 20, ClassId.VVM),
        previewRow(3, 40, ClassId.WAP_MMS),
        previewRow(4, 50, ClassId.STATUS, deliveryStatus = "DELIVERED", relatedMessageId = 5),
        previewRow(5, 51, ClassId.MO_VISIBLE, direction = Direction.OUT),
        previewRow(6, PREVIEW_DAY_MINUTES + 30, ClassId.WAP_OTHER, Severity.SUSPICIOUS, fate = Fate.BLOCKED),
    )

@Composable
private fun MessagesPreviewContent(
    rows: List<MessageListItem>,
    selection: Set<Long> = emptySet(),
) {
    MessagesContent(
        state = MessagesUiState(total = rows.size, quarantineCount = rows.count(::isHeld), selection = selection),
        rows = rows,
        held = rows.filter(::isHeld),
        onShowIndex = {},
        refreshState = LoadState.NotLoading(false),
        appendLoading = false,
        snackbar = SnackbarHostState(),
        onChip = {},
        onToggleReports = {},
        onClearFates = {},
        onClearAttention = {},
        onSearch = {},
        onOpenFilters = {},
        onClickRow = {},
        onLongClickRow = {},
        onPinRow = {},
        onToggleReviewed = {},
        onSwipeLeft = {},
        onClearFilters = {},
        onRetry = {},
        onClearSelection = {},
        onMarkReviewed = {},
        onMarkUnreviewed = {},
        onStar = {},
        onDelete = {},
        onExport = {},
        detailPane = { _, _ -> },
    )
}

@Preview(name = "messages dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MessagesDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { MessagesPreviewContent(previewRows()) }
}

@Preview(name = "messages light")
@Composable
private fun MessagesLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { MessagesPreviewContent(previewRows()) }
}

@Preview(name = "messages selecting dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MessagesSelectingPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { MessagesPreviewContent(previewRows(), selection = setOf(1L, 3L)) }
}

@Preview(name = "messages empty")
@Composable
private fun MessagesEmptyPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { MessagesPreviewContent(emptyList()) }
}
