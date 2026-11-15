package dev.nightjar.app.core.security

import dev.nightjar.contract.Masker

/**
 * How a phone number is shown.
 *
 * [PLAIN] is the stored value. [MASKED] keeps the first three and last three digits ("+91•••••210") using the shared
 * contract masker, so the UI and the logs cannot disagree about what "masked" means.
 */
enum class NumberDisplay {
    PLAIN,
    MASKED,
    ;

    companion object {
        /** The display for `privacy.maskNumbers` / `privacy.hideInNotifications`. */
        fun forSetting(maskNumbers: Boolean): NumberDisplay = if (maskNumbers) MASKED else PLAIN
    }
}

/** Renders a number for the given [display]; null and blank values pass through unchanged. */
fun formatNumber(
    value: String?,
    display: NumberDisplay,
): String? =
    when {
        value.isNullOrBlank() -> value
        display == NumberDisplay.PLAIN -> value
        else -> Masker.maskNumber(value)
    }
