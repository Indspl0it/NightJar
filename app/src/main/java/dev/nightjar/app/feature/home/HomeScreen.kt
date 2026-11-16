package dev.nightjar.app.feature.home

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import dev.nightjar.app.R
import dev.nightjar.app.core.security.NumberDisplay
import dev.nightjar.app.core.security.formatNumber
import dev.nightjar.app.core.telephony.simNames
import dev.nightjar.app.data.model.FilterSpec
import dev.nightjar.app.data.model.MessageListItem
import dev.nightjar.app.data.repository.WeekCounts
import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.feature.detail.releaseFailureRes
import dev.nightjar.app.feature.health.SelfTestSheet
import dev.nightjar.app.feature.health.SelfTestViewModel
import dev.nightjar.app.feature.messages.FilterScreen
import dev.nightjar.app.feature.messages.rowReason
import dev.nightjar.app.feature.messages.rowTitle
import dev.nightjar.app.feature.messages.simLine
import dev.nightjar.app.feature.rules.showLatestSnackbar
import dev.nightjar.app.ui.components.LocalNjBottomInset
import dev.nightjar.app.ui.components.NjBadge
import dev.nightjar.app.ui.components.NjCard
import dev.nightjar.app.ui.components.NjCollapsedTitle
import dev.nightjar.app.ui.components.NjDangerButton
import dev.nightjar.app.ui.components.NjDialog
import dev.nightjar.app.ui.components.NjIcons
import dev.nightjar.app.ui.components.NjLargeTitle
import dev.nightjar.app.ui.components.NjLineIcons
import dev.nightjar.app.ui.components.NjLogoMark
import dev.nightjar.app.ui.components.NjMenuDivider
import dev.nightjar.app.ui.components.NjMenuItem
import dev.nightjar.app.ui.components.NjMenuSwitchItem
import dev.nightjar.app.ui.components.NjOverflowMenu
import dev.nightjar.app.ui.components.NjPrimaryButton
import dev.nightjar.app.ui.components.NjStat
import dev.nightjar.app.ui.components.NjStatStrip
import dev.nightjar.app.ui.components.NjTextButton
import dev.nightjar.app.ui.components.NjTonalButton
import dev.nightjar.app.ui.components.NjTopBarIconButton
import dev.nightjar.app.ui.components.NjTypeIcon
import dev.nightjar.app.ui.components.NjWeekBar
import dev.nightjar.app.ui.components.NjWeekChart
import dev.nightjar.app.ui.components.SkeletonRow
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.components.rememberNjTitleCollapsed
import dev.nightjar.app.ui.text.LocalStrings
import dev.nightjar.app.ui.theme.LocalNjMotionPrefs
import dev.nightjar.app.ui.theme.NjRoles
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.SeverityTokens
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Fate
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun HomeScreen(
    onOpenMessage: (Long) -> Unit,
    onOpenHealth: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenBlindSpots: () -> Unit,
    onOpenRule: (Long) -> Unit = onOpenMessage,
    onSearchMessages: () -> Unit = onOpenMessages,
    onOpenProtection: () -> Unit = onOpenHealth,
    viewModel: HomeViewModel = hiltViewModel(),
    selfTestViewModel: SelfTestViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val attention = viewModel.attention.collectAsLazyPagingItems()
    val selfTest by selfTestViewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // The filter button opens the filter sheet of Messages over Home. "Apply" then opens Messages.
    var showFilter by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val context = LocalContext.current
    // The texts are read when an event comes, so a change of language while Home is open shows the new text.
    val texts by rememberUpdatedState(
        HomeTexts(
            safeOn = stringResource(R.string.snk_11),
            safeOff = stringResource(R.string.snk_11_off),
            undo = stringResource(R.string.action_undo),
            released = stringResource(R.string.detail_released),
            releaseUnavailable = stringResource(R.string.detail_release_unavailable),
        ),
    )
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        // A self-test run on Protection status changes the self-test line of the card.
        selfTestViewModel.reloadLastRun()
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                HomeEvent.Released -> {
                    scope.showLatestSnackbar(snackbar, texts.released)
                }

                HomeEvent.ReleaseUnavailable -> {
                    scope.showLatestSnackbar(snackbar, texts.releaseUnavailable)
                }

                is HomeEvent.ReleaseFailed -> {
                    scope.showLatestSnackbar(snackbar, resources.getString(releaseFailureRes(event.code)))
                }

                is HomeEvent.SafeModeChanged -> {
                    scope.showLatestSnackbar(
                        snackbar = snackbar,
                        message = if (event.enabled) texts.safeOn else texts.safeOff,
                        actionLabel = if (event.undoable) texts.undo else null,
                        duration = SnackbarDuration.Long,
                    ) { viewModel.onAction(HomeAction.UndoSafeMode(!event.enabled)) }
                }
            }
        }
    }
    HomeContent(
        state = state,
        attentionCount = attention.itemCount,
        attentionAt = { index -> if (index < attention.itemCount) attention[index] else null },
        // Not attention.itemKey: the item count of the list comes from the last composition, so after a release
        // shrinks the paged list the key map can ask for an index past the new end. peek() past the end throws.
        attentionKey = { index ->
            val row = if (index < attention.itemCount) attention.peek(index) else null
            row?.let { "attention-" + it.id } ?: "attention-slot-$index"
        },
        snackbar = snackbar,
        onRefresh = { viewModel.onAction(HomeAction.Refresh) },
        onOpenHealth = onOpenHealth,
        onOpenProtection = onOpenProtection,
        onRunSelfTest = selfTestViewModel::open,
        // The setup screen shows the manual steps when no manager app opens. Protection status has them as "How to fix".
        onOpenFramework = { if (!openFrameworkManager(context)) onOpenHealth() },
        onSeeAll = {
            viewModel.openAttention()
            onOpenMessages()
        },
        onOpenMessage = onOpenMessage,
        onOpenClass = { classId ->
            viewModel.openClass(classId)
            onOpenMessages()
        },
        onOpenFilter = { filter ->
            viewModel.openFilter(filter)
            onOpenMessages()
        },
        onRelease = { row -> viewModel.onAction(HomeAction.Release(row.id)) },
        onBlockSimilar = { row -> onOpenRule(row.id) },
        onSetSafeMode = { enabled -> viewModel.onAction(HomeAction.SetSafeMode(enabled)) },
        onHelp = onOpenBlindSpots,
        onSearch = onSearchMessages,
        onOpenFilters = { showFilter = true },
    )
    if (showFilter) {
        FilterScreen(
            onDismiss = { showFilter = false },
            onApplied = {
                showFilter = false
                onOpenMessages()
            },
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

/** The snackbar texts of Home, read through `rememberUpdatedState` when an event comes. */
private data class HomeTexts(
    val safeOn: String,
    val safeOff: String,
    val undo: String,
    val released: String,
    val releaseUnavailable: String,
)

/**
 * Home: large title, status card, "This week" strip, chart, "Needs attention" and "By type".
 * Each section is one lazy item with a stable key, so nothing can render twice. The sections enter one after the other
 * ([njEnter]). The list ends above the floating bar ([LocalNjBottomInset]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    attentionCount: Int,
    attentionAt: (Int) -> MessageListItem?,
    snackbar: SnackbarHostState,
    onRefresh: () -> Unit,
    onOpenHealth: () -> Unit,
    onRunSelfTest: () -> Unit,
    onSeeAll: () -> Unit,
    onOpenMessage: (Long) -> Unit,
    onOpenClass: (ClassId) -> Unit,
    onOpenFilter: (FilterSpec) -> Unit,
    onRelease: (MessageListItem) -> Unit,
    onBlockSimilar: (MessageListItem) -> Unit,
    onSetSafeMode: (Boolean) -> Unit,
    onHelp: () -> Unit,
    onSearch: () -> Unit = {},
    onOpenFilters: () -> Unit = {},
    /** Opens the Protection tab (the Blocking switch). */
    onOpenProtection: () -> Unit = onOpenHealth,
    /** The fix button of the state NOT_ACTIVE: opens the Vector or LSPosed manager app. */
    onOpenFramework: () -> Unit = {},
    /** The lazy key of attention row [index]: the row id, so item state and animation follow the row. */
    attentionKey: (Int) -> Any = { index -> attentionAt(index)?.let { "attention-" + it.id } ?: "attention-slot-$index" },
) {
    var confirmSafeModeOn by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val collapsed by rememberNjTitleCollapsed(listState)
    val locale = LocalConfiguration.current.locales[0]
    val gutter = Modifier.padding(horizontal = NjSpacing.m)
    val shown = minOf(attentionCount, ATTENTION_LIMIT)
    val title = stringResource(R.string.app_name)
    val prefs = LocalNjMotionPrefs.current
    val haptic = LocalHapticFeedback.current
    // A short tick when a quick action runs, only when "Haptic feedback" is on.
    val tick = { if (prefs.haptics) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    // "Today" of the ViewModel window: date texts move to the new day at midnight with the counts.
    val today = windowToday(state.days)
    val menu: @Composable () -> Unit = {
        // Search, filter and menu, each drawn once and all the same size. The menu keeps protection status, safe
        // mode and help.
        NjTopBarIconButton(icon = NjIcons.Search, contentDescription = stringResource(R.string.home_search_messages), onClick = onSearch)
        NjTopBarIconButton(icon = NjIcons.Tune, contentDescription = stringResource(R.string.home_filter_messages), onClick = onOpenFilters)
        HomeMenu(
            safeModeOn = state.safeModeOn,
            onRunSelfTest = onRunSelfTest,
            onOpenHealth = onOpenHealth,
            onHelp = onHelp,
            onToggleSafeMode = {
                // Before turning on; turning off is immediate and offers Undo.
                if (state.safeModeOn) onSetSafeMode(false) else confirmSafeModeOn = true
            },
        )
    }
    Box(Modifier.fillMaxSize().background(NjTheme.roles.bg)) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                // No status bar inset here: the shell Scaffold already pads its content below the status bar.
                // `WindowInsets.statusBars.asPaddingValues()` does not see that consumption and doubled the top space.
                contentPadding =
                    PaddingValues(
                        top = NjSpacing.s,
                        bottom = LocalNjBottomInset.current + NjSpacing.l,
                    ),
                verticalArrangement = Arrangement.spacedBy(NjSpacing.s + 2.dp),
            ) {
                item(key = "title") {
                    NjLargeTitle(
                        title = title,
                        modifier = Modifier.njEnter(0),
                        actions = { menu() },
                    )
                }
                if (state.loading) {
                    item(key = "loading") { Column(gutter) { repeat(3) { SkeletonRow() } } }
                } else {
                    item(key = "status") {
                        StatusCard(
                            state = state,
                            // With the Blocking switch off, the card opens the Protection tab, where the
                            // switch is.
                            onViewStatus = if (state.status == HomeStatus.MONITOR_ONLY) onOpenProtection else onOpenHealth,
                            onAction = { action ->
                                when (action) {
                                    HomeCardAction.OPEN_FRAMEWORK -> onOpenFramework()

                                    // Off is immediate and offers Undo, as the switch in the menu.
                                    HomeCardAction.TURN_OFF_SAFE_MODE -> onSetSafeMode(false)
                                }
                            },
                            modifier = gutter.njEnter(1),
                        )
                    }
                    item(key = "week-header") {
                        HomeSectionTitle(
                            title = stringResource(R.string.home_tile_week),
                            trailingText = weekRangeText(state.days, locale),
                            modifier = gutter.njEnter(2),
                        )
                    }
                    item(key = "strip") {
                        WeekStrip(
                            week = state.week,
                            onOpenFilter = { tile -> onOpenFilter(tileFilter(tile, state.days)) },
                            modifier = gutter.njEnter(2),
                        )
                    }
                    item(key = "chart") {
                        NjWeekChart(
                            bars = state.days.map { NjWeekBar(dayShortLabel(it.label, locale), it.count) },
                            onDayClick = { index -> barFilter(state.days, index)?.let(onOpenFilter) },
                            title = stringResource(R.string.home_chart_title),
                            hint = stringResource(R.string.home_chart_hint),
                            modifier = gutter.njEnter(3),
                        )
                    }
                    if (attentionCount > 0) {
                        item(key = "attention-header") {
                            HomeSectionTitle(
                                title = stringResource(R.string.home_needs_attention),
                                modifier = gutter.njEnter(4),
                                content = {
                                    NjTextButton(text = stringResource(R.string.home_attention_view_all), onClick = onSeeAll)
                                },
                            )
                        }
                        items(shown, key = attentionKey) { index ->
                            attentionAt(index)?.let { row ->
                                AttentionCard(
                                    row = row,
                                    today = today,
                                    maskNumbers = state.maskNumbers,
                                    onOpen = { onOpenMessage(row.id) },
                                    onRelease = {
                                        tick()
                                        onRelease(row)
                                    },
                                    onBlockSimilar = {
                                        tick()
                                        onBlockSimilar(row)
                                    },
                                    modifier = gutter.njEnter(5 + index),
                                )
                            }
                        }
                    }
                    if (state.byType.isNotEmpty()) {
                        item(key = "type-header") {
                            HomeSectionTitle(
                                title = stringResource(R.string.home_by_type),
                                trailingText = stringResource(R.string.home_tile_week),
                                modifier = gutter.njEnter(5 + shown),
                            )
                        }
                        item(key = "types") {
                            TypeList(
                                rows = state.byType,
                                onOpenClass = onOpenClass,
                                modifier = gutter.njEnter(5 + shown),
                            )
                        }
                    }
                }
            }
        }
        NjCollapsedTitle(
            title = title,
            collapsed = collapsed,
            // No status bar padding: the shell consumes safeDrawing, so the inset here is zero.
            modifier = Modifier.align(Alignment.TopStart),
            // The large title draws the buttons while it is in view. The small bar draws them only when the large
            // title has scrolled away, so the buttons never show twice.
            actions = { if (collapsed) menu() },
        )
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LocalNjBottomInset.current),
        )
    }
    if (confirmSafeModeOn) {
        NjDialog(
            title = stringResource(R.string.dlg_12_title),
            text = stringResource(R.string.dlg_12_body),
            confirmText = stringResource(R.string.dlg_12_confirm),
            dismissText = stringResource(R.string.dlg_12_cancel),
            onDismiss = { confirmSafeModeOn = false },
            onConfirm = {
                confirmSafeModeOn = false
                onSetSafeMode(true)
            },
        )
    }
}

/**
 * The "more" button of the title bar with the menu (option A): run self-test, protection status, safe
 * mode as a switch row, a divider and help. [NjOverflowMenu] gives it the same look as every other top-right menu.
 */
@Composable
private fun HomeMenu(
    safeModeOn: Boolean,
    onRunSelfTest: () -> Unit,
    onOpenHealth: () -> Unit,
    onHelp: () -> Unit,
    onToggleSafeMode: () -> Unit,
) {
    NjOverflowMenu(contentDescription = stringResource(R.string.home_more)) { close ->
        NjMenuItem(
            text = stringResource(R.string.action_run_self_test),
            icon = NjLineIcons.SelfTest,
            onClick = {
                close()
                onRunSelfTest()
            },
        )
        NjMenuItem(
            text = stringResource(R.string.home_open_health),
            icon = NjLineIcons.Status,
            onClick = {
                close()
                onOpenHealth()
            },
        )
        NjMenuSwitchItem(
            text = stringResource(R.string.home_safe_mode_toggle),
            supporting = stringResource(R.string.shell_safe_mode_supporting),
            icon = NjLineIcons.SafeMode,
            checked = safeModeOn,
            onClick = {
                close()
                onToggleSafeMode()
            },
        )
        NjMenuDivider()
        NjMenuItem(
            text = stringResource(R.string.home_help),
            icon = NjLineIcons.Info,
            onClick = {
                close()
                onHelp()
            },
        )
    }
}

/** "7 Nov to 13 Nov" for the header of the strip, or null while there are no days. */
@Composable
private fun weekRangeText(
    days: List<DayBar>,
    locale: Locale,
): String? {
    val range = weekRange(days) ?: return null
    val format = DateTimeFormatter.ofPattern("d MMM", locale)
    return stringResource(R.string.home_week_range, range.first.format(format), range.second.format(format))
}

/** Section title (18 sp bold) with an optional grey text or a text button on the right. 16 dp above the title. */
@Composable
private fun HomeSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailingText: String? = null,
    content: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = NjSpacing.xs, top = NjSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = NjTheme.roles.tx,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodyMedium,
                color = NjTheme.roles.mu,
                modifier = Modifier.padding(end = NjSpacing.xs),
            )
        }
        content?.invoke()
    }
}

// ------------------------------------------------------------------ status card

/**
 * The accent colour of a status: green when protected, amber when blocking is off, orange for safe mode, red when the
 * module does not work.
 */
private fun statusTone(
    status: HomeStatus,
    roles: NjRoles,
): Color =
    when (status) {
        HomeStatus.PROTECTED -> roles.ok
        HomeStatus.BLOCKING_PAUSED, HomeStatus.MONITOR_ONLY -> roles.amb
        HomeStatus.SAFE_MODE -> roles.sus
        HomeStatus.NOT_ACTIVE, HomeStatus.UNSUPPORTED -> roles.hi
    }

@Composable
private fun statusChipText(status: HomeStatus): String =
    stringResource(
        when (status) {
            HomeStatus.PROTECTED -> R.string.home_module_active
            HomeStatus.BLOCKING_PAUSED -> R.string.home_x_chip_paused
            HomeStatus.MONITOR_ONLY -> R.string.home_x_chip_monitor
            HomeStatus.NOT_ACTIVE -> R.string.home_x_chip_not_active
            HomeStatus.SAFE_MODE -> R.string.home_x_chip_safe_mode
            HomeStatus.UNSUPPORTED -> R.string.home_x_chip_unsupported
        },
    )

/** "SIM 1", "SIM 1 and SIM 2", or "your SIMs" when no ready slot is known. */
@Composable
private fun simNamesText(slots: List<Int>): String =
    simNames(
        slots = slots,
        slotLabel = { number -> stringResource(R.string.home_sim_slot, number) },
        pair = { first, second -> stringResource(R.string.home_sim_pair, first, second) },
    ) ?: stringResource(R.string.msg_unknown_subs)

/**
 * The status card: a chip with the module state, the title of the state, one line of text and
 * the whole logo. The whole card is one tap target that opens Protection status. A state that needs the user
 * tints the card and shows one fix button ([statusCardAction]). There is no self-test line and no
 * "View status" link. The self-test is in the menu.
 */
@Composable
private fun StatusCard(
    state: HomeUiState,
    onViewStatus: () -> Unit,
    onAction: (HomeCardAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    val status = state.status
    val tone = statusTone(status, roles)
    val body =
        when (status) {
            HomeStatus.PROTECTED -> stringResource(R.string.home_hero_body_protected, simNamesText(state.simSlots))
            HomeStatus.BLOCKING_PAUSED -> stringResource(R.string.msg_st_paused_sub)
            else -> state.heroBody
        }
    val container = if (status == HomeStatus.PROTECTED) roles.s1 else lerp(roles.s1, tone, 0.10f)
    val action = statusCardAction(status)
    NjCard(
        modifier =
            modifier
                .clip(NjShape.card)
                .clickable(
                    onClickLabel = stringResource(R.string.home_card_open_status),
                    role = Role.Button,
                    onClick = onViewStatus,
                ),
        container = container,
        padding = 20.dp,
    ) {
        // The whole logo sits in its own column at the top right, so the text wraps before it at any font
        // size and never runs under it.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NjSpacing.m)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NjSpacing.s)) {
                NjBadge(text = statusChipText(status), color = tone)
                Text(
                    text = state.heroTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    color = roles.tx,
                    modifier = Modifier.padding(top = NjSpacing.s).semantics { heading() },
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = roles.mu,
                )
            }
            NjLogoMark(Modifier.width(LogoMarkWidth).alpha(LOGO_MARK_ALPHA))
        }
        if (action != null) {
            NjPrimaryButton(
                text = stringResource(cardActionLabel(action)),
                onClick = { onAction(action) },
                modifier = Modifier.fillMaxWidth().padding(top = NjSpacing.m),
            )
        }
    }
}

/** The width of the logo mark of the status card: wide enough that the three arcs stay readable. */
private val LogoMarkWidth = 80.dp

/** The whole logo is the brand mark of the card. At 0.5 the outer arc (alpha 0.35 in the artwork) was almost invisible. */
private const val LOGO_MARK_ALPHA = 0.8f

/** The label of the fix button: the label of the same action on the setup screen, where there is one. */
private fun cardActionLabel(action: HomeCardAction): Int =
    when (action) {
        HomeCardAction.OPEN_FRAMEWORK -> R.string.setup_open_lsposed
        HomeCardAction.TURN_OFF_SAFE_MODE -> R.string.home_turn_off_safe_mode
    }

/** Opens the first installed Vector or LSPosed manager app, as the setup screen does. False when none opens. */
private fun openFrameworkManager(context: Context): Boolean {
    val intent =
        FrameworkManagerPackages.firstNotNullOfOrNull { runCatching { context.packageManager.getLaunchIntentForPackage(it) }.getOrNull() }
            ?: return false
    return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}

/** The manager apps of Vector and LSPosed: the same list as the setup screen (OnboardingScreen). */
private val FrameworkManagerPackages = listOf("org.lsposed.manager", "org.matrix.vector")

// ------------------------------------------------------------------ week strip

/** Hidden, Suspicious, On hold and Blocked as one strip. A tap on a column opens Messages with the matching filter. */
@Composable
private fun WeekStrip(
    week: WeekCounts,
    onOpenFilter: (WeekTile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    val stats =
        WeekTile.entries.map { tile ->
            val (icon, label, tint) =
                when (tile) {
                    // One line style for the 4 icons, each in its status colour.
                    WeekTile.HIDDEN -> Triple(NjLineIcons.Hidden, R.string.home_stat_hidden, roles.acc)

                    WeekTile.SUSPICIOUS -> Triple(NjLineIcons.Suspicious, R.string.home_stat_suspicious, roles.sus)

                    WeekTile.ON_HOLD -> Triple(NjLineIcons.OnHold, R.string.home_stat_on_hold, roles.amb)

                    WeekTile.BLOCKED -> Triple(NjLineIcons.Blocked, R.string.home_stat_blocked, roles.hi)
                }
            NjStat(icon, tileCount(tile, week), stringResource(label), tint) { onOpenFilter(tile) }
        }
    NjStatStrip(stats = stats, modifier = modifier)
}

// ------------------------------------------------------------------ needs attention

/**
 * One held or suspicious message: type icon, title, time, sender line, tags, the reason and the quick actions Release and
 * Block similar. A row with no quick action shows a chevron and opens the message when the user taps it.
 */
@Composable
private fun AttentionCard(
    row: MessageListItem,
    today: LocalDate?,
    maskNumbers: Boolean,
    onOpen: () -> Unit,
    onRelease: () -> Unit,
    onBlockSimilar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    val strings = LocalStrings.current
    val actions = attentionActions(row)
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember { ZoneId.systemDefault() }
    // A High row says "High", not "Suspicious" (same label as the Messages list). The tone stays as designed.
    val tagSeverity = attentionTagSeverity(row)
    val tone = if (tagSeverity != null) roles.sus else roles.amb
    // The sender is masked when the row is drawn, not when the message is stored.
    val sender = formatNumber(row.originator, NumberDisplay.forSetting(maskNumbers))
    val blocked = stringResource(R.string.home_stat_blocked)
    val subline =
        listOfNotNull(
            sender?.takeIf { it.isNotBlank() },
            simLine(row, strings).takeIf { it.isNotBlank() },
            blocked.takeIf { row.fate == Fate.BLOCKED },
        ).joinToString(" · ")
    val severityTag = tagSeverity?.let { SeverityTokens.label(it) }
    val onHold = stringResource(R.string.home_chip_quarantined)
    val tagParts = listOfNotNull(severityTag, onHold.takeIf { row.isQuarantined })
    val tags =
        when (tagParts.size) {
            2 -> stringResource(R.string.home_x_tag_separator, tagParts[0], tagParts[1])
            1 -> tagParts[0]
            else -> null
        }
    val reason =
        if (row.isQuarantined) stringResource(R.string.home_attention_on_hold_body) else rowReason(row, strings)
    val time =
        // Keyed on the window day too: after midnight a row of yesterday shows its date, not only a time.
        remember(row.receivedAtWall, locale, today) {
            val instant =
                java.time.Instant
                    .ofEpochMilli(row.receivedAtWall)
                    .atZone(zone)
            if (isOnDay(row.receivedAtWall, today ?: LocalDate.now(zone), zone)) {
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(instant)
            } else {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(instant)
            }
        }
    NjCard(modifier = modifier, onClick = onOpen) {
        Row(verticalAlignment = Alignment.Top) {
            NjTypeIcon(family = ClassRegistry.of(row.classId).family, tint = tone, decorative = true)
            Spacer(Modifier.width(NjSpacing.s + 4.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NjSpacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rowTitle(row, strings),
                        style = MaterialTheme.typography.titleMedium,
                        color = roles.tx,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(NjSpacing.s))
                    Text(text = time, style = MaterialTheme.typography.labelMedium, color = roles.mu, maxLines = 1)
                }
                if (subline.isNotBlank()) {
                    Text(
                        text = subline,
                        style = MaterialTheme.typography.bodyMedium,
                        color = roles.mu,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tags != null) NjBadge(text = tags, color = tone)
            }
            if (!actions.any) {
                Icon(
                    NjIcons.Chevron,
                    contentDescription = null,
                    tint = roles.mu,
                    modifier = Modifier.padding(start = NjSpacing.s).size(20.dp).align(Alignment.CenterVertically),
                )
            }
        }
        if (reason != null) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = roles.mu,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = NjSpacing.s + 4.dp),
            )
        }
        if (actions.any) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = NjSpacing.s + 4.dp),
                horizontalArrangement = Arrangement.spacedBy(NjSpacing.s + 2.dp),
            ) {
                if (actions.release) {
                    NjTonalButton(
                        text = stringResource(R.string.home_action_release),
                        onClick = onRelease,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (actions.blockSimilar) {
                    NjDangerButton(
                        text = stringResource(R.string.home_action_block_similar),
                        onClick = onBlockSimilar,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ by type

/** The "By type" card: one row for each type, with the count and a bar relative to the biggest count. */
@Composable
private fun TypeList(
    rows: List<TypeRow>,
    onOpenClass: (ClassId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    val max = rows.maxOfOrNull { it.count } ?: 0
    NjCard(modifier = modifier, padding = NjSpacing.m) {
        rows.forEach { row ->
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(NjShape.chip)
                        .clickable(role = Role.Button) { onOpenClass(row.classId) }
                        .heightIn(min = 48.dp)
                        .padding(vertical = NjSpacing.s),
                verticalArrangement = Arrangement.spacedBy(NjSpacing.s),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = roles.tx,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = row.count.toString(), style = MaterialTheme.typography.titleMedium, color = roles.mu)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(NjShape.pill)
                        .background(roles.s2),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(typeBarFraction(row.count, max))
                            .fillMaxHeight()
                            .clip(NjShape.pill)
                            .background(roles.acc),
                    )
                }
            }
        }
    }
}
