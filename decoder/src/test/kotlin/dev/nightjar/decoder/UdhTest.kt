package dev.nightjar.decoder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UdhTest {
    @Test
    fun `concatenation 8 bit`() {
        val info = Udh.decodeWithInfo(ByteReader(Hex.decode("050003010201"))).second
        assertEquals(1, info.concat?.reference)
        assertEquals(2, info.concat?.total)
        assertEquals(1, info.concat?.sequence)
        assertEquals(false, info.concat?.sixteenBit)
        assertTrue(info.multipart)
        assertEquals(6, info.totalOctets)
    }

    @Test
    fun `concatenation 16 bit`() {
        val info = Udh.decodeWithInfo(ByteReader(Hex.decode("06080401010201"))).second
        assertEquals(257, info.concat?.reference)
        assertEquals(true, info.concat?.sixteenBit)
    }

    @Test
    fun `invalid concatenation values are ignored like android`() {
        // Sequence 3 of total 2 (UdhParser.validConcat).
        val (tree, info) = Udh.decodeWithInfo(ByteReader(Hex.decode("050003010203")))
        assertNull(info.concat)
        assertFalse(info.multipart)
        assertEquals(1, collectErrors(tree).size, FieldJson.toJson(tree))
    }

    @Test
    fun `an element with a wrong length is shown raw with an error`() {
        // An 8-bit port element with 3 octets instead of 2.
        val (tree, info) = Udh.decodeWithInfo(ByteReader(Hex.decode("0504030B8400")))
        assertNull(info.ports)
        val ie = tree.children.first()
        assertEquals("0B8400", ie.childValue("raw"))
        assertEquals(1, collectErrors(tree).size, FieldJson.toJson(tree))
    }

    @Test
    fun `eight and sixteen bit ports`() {
        val eight = Udh.decodeWithInfo(ByteReader(Hex.decode("0404020B84"))).second
        assertEquals(11, eight.ports?.destination)
        assertEquals(132, eight.ports?.originator)
        val sixteen = Udh.decodeWithInfo(ByteReader(Hex.decode("0605040B8423F0"))).second
        assertEquals(2948, sixteen.ports?.destination)
        assertEquals(9200, sixteen.ports?.originator)
    }

    @Test
    fun `national language shift ies are decoded`() {
        val (tree, info) = Udh.decodeWithInfo(ByteReader(Hex.decode("03240106")))
        val ie = tree.children.first()
        assertEquals("national_language_single_shift", ie.childValue("name"))
        assertEquals(6, ie.childValue("language_code"))
        assertEquals("Hindi", ie.childValue("language"))
        assertEquals(6, info.singleShift)
        assertEquals(0, info.lockingShift)
    }

    @Test
    fun `reserved ies keep the raw bytes and are flagged`() {
        val tree = Udh.decode(ByteReader(Hex.decode("034201FF")))
        val ie = tree.children.first()
        assertEquals("reserved_0x42", ie.childValue("name"))
        assertEquals(true, ie.childValue("reserved"))
        assertEquals("FF", ie.childValue("raw"))
    }

    @Test
    fun `sme specific ies are not reserved`() {
        val ie = Udh.decode(ByteReader(Hex.decode("038001AA"))).children.first()
        assertEquals("sme_to_sme_specific_0x80", ie.childValue("name"))
        assertNull(ie.child("reserved"))
    }

    @Test
    fun `truncated ie records one error node and no fields`() {
        // UDHL 5 with 3 octets: the concatenation element is cut.
        val (tree, info) = Udh.decodeWithInfo(ByteReader(Hex.decode("05000301")))
        assertEquals(1, collectErrors(tree).size, FieldJson.toJson(tree))
        val ie = tree.children.first { it.name == "information_element" }
        assertEquals("01", ie.childValue("raw"))
        assertNull(ie.child("reference"))
        assertNull(info.concat)
        assertEquals(4, tree.length)
    }

    @Test
    fun `an ie without a length octet records an error`() {
        val tree = Udh.decode(ByteReader(Hex.decode("0100")))
        assertEquals(1, collectErrors(tree).size, FieldJson.toJson(tree))
    }

    @Test
    fun `special sms indication decodes the octet fields`() {
        // Octet 1 0x82: store, basic type email (10). Octet 2: 3 messages (TS 23.040 9.2.3.24.2).
        val tree = Udh.decode(ByteReader(Hex.decode("0401028203")))
        val ie = tree.children.first()
        assertEquals("special_sms_indication", ie.childValue("name"))
        assertEquals(true, ie.childValue("store"))
        assertEquals("email", ie.childValue("type"))
        assertEquals("none", ie.childValue("extended_type"))
        assertEquals(0, ie.childValue("profile"))
        assertEquals(3, ie.childValue("message_count"))
    }

    @Test
    fun `toolkit command and response headers are flagged`() {
        assertTrue(Udh.decodeWithInfo(ByteReader(Hex.decode("027000"))).second.toolkitCommand)
        assertTrue(Udh.decodeWithInfo(ByteReader(Hex.decode("027100"))).second.toolkitResponse)
    }

    @Test
    fun `toolkit header with data is an error`() {
        val tree = Udh.decode(ByteReader(Hex.decode("03700101")))
        assertEquals(1, collectErrors(tree).size, FieldJson.toJson(tree))
    }

    @Test
    fun `fill bits derive from the header length`() {
        assertEquals(6, Udh.fillBits(1))
        assertEquals(1, Udh.fillBits(6))
        assertEquals(0, Udh.fillBits(7))
        assertEquals(1, Udh.fillBits(13))
    }

    private fun collectErrors(node: Field): List<Field> {
        val out = mutableListOf<Field>()
        if (node.error != null) out.add(node)
        for (child in node.children) out.addAll(collectErrors(child))
        return out
    }
}
