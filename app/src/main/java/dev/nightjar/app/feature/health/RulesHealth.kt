package dev.nightjar.app.feature.health

import dev.nightjar.app.core.ipc.HookHealth

/**
 * What the payload reports about the rules snapshot that it applied. [HealthUiState.rules] is null when no
 * payload session or no health document exists, so Health shows no rules row.
 */
data class RulesHealth(
    /** The exception class name the payload reported. Never message text. */
    val error: String? = null,
    /** The payload applied another revision than the current rules of the app. */
    val behind: Boolean = false,
    /** The number of rules in the applied snapshot that the payload cannot use. */
    val skipped: Int = 0,
)

/**
 * [health] is the last hook-health document, [appRevision] the revision of the current rules of the app (the
 * revision that RulesSync pushes). Health refreshes at each health push, so a revision that differs after a push is a
 * snapshot that the payload did not apply.
 */
fun rulesHealth(
    health: HookHealth?,
    appRevision: Long?,
): RulesHealth? {
    if (health == null) return null
    val payloadRevision = health.rulesRevision
    return RulesHealth(
        error = health.rulesError?.let(::errorClassName),
        behind = payloadRevision != null && appRevision != null && payloadRevision != appRevision,
        skipped = (health.rulesSkipped ?: 0).coerceAtLeast(0),
    )
}

/**
 * The payload sends a class name only. The screen keeps the simple name and only identifier characters, so a value from
 * a different payload cannot put message text on the screen. An error with no usable name is still an error.
 */
internal fun errorClassName(raw: String): String =
    raw
        .substringAfterLast('.')
        .filter { it.isLetterOrDigit() || it == '_' || it == '$' }
        .take(MAX_ERROR_CHARS)
        .ifEmpty { FALLBACK_ERROR }

private const val MAX_ERROR_CHARS = 64
private const val FALLBACK_ERROR = "Exception"
