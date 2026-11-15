package dev.nightjar.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.PreviewTheme

/**
 * A settings row that opens another screen: optional [leadingIcon] in an `s2` square, [title], an optional [supporting]
 * line, an optional [value] on the right and a chevron. The whole row is a button, 56 dp high or more. Use inside
 * [NjGroup]; for a toggle use [NjSwitchRow].
 */
@Suppress("ComposableLambdaParameterNaming") // An optional trailing slot, not the main content. Callers pass it by name.
@Composable
fun NjNavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leadingIcon: ImageVector? = null,
    value: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    NjListRow(
        title = title,
        modifier = modifier,
        supporting = supporting,
        leadingIcon = leadingIcon,
        trailingValue = value,
        trailing = trailing,
        onClick = onClick,
        showChevron = true,
    )
}

@Composable
private fun PreviewSettingsRows() {
    NjGroup(modifier = Modifier.padding(NjSpacing.m)) {
        NjNavRow(
            title = stringResource(R.string.nj_cmp_preview_row_nav),
            supporting = stringResource(R.string.nj_cmp_preview_row_nav_sub),
            leadingIcon = NjIcons.Appearance,
            value = stringResource(R.string.nj_cmp_preview_row_value),
            onClick = {},
        )
        NjRowDivider()
        NjSwitchRow(
            title = stringResource(R.string.nj_cmp_preview_row_switch),
            checked = true,
            onCheckedChange = {},
            leadingIcon = NjIcons.Bell,
        )
    }
}

@Preview(name = "Settings rows dark")
@Composable
private fun NjSettingsRowsDarkPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) { PreviewSettingsRows() }
}

@Preview(name = "Settings rows light")
@Composable
private fun NjSettingsRowsLightPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) { PreviewSettingsRows() }
}
