package dev.nightjar.decoder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SUPL INIT in X.691 UPER. The vectors are the same as in the payload tests, so the
 * decoder and the payload `WapContentType.suplNotificationType` read the same bits. Synthetic data only.
 */
class SuplTest {
    private val v1 = "0011020000400000004600001008201000"
    private val v2 = "002F020000C001D58C66C80E000000028555ECD96B8D40E9BD80D000142AAF66CB5C6A074DEC008101820283038400"
    private val v3 = "0011020000400000004600001008201008"
    private val v4 = "001102000040000000460000100820900C"
    private val v5 = "0011020000400000004600001008201010"
    private val v6 = "00100200004000000046000010080010"
    private val v7 = "000F02000040000000460000100A80"

    @Test
    fun `v1 decodes header session and a silent notification`() {
        val tree = Supl.decode(v1)
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
        assertEquals("17", tree.textValue("length"))
        assertEquals("2.0.0", tree.textValue("version"))
        assertNull(tree.child("session_id/set_session_id"))
        assertEquals("00000001", tree.textValue("session_id/slp_session_id/session_id"))
        assertEquals("192.0.2.1", tree.textValue("session_id/slp_session_id/slp_id"))
        assertEquals("ipAddress", tree.textValue("session_id/slp_session_id/slp_id/type"))
        assertEquals("msSUPLINIT", tree.textValue("message"))
        assertEquals("agpsSETbased", tree.textValue("init/pos_method"))
        assertEquals("noNotificationNoVerification", tree.textValue("init/notification/notification_type"))
        assertEquals("proxy", tree.textValue("init/slp_mode"))
    }

    @Test
    fun `notification types of v1 to v5 match the shared results`() {
        val expected =
            mapOf(
                v1 to "noNotificationNoVerification",
                v2 to "notificationOnly",
                v3 to "notificationAndVerficationAllowedNA",
                v4 to "notificationAndVerficationDeniedNA",
                v5 to "privacyOverride",
            )
        for ((hex, type) in expected) {
            assertEquals(type, Supl.decode(hex).textValue("init/notification/notification_type"), hex)
        }
    }

    @Test
    fun `v2 decodes the set id the fqdn the slp address and the mac`() {
        val tree = Supl.decode(v2)
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
        assertEquals("47", tree.textValue("length"))
        assertEquals("7", tree.textValue("session_id/set_session_id/session_id"))
        assertEquals("198.51.100.7", tree.textValue("session_id/set_session_id/set_id"))
        assertEquals("iPAddress", tree.textValue("session_id/set_session_id/set_id/type"))
        assertEquals("example.org", tree.textValue("session_id/slp_session_id/slp_id"))
        assertEquals("fqdn", tree.textValue("session_id/slp_session_id/slp_id/type"))
        assertEquals("agpsSETassisted", tree.textValue("init/pos_method"))
        assertEquals("true", tree.textValue("init/presence/mac"))
        assertEquals("false", tree.textValue("init/presence/qop"))
        assertEquals("example.org", tree.textValue("init/slp_address"))
        assertEquals("proxy", tree.textValue("init/slp_mode"))
        assertEquals("0102030405060708", tree.textValue("init/mac"))
        assertNull(tree.child("init/key_identity"))
    }

    @Test
    fun `nested nodes start at their own octet`() {
        val tree = Supl.decode(v2)
        val slp = tree.child("session_id/slp_session_id/slp_id")!!
        val parent = tree.child("session_id/slp_session_id")!!
        assertTrue(slp.offset >= parent.offset, "${slp.offset} < ${parent.offset}")
        // The SUPLINIT slp address starts at bit 228, so in octet 28.
        assertEquals(28, tree.child("init/slp_address")?.offset)
    }

    @Test
    fun `v4 decodes pos method no position`() {
        assertEquals("noPosition", Supl.decode(v4).textValue("init/pos_method"))
    }

    @Test
    fun `v6 has no notification element`() {
        val tree = Supl.decode(v6)
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
        assertEquals("false", tree.textValue("init/presence/notification"))
        assertNull(tree.child("init/notification"))
        assertEquals("proxy", tree.textValue("init/slp_mode"))
    }

    @Test
    fun `v7 is not supl init and gets a note without an error`() {
        val tree = Supl.decode(v7)
        assertEquals("msSUPLEND", tree.textValue("message"))
        assertNotNull(tree.child("note"))
        assertNull(tree.child("init"))
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
    }

    @Test
    fun `v3 cut to 16 octets records one error with an offset`() {
        val tree = Supl.decode(v3.substring(0, 32))
        val errors = collectErrors(tree)
        assertEquals(1, errors.size, FieldJson.toJson(tree))
        assertTrue(errors.all { it.offset >= 0 })
        assertNull(tree.child("init/notification/notification_type"))
    }

    @Test
    fun `an extension bit in suplinit is a value node and not an error`() {
        // V1 with the SUPLINIT extension bit (bit 113) set: octet 14 0x20 becomes 0x60.
        val bytes = Hex.decode(v1)
        bytes[14] = (bytes[14].toInt() or 0x40).toByte()
        val tree = Supl.decode(bytes)
        assertEquals("true", tree.textValue("init/presence/extension"))
        assertEquals("present (not decoded)", tree.textValue("init/extension"))
        assertTrue(collectErrors(tree).isEmpty(), FieldJson.toJson(tree))
    }

    @Test
    fun `empty input is total`() {
        assertNotNull(Supl.decode(""))
        assertNotNull(Supl.decode(ByteArray(0)))
    }

    private fun collectErrors(node: Field): List<Field> {
        val out = mutableListOf<Field>()
        if (node.error != null) out.add(node)
        for (child in node.children) out.addAll(collectErrors(child))
        return out
    }
}
