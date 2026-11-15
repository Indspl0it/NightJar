package dev.nightjar.decoder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OmaFormatsTest {
    /** NAPDEF (NAME, NAP-ADDRESS), PXPHYSICAL (PXADDR, PORTNBR), PXLOGICAL (STARTPAGE). */
    private val cp =
        "020B6A0045C65501870706034D792041504E000187080603696E7465726E65740001" +
            "01C652018720060331302E302E302E3100018723060338303830000101" +
            "C65101871C0603687474703A2F2F6578616D706C652E636F6D2F00010101"

    /**
     * An APPLICATION characteristic: SWITCH_PAGE 1 inside the attribute list, TYPE=APPLICATION (0x55 on attribute
     * page 1), then two parm elements with inline name and value strings.
     */
    private val cpApplication =
        "020B6A0045C6000155018705034150504944000603773200018705034E414D4500" +
            "060342726F7773657200010101"

    @Test
    fun `oma cp parses characteristics and parms`() {
        val tree = OmaCp.decode(cp)
        val document = tree.child("wbxml/document/wap-provisioningdoc")
        assertNotNull(document)
        assertEquals(3, document!!.children.count { it.name == "characteristic" })
        assertEquals("My APN", document.childValue("characteristic/parm/@value"))
    }

    @Test
    fun `oma cp summary gives the nap address as apn and the name apart`() {
        val tree = OmaCp.decode(cp)
        // The APN is NAP-ADDRESS, not the display NAME.
        assertEquals("internet", tree.childValue("summary/apn"))
        assertEquals("My APN", tree.childValue("summary/name"))
        assertEquals("10.0.0.1:8080", tree.childValue("summary/proxy"))
        assertEquals("http://example.com/", tree.childValue("summary/home_page"))
        assertEquals("NAPDEF,PXPHYSICAL,PXLOGICAL", tree.childValue("summary/characteristic_types"))
        assertEquals(5, tree.childValue("summary/parm_count"))
    }

    @Test
    fun `oma cp summary carries byte ranges`() {
        val tree = OmaCp.decode(cp)
        val apn = tree.child("summary/apn")
        assertNotNull(apn)
        assertTrue(apn!!.offset > 0)
        assertTrue(apn.length > 0)
    }

    @Test
    fun `switch page in an attribute list selects attribute page 1`() {
        val tree = OmaCp.decode(cpApplication)
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
        val characteristic = tree.child("wbxml/document/wap-provisioningdoc/characteristic")
        assertNotNull(characteristic, FieldJson.toJson(tree))
        assertEquals("APPLICATION", characteristic!!.childValue("@type"))
        val parms = characteristic.children.filter { it.name == "parm" }
        assertEquals(listOf("APPID", "NAME"), parms.map { it.childValue("@name") })
        assertEquals(listOf("w2", "Browser"), parms.map { it.childValue("@value") })
        assertEquals("APPLICATION", tree.childValue("summary/characteristic_types"))
        assertNull(tree.child("summary/apn"))
    }

    @Test
    fun `truncated oma cp is total`() {
        val tree = OmaCp.decode(cp.substring(0, 16))
        assertNotNull(tree.child("wbxml"))
        assertTrue(collectErrors(tree).isNotEmpty(), FieldJson.toJson(tree))
    }

    @Test
    fun `oma dm package 0 decodes the digest and the trigger header`() {
        val tree = OmaDm.decode(dmNotification())
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
        assertEquals(32, tree.textValue("digest")?.length)
        assertEquals(0, tree.child("digest")?.offset)
        assertEquals("1.2", tree.textValue("trigger_header/version"))
        assertEquals("background", tree.textValue("trigger_header/ui_mode"))
        assertEquals("server", tree.textValue("trigger_header/initiator"))
        assertEquals("0", tree.textValue("trigger_header/future_use"))
        assertEquals("1234", tree.textValue("trigger_header/session_id"))
        assertEquals("14", tree.textValue("trigger_header/server_id_length"))
        assertEquals("com.example.dm", tree.textValue("trigger_header/server_id"))
        assertEquals("CAFE", tree.textValue("trigger_body"))
        assertEquals(16, tree.child("trigger_header")?.offset)
    }

    @Test
    fun `oma dm user initiated with no server id and no body decodes`() {
        // Version 1.2, ui-mode not-specified, initiator user, session 0x0001, server id length 0.
        val bytes = Hex.decode("00".repeat(16) + "0300000000" + "0001" + "00")
        val tree = OmaDm.decode(bytes)
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
        assertEquals("not-specified", tree.textValue("trigger_header/ui_mode"))
        assertEquals("user", tree.textValue("trigger_header/initiator"))
        assertEquals("0001", tree.textValue("trigger_header/session_id"))
        assertNull(tree.child("trigger_header/server_id"))
        assertNull(tree.child("trigger_body"))
    }

    @Test
    fun `truncated oma dm records one error and no invented field`() {
        val tree = OmaDm.decode(dmNotification().copyOfRange(0, 20))
        assertEquals(1, collectErrors(tree).size, FieldJson.toJson(tree))
        assertEquals("1.2", tree.textValue("trigger_header/version"))
        assertNull(tree.child("trigger_header/future_use"))
        assertNull(tree.child("trigger_header/session_id"))
    }

    /** Digest 01..10, version 1.2, background, server, session 0x1234, server id, body CAFE. Synthetic data. */
    private fun dmNotification(): ByteArray {
        val server = "com.example.dm".toByteArray(Charsets.UTF_8)
        return Hex.decode(
            (1..16).joinToString("") { hexByte(it) } + "0318000000" + "1234" + hexByte(server.size) + Hex.encode(server) + "CAFE",
        )
    }

    private fun collectErrors(node: Field): List<Field> {
        val out = mutableListOf<Field>()
        if (node.error != null) out.add(node)
        for (child in node.children) out.addAll(collectErrors(child))
        return out
    }
}
