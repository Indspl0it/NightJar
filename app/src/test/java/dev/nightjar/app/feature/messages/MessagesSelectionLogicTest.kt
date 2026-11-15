package dev.nightjar.app.feature.messages

import dev.nightjar.app.data.db.FeedIdRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The "Select all N" checkbox, "Invert selection" and the facts noted for each selected row. */
class MessagesSelectionLogicTest {
    @Test
    fun `the checkbox is empty with no selection`() {
        assertEquals(SelectAllState.NONE, selectAllState(selected = 0, total = 10))
        assertEquals(SelectAllState.NONE, selectAllState(selected = 0, total = null))
    }

    @Test
    fun `the checkbox is partly checked while some rows are selected`() {
        assertEquals(SelectAllState.SOME, selectAllState(selected = 3, total = 10))
        // The count is not known yet.
        assertEquals(SelectAllState.SOME, selectAllState(selected = 3, total = null))
    }

    @Test
    fun `the checkbox is checked when every row is selected`() {
        assertEquals(SelectAllState.ALL, selectAllState(selected = 10, total = 10))
        // A row that arrived or was deleted elsewhere can make the selection larger than the list.
        assertEquals(SelectAllState.ALL, selectAllState(selected = 11, total = 10))
        assertEquals(SelectAllState.SOME, selectAllState(selected = 1, total = 0))
    }

    @Test
    fun `invert selects the other rows of the list in list order`() {
        val all = listOf(5L, 4L, 3L, 2L, 1L)
        assertEquals(listOf(5L, 3L, 1L), invertedSelection(all, setOf(4L, 2L)).toList())
    }

    @Test
    fun `invert of no selection selects all, and invert of all ends the selection`() {
        val all = listOf(3L, 2L, 1L)
        assertEquals(all.toSet(), invertedSelection(all, emptySet()))
        assertTrue(invertedSelection(all, all.toSet()).isEmpty())
    }

    @Test
    fun `invert drops a selected id that is no longer in the list`() {
        assertEquals(setOf(2L), invertedSelection(listOf(2L, 1L), setOf(1L, 99L)))
    }

    @Test
    fun `facts keep the review and hold state of the selected rows only`() {
        val facts = SelectionFacts()
        val rows =
            listOf(
                FeedIdRow(id = 1, isReviewed = true, isHeld = false),
                FeedIdRow(id = 2, isReviewed = false, isHeld = true),
                FeedIdRow(id = 3, isReviewed = false, isHeld = false),
            )
        facts.replace(rows, selection = setOf(1L, 3L))
        assertTrue(facts.wasReviewed(1))
        assertFalse(facts.wasReviewed(3))
        assertFalse(facts.anyHeld(listOf(1L, 3L)))
        // Row 2 is not selected, so its hold state is not noted.
        assertFalse(facts.anyHeld(listOf(2L)))
    }

    @Test
    fun `a noted held row makes the delete question warn, and forget removes it`() {
        val facts = SelectionFacts()
        facts.note(7, wasReviewed = false, isHeld = true)
        assertTrue(facts.anyHeld(listOf(7L)))
        facts.forget(7)
        assertFalse(facts.anyHeld(listOf(7L)))
        assertFalse(facts.wasReviewed(7))
    }

    @Test
    fun `clear forgets every fact`() {
        val facts = SelectionFacts()
        facts.note(1, wasReviewed = true, isHeld = true)
        facts.clear()
        assertFalse(facts.wasReviewed(1))
        assertFalse(facts.anyHeld(listOf(1L)))
    }
}
