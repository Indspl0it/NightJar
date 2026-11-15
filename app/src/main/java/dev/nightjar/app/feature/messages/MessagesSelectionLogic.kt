package dev.nightjar.app.feature.messages

import dev.nightjar.app.data.db.FeedIdRow

/** The actions of the selection mode of the Messages list. */
internal class SelectionCallbacks(
    val onMarkReviewed: () -> Unit,
    val onMarkUnreviewed: () -> Unit,
    val onStar: () -> Unit,
    val onExport: () -> Unit,
    val onDelete: () -> Unit,
    val onSelectAll: () -> Unit,
    val onInvert: () -> Unit,
    /** Ends the selection mode: the close icon, "Clear selection" and Back. */
    val onClear: () -> Unit,
)

/** The state of the "Select all N" checkbox while the user selects messages. */
internal enum class SelectAllState {
    NONE,
    SOME,
    ALL,
}

/**
 * The checkbox state for [selected] selected rows of a list with [total] rows. While the total is not known yet, a
 * selection shows as [SelectAllState.SOME].
 */
internal fun selectAllState(
    selected: Int,
    total: Int?,
): SelectAllState =
    when {
        selected <= 0 -> SelectAllState.NONE
        total != null && total > 0 && selected >= total -> SelectAllState.ALL
        else -> SelectAllState.SOME
    }

/** "Invert selection": every id of [all] (the full current list) that is not in [selection], in list order. */
internal fun invertedSelection(
    all: List<Long>,
    selection: Set<Long>,
): Set<Long> = all.filterTo(LinkedHashSet()) { id -> id !in selection }

/**
 * The facts of each selected row, noted when the row is selected: its review state, so a bulk Undo restores only what
 * changed, and its hold state, so the delete question can warn about held messages.
 */
internal class SelectionFacts {
    private val reviewed = HashMap<Long, Boolean>()
    private val held = HashSet<Long>()

    fun note(
        id: Long,
        wasReviewed: Boolean,
        isHeld: Boolean,
    ) {
        reviewed[id] = wasReviewed
        if (isHeld) held.add(id) else held.remove(id)
    }

    fun forget(id: Long) {
        reviewed.remove(id)
        held.remove(id)
    }

    /** Replaces the facts with the facts of the rows of [rows] that are in [selection]. */
    fun replace(
        rows: List<FeedIdRow>,
        selection: Set<Long>,
    ) {
        clear()
        rows.forEach { row -> if (row.id in selection) note(row.id, row.isReviewed, row.isHeld) }
    }

    fun clear() {
        reviewed.clear()
        held.clear()
    }

    fun wasReviewed(id: Long): Boolean = reviewed[id] == true

    fun anyHeld(ids: Collection<Long>): Boolean = ids.any { id -> id in held }
}
