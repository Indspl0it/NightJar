package dev.nightjar.app.feature.export

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CsvSafeTest {
    @Test
    fun formulaStartsAreNeutralised() {
        assertEquals("'=HYPERLINK(\"http://x\")", csvSafe("=HYPERLINK(\"http://x\")"))
        assertEquals("'@SUM(1)", csvSafe("@SUM(1)"))
        assertEquals("'+cmd|' /C calc'!A0", csvSafe("+cmd|' /C calc'!A0"))
    }

    @Test
    fun phoneNumbersAndPlainTextAreUntouched() {
        assertEquals("+14155550123", csvSafe("+14155550123"))
        assertEquals("hello", csvSafe("hello"))
        assertEquals("", csvSafe(""))
        assertNull(csvSafe(null))
    }
}
