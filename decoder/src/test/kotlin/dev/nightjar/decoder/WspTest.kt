package dev.nightjar.decoder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WspTest {
    private val siWbxml =
        "02056A0045C60D0378797A008503656D61696C2F3132332F6162632E776D6C00" +
            "0AC3071999062515231510C304199906300103596F7520686176652034206E657720652D6D61696C73000101"

    private fun push(contentType: String, body: String): String {
        val headerBytes = contentType.length / 2
        return "0106" + hexByte(headerBytes) + contentType + body
    }

    @Test
    fun `push pdu decodes tid type and headers length`() {
        val tree = Wsp.decode(push("AE", siWbxml))
        assertEquals(1, tree.childValue("transaction_id"))
        assertEquals("Push", tree.childValue("pdu_type"))
        // WAP-230 8.1.2: a uintvar holds up to 32 bits unsigned, so the decoder keeps it as a Long (VR01-D03).
        assertEquals(1L, tree.childValue("headers_length"))
        assertEquals("application/vnd.wap.sic", tree.childValue("content_type"))
    }

    @Test
    fun `well known content types map`() {
        assertEquals("application/vnd.wap.connectivity-wbxml", Wsp.contentTypeName(0x36))
        assertEquals("application/vnd.syncml.dm+wbxml", Wsp.contentTypeName(0x42))
        assertEquals("application/vnd.wap.mms-message", Wsp.contentTypeName(0x3E))
        assertTrue(Wsp.contentTypeName(0x7E).startsWith("unknown("))
    }

    @Test
    fun `application id short integer is mapped`() {
        val tree = Wsp.decode(push("AE" + "AF87", siWbxml))
        assertEquals("x-wap-application:syncml.dm", tree.childValue("headers/header/value"))
    }

    @Test
    fun `application id four is the MMS user agent`() {
        val tree = Wsp.decode(push("BE" + "AF84", ""))
        assertEquals("x-wap-application:mms.ua", tree.childValue("headers/header/value"))
        assertEquals("X-Wap-Application-Id", tree.childValue("headers/header/name"))
    }

    @Test
    fun `well known application ids follow the WINA registry`() {
        assertEquals("x-wap-application:push.sia", Wsp.applicationIdName(0x01))
        assertEquals("x-wap-application:wml.ua", Wsp.applicationIdName(0x02))
        assertEquals("x-wap-application:wta.ua", Wsp.applicationIdName(0x03))
        assertEquals("x-wap-application:mms.ua", Wsp.applicationIdName(0x04))
        assertEquals("x-wap-application:push.syncml", Wsp.applicationIdName(0x05))
        assertEquals("x-wap-application:loc.ua", Wsp.applicationIdName(0x06))
        assertEquals("x-wap-application:syncml.dm", Wsp.applicationIdName(0x07))
        assertEquals("x-wap-application:drm.ua", Wsp.applicationIdName(0x08))
        assertEquals("x-wap-application:emn.ua", Wsp.applicationIdName(0x09))
        assertEquals("x-wap-application:wv.ua", Wsp.applicationIdName(0x0A))
    }

    @Test
    fun `general form content type reads a short integer media type`() {
        val tree = Wsp.decode(push("018A", ""))
        assertEquals("text/vnd.wap.wta-event", tree.childValue("content_type"))
        assertEquals(10L, tree.childValue("content_type/code"))
        assertEquals(1, tree.childValue("content_type/value_length"))
    }

    @Test
    fun `general form content type reads text media and its parameters`() {
        val media = "6170706C69636174696F6E2F766E642E73796E636D6C2E646D2B7762786D6C"
        // Text media type (31 characters and NUL), then the Charset parameter 0x81 with utf-8 (0xEA).
        val tree = Wsp.decode(push("1F22" + media + "00" + "81EA", ""))
        assertEquals("application/vnd.syncml.dm+wbxml", tree.childValue("content_type"))
        assertEquals(false, tree.childValue("content_type/well_known"))
        assertEquals("Charset", tree.childValue("content_type/parameters/parameter/name"))
        assertEquals(34, tree.childValue("content_type/value_length"))
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
    }

    @Test
    fun `general form with a long integer media type`() {
        // Value-length 3, Short-length 2, 0x0312 (application/vnd.omaloc-supl-init).
        val tree = Wsp.decode(push("03020312", ""))
        assertEquals("application/vnd.omaloc-supl-init", tree.childValue("content_type"))
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
    }

    @Test
    fun `header length above 128 is read as a uintvar`() {
        val headers = StringBuilder("AE")
        repeat(66) { headers.append("AF84") }
        val hex = "0106" + "8105" + headers + "00"
        val tree = Wsp.decode(hex)
        assertEquals(133L, tree.childValue("headers_length"))
        assertEquals(66, tree.child("headers")!!.children.size)
        assertEquals(
            "x-wap-application:mms.ua",
            tree
                .child("headers")!!
                .children
                .last()
                .childValue("value"),
        )
    }

    @Test
    fun `text content type is read as a string`() {
        val tree = Wsp.decode(push("746578742F706C61696E00", ""))
        assertEquals("text/plain", tree.childValue("content_type"))
        assertEquals(false, tree.childValue("content_type/well_known"))
    }

    @Test
    fun `body keeps the raw bytes`() {
        val tree = Wsp.decode(push("AE", siWbxml))
        assertEquals(siWbxml.uppercase(), tree.childValue("body"))
    }

    @Test
    fun `headers length 0 is an error`() {
        // pduparse AN-WSP-HLEN.
        val tree = Wsp.decode("010600AE")
        assertEquals(1, collectErrors(tree).size, FieldJson.toJson(tree))
        assertEquals(null, tree.child("content_type"))
    }

    @Test
    fun `a pdu type that is not push is flagged`() {
        val tree = Wsp.decode("010101AE")
        assertTrue(collectErrors(tree).isNotEmpty(), FieldJson.toJson(tree))
    }

    @Test
    fun `the body is decoded further by content type`() {
        // SI, SUPL INIT (V1 of) and OMA DM package 0.
        val si = Wsp.decode(push("AE", siWbxml))
        assertNotNull(si.child("wbxml/document/si"), FieldJson.toJson(si))
        assertEquals(si.child("body")?.offset, si.child("wbxml")?.offset)
        val supl = Wsp.decode(push("03020312", "0011020000400000004600001008201000"))
        assertEquals("noNotificationNoVerification", supl.textValue("supl_init/init/notification/notification_type"))
        val dm = Wsp.decode(push("C4", "00".repeat(16) + "0318000000" + "1234" + "00"))
        assertEquals("1.2", dm.textValue("oma_dm_notification/trigger_header/version"))
        assertEquals(null, Wsp.decode(push("BE", "8C82")).child("wbxml"))
    }

    @Test
    fun `truncated headers record an error`() {
        val tree = Wsp.decode("010603AE")
        assertNotNull(tree.child("headers"))
        assertTrue(collectErrors(tree).isNotEmpty(), FieldJson.toJson(tree))
    }

    @Test
    fun `empty input is total`() {
        val tree = Wsp.decode("")
        assertNotNull(tree)
    }

    private fun collectErrors(node: Field): List<Field> {
        val out = mutableListOf<Field>()
        if (node.error != null) out.add(node)
        for (child in node.children) out.addAll(collectErrors(child))
        return out
    }
}
