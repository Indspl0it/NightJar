package dev.nightjar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme
import kotlinx.coroutines.launch

/**
 * A list row with two swipe actions. Swipe to the right: "Mark as reviewed" (colour `ok`). Swipe to the left: "Block"
 * (colour `hi`). The row always returns to its place after the swipe. The caller changes the list in [onReview] or
 * [onBlock]. A swipe is not reachable with TalkBack, so both actions are also custom accessibility actions of the row.
 * Put the row content (for example an [NjCard]) in [content].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NjSwipeRow(
    onReview: () -> Unit,
    onBlock: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val review by rememberUpdatedState(onReview)
    val block by rememberUpdatedState(onBlock)
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val reviewLabel = stringResource(R.string.nj_cmp_swipe_review)
    val blockLabel = stringResource(R.string.nj_cmp_swipe_block)
    val roles = NjTheme.roles
    SwipeToDismissBox(
        state = state,
        modifier =
            modifier.semantics {
                customActions =
                    listOf(
                        CustomAccessibilityAction(reviewLabel) {
                            onReview()
                            true
                        },
                        CustomAccessibilityAction(blockLabel) {
                            onBlock()
                            true
                        },
                    )
            },
        backgroundContent = {
            when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    SwipeBackground(roles.ok, NjIcons.Check, reviewLabel, end = false)
                }

                SwipeToDismissBoxValue.EndToStart -> {
                    SwipeBackground(roles.hi, NjIcons.Close, blockLabel, end = true)
                }

                SwipeToDismissBoxValue.Settled -> {
                    Unit
                }
            }
        },
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> review()
                SwipeToDismissBoxValue.EndToStart -> block()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            scope.launch { state.reset() }
        },
        content = content,
    )
}

@Composable
private fun SwipeBackground(
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    end: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .clip(NjShape.card)
                .background(badgeContainer(NjTheme.roles, color))
                .padding(horizontal = NjSpacing.l),
        horizontalArrangement = if (end) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = color,
            modifier = Modifier.padding(start = NjSpacing.s),
        )
    }
}

@Preview(name = "Swipe row dark", widthDp = 400)
@Composable
private fun NjSwipeRowPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        NjSwipeRow(onReview = {}, onBlock = {}, modifier = Modifier.padding(NjSpacing.m)) {
            NjCard(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.nj_cmp_preview_row_nav), color = NjTheme.roles.tx)
            }
        }
    }
}
