package dev.nightjar.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme

/** The list scrolls this far (about the height of the large title) before the small title shows. */
internal val NjTitleCollapseDistance = 48.dp

/** True when a list at [firstIndex] and [offsetPx] has scrolled past [thresholdPx]. Any item after the first counts. */
internal fun titleCollapsed(
    firstIndex: Int,
    offsetPx: Int,
    thresholdPx: Float,
): Boolean = firstIndex > 0 || offsetPx > thresholdPx

/** True when [listState] has scrolled far enough that the large title is out of view. Read it to drive [NjCollapsedTitle]. */
@Composable
fun rememberNjTitleCollapsed(listState: LazyListState): State<Boolean> {
    val threshold = with(LocalDensity.current) { NjTitleCollapseDistance.toPx() }
    return remember(listState, threshold) {
        derivedStateOf {
            titleCollapsed(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, threshold)
        }
    }
}

/**
 * The large title (28 sp bold) at the top of a screen, with an optional [subtitle] line above it (for example
 * the date) and [actions] on the right. Put it as the first item of the list. When the list scrolls, show
 * [NjCollapsedTitle] above the list, driven by [rememberNjTitleCollapsed].
 */
@Composable
fun NjLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = NjSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NjTheme.roles.mu,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.displaySmall,
                color = NjTheme.roles.tx,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(NjSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/** Height of the small top bar ([NjCollapsedTitle]). */
val NjTopBarHeight = 56.dp

/**
 * The small top bar that replaces the large title when the list scrolls: [title] at 18 sp, an optional back arrow
 * ([onBack]) and [actions]. The bar and the title fade in when [collapsed] becomes true. The fade is instant when
 * animations are off. [backIcon] and [backLabel] replace the arrow, for example with a close icon that ends a selection.
 */
@Composable
fun NjCollapsedTitle(
    title: String,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backIcon: ImageVector = NjIcons.Back,
    backLabel: String = stringResource(R.string.action_back),
    actions: @Composable RowScope.() -> Unit = {},
) {
    val motion = currentNjMotion()
    val fade by animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = motion.tabChangeSpec<Float>(),
        label = "njCollapsedTitle",
    )
    val roles = NjTheme.roles
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(roles.glass.copy(alpha = roles.glass.alpha * fade))
                .heightIn(min = NjTopBarHeight),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = NjTopBarHeight).padding(horizontal = NjSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(backIcon, contentDescription = backLabel, tint = roles.tx)
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = roles.tx,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = NjSpacing.s)
                        .alpha(fade)
                        .then(if (collapsed) Modifier.semantics { heading() } else Modifier),
            )
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Preview(name = "Large title dark", widthDp = 400)
@Composable
private fun NjLargeTitlePreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        Column(Modifier.background(NjTheme.roles.bg), verticalArrangement = Arrangement.spacedBy(NjSpacing.m)) {
            NjLargeTitle(title = stringResource(R.string.nj_cmp_preview_title), subtitle = stringResource(R.string.nj_cmp_preview_date))
            NjCollapsedTitle(title = stringResource(R.string.nj_cmp_preview_title), collapsed = true)
        }
    }
}
