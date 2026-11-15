package dev.nightjar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.domain.ClassFamily
import dev.nightjar.app.ui.theme.PreviewTheme

@Composable
internal fun familyLabel(family: ClassFamily): String =
    stringResource(
        when (family) {
            ClassFamily.SILENT -> R.string.family_silent
            ClassFamily.SIM -> R.string.family_sim
            ClassFamily.WAP -> R.string.family_wap
            ClassFamily.VOICEMAIL -> R.string.family_voicemail
            ClassFamily.APP -> R.string.family_app
            ClassFamily.BROADCAST -> R.string.family_broadcast
            ClassFamily.OUTBOUND -> R.string.family_outbound
            ClassFamily.SYSTEM -> R.string.family_system
        },
    )

/** A skeleton row while the feed loads ("never a blank screen"). */
@Composable
fun SkeletonRow(
    modifier: Modifier = Modifier,
    widthFraction: Float = 0.6f,
) {
    val description = stringResource(R.string.cd_skeleton_row)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(widthFraction)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Box(
                Modifier
                    .fillMaxWidth(widthFraction * 0.7f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
    }
}

// ------------------------------------------------------------------ previews (light and dark, AC)

@Preview(name = "Skeleton row light")
@Composable
private fun SkeletonRowLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { SkeletonRow() }
}

@Preview(name = "Skeleton row dark")
@Composable
private fun SkeletonRowDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { SkeletonRow() }
}
