package dev.nightjar.app.feature.health

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.ui.components.LocalNjBottomInset
import dev.nightjar.app.ui.components.NjGroup
import dev.nightjar.app.ui.components.NjIcons
import dev.nightjar.app.ui.components.NjRowDivider
import dev.nightjar.app.ui.components.NjTopBarHeight
import dev.nightjar.app.ui.components.NjTypeGlyphs
import dev.nightjar.app.ui.components.njEnter
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.PreviewTheme

/** A static page, no data. */
@Composable
fun BlindSpotsScreen(onBack: () -> Unit) {
    BlindSpotsContent(onBack = onBack, onePlusNote = isOnePlusFamily())
}

/** One limit of the page: a glyph, a short title, one line, and the 1 or 2 sentences that a tap shows. */
private data class BlindSpot(
    val icon: ImageVector,
    @StringRes val title: Int,
    @StringRes val line: Int,
    @StringRes val more: Int,
)

/**
 * A normal top bar with a title that wraps to 2 lines, one intro line, then one row for each limit in a
 * group. A tap on a row shows 1 or 2 more sentences. The list ends above the floating bar ([LocalNjBottomInset]).
 */
@Composable
fun BlindSpotsContent(
    onePlusNote: Boolean,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val spots =
        listOfNotNull(
            BlindSpot(HealthIcons.Chip, R.string.blindspot_modem_title, R.string.blindspot_modem_line, R.string.blindspot_modem_more),
            BlindSpot(HealthIcons.Cloud, R.string.blindspot_rcs_title, R.string.blindspot_rcs_line, R.string.blindspot_rcs_more),
            BlindSpot(
                NjTypeGlyphs.Apps,
                R.string.blindspot_delivery_title,
                R.string.blindspot_delivery_line,
                R.string.blindspot_delivery_more,
            ),
            BlindSpot(NjIcons.Lock, R.string.blindspot_unlock_title, R.string.blindspot_unlock_line, R.string.blindspot_unlock_more),
            // The OnePlus/Oppo note only applies on those builds.
            BlindSpot(
                HealthIcons.Phone,
                R.string.blindspot_oneplus_title,
                R.string.blindspot_oneplus_line,
                R.string.blindspot_oneplus_more,
            ).takeIf { onePlusNote },
        )
    Column(modifier.fillMaxSize().background(NjTheme.roles.bg)) {
        TopBar(title = stringResource(R.string.screen_s09b), onBack = onBack)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = NjSpacing.s, bottom = LocalNjBottomInset.current + NjSpacing.l),
            verticalArrangement = Arrangement.spacedBy(NjSpacing.m),
        ) {
            item(key = "intro") {
                Text(
                    stringResource(R.string.blindspot_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NjTheme.roles.mu,
                    modifier = Modifier.padding(horizontal = NjSpacing.m).njEnter(0),
                )
            }
            item(key = "spots") {
                NjGroup(Modifier.padding(horizontal = NjSpacing.m).njEnter(1)) {
                    spots.forEachIndexed { index, spot ->
                        if (index > 0) NjRowDivider()
                        SpotRow(spot)
                    }
                }
            }
        }
    }
}

/**
 * The top bar of the page: the back arrow and the title at the size of the small top bar. The title wraps to a second
 * line when it is long, and it is never cut.
 */
@Composable
private fun TopBar(
    title: String,
    onBack: (() -> Unit)?,
) {
    val roles = NjTheme.roles
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = NjTopBarHeight)
                .padding(horizontal = NjSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(NjIcons.Back, contentDescription = stringResource(R.string.action_back), tint = roles.tx)
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = roles.tx,
            maxLines = 2,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = NjSpacing.s, vertical = NjSpacing.xs)
                    .semantics { heading() },
        )
    }
}

/** One limit: glyph, title, one line and a chevron. A tap shows or hides the more sentences under the line. */
@Composable
private fun SpotRow(spot: BlindSpot) {
    val roles = NjTheme.roles
    var expanded by rememberSaveable(spot.title) { mutableStateOf(false) }
    // TalkBack says whether the more sentences are open (review screens-trust L7).
    val rowState = stringResource(if (expanded) R.string.cd_row_open else R.string.cd_row_closed)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = rowState }
            .heightIn(min = 56.dp)
            .padding(horizontal = NjSpacing.m, vertical = NjSpacing.s + 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(spot.icon, contentDescription = null, tint = roles.acc, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(NjSpacing.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(spot.title), style = MaterialTheme.typography.bodyLarge, color = roles.tx)
                Text(stringResource(spot.line), style = MaterialTheme.typography.bodyMedium, color = roles.mu)
            }
            Spacer(Modifier.width(NjSpacing.xs))
            // The chevron points down when the row is closed and up when it is open.
            Icon(
                NjIcons.Chevron,
                contentDescription = null,
                tint = roles.mu,
                modifier = Modifier.size(20.dp).rotate(if (expanded) -90f else 90f),
            )
        }
        if (expanded) {
            Text(
                stringResource(spot.more),
                style = MaterialTheme.typography.bodyMedium,
                color = roles.mu,
                modifier = Modifier.padding(start = 22.dp + NjSpacing.m, top = NjSpacing.s),
            )
        }
    }
}

/** The OnePlus/Oppo note only applies on those builds. */
private fun isOnePlusFamily(): Boolean {
    val maker = Build.MANUFACTURER.lowercase()
    val brand = Build.BRAND.lowercase()
    return "oneplus" in maker || "oneplus" in brand || "oppo" in maker || "oppo" in brand
}

@Preview(name = "S-09b blind spots light")
@Composable
private fun BlindSpotsLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) {
        BlindSpotsContent(onePlusNote = true, onBack = {})
    }
}

@Preview(name = "S-09b blind spots dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BlindSpotsDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        BlindSpotsContent(onePlusNote = false, onBack = {})
    }
}
