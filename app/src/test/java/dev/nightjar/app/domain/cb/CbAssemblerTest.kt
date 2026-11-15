package dev.nightjar.app.domain.cb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Page folding, and the stored pages given again after a restart. Synthetic pages. */
class CbAssemblerTest {
    private fun page(index: Int): CbPage =
        CbPage(serial = 0x1234, messageId = 4370, page = index, pages = 3, emergency = true, text = "p$index", format = "cb")

    @Test
    fun `a new assembler given the stored pages counts them in the revision`() {
        // The process restarted: page 1 is stored, page 2 arrives.
        val assembler = CbAssembler()
        assembler.accept(page(1), byteArrayOf(1), subId = 1)
        val assembly = assembler.accept(page(2), byteArrayOf(2), subId = 1)
        assertEquals(2, assembly.pagesSeen)
        assertFalse(assembly.complete)
        assertEquals("p1p2", assembly.text)
    }

    @Test
    fun `a stored page given again changes nothing`() {
        val assembler = CbAssembler()
        assembler.accept(page(1), byteArrayOf(1), subId = 1)
        assembler.accept(page(2), byteArrayOf(2), subId = 1)
        val again = assembler.accept(page(1), byteArrayOf(1), subId = 1)
        assertEquals(2, again.pagesSeen)
        assertTrue(assembler.isDuplicate(page(2), subId = 1))
        assertFalse(assembler.isDuplicate(page(3), subId = 1))
    }

    @Test
    fun `the last page completes the group`() {
        val assembler = CbAssembler()
        (1..3).forEach { assembler.accept(page(it), byteArrayOf(it.toByte()), subId = 1) }
        val done = assembler.accept(page(3), byteArrayOf(3), subId = 1)
        assertTrue(done.complete)
        assertEquals("p1p2p3", done.text)
    }
}
