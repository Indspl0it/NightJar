package dev.nightjar.app.feature.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nightjar.app.ui.components.LocalNjBottomInset
import dev.nightjar.app.ui.components.NjCollapsedTitle
import dev.nightjar.app.ui.components.NjLargeTitle
import dev.nightjar.app.ui.components.NjRowDivider
import dev.nightjar.app.ui.components.NjSectionHeader
import dev.nightjar.app.ui.components.NjShimmerCard
import dev.nightjar.app.ui.components.NjTopBarHeight
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.components.rememberNjTitleCollapsed
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme

/** The gap above a card or a group (redesign 7.2: 10 to 12 dp between cards). */
internal val ToolCardGap = 12.dp

/**
 * The frame of the tool screens: Rules, Rule editor, Known senders, Logs, Audit history and Export.
 *
 * The large title is the first item of the list. The small bar with the back arrow and [actions] stays on top, and its
 * title fades in when the large title scrolls out of view. The list ends above the floating bar ([LocalNjBottomInset])
 * or above [bottomBar] when the screen has one. [listEndGap] is the free space after the last item (more when a
 * [floating] button covers the end of the list).
 */
@Composable
internal fun ToolScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState? = null,
    listState: LazyListState = rememberLazyListState(),
    listEndGap: Dp = NjSpacing.l,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    floating: (@Composable BoxScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val collapsed by rememberNjTitleCollapsed(listState)
    val bottomInset = LocalNjBottomInset.current
    // The shell consumes the status bar inset, so this padding is 0 inside the shell and correct outside it.
    Box(modifier.fillMaxSize().background(NjTheme.roles.bg).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding =
                        PaddingValues(
                            top = NjTopBarHeight,
                            bottom = listEndGap + if (bottomBar == null) bottomInset else 0.dp,
                        ),
                ) {
                    item(key = "title", contentType = "title") {
                        NjLargeTitle(title = title, modifier = Modifier.njEnter(0))
                    }
                    content()
                }
                if (snackbar != null) {
                    SnackbarHost(
                        hostState = snackbar,
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = if (bottomBar == null) bottomInset else 0.dp),
                    )
                }
            }
            if (bottomBar != null) {
                Box(Modifier.fillMaxWidth().padding(bottom = bottomInset)) { bottomBar() }
            }
        }
        NjCollapsedTitle(
            title = title,
            collapsed = collapsed,
            modifier = Modifier.align(Alignment.TopStart),
            onBack = onBack,
            actions = actions,
        )
        floating?.invoke(this)
    }
}

/** The side margins and the gap above one card or one control of a tool screen. */
internal fun Modifier.toolCard(): Modifier = padding(start = NjSpacing.m, end = NjSpacing.m, top = ToolCardGap)

/** A section title above a group of cards (redesign 7.2: about 26 dp above it). */
@Composable
internal fun ToolSection(
    title: String,
    modifier: Modifier = Modifier,
) {
    NjSectionHeader(
        title = title,
        modifier = modifier.padding(start = NjSpacing.m, end = NjSpacing.m, top = NjSpacing.m + 2.dp),
    )
}

/** [count] card placeholders while the screen loads. */
internal fun LazyListScope.toolLoading(count: Int) {
    items(count, key = { "loading-$it" }, contentType = { "loading" }) {
        NjShimmerCard(Modifier.toolCard())
    }
}

private val GroupTopShape = NjShape.card.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize)
private val GroupBottomShape = NjShape.card.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize)

/**
 * One row of a card group (`s1`, 24 dp corners) where each row is its own list item, so a long list stays lazy. The
 * first row carries the gap above the group. Each row after the first starts with a divider, unless [divider] is false
 * (for example for the lines of a log).
 */
@Composable
internal fun ToolGroupRow(
    position: ToolRowPosition,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape =
        when (position) {
            ToolRowPosition.ONLY -> NjShape.card
            ToolRowPosition.FIRST -> GroupTopShape
            ToolRowPosition.MIDDLE -> RectangleShape
            ToolRowPosition.LAST -> GroupBottomShape
        }
    val top = if (position == ToolRowPosition.ONLY || position == ToolRowPosition.FIRST) ToolCardGap else 0.dp
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = NjSpacing.m, end = NjSpacing.m, top = top)
            .clip(shape)
            .background(NjTheme.roles.s1),
    ) {
        if (divider && (position == ToolRowPosition.MIDDLE || position == ToolRowPosition.LAST)) NjRowDivider()
        content()
    }
}
