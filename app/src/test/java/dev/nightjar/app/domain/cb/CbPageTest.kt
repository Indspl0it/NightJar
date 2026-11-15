package dev.nightjar.app.domain.cb

import dev.nightjar.decoder.Decoders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The CB header is read from the GSM and the UMTS layout (TS 23.041). Synthetic PDUs. */
class CbPageTest {
    private fun bytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Type 1, message id 0x1112, serial 0x1234, 8-bit DCS, two pages of 5 octets each. */
    private val umts =
        bytes(
            "01" + "1112" + "1234" + "44" + "02" +
                "5041474531" + "00".repeat(77) + "05" +
                "5041474532" + "00".repeat(77) + "05",
        )

    /** Serial 0x1234, message id 0x1112, GSM 7-bit DCS, page 2 of 3, 82 content octets. */
    private val gsmPage2of3 = bytes("1234" + "1112" + "0F" + "23" + "00".repeat(82))

    @Test
    fun `a UMTS message is one whole page`() {
        val page = CbPage.of(umts, "cb_umts")
        assertEquals(0x1112, page.messageId)
        assertEquals(0x1234, page.serial)
        // The decoder joins the pages of the PDU, so the assembler must not wait for more.
        assertEquals(1, page.page)
        assertEquals(1, page.pages)
        assertEquals("50414745315041474532", page.text)
    }

    @Test
    fun `the UMTS layout is found without the format`() {
        val page = CbPage.of(umts, null)
        assertEquals(0x1112, page.messageId)
        assertEquals(0x1234, page.serial)
        assertEquals("cb_umts", page.format)
    }

    @Test
    fun `a GSM page keeps its page parameter`() {
        val page = CbPage.of(gsmPage2of3, "cb")
        assertEquals(0x1112, page.messageId)
        assertEquals(0x1234, page.serial)
        assertEquals(2, page.page)
        assertEquals(3, page.pages)
        assertEquals("cb", page.format)
    }

    @Test
    fun `a PDU cut off inside the header has no identity`() {
        // VR01-DOM09: the decoder can read the missing octets as zero; "01 11" must not become ETWS channel 0x1100.
        assertEquals(CbPage.UNKNOWN, CbPage.of(bytes("0111"), "cb_umts"))
        assertEquals(CbPage.UNKNOWN, CbPage.of(bytes("123411"), "cb"))
        assertEquals(-1, CbPage.of(bytes("0111"), null).messageId)
    }

    @Test
    fun `a decoder tree without a complete header has no identity`() {
        val tree = Decoders.decodeBytes(Decoders.CELL_BROADCAST, bytes("123411"))
        assertEquals(-1, CbPage.of(tree).messageId)
    }
}
