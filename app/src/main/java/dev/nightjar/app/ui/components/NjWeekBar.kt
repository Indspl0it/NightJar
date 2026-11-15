package dev.nightjar.app.ui.components

import androidx.compose.runtime.Immutable

/** One bar of [NjWeekChart]: the short day name under the bar and the number above it. */
@Immutable
data class NjWeekBar(
    val label: String,
    val value: Int,
)
