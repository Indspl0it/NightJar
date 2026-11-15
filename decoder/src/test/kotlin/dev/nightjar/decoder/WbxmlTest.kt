package dev.nightjar.decoder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WbxmlTest {
    private val si =
        "02056A0045C60D0378797A008503656D61696C2F3132332F6162632E776D6C00" +
            "0AC3071999062515231510C304199906300103596F7520686176652034206E657720652D6D61696C73000101"
    private val sl = "02066A00850A0378797A00850370706169642F3132332F6162632E776D6C0001"
    private val co = "02076A0045860503666F6F2E776D6C00018705032F626172000101"

    @Test
    fun `si document decodes to xml with attributes`() {
        val result = Wbxml.decode(si)
        assertEquals("SI 1.0", result.field.textValue("public_id/name"))
        assertEquals(106, result.field.childValue("charset"))
        val indication = result.field.child("document/si/indication")
        assertNotNull(indication)
        assertEquals("http://www.xyz.com/email/123/abc.wml", indication!!.childValue("@href"))
        assertEquals("1999-06-25T15:23:15Z", indication.childValue("@created"))
        assertEquals("1999-06-30T00:00:00Z", indication.childValue("@si-expires"))
        assertEquals("You have 4 new e-mails", indication.childValue("#text"))
        assertTrue(result.xml.contains("<si><indication"))
        assertTrue(result.xml.contains("href=\"http://www.xyz.com/email/123/abc.wml\""))
    }

    @Test
    fun `sl document decodes`() {
        val result = Wbxml.decode(sl)
        assertEquals("SL 1.0", result.field.textValue("public_id/name"))
        assertEquals("http://www.xyz.com/ppaid/123/abc.wml", result.field.childValue("document/sl/@href"))
    }

    @Test
    fun `co document decodes both invalidations`() {
        val result = Wbxml.decode(co)
        val children = result.field.child("document/co")!!.children
        assertEquals("invalidate-object", children[0].name)
        assertEquals("foo.wml", children[0].childValue("@uri"))
        assertEquals("invalidate-service", children[1].name)
        assertEquals("/bar", children[1].childValue("@uri"))
    }

    @Test
    fun `string table references are resolved`() {
        val bytes = Hex.decode("02056A" + "04" + "78797A00" + "45C60B8300010101")
        val result = Wbxml.decode(bytes)
        assertTrue(result.xml.contains("href=\"xyz\""), result.xml)
    }

    @Test
    fun `string table reference is a byte offset and may point mid string`() {
        val table = "6162630064656600"
        val atStart = Wbxml.decode(Hex.decode("02056A08" + table + "45830401"))
        assertEquals("def", atStart.field.childValue("document/si/#text"))
        assertTrue(collectErrors(atStart.field).isEmpty(), atStart.xml)
        val midString = Wbxml.decode(Hex.decode("02056A08" + table + "45830101"))
        assertEquals("bc", midString.field.childValue("document/si/#text"))
    }

    @Test
    fun `string table offset past the table is an error`() {
        // pduparse AN-WBXML-STR.
        val result = Wbxml.decode(Hex.decode("02056A04" + "78797A00" + "45830901"))
        assertTrue(collectErrors(result.field).any { it.error!!.contains("outside the table") }, result.xml)
    }

    @Test
    fun `an end token without an open element is an error`() {
        val result = Wbxml.decode(Hex.decode("02056A00" + "450101"))
        assertEquals(1, collectErrors(result.field).size, result.xml)
    }

    @Test
    fun `an unknown wbxml version is flagged`() {
        val result = Wbxml.decode(Hex.decode("04056A00" + "4501"))
        assertTrue(collectErrors(result.field).any { it.error!!.contains("version") }, result.xml)
    }

    @Test
    fun `literal tag reads its name from the string table`() {
        val result = Wbxml.decode(Hex.decode("02056A04" + "6C697400" + "0400"))
        assertEquals(
            "lit",
            result.field
                .child("document")!!
                .children[0]
                .name,
        )
        assertTrue(result.xml.contains("<lit/>"), result.xml)
    }

    @Test
    fun `literal attribute reads its name from the string table`() {
        // LITERAL (0x04) is the only literal in attribute space (WAP-192 5.8.4.1).
        val result = Wbxml.decode(Hex.decode("02056A04" + "6C697400" + "85" + "0400" + "01"))
        val si = result.field.child("document/si")!!
        assertEquals("", si.childValue("@lit"))
        assertTrue(result.xml.contains("lit=\"\""), result.xml)
        assertTrue(collectErrors(result.field).isEmpty(), result.xml)
    }

    @Test
    fun `literal c in attribute space is an error`() {
        // pduparse AN-WBXML-TOK: 0x44 (LITERAL_C) is a tag token only.
        val result = Wbxml.decode(Hex.decode("02056A04" + "6C697400" + "85" + "4400" + "01"))
        assertTrue(collectErrors(result.field).any { it.error!!.contains("0x44") }, result.xml)
    }

    @Test
    fun `literal attribute with a value decodes the value`() {
        val result = Wbxml.decode(Hex.decode("02056A04" + "6C697400" + "85" + "0400" + "037600" + "01"))
        assertEquals("v", result.field.childValue("document/si/@lit"))
    }

    @Test
    fun `deep nesting fails at the depth cap instead of overflowing`() {
        // 0x45 is <si> with content; 200 open elements.
        val bytes = Hex.decode("02056A00") + ByteArray(200) { 0x45 }
        val result = Wbxml.decode(bytes)
        assertTrue(collectErrors(result.field).any { it.error!!.contains("nested more than") }, result.xml)
        assertTrue(countNodes(result.field) < 10_000)
    }

    @Test
    fun `output is capped and text nodes keep a preview`() {
        val table = "41".repeat(1024) + "00"
        val refs = "8300".repeat(2_000)
        val result = Wbxml.decode(Hex.decode("02056A" + "8801" + table + "45" + refs + "01"))
        assertTrue(result.xml.length <= 256 * 1024, "xml ${result.xml.length}")
        assertTrue(collectErrors(result.field).any { it.error!!.contains("output limit") })
        for (child in result.field.child("document/si")!!.children) {
            assertTrue((child.value as String).length <= 256, "unbounded #text preview")
        }
    }

    private fun countNodes(node: Field): Int = 1 + node.children.sumOf { countNodes(it) }

    @Test
    fun `opaque attributes decode as hex`() {
        val result = Wbxml.decode(si)
        assertTrue(result.xml.contains("created="), result.xml)
    }

    @Test
    fun `truncated wbxml records an error instead of throwing`() {
        val result = Wbxml.decode(si.substring(0, 20))
        assertTrue(collectErrors(result.field).isNotEmpty())
    }

    @Test
    fun `empty input is total`() {
        val result = Wbxml.decode("")
        assertNotNull(result.field)
    }

    @Test
    fun `unknown public id falls back to raw tokens`() {
        val result = Wbxml.decode("020C6A004501")
        assertTrue(result.field.textValue("public_id/name")!!.startsWith("unknown"))
    }

    private fun collectErrors(node: Field): List<Field> {
        val out = mutableListOf<Field>()
        if (node.error != null) out.add(node)
        for (child in node.children) out.addAll(collectErrors(child))
        return out
    }
}
