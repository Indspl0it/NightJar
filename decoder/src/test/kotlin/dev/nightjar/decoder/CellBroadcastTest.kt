package dev.nightjar.decoder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CellBroadcastTest {
    private fun page(messageId: Int, dcs: Int, pageByte: Int, text: String): ByteArray {
        val septets = Gsm7.encodeDefault(text)
        val padded = IntArray(93) { if (it < septets.size) septets[it] else 0x0D }
        return Hex.decode(
            "1234" + hexDigits(messageId.toLong(), 4) + hexByte(dcs) + hexByte(pageByte) + Hex.encode(Gsm7.pack(padded)),
        )
    }

    @Test
    fun `gsm page decodes serial identifier dcs and text`() {
        val tree = CellBroadcast.decode(page(4352, 0x0F, 0x11, "Earthquake alert"))
        assertEquals("0x1234", tree.childValue("serial_number"))
        assertEquals(0, tree.childValue("serial_number/geographical_scope"))
        assertEquals(0x123, tree.childValue("serial_number/message_code"))
        assertEquals(4, tree.childValue("serial_number/update_number"))
        assertEquals(4352, tree.childValue("message_identifier"))
        assertEquals("ETWS earthquake warning", tree.childValue("message_identifier/channel"))
        assertEquals(true, tree.childValue("message_identifier/emergency"))
        assertEquals("1 of 1", tree.childValue("page_parameter"))
        assertEquals("Earthquake alert", tree.childValue("content/text"))
    }

    @Test
    fun `cmas presidential channel is recognised`() {
        val tree = CellBroadcast.decode(page(4370, 0x0F, 0x11, "Presidential alert"))
        assertEquals("CMAS Presidential / EU-Alert Level 1", tree.childValue("message_identifier/channel"))
        assertTrue(tree.childValue("message_identifier/emergency") as Boolean)
    }

    @Test
    fun `multi page messages report page n of m`() {
        val tree = CellBroadcast.decode(page(4370, 0x0F, 0x23, "Second"))
        assertEquals("2 of 3", tree.childValue("page_parameter/page_n_of_m"))
        assertEquals(3, tree.childValue("page_parameter/total"))
    }

    @Test
    fun `ucs2 pages decode`() {
        val bytes = Hex.decode("1234" + "1112" + "48" + "11" + Hex.encode("Hi".toByteArray(Charsets.UTF_16BE)) + "00".repeat(78))
        val tree = CellBroadcast.decode(bytes)
        assertEquals("UCS-2", tree.childValue("dcs/alphabet"))
        assertEquals("Hi", tree.childValue("content/text"))
    }

    @Test
    fun `language group dcs names the language`() {
        val tree = CellBroadcast.decode(page(4370, 0x01, 0x11, "Hello"))
        assertEquals("English", tree.childValue("dcs/language"))
        assertEquals("GSM 7-bit", tree.childValue("dcs/alphabet"))
    }

    @Test
    fun `eight bit pages keep hex`() {
        val bytes = Hex.decode("1234" + "1112" + "44" + "11" + "DEADBEEF" + "00".repeat(78))
        val tree = CellBroadcast.decode(bytes)
        assertEquals("8-bit", tree.childValue("dcs/alphabet"))
        assertTrue((tree.childValue("content/hex") as String).startsWith("DEADBEEF"))
    }

    @Test
    fun `umts page decodes message type and page count`() {
        val umts =
            Hex.decode(
                "011112" + "1234" + "0F" + "01" +
                    Hex.encode(
                        Gsm7.pack(
                            IntArray(93) {
                                if (it <
                                    Gsm7.encodeDefault("Presidential alert").size
                                ) {
                                    Gsm7.encodeDefault("Presidential alert")[it]
                                } else {
                                    0x0D
                                }
                            },
                        ),
                    ) +
                    "52",
            )
        val tree = CellBroadcast.decode(umts, CbFormat.UMTS)
        assertEquals("UMTS", tree.value)
        assertEquals(1, tree.childValue("number_of_pages"))
        assertEquals("Presidential alert", tree.childValue("content/text"))
        assertEquals(0x52, tree.childValue("pages/page/page_length"))
    }

    @Test
    fun `umts multi page decodes every page and applies its length`() {
        val page1 = "5041474531" + "00".repeat(77)
        val page2 = "5041474532" + "00".repeat(77)
        val umts = Hex.decode("01" + "1112" + "1234" + "44" + "02" + page1 + "05" + page2 + "05")
        val tree = CellBroadcast.decode(umts, CbFormat.UMTS)
        assertEquals(2, tree.childValue("number_of_pages"))
        val pages = tree.child("pages")!!.children
        assertEquals(2, pages.size)
        assertEquals(5, pages[0].childValue("page_length"))
        assertEquals("5041474531", pages[0].childValue("data"))
        assertEquals(5, pages[1].childValue("page_length"))
        assertEquals("5041474532", pages[1].childValue("data"))
        assertEquals("50414745315041474532", tree.childValue("content/hex"))
    }

    @Test
    fun `umts page count above fifteen is capped and reported`() {
        val umts = Hex.decode("01" + "1112" + "1234" + "44" + "FF" + ("00".repeat(82) + "00").repeat(15))
        val tree = CellBroadcast.decode(umts, CbFormat.UMTS)
        assertEquals(15, tree.child("pages")!!.children.size)
        assertTrue(collectErrors(tree).isNotEmpty(), FieldJson.toJson(tree))
    }

    /** ETWS primary notification with the given two warning-type octets (TS 23.041 9.4.1.3.4). */
    private fun etwsPrimary(warningType: String): Field {
        val security = ByteArray(50)
        security[0] = 0x62
        security[1] = 0x11
        security[2] = 0x50
        security[3] = 0x21
        security[4] = 0x43
        security[5] = 0x65
        security[6] = 0x80.toByte()
        return CellBroadcast.decode(Hex.decode("1234" + "1100" + warningType + Hex.encode(security)))
    }

    @Test
    fun `etws primary notification decodes warning type and timestamp`() {
        val tree = etwsPrimary("0180")
        assertEquals("ETWS-primary", tree.value)
        assertEquals("earthquake", tree.childValue("warning_type"))
        assertEquals(0, tree.childValue("warning_type/value"))
        assertEquals(true, tree.childValue("warning_type/emergency_user_alert"))
        assertEquals(true, tree.childValue("warning_type/popup"))
        assertEquals(2026, tree.childValue("warning_security_information/timestamp/year"))
        assertEquals("2026-11-05T12:34:56+02:00", tree.childValue("warning_security_information/timestamp"))
        assertNotNull(tree.child("warning_security_information/digital_signature"))
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
    }

    @Test
    fun `etws primary without security information has no error`() {
        val tree = CellBroadcast.decode(Hex.decode("123411000180"))
        assertEquals("ETWS-primary", tree.value)
        assertNull(tree.child("warning_security_information"))
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
    }

    @Test
    fun `etws primary uses the shared time stamp with a negative zone`() {
        // Zone octet 0x28: 2 quarters, sign bit set -> -00:30.
        val tree = CellBroadcast.decode(Hex.decode("12341100" + "0180" + "62115021436528"))
        assertEquals("2026-11-05T12:34:56-00:30", tree.childValue("warning_security_information/timestamp"))
    }

    @Test
    fun `an etws identifier in the umts format is not a primary notification`() {
        // Octets 2-3 of a UMTS message are not the message identifier.
        val umts = Hex.decode("01" + "1100" + "1234" + "0F" + "01" + "00".repeat(82) + "00")
        assertEquals("UMTS", CellBroadcast.decode(umts, CbFormat.UMTS).value)
    }

    @Test
    fun `etws warning type value is bits 15 to 9`() {
        val tree = etwsPrimary("0200")
        assertEquals(1, tree.childValue("warning_type/value"))
        assertEquals("tsunami", tree.childValue("warning_type"))
        assertEquals(false, tree.childValue("warning_type/emergency_user_alert"))
        assertEquals(false, tree.childValue("warning_type/popup"))
    }

    @Test
    fun `etws emergency user alert is bit 8`() {
        val tree = etwsPrimary("0100")
        assertEquals(0, tree.childValue("warning_type/value"))
        assertEquals(true, tree.childValue("warning_type/emergency_user_alert"))
        assertEquals(false, tree.childValue("warning_type/popup"))
    }

    @Test
    fun `etws popup is bit 7 of the second octet`() {
        val tree = etwsPrimary("0080")
        assertEquals(0, tree.childValue("warning_type/value"))
        assertEquals(false, tree.childValue("warning_type/emergency_user_alert"))
        assertEquals(true, tree.childValue("warning_type/popup"))
    }

    @Test
    fun `truncated umts header gives no zero filled identifier`() {
        // 01 11: message type and half of the message identifier. A zero-filled 0x1100 would read as ETWS (VR01-D15).
        val tree = CellBroadcast.decode(Hex.decode("0111"), CbFormat.UMTS)
        assertEquals("01", tree.childValue("message_type"))
        assertNull(tree.child("message_identifier"), FieldJson.toJson(tree))
        assertNull(tree.child("serial_number"), FieldJson.toJson(tree))
        assertNull(tree.child("dcs"), FieldJson.toJson(tree))
        assertEquals(1, collectErrors(tree).size, FieldJson.toJson(tree))
    }

    @Test
    fun `truncated gsm header keeps the fields before the cut only`() {
        val tree = CellBroadcast.decode(Hex.decode("123411"))
        assertEquals("0x1234", tree.childValue("serial_number"))
        assertNull(tree.child("message_identifier"), FieldJson.toJson(tree))
        assertNull(tree.child("page_parameter"), FieldJson.toJson(tree))
        assertEquals(1, collectErrors(tree).size, FieldJson.toJson(tree))
    }

    @Test
    fun `truncated page records one error`() {
        // Identifier 0x0032 (not ETWS), so the GSM page path runs.
        val tree = CellBroadcast.decode(Hex.decode("123400320F11" + "00".repeat(4)))
        assertEquals("GSM", tree.value)
        assertEquals(1, collectErrors(tree).size, FieldJson.toJson(tree))
    }

    @Test
    fun `group 0010 is gsm 7 bit with a language`() {
        // 0x22 is Arabic in GSM 7-bit, not UCS-2 or 8-bit.
        val tree = CellBroadcast.decode(page(0x0032, 0x22, 0x11, "Hello"))
        assertEquals("GSM 7-bit", tree.childValue("dcs/alphabet"))
        assertEquals("Arabic", tree.childValue("dcs/language"))
        assertEquals("Hello", tree.childValue("content/text"))
        assertEquals("Czech", CellBroadcast.decode(page(0x0032, 0x20, 0x11, "A")).childValue("dcs/language"))
        assertEquals(true, CellBroadcast.decode(page(0x0032, 0x25, 0x11, "A")).childValue("dcs/reserved"))
        assertEquals("GSM 7-bit", CellBroadcast.decode(page(0x0032, 0x30, 0x11, "A")).childValue("dcs/alphabet"))
    }

    @Test
    fun `gsm language indicator has three septets`() {
        val tree = CellBroadcast.decode(page(0x0032, 0x10, 0x11, "EN\rHello"))
        assertEquals("EN", tree.childValue("content/language_indicator"))
        assertEquals(3, tree.child("content/language_indicator")?.length)
        assertEquals("Hello", tree.childValue("content/text"))
    }

    @Test
    fun `class 00 of group 1111 is no message class`() {
        // TS 23.038 clause 5, CBS coding: 00 in group 1111 means no class.
        assertNull(CellBroadcast.decode(page(0x0032, 0xF0, 0x11, "A")).child("dcs/message_class"))
        assertEquals(1, CellBroadcast.decode(page(0x0032, 0xF1, 0x11, "A")).childValue("dcs/message_class"))
    }

    @Test
    fun `reserved coding groups are flagged and read as gsm 7 bit`() {
        val tree = CellBroadcast.decode(page(0x0032, 0xA0, 0x11, "Area"))
        assertEquals(true, tree.childValue("dcs/reserved"))
        assertEquals("GSM 7-bit", tree.childValue("dcs/alphabet"))
        assertEquals("Area", tree.childValue("content/text"))
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
    }

    @Test
    fun `group 1001 parses the user data header`() {
        // UDH 050003010201 (6 octets, 1 fill bit), then the text.
        val udh = Hex.decode("050003010201")
        val text = Gsm7.pack(Gsm7.encodeDefault("Hello"), Udh.fillBits(udh.size))
        val content = udh + text + ByteArray(82 - udh.size - text.size)
        val tree = CellBroadcast.decode(Hex.decode("12340032" + "90" + "11") + content)
        assertEquals(true, tree.childValue("dcs/has_udh"))
        assertNotNull(tree.child("content/udh"))
        assertTrue((tree.childValue("content/text") as String).startsWith("Hello"))
    }

    @Test
    fun `an invalid page parameter is read as 1 of 1`() {
        // Page 0 or page above total (Android SmsCbHeader).
        for (pageByte in listOf(0x00, 0x32, 0x10)) {
            val tree = CellBroadcast.decode(page(0x0032, 0x0F, pageByte, "A"))
            assertEquals("1 of 1", tree.childValue("page_parameter"), hexByte(pageByte))
            assertNotNull(tree.child("page_parameter/note"))
            assertNotNull(tree.child("page_parameter/raw"))
        }
        assertNull(CellBroadcast.decode(page(0x0032, 0x0F, 0x23, "A")).child("page_parameter/note"))
    }

    @Test
    fun `message identifier labels follow ts 23 041`() {
        fun channel(id: Int): Any? = CellBroadcast.decode(page(id, 0x0F, 0x11, "A")).childValue("message_identifier/channel")
        assertEquals("area information (operator allocated)", channel(50))
        assertEquals("operator allocated", channel(999))
        assertEquals("CMAS Presidential / EU-Alert Level 1 (additional language)", channel(4383))
        assertEquals("CMAS public safety", channel(4396))
        assertEquals("CMAS public safety (additional language)", channel(4397))
        assertEquals("CMAS state or local test", channel(4398))
        assertEquals("CMAS state or local test (additional language)", channel(4399))
        assertEquals("CMAS geo-fencing trigger", channel(4400))
    }

    @Test
    fun `emergency and subtype come from the shared tables`() {
        // Contract CbChannels and pduparse CbCategories.
        val etwsFuture = CellBroadcast.decode(page(4360, 0x0F, 0x11, "A"))
        assertEquals(true, etwsFuture.childValue("message_identifier/emergency"))
        val area = CellBroadcast.decode(page(50, 0x0F, 0x11, "A"))
        assertEquals(false, area.childValue("message_identifier/emergency"))
        assertNotNull(area.child("message_identifier/subtype"))
        // CL-CB-SIM (data download) has no subtype.
        assertNull(CellBroadcast.decode(page(4096, 0x0F, 0x11, "A")).child("message_identifier/subtype"))
    }

    @Test
    fun `umts message type and page length are checked`() {
        val badType = Hex.decode("02" + "1112" + "1234" + "0F" + "01" + "00".repeat(82) + "05")
        assertEquals(1, collectErrors(CellBroadcast.decode(badType, CbFormat.UMTS)).size)
        val longPage = Hex.decode("01" + "1112" + "1234" + "0F" + "01" + "00".repeat(82) + "60")
        val tree = CellBroadcast.decode(longPage, CbFormat.UMTS)
        assertEquals(1, collectErrors(tree).size, FieldJson.toJson(tree))
        assertNotNull(collectErrors(tree.child("pages/page")!!).firstOrNull())
    }

    private fun collectErrors(node: Field): List<Field> {
        val out = mutableListOf<Field>()
        if (node.error != null) out.add(node)
        for (child in node.children) out.addAll(collectErrors(child))
        return out
    }
}
