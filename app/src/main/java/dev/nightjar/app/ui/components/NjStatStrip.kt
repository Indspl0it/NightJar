package dev.nightjar.app.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme

/** Radius of the 32 dp icon square in a stat column. */
private val StatIconShape =
    androidx.compose.foundation.shape
        .RoundedCornerShape(10.dp)

/**
 * One card with equal columns (the screens use 4). Each column is a button: an icon in a 32 dp square, the number in
 * 26 sp bold and the label in 12 sp. TalkBack reads "label, number". Do not use a 2 by 2 grid of large tiles.
 */
@Composable
fun NjStatStrip(
    stats: List<NjStat>,
    modifier: Modifier = Modifier,
) {
    NjCard(modifier = modifier, padding = NjSpacing.s) {
        Row {
            stats.forEach { stat -> StatColumn(stat, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun StatColumn(
    stat: NjStat,
    modifier: Modifier = Modifier,
) {
    val roles = NjTheme.roles
    val source = remember { MutableInteractionSource() }
    val description = stringResource(R.string.nj_cmp_stat_cd, stat.label, stat.value)
    Column(
        modifier =
            modifier
                .njPressScale(source)
                .clip(StatColumnShape)
                .clickable(
                    interactionSource = source,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = stat.onClick,
                ).semantics { contentDescription = description }
                .heightIn(min = 48.dp)
                .padding(vertical = NjSpacing.s, horizontal = NjSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The merged description above replaces the child texts, so the icon and texts stay out of the tree.
        Column(Modifier.clearAndSetSemantics { }, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(32.dp).clip(StatIconShape).background(roles.s2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(stat.icon, contentDescription = null, tint = stat.tint, modifier = Modifier.size(18.dp))
            }
            Text(
                text = stat.value.toString(),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, lineHeight = 32.sp),
                color = roles.tx,
                maxLines = 1,
                modifier = Modifier.padding(top = NjSpacing.s),
            )
            Text(
                text = stat.label,
                style = MaterialTheme.typography.labelMedium,
                color = roles.mu,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val StatColumnShape =
    androidx.compose.foundation.shape
        .RoundedCornerShape(16.dp)

@Composable
private fun PreviewStrip() {
    val roles = NjTheme.roles
    NjStatStrip(
        stats =
            listOf(
                NjStat(NjLineIcons.Hidden, 47, stringResource(R.string.nj_cmp_preview_hidden), roles.acc) {},
                NjStat(NjLineIcons.Suspicious, 3, stringResource(R.string.nj_cmp_preview_suspicious), roles.sus) {},
                NjStat(NjLineIcons.OnHold, 2, stringResource(R.string.nj_cmp_preview_on_hold), roles.amb) {},
                NjStat(NjLineIcons.Blocked, 5, stringResource(R.string.nj_cmp_preview_blocked), roles.hi) {},
            ),
        modifier = Modifier.padding(NjSpacing.m),
    )
}

@Preview(name = "Stat strip dark", widthDp = 400)
@Composable
private fun NjStatStripDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewStrip() }
}

@Preview(name = "Stat strip light", widthDp = 400)
@Composable
private fun NjStatStripLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { PreviewStrip() }
}
