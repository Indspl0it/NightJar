package dev.nightjar.app.feature.logs

import dev.nightjar.app.core.log.LogLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The viewer never shows, copies or exports an unmasked number, and it does not destroy the timestamp.
 * The numbers are from ranges reserved for fiction (+1 555 01xx, UK 07700 900xxx).
 */
class LogRedactorTest {
    @Test
    fun `a timestamp is left intact`() {
        val line = "2026-11-05T12:34:56.789+05:30  I  ingest  batch committed count=3"
        assertEquals(line, LogRedactor.redact(line))
    }

    @Test
    fun `international numbers are masked`() {
        val redacted = LogRedactor.redact("sender +15555550123 delivered")
        assertFalse(redacted.contains("+15555550123"))
        assertTrue(redacted.contains("+1\u2022\u2022\u2022\u2022\u2022123"))
    }

    @Test
    fun `long bare digit runs are masked`() {
        val redacted = LogRedactor.redact("originator=447700900123")
        assertFalse(redacted.contains("447700900123"))
    }

    @Test
    fun `national numbers with spaces or dashes are masked`() {
        listOf("07700 900123", "07700-900123", "0770 090 0123", "07700.900.123").forEach { number ->
            val redacted = LogRedactor.redact("from $number done")
            assertFalse(redacted.contains(number), number)
            assertTrue(redacted.contains("\u2022\u2022\u2022\u2022\u2022123"), redacted)
        }
    }

    @Test
    fun `short grouped numbers, dates and versions survive`() {
        val line = "2026-11-05T12:34:56.789+05:30  I  app  version 0.3.1 build 2026-11-05 pin 12 34 parts 3"
        assertEquals(line, LogRedactor.redact(line))
    }

    @Test
    fun `short codes and counts survive`() {
        val line = "smsc 12345 parts 3"
        assertEquals(line, LogRedactor.redact(line))
    }

    @Test
    fun `a parsed line keeps its level and tag`() {
        val parsed = parseLogLine("2026-11-05T12:34:56.789+05:30  W  hook  filter missing")
        assertEquals("2026-11-05T12:34:56.789+05:30", parsed.at)
        assertEquals(LogLevel.W, parsed.level)
        assertEquals("hook", parsed.tag)
        assertEquals("filter missing", parsed.message)
    }

    @Test
    fun `an unstructured line is kept verbatim after redaction`() {
        val parsed = parseLogLine("garbage +15555550123")
        assertEquals(null, parsed.level)
        assertTrue(parsed.raw.contains("\u2022"))
    }
}
