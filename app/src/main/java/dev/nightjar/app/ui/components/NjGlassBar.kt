package dev.nightjar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import dev.nightjar.app.ui.theme.NjRoles
import dev.nightjar.app.ui.theme.NjTheme

/** The size values of the floating bar. */
object NjGlassBarSpec {
    /** Space between the bar and the left and the right screen edge. */
    val SideMargin: Dp = 24.dp

    /** Space between the bar and the bottom screen edge, when no system bar is there. */
    val BottomMargin: Dp = 28.dp

    val Height: Dp = 64.dp
    val Radius: Dp = 32.dp

    /** Space between the top of the bar and the last list item above it. */
    val ContentGap: Dp = 16.dp

    /** Gap between the bar and the system navigation bar, when that bar is taller than [BottomMargin]. */
    val NavBarGap: Dp = 8.dp

    /** The bar does not grow wider than this on a wide window. */
    val MaxWidth: Dp = 560.dp

    /** Space between the bar edge and the first and the last tab. */
    val InnerPadding: Dp = 6.dp

    /** Space above and below the icon and the label in the selected pill (6 px). */
    val PillPaddingVertical: Dp = 6.dp

    /**
     * Space at the left and the right of the icon and the label in the selected pill (10 px).
     * In a narrow tab cell the space shrinks to [PillPaddingHorizontalMin] before the label is cut.
     */
    val PillPaddingHorizontal: Dp = 10.dp

    /** The smallest space at the left and the right of the pill content, in a narrow tab cell. */
    val PillPaddingHorizontalMin: Dp = 2.dp

    /** Corner radius of the selected pill. */
    val PillRadius: Dp = 20.dp

    /** Space between the icon and the label of a tab. */
    val IconLabelGap: Dp = 2.dp

    /** Elevation of the soft shadow under the bar. */
    val Elevation: Dp = 6.dp
}

/**
 * The bottom inset that the shell gives to the screens while the floating bar is on screen.
 *
 * The floating bar covers the bottom of every tab. A screen adds this value to the bottom `contentPadding` of its
 * list, so that the last item stops above the bar. The value is 0.dp when no bar floats (a Material bar or a rail
 * is in use, or the keyboard is open), because the layout already leaves room then.
 */
val LocalNjBottomInset = compositionLocalOf { 0.dp }

/** Space from the screen bottom edge to the bar: [NjGlassBarSpec.BottomMargin], or more above a tall system bar. */
fun njFloatingBarBottomMargin(navigationBarInset: Dp): Dp =
    max(NjGlassBarSpec.BottomMargin, navigationBarInset + NjGlassBarSpec.NavBarGap)

/** The value for [LocalNjBottomInset]: the bar, the space below it and the gap above it. */
fun njFloatingBarContentInset(navigationBarInset: Dp): Dp =
    njFloatingBarBottomMargin(navigationBarInset) + NjGlassBarSpec.Height + NjGlassBarSpec.ContentGap

/**
 * The largest width (px) of the pill content in a tab cell of [cellMax] px: the cell less [padMin] at each side.
 * [cellMax] is [Constraints.Infinity] when the cell has no width limit.
 */
fun njPillContentMax(
    cellMax: Int,
    padMin: Int,
): Int = if (cellMax == Constraints.Infinity) Constraints.Infinity else (cellMax - 2 * padMin).coerceAtLeast(0)

/**
 * The pill width (px) for content of [content] px: the content plus [padMax] at each side, but never wider than the
 * cell ([cellMax] px). In a narrow cell the side space shrinks, and the content keeps its full width.
 */
fun njPillWidth(
    cellMax: Int,
    content: Int,
    padMax: Int,
): Int = minOf(cellMax, content + 2 * padMax)

/** One tab of [NjGlassBar]. [icon] draws the glyph (and a badge) in the colour of the current content colour. */
@Immutable
class NjGlassBarItem(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val icon: @Composable () -> Unit,
)

/** The colours of [NjGlassBar] (option A of `nightjar-bottombar.html`). */
@Immutable
data class NjGlassBarColors(
    val container: Color,
    val border: Color,
    val pill: Color,
    val selectedContent: Color,
    val content: Color,
)

/** The alpha of the accent in the selected pill: light, so the pill does not stand out too much. */
private const val PILL_ALPHA = 0.14f

/** How far the selected content moves from the accent to the text colour in dark mode (a softer accent). */
private const val SELECTED_TO_TEXT = 0.3f

/**
 * The bar colours from the NightJar [roles]. The Monet bar was too grey and its pill
 * too strong, so the bar has the card colour with a thin outline, the selected pill is the accent at 14 %, and the
 * other tabs use the muted text colour.
 */
fun njGlassBarColors(roles: NjRoles): NjGlassBarColors =
    NjGlassBarColors(
        container = roles.s1,
        border = roles.line,
        pill = roles.acc.copy(alpha = PILL_ALPHA),
        selectedContent = if (roles.isDark) lerp(roles.acc, roles.tx, SELECTED_TO_TEXT) else roles.acc,
        content = roles.mu,
    )

/** The bar colours of the current theme. */
@Composable
fun rememberNjGlassBarColors(): NjGlassBarColors = njGlassBarColors(NjTheme.roles)

/**
 * The floating tab bar: 64 dp high with a 32 dp radius, solid (no blur and no see-through),
 * in the card colour with a thin outline and a soft shadow.
 *
 * The tabs have equal widths and the same alignment. The selected pill wraps the icon and the label with
 * [NjGlassBarSpec.PillPaddingVertical] above and below and up to [NjGlassBarSpec.PillPaddingHorizontal] at the sides.
 * In a narrow tab cell (small screen or large font) the side space shrinks to [NjGlassBarSpec.PillPaddingHorizontalMin],
 * so the label gets the full cell width before it is cut with an ellipsis. The labels have the size of `labelMedium`:
 * the selected label is Medium, the others are Normal (500 and regular). The whole tab cell is the touch target.
 * The caller places the bar: this composable only sets its height and its shape.
 */
@Composable
fun NjGlassBar(
    items: List<NjGlassBarItem>,
    modifier: Modifier = Modifier,
    navigationDescription: String = "",
    colors: NjGlassBarColors = rememberNjGlassBarColors(),
) {
    val shape: Shape = RoundedCornerShape(NjGlassBarSpec.Radius)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(NjGlassBarSpec.Height)
                .shadow(elevation = NjGlassBarSpec.Elevation, shape = shape)
                .background(colors.container, shape)
                .border(1.dp, colors.border, shape)
                .clip(shape)
                .semantics { if (navigationDescription.isNotBlank()) contentDescription = navigationDescription }
                .selectableGroup()
                .padding(horizontal = NjGlassBarSpec.InnerPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            GlassBarTab(item, colors, Modifier.weight(1f))
        }
    }
}

@Composable
private fun GlassBarTab(
    item: NjGlassBarItem,
    colors: NjGlassBarColors,
    modifier: Modifier = Modifier,
) {
    val tint = if (item.selected) colors.selectedContent else colors.content
    val pillShape = RoundedCornerShape(NjGlassBarSpec.PillRadius)
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .selectable(selected = item.selected, role = Role.Tab, onClick = item.onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) {
            Column(
                modifier =
                    Modifier
                        .clip(pillShape)
                        .background(if (item.selected) colors.pill else Color.Transparent)
                        .pillHorizontalPadding()
                        .padding(vertical = NjGlassBarSpec.PillPaddingVertical),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NjGlassBarSpec.IconLabelGap),
            ) {
                item.icon()
                Text(
                    text = item.label,
                    style =
                        MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (item.selected) FontWeight.Medium else FontWeight.Normal,
                        ),
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The side space of the pill (M3): up to [NjGlassBarSpec.PillPaddingHorizontal], less in a narrow cell. The content is
 * measured with the cell width less [NjGlassBarSpec.PillPaddingHorizontalMin] at each side, and it stays centred. The
 * clip and the background before this modifier draw the pill at the final width.
 */
private fun Modifier.pillHorizontalPadding(): Modifier =
    layout { measurable, constraints ->
        val padMin = NjGlassBarSpec.PillPaddingHorizontalMin.roundToPx()
        val padMax = NjGlassBarSpec.PillPaddingHorizontal.roundToPx()
        val contentMax = njPillContentMax(constraints.maxWidth, padMin)
        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = contentMax))
        val width = njPillWidth(constraints.maxWidth, placeable.width, padMax).coerceAtLeast(constraints.minWidth)
        layout(width, placeable.height) {
            placeable.place((width - placeable.width) / 2, 0)
        }
    }
