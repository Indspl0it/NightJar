package dev.nightjar.decoder

import dev.nightjar.contract.Subtype
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmsPpTest {
    private val none = "00130D00000000B00010000000000100" + "48656C6C6F"

    /** Minimal valid command packet with the given SPI, KIc and KID octets. */
    private fun packet(
        spi1: Int,
        spi2: Int,
        kic: Int = 0,
        kid: Int = 0,
    ): String = "00130D" + hexByte(spi1) + hexByte(spi2) + hexByte(kic) + hexByte(kid) + "B00010000000000100" + "48656C6C6F"

    @Test
    fun `command header decodes lengths and tar`() {
        val tree = SmsPp.decode(none)
        assertEquals(19, tree.childValue("cpl"))
        assertEquals(13, tree.childValue("chl"))
        assertEquals("B00010", tree.childValue("tar"))
        assertEquals("Remote File Management (31.116 compact)", tree.childValue("tar/application"))
        // The pduparse subtype of the TAR, the same as the class.
        assertEquals(Subtype.SimOta.RFM, tree.childValue("tar/subtype"))
        assertNotNull(tree.child("sim_ota_subtypes"))
        assertEquals(1L, tree.childValue("cntr/value"))
        assertEquals(0, tree.childValue("pcntr"))
        assertEquals("48656C6C6F", tree.childValue("secured_data"))
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
    }

    @Test
    fun `spi none states no security in plain words`() {
        val tree = SmsPp.decode(none)
        assertEquals("none", tree.childValue("spi/rc_cc_ds"))
        assertEquals(false, tree.childValue("spi/ciphering"))
        assertEquals("none", tree.childValue("spi/counter"))
        assertEquals("none", tree.childValue("spi/por"))
        assertEquals("", tree.childValue("rc_cc_ds"))
    }

    @Test
    fun `spi with cryptographic checksum and counter decodes`() {
        val cc = "001B1532000002B00010000000000200" + "1122334455667788" + "48656C6C6F"
        val tree = SmsPp.decode(cc)
        assertEquals("cryptographic-checksum", tree.childValue("spi/rc_cc_ds"))
        assertEquals("higher-than-last", tree.childValue("spi/counter"))
        assertEquals("1122334455667788", tree.childValue("rc_cc_ds"))
        assertEquals(21, tree.childValue("chl"))
    }

    @Test
    fun `spi with ciphering and por decodes`() {
        val hex = "001B1526310002B00010000000000200" + "1122334455667788" + "48656C6C6F"
        val tree = SmsPp.decode(hex)
        assertEquals(true, tree.childValue("spi/ciphering"))
        assertEquals("required", tree.childValue("spi/por"))
        assertEquals("none", tree.childValue("spi/por_security"))
        assertEquals(true, tree.childValue("spi/por_ciphering"))
        assertEquals("SMS-SUBMIT", tree.childValue("spi/por_transport"))
        assertEquals("AES-CMAC", tree.childValue("kid/algorithm"))
        // With ciphering the counter, the padding counter and the secured data are ciphertext.
        assertEquals(true, tree.childValue("cntr/ciphered"))
        assertNull(tree.child("cntr/value"))
        assertEquals("00", tree.childValue("pcntr"))
        assertEquals(true, tree.childValue("secured_data/ciphered"))
        assertEquals(true, tree.childValue("rc_cc_ds/ciphered"))
    }

    @Test
    fun `kic mode is bits 3 and 4`() {
        // TS 102 225 5.1.2: mode = (kic shr 2) and 3.
        fun kic(value: Int): Any? = SmsPp.decode(packet(0, 0, kic = value)).childValue("kic/algorithm")
        assertEquals("DES-CBC", kic(0x01))
        assertEquals("3DES-2-key-outer-CBC", kic(0x05))
        assertEquals("3DES-2-key-outer-CBC", kic(0x15))
        assertEquals("3DES-3-key-outer-CBC", kic(0x09))
        assertEquals("DES-ECB", kic(0x0D))
        assertEquals("AES-CBC", kic(0x02))
    }

    @Test
    fun `kid tables depend on the spi1 security type`() {
        // RC algorithm 10 is reserved; CC with DES mode 11 is reserved.
        assertEquals("CRC32", SmsPp.decode(packet(0x01, 0, kid = 0x05)).childValue("kid/algorithm"))
        assertEquals("reserved", SmsPp.decode(packet(0x01, 0, kid = 0x02)).childValue("kid/algorithm"))
        assertEquals("DES-reserved-mode", SmsPp.decode(packet(0x02, 0, kid = 0x0D)).childValue("kid/algorithm"))
        assertEquals("AES-CMAC", SmsPp.decode(packet(0x02, 0, kid = 0x02)).childValue("kid/algorithm"))
    }

    @Test
    fun `por transport is spi2 bit 6`() {
        assertEquals("SMS-DELIVER-REPORT", SmsPp.decode(packet(0, 0x01)).childValue("spi/por_transport"))
        assertEquals("SMS-SUBMIT", SmsPp.decode(packet(0, 0x21)).childValue("spi/por_transport"))
    }

    @Test
    fun `a short chl and octets after the cpl are flagged`() {
        // CHL 12 (< 13) is AN-OTA-HDR in pduparse.
        val shortHeader = SmsPp.decode("00120C00000000B000100000000001" + "48656C6C6F")
        assertTrue(collectErrors(shortHeader).any { it.error!!.contains("CHL") }, FieldJson.toJson(shortHeader))
        val trailing = SmsPp.decode(none + "AA")
        assertEquals("AA", trailing.childValue("trailing_data"))
        assertTrue(collectErrors(trailing).any { it.error!!.contains("CPL") }, FieldJson.toJson(trailing))
    }

    @Test
    fun `spi rc_cc_ds bits name every check`() {
        val expected = listOf("none", "redundancy-check", "cryptographic-checksum", "digital-signature")
        for (value in 0..3) {
            val tree = SmsPp.decode(packet(value, 0))
            assertEquals(expected[value], tree.childValue("spi/rc_cc_ds"))
        }
    }

    @Test
    fun `spi ciphering bit is bit 3`() {
        assertEquals(false, SmsPp.decode(packet(0x00, 0x00)).childValue("spi/ciphering"))
        assertEquals(true, SmsPp.decode(packet(0x04, 0x00)).childValue("spi/ciphering"))
    }

    @Test
    fun `spi counter uses bits 4 and 5`() {
        val expected = listOf("none", "available", "higher-than-last", "one-higher-than-last")
        for (value in 0..3) {
            val tree = SmsPp.decode(packet(value shl 3, 0))
            assertEquals(expected[value], tree.childValue("spi/counter"))
        }
    }

    @Test
    fun `spi por bits name every requirement`() {
        val expected = listOf("none", "required", "on-error", "reserved")
        for (value in 0..3) {
            val tree = SmsPp.decode(packet(0, value))
            assertEquals(expected[value], tree.childValue("spi/por"))
        }
    }

    @Test
    fun `spi por security uses bits 3 and 4`() {
        val expected = listOf("none", "redundancy-check", "cryptographic-checksum", "digital-signature")
        for (value in 0..3) {
            val tree = SmsPp.decode(packet(0, value shl 2))
            assertEquals(expected[value], tree.childValue("spi/por_security"))
        }
    }

    @Test
    fun `spi por ciphering is bit 5`() {
        assertEquals(false, SmsPp.decode(packet(0, 0x00)).childValue("spi/por_ciphering"))
        assertEquals(true, SmsPp.decode(packet(0, 0x10)).childValue("spi/por_ciphering"))
    }

    @Test
    fun `unknown tar is labelled`() {
        val tree = SmsPp.decode("00130D00000000B00010000000000100" + "00")
        assertNotNull(tree.childValue("tar/application"))
    }

    @Test
    fun `sim browser tars are named`() {
        // S@T push 'PSH', S@T 'S@T' and the SIMtester WIB TARs; the neighbours keep their old names.
        fun tarName(tar: String): Any? =
            SmsPp.decode("00130D00000000${tar}000000000100" + "48656C6C6F").childValue("tar/application")
        assertEquals("S@T Browser (push)", tarName("505348"))
        assertEquals("S@T Browser", tarName("534054"))
        assertEquals("Wireless Internet Browser (WIB)", tarName("000001"))
        assertEquals("Wireless Internet Browser (WIB)", tarName("000009"))
        assertEquals("Issuer Security Domain", tarName("000000"))
        assertEquals("issuer-specific", tarName("00000A"))
        assertEquals("issuer-specific", tarName("505349"))
    }

    @Test
    fun `response packet decodes status and additional data`() {
        val response = "00120EB0001000000000020000" + "61626364" + "78797A"
        val tree = SmsPp.decodeResponse(response)
        assertEquals(18, tree.childValue("rpl"))
        assertEquals(14, tree.childValue("rhl"))
        assertEquals("PoR OK", tree.childValue("status/meaning"))
        assertEquals("78797A", tree.childValue("additional_data"))
    }

    @Test
    fun `response status codes are named`() {
        val response = "00120EB0001000000000020009" + "61626364" + "00"
        val tree = SmsPp.decodeResponse(response)
        assertEquals("TAR unknown", tree.childValue("status/meaning"))
    }

    @Test
    fun `truncated command records the cut once`() {
        val tree = SmsPp.decode(none.substring(0, 12))
        assertTrue(collectErrors(tree).isNotEmpty(), FieldJson.toJson(tree))
        // No field after the end of the data (rule).
        assertNull(tree.child("tar"))
    }

    @Test
    fun `inconsistent cpl records an error`() {
        val tree = SmsPp.decode("00050D00000000B00010000000000100" + "00")
        assertTrue(collectErrors(tree).isNotEmpty())
    }

    private fun collectErrors(node: Field): List<Field> {
        val out = mutableListOf<Field>()
        if (node.error != null) out.add(node)
        for (child in node.children) out.addAll(collectErrors(child))
        return out
    }
}
