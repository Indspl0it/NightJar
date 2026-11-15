package dev.nightjar.decoder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FieldTest {
    private fun tree(): Field =
        field(
            "root",
            null,
            0,
            4,
            listOf(
                field("a", 1, 0, 1),
                group("b", 1, 3, listOf(field("c", "x", 2, 2))),
            ),
        )

    @Test
    fun `child lookup walks the tree`() {
        val root = tree()
        assertEquals(1, root.childValue("a"))
        assertEquals("x", root.childValue("b/c"))
        assertNull(root.child("b/missing"))
        assertNull(root.childValue("nope"))
    }

    @Test
    fun `hex helpers round trip`() {
        val bytes = Hex.decode("00 01 0A ff")
        assertEquals("00010AFF", Hex.encode(bytes))
        assertNull(Hex.decodeOrNull("0"))
        assertNull(Hex.decodeOrNull("zz"))
        // Arabic-Indic and fullwidth digits are not hex digits.
        assertNull(Hex.decodeOrNull("١٢"))
        assertNull(Hex.decodeOrNull("０１"))
    }

    @Test
    fun `reader never throws and records the failing offset`() {
        val reader = ByteReader(Hex.decode("0102"))
        assertEquals(1, reader.u8())
        assertEquals(2, reader.u8())
        assertEquals(0, reader.u8())
        val failure = reader.failure
        assertNotNull(failure)
        assertEquals(2, failure!!.offset)
        assertEquals(0, reader.u8())
        assertEquals(failure, reader.failure)
    }

    @Test
    fun `reader slice bounds the child and reports truncation`() {
        val reader = ByteReader(Hex.decode("0102030405"))
        val child = reader.slice(10)
        assertEquals(5, child.remaining)
        assertEquals(0, child.baseOffset)
        assertNotNull(reader.failure)
        assertEquals(0, reader.remaining)
    }

    @Test
    fun `reader slice with a negative count fails and is empty`() {
        val reader = ByteReader(Hex.decode("0102"))
        val child = reader.slice(-1)
        assertEquals(0, child.remaining)
        assertNotNull(reader.failure)
        assertEquals(2, reader.remaining)
    }

    @Test
    fun `one failure gives one error node across slices`() {
        val reader = ByteReader(Hex.decode("0102"))
        val child = reader.slice(5)
        child.take(3)
        assertTrue(child.failed)
        assertNotNull(child.errorField())
        assertNull(child.errorField())
        assertNull(reader.errorField())
    }

    @Test
    fun `hex formatting does not depend on the default locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("mr-IN-u-nu-deva"))
            assertEquals("0A", hexByte(10))
            assertEquals("0042", hexDigits(0x42, 4))
            assertEquals("07", pad(7, 2))
            assertEquals("-05", pad(-5, 2))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `reader slice keeps absolute offsets of the parent`() {
        val reader = ByteReader(Hex.decode("0102030405"), baseOffset = 10)
        val first = reader.u8()
        assertEquals(0x01, first)
        val childStart = reader.absolutePos
        val child = reader.slice(2)
        assertEquals(childStart, child.absolutePos)
        assertEquals(2, child.remaining)
        assertEquals(0x02, child.u8())
        assertEquals(childStart + 1, child.absolutePos)
    }

    @Test
    fun `reader uintvar decodes multi octet values`() {
        val reader = ByteReader(Hex.decode("8100"))
        assertEquals(128L, reader.uintvar())
        assertNull(reader.failure)
    }

    @Test
    fun `json writer is canonical and compact`() {
        val json = FieldJson.toJson(field("n", "a\"b", 1, 2, listOf(field("c", true, 1, 1))))
        assertEquals(
            "{\"name\":\"n\",\"value\":\"a\\\"b\",\"offset\":1,\"length\":2,\"children\":[{\"name\":\"c\",\"value\":true,\"offset\":1,\"length\":1}]}",
            json,
        )
    }

    @Test
    fun `json writer escapes non ascii`() {
        val json = FieldJson.toJson(field("n", "\u00e9\u20ac", 0, 0))
        assertTrue(json.contains("\\u00e9"), json)
        assertTrue(json.contains("\\u20ac"), json)
    }

    @Test
    fun `error nodes carry the message and a zero length`() {
        val node = errorField("error", 3, "boom")
        assertEquals("boom", node.error)
        assertEquals(3, node.offset)
        assertEquals(0, node.length)
        assertTrue(FieldJson.toJson(node).contains("\"error\":\"boom\""))
    }
}
