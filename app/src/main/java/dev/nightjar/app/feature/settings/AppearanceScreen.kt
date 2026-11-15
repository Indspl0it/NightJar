package dev.nightjar.app.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.Settings
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.ui.components.NjCard
import dev.nightjar.app.ui.components.NjGroup
import dev.nightjar.app.ui.components.NjIcons
import dev.nightjar.app.ui.components.NjOption
import dev.nightjar.app.ui.components.NjRowDivider
import dev.nightjar.app.ui.components.NjSectionHeader
import dev.nightjar.app.ui.components.NjSegmented
import dev.nightjar.app.ui.components.NjSwitchRow
import dev.nightjar.app.ui.theme.NjAccent
import dev.nightjar.app.ui.theme.NjRoles
import dev.nightjar.app.ui.theme.NjShape
import dev.nightjar.app.ui.theme.NjSpacing
import dev.nightjar.app.ui.theme.NjSurfaceMode
import dev.nightjar.app.ui.theme.NjTheme
import dev.nightjar.app.ui.theme.njRoles

/**
 * What the groups Theme and Colour of the Appearance screen can change. One lambda per setting, so the screen has no
 * ViewModel type in it.
 */
internal class AppearanceColourActions(
    val onTheme: (ThemeMode) -> Unit,
    val onAccent: (NjAccent) -> Unit,
    val onDynamicColor: (Boolean) -> Unit,
    val onTrueBlack: (Boolean) -> Unit,
)

/** What the groups Surfaces and Motion of the Appearance screen can change. One lambda per setting. */
internal class AppearanceMotionActions(
    val onFloatingBar: (Boolean) -> Unit,
    val onListAnimations: (Boolean) -> Unit,
    val onListBounce: (Boolean) -> Unit,
    val onReduceMotion: (Boolean) -> Unit,
    val onHaptics: (Boolean) -> Unit,
)

/** The theme mode the picker shows. An old [ThemeMode.BLACK] shows as Dark: true black is its own switch. */
internal fun pickerThemeMode(theme: ThemeMode): ThemeMode = if (theme == ThemeMode.BLACK) ThemeMode.DARK else theme

/** True when the surfaces in the preview are dark, for the chosen [theme] and the system setting [systemDark]. */
internal fun previewDark(
    theme: ThemeMode,
    systemDark: Boolean,
): Boolean =
    when (theme) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.BLACK -> true
    }

/** The surface mode of the preview: black only in a dark theme. */
internal fun previewSurfaceMode(
    settings: Settings,
    systemDark: Boolean,
): NjSurfaceMode =
    when {
        !previewDark(settings.theme, systemDark) -> NjSurfaceMode.LIGHT
        settings.effectiveTrueBlack -> NjSurfaceMode.BLACK
        else -> NjSurfaceMode.DARK
    }

/**
 * Appearance: the live preview card, then the groups Theme, Colour,
 * Surfaces and Motion. Each change goes to the settings store. `MainActivity` collects the store, so the whole app
 * follows at once.
 */
@Composable
internal fun ColumnScope.AppearanceContent(
    settings: Settings,
    colour: AppearanceColourActions,
    motion: AppearanceMotionActions,
) {
    val s = settings
    AppearancePreview(s)

    NjSectionHeader(stringResource(R.string.nj_set_appearance_section_theme))
    NjGroup {
        Column(Modifier.padding(NjSpacing.m), verticalArrangement = Arrangement.spacedBy(NjSpacing.m)) {
            NjSegmented(
                options =
                    listOf(
                        NjOption(ThemeMode.SYSTEM, stringResource(R.string.theme_system)),
                        NjOption(ThemeMode.LIGHT, stringResource(R.string.theme_light)),
                        NjOption(ThemeMode.DARK, stringResource(R.string.theme_dark)),
                    ),
                selected = pickerThemeMode(s.theme),
                onSelect = colour.onTheme,
            )
            Text(stringResource(R.string.nj_set_appearance_colour), style = MaterialTheme.typography.titleMedium)
            AccentPicker(
                selected = s.accent,
                enabled = !s.dynamicColor,
                dark = previewDark(s.theme, isSystemInDarkTheme()),
                onSelect = colour.onAccent,
            )
        }
    }

    NjSectionHeader(stringResource(R.string.nj_set_appearance_section_colour))
    NjGroup {
        NjSwitchRow(
            title = stringResource(R.string.nj_set_appearance_wallpaper),
            supporting = stringResource(R.string.nj_set_appearance_wallpaper_sub),
            checked = s.dynamicColor,
            onCheckedChange = colour.onDynamicColor,
        )
        NjRowDivider()
        NjSwitchRow(
            title = stringResource(R.string.nj_set_appearance_true_black),
            supporting = stringResource(R.string.nj_set_appearance_true_black_sub),
            checked = s.effectiveTrueBlack,
            onCheckedChange = colour.onTrueBlack,
        )
    }

    NjSectionHeader(stringResource(R.string.nj_set_appearance_section_surfaces))
    NjGroup {
        NjSwitchRow(
            title = stringResource(R.string.nj_set_appearance_floating_bar),
            supporting = stringResource(R.string.nj_set_appearance_floating_bar_sub),
            checked = s.floatingBar,
            onCheckedChange = motion.onFloatingBar,
        )
    }

    NjSectionHeader(stringResource(R.string.nj_set_appearance_section_motion))
    NjGroup {
        NjSwitchRow(
            title = stringResource(R.string.nj_set_appearance_list_animations),
            supporting = stringResource(R.string.nj_set_appearance_list_animations_sub),
            checked = s.listAnimations,
            onCheckedChange = motion.onListAnimations,
            enabled = !s.reduceMotion,
        )
        NjRowDivider()
        NjSwitchRow(
            title = stringResource(R.string.nj_set_appearance_bounce),
            supporting = stringResource(R.string.nj_set_appearance_bounce_sub),
            checked = s.listBounce,
            onCheckedChange = motion.onListBounce,
            enabled = !s.reduceMotion,
        )
        NjRowDivider()
        NjSwitchRow(
            title = stringResource(R.string.nj_set_appearance_reduce_motion),
            supporting = stringResource(R.string.nj_set_appearance_reduce_motion_sub),
            checked = s.reduceMotion,
            onCheckedChange = motion.onReduceMotion,
        )
        NjRowDivider()
        NjSwitchRow(
            title = stringResource(R.string.nj_set_appearance_haptics),
            supporting = stringResource(R.string.nj_set_appearance_haptics_sub),
            checked = s.haptics,
            onCheckedChange = motion.onHaptics,
        )
    }
}

@StringRes
private fun accentName(accent: NjAccent): Int =
    when (accent) {
        NjAccent.NIGHT_BLUE -> R.string.nj_set_accent_night_blue
        NjAccent.MOSS -> R.string.nj_set_accent_moss
        NjAccent.EMBER -> R.string.nj_set_accent_ember
        NjAccent.ORCHID -> R.string.nj_set_accent_orchid
        NjAccent.MONO -> R.string.nj_set_accent_mono
    }

/** One swatch per colour theme. The selected swatch has a ring in its own colour and a label. */
@Composable
private fun AccentPicker(
    selected: NjAccent,
    enabled: Boolean,
    dark: Boolean,
    onSelect: (NjAccent) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup().alpha(if (enabled) 1f else 0.4f),
        horizontalArrangement = Arrangement.spacedBy(NjSpacing.s),
    ) {
        NjAccent.entries.forEach { accent ->
            val isSelected = accent == selected
            val name = stringResource(accentName(accent))
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(NjShape.chip)
                        .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) { onSelect(accent) }
                        .padding(vertical = NjSpacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NjSpacing.xs),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(SWATCH_OUTER)
                            .then(if (isSelected) Modifier.border(2.dp, accent.color(dark), CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(if (isSelected) SWATCH_INNER_SELECTED else SWATCH_OUTER)
                            .clip(CircleShape)
                            .background(accent.color(dark)),
                    )
                }
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) NjTheme.roles.tx else NjTheme.roles.mu,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The live preview card: a status row with the surfaces and the accent of the current settings, so a
 * change shows here before the user leaves the screen.
 */
@Composable
private fun AppearancePreview(settings: Settings) {
    val systemDark = isSystemInDarkTheme()
    val mode = previewSurfaceMode(settings, systemDark)
    val dark = mode != NjSurfaceMode.LIGHT
    // With wallpaper colours on, the real accent is `primary` of the live theme.
    val liveAccent = MaterialTheme.colorScheme.primary
    val accent = if (settings.dynamicColor) liveAccent else settings.accent.color(dark)
    val roles: NjRoles = remember(mode, accent) { njRoles(mode, accent) }
    // Text on the accent: the dark background in dark modes, white (s1) in the light mode.
    val onAccent = if (dark) roles.bg else roles.s1
    val description = stringResource(R.string.nj_set_appearance_preview_cd)
    val locale = LocalConfiguration.current.locales[0]
    NjCard(
        modifier = Modifier.semantics { contentDescription = description },
        container = roles.s1,
    ) {
        Text(
            stringResource(R.string.nj_set_appearance_preview_title).uppercase(locale),
            style = MaterialTheme.typography.labelMedium,
            color = roles.mu,
        )
        Spacer(Modifier.height(NjSpacing.s))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(NjShape.group)
                .background(roles.bg)
                .padding(NjSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NjSpacing.s),
        ) {
            Box(Modifier.size(44.dp).clip(NjShape.iconSquare).background(roles.s2), contentAlignment = Alignment.Center) {
                Icon(NjIcons.Shield, contentDescription = null, tint = roles.acc, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.nj_set_appearance_preview_headline),
                    style = MaterialTheme.typography.titleMedium,
                    color = roles.tx,
                )
                Text(
                    stringResource(R.string.nj_set_appearance_preview_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = roles.mu,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(R.string.nj_set_appearance_preview_action),
                style = MaterialTheme.typography.labelLarge,
                color = onAccent,
                modifier = Modifier.clip(NjShape.pill).background(roles.acc).padding(horizontal = NjSpacing.m, vertical = NjSpacing.s),
            )
        }
    }
}

private val SWATCH_OUTER = 50.dp
private val SWATCH_INNER_SELECTED = 40.dp
