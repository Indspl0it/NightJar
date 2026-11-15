package dev.nightjar.decoder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TpduTest {
    private val deliver = "06912143658709040ED0CEE411495506A500006211502143652205C8329BFD06"

    @Test
    fun `smsc decodes swapped bcd with filler stripped`() {
        val tree = Tpdu.decode(deliver)
        assertEquals("1234567890", tree.childValue("smsc"))
        assertEquals("international", tree.textValue("smsc/type_of_number"))
    }

    @Test
    fun `smsc length zero means default`() {
        val tree = Tpdu.decode("0004" + "0BD0" + "F600000000" + "00")
        assertEquals("none", tree.childValue("smsc"))
    }

    @Test
    fun `deliver decodes first octet flags`() {
        val tree = Tpdu.decode(deliver)
        assertEquals("SMS-DELIVER", tree.childValue("first_octet/mti"))
        assertEquals(true, tree.childValue("first_octet/mms"))
        assertEquals(false, tree.childValue("first_octet/udhi"))
    }

    @Test
    fun `alphanumeric originator decodes seven bit text`() {
        val tree = Tpdu.decode(deliver)
        assertEquals("NIGHTJAR", tree.childValue("originator"))
        assertEquals("alphanumeric", tree.textValue("originator/type_of_number"))
    }

    @Test
    fun `scts includes the quarter hour timezone`() {
        val tree = Tpdu.decode(deliver)
        assertEquals("2026-11-05T12:34:56+05:30", tree.childValue("scts"))
        assertEquals(22, tree.childValue("scts/timezone_quarters"))
    }

    @Test
    fun `negative timezone is signed`() {
        val tree = Tpdu.decode("000402D04100006211502143652A" + "05C8329BFD06")
        assertEquals("2026-11-05T12:34:56-05:30", tree.childValue("scts"))
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
    }

    @Test
    fun `zone between minus 15 and minus 45 minutes keeps the minus sign`() {
        // Zone octet 0x28: 2 quarters, sign bit set -> -00:30.
        val tree = Tpdu.decode("000402D041000062115021436528" + "05C8329BFD06")
        assertEquals("2026-11-05T12:34:56-00:30", tree.childValue("scts"))
    }

    @Test
    fun `invalid bcd and impossible dates give an error node`() {
        // Month 13 (semi-octets 31): not a valid date.
        val tree = Tpdu.decode("000402D04100006231502143652205C8329BFD06")
        assertNull(tree.childValue("scts"))
        assertTrue(collectErrors(tree.child("scts")!!).isNotEmpty(), FieldJson.toJson(tree))
    }

    @Test
    fun `years from 90 are in the twentieth century like pduparse`() {
        val tree = Tpdu.decode("000402D04100009911502143652205C8329BFD06")
        assertEquals("1999-11-05T12:34:56+05:30", tree.childValue("scts"))
    }

    @Test
    fun `gsm seven bit user data decodes`() {
        val tree = Tpdu.decode(deliver)
        assertEquals("Hello", tree.childValue("user_data/text"))
        assertEquals(5, tree.childValue("user_data/udl"))
    }

    @Test
    fun `ucs2 user data decodes`() {
        val tree = Tpdu.decode("000402D041000862115021436522" + "0A00480065006C006C006F")
        assertEquals("Hello", tree.childValue("user_data/text"))
        assertEquals("00480065006C006C006F", tree.childValue("user_data/hex"))
    }

    @Test
    fun `multipart udh is decoded with fill bits`() {
        // UDH 6 octets + 1 fill bit = 7 septets, then "Hello" (5 septets): UDL 12.
        val text = Hex.encode(Gsm7.pack(Gsm7.encodeDefault("Hello"), Udh.fillBits(6)))
        val tree = Tpdu.decode("06912143658709440ED0CEE411495506A50000621150214365800C050003010201$text")
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
        assertEquals("Hello", tree.childValue("user_data/text"))
        val udh = tree.child("user_data/udh")
        assertNotNull(udh)
        val ie = udh!!.children.first()
        assertEquals("concatenated_8bit", ie.childValue("name"))
        assertEquals(1, ie.childValue("reference"))
        assertEquals(2, ie.childValue("total"))
        assertEquals(1, ie.childValue("sequence"))
    }

    @Test
    fun `dcs class and mwi groups are decoded`() {
        val mwi = Dcs.decode(0xC8)
        assertEquals("voicemail", mwi.mwi?.type)
        assertTrue(mwi.mwi?.active == true)
        assertTrue(mwi.mwi?.discard == true)
        val flash = Dcs.decode(0x10)
        assertEquals(0, flash.messageClass)
        assertEquals(Alphabet.GSM7, flash.alphabet)
        assertEquals(Alphabet.EIGHT_BIT, Dcs.decode(0x04).alphabet)
        assertEquals(Alphabet.UCS2, Dcs.decode(0x08).alphabet)
        assertTrue(Dcs.decode(0x24).compressed)
    }

    @Test
    fun `status report decodes status and parameter indicator`() {
        // MR 01, recipient, SCTS, DT, status 00, TP-PI 81 00 (extension octet), PID 40.
        val tree = Tpdu.decode("0006" + "01" + "0A912143658709" + "62115021436580" + "62115021436580" + "00" + "8100" + "40")
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
        assertEquals("SMS-STATUS-REPORT", tree.childValue("first_octet/mti"))
        assertEquals("1234567890", tree.childValue("recipient"))
        assertEquals("completed: received by the SME", tree.textValue("status/meaning"))
        assertEquals("8100", tree.childValue("parameter_indicator"))
        assertEquals(true, tree.childValue("parameter_indicator/pid"))
        assertEquals(false, tree.childValue("parameter_indicator/dcs"))
        assertEquals(false, tree.childValue("parameter_indicator/udl"))
        assertEquals("short message type 0", tree.textValue("pid/meaning"))
        assertNull(tree.child("user_data"))
    }

    @Test
    fun `status classes follow ts 23 040`() {
        fun meaning(status: String): String? =
            Tpdu.decode("0006010A912143658709" + "62115021436580" + "62115021436580" + status).textValue("status/meaning")
        assertEquals("completed: SC-specific", meaning("10"))
        assertEquals("temporary error: SC still trying", meaning("20"))
        assertEquals("permanent error: SC stopped", meaning("40"))
        assertEquals("temporary error: SC stopped", meaning("60"))
        assertEquals("reserved", meaning("80"))
    }

    @Test
    fun `submit decodes destination and relative validity`() {
        // First octet 0x11: MTI 01 (SUBMIT), VPF 10 (relative) -> exactly one VP octet.
        val tree = Tpdu.decode("0011090A9121436587090000AA02C832")
        assertEquals("SMS-SUBMIT", tree.childValue("first_octet/mti"))
        assertEquals("relative", tree.childValue("first_octet/vpf"))
        assertEquals("1234567890", tree.childValue("destination"))
        assertEquals("4 days", tree.textValue("validity_period/relative"))
    }

    @Test
    fun `submit enhanced validity period is seven raw octets`() {
        // First octet 0x09: MTI 01 (SUBMIT), VPF 01 (enhanced) -> seven VP octets.
        val tree = Tpdu.decode("0009090A91214365870900000102030405060702C832")
        assertEquals("enhanced", tree.childValue("first_octet/vpf"))
        assertEquals("01020304050607", tree.childValue("validity_period_enhanced"))
    }

    @Test
    fun `submit absolute validity period is a timestamp`() {
        // First octet 0x19: MTI 01 (SUBMIT), VPF 11 (absolute) -> seven semi-octet VP octets.
        val tree = Tpdu.decode("0019090A91214365870900006211502143652202C832")
        assertEquals("absolute", tree.childValue("first_octet/vpf"))
        assertEquals("2026-11-05T12:34:56+05:30", tree.childValue("validity_period_absolute/iso8601"))
    }

    @Test
    fun `mti three is reserved and keeps the raw remainder`() {
        val tree = Tpdu.decode("0003010203")
        assertEquals("reserved", tree.value)
        assertEquals("reserved", tree.childValue("first_octet/mti"))
        assertEquals("010203", tree.childValue("raw"))
        assertNull(tree.child("command_type"))
    }

    @Test
    fun `direction labels mti one and two`() {
        assertEquals(
            "SMS-SUBMIT-REPORT",
            Tpdu.decode("0009000102030405060708090A0B", TpduDirection.SC_TO_MS).value,
        )
        assertEquals("SMS-SUBMIT", Tpdu.decode("0009000102030405060708090A0B").value)
        // SMS-COMMAND: MR 01, PID 40, CT 02, MN 05, DA, CDL 3, CD (TS 23.040 9.2.2.4).
        val command = Tpdu.decode("000201400205" + "0A912143658709" + "03010203", TpduDirection.MS_TO_SC)
        assertTrue(collectErrors(command).isEmpty(), FieldJson.toJson(command))
        assertEquals("SMS-COMMAND", command.value)
        // TS 23.040 9.2.3.19 TP-Command-Type 00000010.
        assertEquals("delete previously submitted short message", command.childValue("command_type/meaning"))
        assertEquals(5, command.childValue("message_number"))
        assertEquals("1234567890", command.childValue("destination"))
        assertEquals(3, command.childValue("command_data_length"))
        assertEquals("010203", command.childValue("command_data"))
        assertNull(command.child("dcs"))
        assertNull(command.child("user_data"))
        assertEquals("MS_TO_SC", command.childValue("first_octet/direction"))
        assertNotNull(command.child("first_octet/srr"))
        assertNull(command.child("first_octet/mms"))
        val deliverReport = Tpdu.decode("00000102", TpduDirection.MS_TO_SC)
        assertEquals("SMS-DELIVER-REPORT", deliverReport.value)
        assertEquals("0102", deliverReport.childValue("raw"))
        val report = Tpdu.decode("00060A912143658709")
        assertEquals("SMS-STATUS-REPORT", report.value)
        assertNull(report.child("command_type"))
    }

    @Test
    fun `pid is matched on the whole octet`() {
        // First octet 0x01: SMS-SUBMIT with no validity period, so every case is a complete PDU.
        val submit = "0001010A912143658709"

        fun tree(pid: Int): Field = Tpdu.decode(submit + hexByte(pid) + "0002C834")

        fun meaning(pid: Int): String? = tree(pid).textValue("pid/meaning")
        assertTrue(collectErrors(tree(0x40)).isEmpty(), FieldJson.toJson(tree(0x40)))
        assertEquals("short message type 0", meaning(0x40))
        assertEquals("replace short message type 1", meaning(0x41))
        assertEquals("replace short message type 7", meaning(0x47))
        assertEquals("device triggering", meaning(0x48))
        assertEquals("Enhanced Message Service (obsolete)", meaning(0x5E))
        assertEquals("return call", meaning(0x5F))
        assertEquals("ANSI-136 R-DATA", meaning(0x7C))
        assertEquals("(U)SIM data download", meaning(0x7F))
        assertEquals("telematic: GSM/UMTS mobile station", meaning(0x3F))
        // The reserved telematic codes get the same flag as TpduParser.reservedPid.
        assertEquals("telematic: reserved", meaning(0x2E))
        assertEquals(true, tree(0x2E).childValue("pid/reserved"))
        assertNull(tree(0x40).child("pid/reserved"))
    }

    @Test
    fun `truncated tpdu reports one error node with an offset`() {
        val tree = Tpdu.decode(deliver.substring(0, 24))
        val errors = collectErrors(tree)
        assertEquals(1, errors.size, FieldJson.toJson(tree))
        assertTrue(errors.all { it.offset >= 0 })
        // No field after the end of the data.
        assertNull(tree.child("pid"))
        assertNull(tree.child("dcs"))
    }

    @Test
    fun `bad udhl still decodes without throwing`() {
        val tree = Tpdu.decode("004402D0410000621150214365807F40")
        assertTrue(collectErrors(tree).isNotEmpty())
    }

    @Test
    fun `a bare tpdu without smsc prefix is read as such`() {
        // The read with an SMSC of 4 octets fails, so the bare read of TpduParser is used.
        val tree = Tpdu.decode("0402D04100006211502143652205C8329BFD06")
        assertNull(tree.childValue("smsc"))
        assertNotNull(tree.child("smsc/note"))
        assertEquals("SMS-DELIVER", tree.value)
        assertEquals("Hello", tree.childValue("user_data/text"))
    }

    @Test
    fun `wap push on port 2948 gets a nested wsp tree`() {
        // UDH 16-bit ports 2948/9200, 8-bit data, single part: the WSP push of the corpus.
        val push =
            "010603AEAF82" + "02056A0045C60D0378797A008503656D61696C2F3132332F6162632E776D6C00" +
                "0AC3071999062515231510C304199906300103596F7520686176652034206E657720652D6D61696C73000101"
        val udh = "0605040B8423F0"
        val udl = hexByte(udh.length / 2 + push.length / 2)
        val tree = Tpdu.decode("004402D041" + "0004" + "62115021436522" + udl + udh + push)
        val wsp = tree.child("user_data/wsp_push")
        assertNotNull(wsp, FieldJson.toJson(tree))
        // Absolute offsets: the nested tree starts after the UDH.
        assertEquals(tree.child("user_data/hex")?.offset, wsp!!.offset)
        assertEquals("application/vnd.wap.sic", wsp.textValue("content_type"))
    }

    @Test
    fun `wap push on port 2948 is decoded for a 7 bit dcs too`() {
        // DECODER-P04: the framework gives the octets after the UDH to WapPushOverSms whatever the DCS.
        val push = "010603AEAF82" + "02056A0045C60D0378797A00850301"
        val udh = "0605040B8423F0"
        val octets = udh.length / 2 + push.length / 2
        // UDL in septets; one padding octet fills the last packed septet.
        val udl = (octets * 8 + 6) / 7
        val tree = Tpdu.decode("004402D041" + "0000" + "62115021436522" + hexByte(udl) + udh + push + "00")
        val wsp = tree.child("user_data/wsp_push")
        assertNotNull(wsp, FieldJson.toJson(tree))
        assertEquals("application/vnd.wap.sic", wsp!!.textValue("content_type"))
    }

    @Test
    fun `invalid hex gives an error tree`() {
        val tree = Tpdu.decode("zz")
        assertNotNull(tree.error)
        assertNull(tree.child("first_octet"))
    }

    private fun collectErrors(node: Field): List<Field> {
        val out = mutableListOf<Field>()
        if (node.error != null) out.add(node)
        for (child in node.children) out.addAll(collectErrors(child))
        return out
    }
}
