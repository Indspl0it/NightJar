package dev.nightjar.app.core.ipc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * The app reads the text and TP-SCTS from the stored PDUs. The PDUs are synthetic: a 4-digit
 * originator and one-letter texts.
 */
class PduTextTest {
    private fun hex(text: String): ByteArray =
        text
            .replace(" ", "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

    /** SMS-DELIVER, no SMSC, originator 1234, GSM 7-bit, SCTS 2026-11-13 10:20:30 +00:00, text "Hi". */
    private val single = hex("00 04 04 81 2143 00 00 62113101020300 02 C834")

    /** Two parts of one concatenated message (reference 0x2A): part 1 is "A", part 2 is "B". */
    private val part1 = hex("00 44 04 81 2143 00 00 62113101020300 08 05 00 03 2A 02 01 82")
    private val part2 = hex("00 44 04 81 2143 00 00 62113101020300 08 05 00 03 2A 02 02 84")

    @Test
    fun `a single deliver gives its text and the modem time stamp`() {
        val decoded = PduText.decode(listOf(single), "3gpp")
        assertEquals("Hi", decoded.text)
        assertEquals(OffsetDateTime.parse("2026-11-13T10:20:30+00:00").toInstant().toEpochMilli(), decoded.modemTs)
    }

    @Test
    fun `parts are joined in sequence order, not in arrival order`() {
        assertEquals("AB", PduText.decode(listOf(part2, part1), "3gpp").text)
    }

    @Test
    fun `an unknown format or broken bytes give no text and never throw`() {
        assertNull(PduText.decode(listOf(single), "3gpp2").text)
        assertNull(PduText.decode(listOf(byteArrayOf(0x00, 0x01)), "3gpp").text)
        assertNull(PduText.decode(emptyList(), "3gpp").text)
    }
}
