package dev.nightjar.app.feature.detail

import dev.nightjar.app.data.model.Pdu
import dev.nightjar.contract.Severity
import dev.nightjar.decoder.Decoders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetailLogicTest {
    @Test
    fun `flatten keeps depth and order`() {
        val tree = Decoders.decode("tpdu", "00040B919876543210")
        val flat = flattenFields(tree)
        assertTrue(flat.isNotEmpty())
        assertEquals(0, flat.first().depth)
        assertTrue(flat.drop(1).all { it.depth >= 1 })
    }

    @Test
    fun `deepest node wins for a byte offset`() {
        val tree = Decoders.decode("tpdu", "00040B919876543210")
        val flat = flattenFields(tree)
        val offset = flat.last().offset
        val node = deepestNodeAt(flat, offset)
        assertNotNull(node)
        assertTrue(node!!.depth >= 1)
        assertNull(deepestNodeAt(flat, -1))
    }

    @Test
    fun `release requires quarantine, releasability and a bound releaser`() {
        assertTrue(canRelease(quarantined = true, releasable = true, releaserBound = true))
        assertFalse(canRelease(quarantined = true, releasable = false, releaserBound = true))
        assertFalse(canRelease(quarantined = true, releasable = true, releaserBound = false))
        assertFalse(canRelease(quarantined = false, releasable = true, releaserBound = true))
    }

    @Test
    fun `raw bytes concatenate pdu parts in order`() {
        val bytes =
            rawBytes(
                listOf(
                    Pdu(1, byteArrayOf(1, 2), "3gpp"),
                    Pdu(2, byteArrayOf(3), "3gpp"),
                ),
            )
        assertEquals(listOf<Byte>(1, 2, 3), bytes.toList())
    }

    @Test
    fun `decoder kind follows the stored format`() {
        assertEquals(Decoders.TPDU, decoderKind("3gpp"))
        assertEquals(Decoders.CELL_BROADCAST, decoderKind("cb"))
        assertEquals(Decoders.CELL_BROADCAST_UMTS, decoderKind("cb_umts"))
        assertEquals(Decoders.CELL_BROADCAST_UMTS, decoderKind("CB_UMTS"))
        assertEquals(Decoders.TPDU, decoderKind(null))
    }

    @Test
    fun `parts waiting counts the missing parts`() {
        assertEquals(2, partsWaiting(total = 4, have = 2))
        assertEquals(0, partsWaiting(total = 1, have = 1))
        assertEquals(0, partsWaiting(total = 3, have = 4))
        assertEquals(0, partsWaiting(total = 0, have = 0))
    }

    @Test
    fun `a new detail state has not deleted the message and has every dialog closed`() {
        // A fresh screen has not deleted anything, so a null detail means "deleted elsewhere".
        assertFalse(DetailUiState(deleted = true, view = null).deletedHere)
        assertTrue(DetailUiState(deleted = true, deletedHere = true, view = null).deletedHere)
        // Every dialog starts closed; the actions are the only way to open one.
        val initial = DetailUiState()
        assertFalse(initial.showReleaseDialog)
        assertFalse(initial.showExpectedDialog)
        assertFalse(initial.showTrustDialog)
        assertFalse(initial.showDeleteDialog)
    }

    @Test
    fun `only a debug injection gets the debug test fact row`() {
        val fact = debugTestFact(debugInjected = true, label = "Test", text = "Debug test message.")
        assertEquals(DetailFact(label = "Test", value = "Debug test message.", full = true), fact)
        assertNull(debugTestFact(debugInjected = false, label = "Test", text = "Debug test message."))
    }

    @Test
    fun `the evidence card keeps only facts with a real value`() {
        val port = DetailFact("Destination port", "2948", mono = true)
        val facts =
            listOf(
                DetailFact("Service centre", "None", mono = true),
                port,
                DetailFact("Content type", "unknown"),
                DetailFact("User data length", " "),
            )
        assertEquals(listOf(port), factsWithValue(facts))
        assertTrue(isRealValue("0"))
        assertTrue(isRealValue("reserved"))
        assertFalse(isRealValue(null))
        assertFalse(isRealValue("NONE"))
    }

    @Test
    fun `the evidence card shows only when it has a line`() {
        val empty = listOf(DetailFact("Service centre", "None"))
        assertFalse(hasEvidence(empty, emptyList(), emptyList(), emptyList()))
        assertTrue(hasEvidence(listOf(DetailFact("WAP port", "2948")), emptyList(), emptyList(), emptyList()))
        // A real format error, an anomaly or an other matching type still shows the card.
        assertTrue(hasEvidence(empty, listOf("Header: too short"), emptyList(), emptyList()))
        assertTrue(hasEvidence(empty, emptyList(), listOf(AnomalyRow("Bad header", Severity.SUSPICIOUS)), emptyList()))
        assertTrue(hasEvidence(empty, emptyList(), emptyList(), listOf(ExtraMatchRow("Data message", "CODE"))))
    }
}
