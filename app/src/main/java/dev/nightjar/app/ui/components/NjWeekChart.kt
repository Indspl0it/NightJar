package dev.nightjar.app.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme

/** Tallest bar. */
private val MaxBarHeight = 104.dp

/** Shortest bar, so a bar of 0 stays visible and tappable. */
private val MinBarHeight = 12.dp

private val BarShape = RoundedCornerShape(12.dp)

/** Bar height as a fraction of the tallest bar: 0 for no data, never less than [minFraction] for a bar with a value. */
internal fun barFractions(
    values: List<Int>,
    minFraction: Float,
): List<Float> {
    val max = values.maxOrNull()?.coerceAtLeast(0) ?: 0
    return values.map { v ->
        if (max == 0) minFraction else (v.coerceAtLeast(0).toFloat() / max).coerceAtLeast(minFraction)
    }
}

/**
 * Bars for 7 days. The last bar is in the accent colour, the others in `s3`. The number is above each bar and the day
 * name below. Each bar is a button: a tap calls [onDayClick] with the index in [bars]. TalkBack reads "day: number".
 */
@Composable
fun NjWeekChart(
    bars: List<NjWeekBar>,
    onDayClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    hint: String? = null,
) {
    val fractions = barFractions(bars.map { it.value }, MinBarHeight / MaxBarHeight)
    NjCard(modifier = modifier, padding = NjSpacing.m + 2.dp) {
        if (title != null || hint != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (title != null) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = NjTheme.roles.tx,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.labelLarge, color = NjTheme.roles.mu)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = NjSpacing.s),
            horizontalArrangement = Arrangement.spacedBy(NjSpacing.s),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEachIndexed { index, bar ->
                BarColumn(
                    bar = bar,
                    barHeight = MaxBarHeight * fractions[index],
                    last = index == bars.lastIndex,
                    onClick = { onDayClick(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BarColumn(
    bar: NjWeekBar,
    barHeight: Dp,
    last: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    val source = remember { MutableInteractionSource() }
    val description = stringResource(R.string.nj_cmp_week_bar_cd, bar.label, bar.value)
    Column(
        modifier =
            modifier
                .njPressScale(source)
                .clip(BarShape)
                .clickable(
                    interactionSource = source,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics { contentDescription = description }
                .heightIn(min = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Column(Modifier.clearAndSetSemantics { }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = bar.value.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = roles.mu,
                maxLines = 1,
            )
            Box(
                Modifier
                    .padding(top = NjSpacing.xs, bottom = NjSpacing.xs)
                    .fillMaxWidth()
                    .height(barHeight)
                    .clip(BarShape)
                    .background(if (last) roles.acc else roles.s3),
            )
            Text(
                text = bar.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (last) roles.tx else roles.mu,
                fontWeight = if (last) FontWeight.Bold else null,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PreviewChart() {
    val labels = stringResource(R.string.nj_cmp_preview_days).split(",")
    val values = listOf(4, 6, 3, 9, 7, 12, 6)
    NjWeekChart(
        bars = labels.zip(values) { l, v -> NjWeekBar(l, v) },
        onDayClick = {},
        title = stringResource(R.string.nj_cmp_preview_chart_title),
        hint = stringResource(R.string.nj_cmp_preview_chart_hint),
        modifier = Modifier.padding(NjSpacing.m),
    )
}

@Preview(name = "Week chart dark", widthDp = 400)
@Composable
private fun NjWeekChartDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewChart() }
}

@Preview(name = "Week chart light", widthDp = 400)
@Composable
private fun NjWeekChartLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { PreviewChart() }
}
