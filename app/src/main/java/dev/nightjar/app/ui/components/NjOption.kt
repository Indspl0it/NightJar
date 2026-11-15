package dev.nightjar.app.ui.components

/** One selectable value with its label; used by [NjFilterChips] and [NjChoiceSheet]. */
data class NjOption<T>(
    val value: T,
    val label: String,
    val supporting: String? = null,
)
