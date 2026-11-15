package dev.nightjar.app.ui.mode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.nightjar.app.core.settings.UiMode

/**
 * The current detail level.
 *
 * Defaults to [UiMode.SIMPLE], so a screen rendered without the provider — a preview, a test — never leaks Researcher
 * controls. Switching the mode is a settings write and nothing else: no data is created, migrated or deleted.
 */
val LocalMode = staticCompositionLocalOf { UiMode.SIMPLE }

/** True when the composition is in Researcher mode. */
val LocalIsResearcher: Boolean
    @Composable get() = LocalMode.current == UiMode.RESEARCHER

/** Provides [mode] to everything below. */
@Composable
fun ProvideMode(
    mode: UiMode,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMode provides mode, content = content)
}
