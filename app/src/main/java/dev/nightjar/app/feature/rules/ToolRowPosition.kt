package dev.nightjar.app.feature.rules

/** Where a row sits in a group whose rows are separate list items. */
internal enum class ToolRowPosition {
    ONLY,
    FIRST,
    MIDDLE,
    LAST,
    ;

    companion object {
        fun of(
            index: Int,
            count: Int,
        ): ToolRowPosition =
            when {
                count <= 1 -> ONLY
                index == 0 -> FIRST
                index == count - 1 -> LAST
                else -> MIDDLE
            }
    }
}
