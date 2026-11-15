package dev.nightjar.app.core.telephony

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SimNamesTest {
    private fun names(slots: List<Int>): String? = simNames(slots, slotLabel = { "SIM $it" }, pair = { a, b -> "$a and $b" })

    @Test
    fun `no ready slot gives null so the caller writes your SIMs`() {
        assertNull(names(emptyList()))
    }

    @Test
    fun `one slot gives its 1-based label`() {
        assertEquals("SIM 1", names(listOf(0)))
        assertEquals("SIM 2", names(listOf(1)))
    }

    @Test
    fun `two slots give both labels in slot order`() {
        assertEquals("SIM 1 and SIM 2", names(listOf(0, 1)))
    }

    @Test
    fun `more than two slots give null`() {
        assertNull(names(listOf(0, 1, 2)))
    }
}
