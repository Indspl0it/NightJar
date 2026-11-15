package dev.nightjar.app.domain.cb

/**
 * Preview rule: one line, at most 120 characters, never splitting a surrogate pair.
 *
 * Used by CB ingest. `EventJson.preview` in the IPC parser applies the same rule to SMS rows.
 */
fun previewText(text: String?): String {
    if (text.isNullOrBlank()) return ""
    val collapsed = text.replace('\n', ' ').replace('\r', ' ').trim()
    if (collapsed.length <= MAX_PREVIEW) return collapsed
    var cut = MAX_PREVIEW - 1
    if (Character.isHighSurrogate(collapsed[cut - 1])) cut--
    return collapsed.substring(0, cut) + "…"
}

private const val MAX_PREVIEW = 120
