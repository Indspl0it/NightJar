package dev.nightjar.decoder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Gsm7Test {
    @Test
    fun `default alphabet decodes packed septets`() {
        val septets = Gsm7.encodeDefault("Hello")
        assertEquals("Hello", Gsm7.decode(Gsm7.pack(septets), septets.size))
    }

    @Test
    fun `extension table maps euro and braces`() {
        val septets = Gsm7.encodeDefault("{\u20ac}")
        assertEquals(6, septets.size)
        assertEquals("{\u20ac}", Gsm7.decode(Gsm7.pack(septets), septets.size))
    }

    @Test
    fun `fill bits shift the first septet`() {
        val septets = Gsm7.encodeDefault("Hello")
        val packed = Gsm7.pack(septets, fillBits = 6)
        assertEquals("Hello", Gsm7.decode(packed, septets.size, fillBits = 6))
    }

    @Test
    fun `escape escape gives a space and an unknown extension falls back to the table`() {
        // TS 23.038 6.2.1.1: ESC ESC is a space; an extension septet without an entry uses the main table.
        assertEquals(" ", Gsm7.decode(Gsm7.pack(intArrayOf(Gsm7.ESCAPE, Gsm7.ESCAPE)), 2))
        assertEquals("A", Gsm7.decode(Gsm7.pack(intArrayOf(Gsm7.ESCAPE, 0x41)), 2))
    }

    @Test
    fun `national language locking shift decodes hindi`() {
        // Hindi locking shift table index 6, septet 0x01 is U+0902.
        val septets = intArrayOf(0x01, 0x02, 0x03)
        val text = Gsm7.decode(Gsm7.pack(septets), septets.size, lockingTable = 6)
        assertEquals("\u0902\u0903\u0905", text)
    }

    @Test
    fun `national language single shift decodes tamil`() {
        // Tamil single shift table index 11; septets 0x1C/0x1D are U+0BE6/U+0BE7.
        val septets = intArrayOf(Gsm7.ESCAPE, 0x1C, Gsm7.ESCAPE, 0x1D)
        val text = Gsm7.decode(Gsm7.pack(septets), septets.size, singleShiftTable = 11)
        assertEquals("\u0be6\u0be7", text)
    }

    @Test
    fun `bengali locking shift table decodes`() {
        val text = Gsm7.decode(Gsm7.pack(intArrayOf(0x00, 0x01, 0x02)), 3, lockingTable = 4)
        assertEquals("\u0981\u0982\u0983", text)
    }

    @Test
    fun `spanish locking shift falls back to the default alphabet`() {
        val text = Gsm7.decode(Gsm7.pack(intArrayOf(0x20, 0x21)), 2, lockingTable = 2)
        assertEquals(" !", text)
    }

    @Test
    fun `trailing escape renders a space`() {
        val text = Gsm7.decode(Gsm7.pack(intArrayOf(Gsm7.ESCAPE)), 1)
        assertEquals(" ", text)
    }

    @Test
    fun `ucs2 decodes surrogates`() {
        val emoji = "\uD83D\uDE00"
        val bytes = emoji.toByteArray(Charsets.UTF_16BE)
        assertEquals(emoji, Gsm7.decodeUcs2(bytes))
    }

    @Test
    fun `ucs2 pads an odd trailing octet`() {
        val text = Gsm7.decodeUcs2(Hex.decode("004100"))
        assertEquals("A\uFFFD", text)
    }

    @Test
    fun `unpacking more septets than available stops at the data end`() {
        val text = Gsm7.decode(Hex.decode("C8"), 10)
        assertEquals("H", text)
    }

    @Test
    fun `language index rejects reserved codes`() {
        assertEquals(1, Gsm7.languageIndex(1))
        assertEquals(13, Gsm7.languageIndex(13))
        assertEquals(-1, Gsm7.languageIndex(0))
        assertEquals(-1, Gsm7.languageIndex(14))
        assertEquals("Hindi", Gsm7.languageName(6))
        assertFalse(Gsm7.languageName(0).isEmpty())
        assertNotNull(Gsm7.tableChar(6, 0x01))
    }
}
