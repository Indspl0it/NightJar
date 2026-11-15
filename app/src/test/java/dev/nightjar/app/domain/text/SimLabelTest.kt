package dev.nightjar.app.domain.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SimLabelTest {
    @Test
    fun `operator and slot join with a middle dot`() = assertEquals("Jio · SIM 1", simDisplay(0, "Jio"))

    @Test
    fun `an unknown slot is left out and never printed as zero or a question mark`() {
        assertEquals("Airtel", simDisplay(-1, "Airtel"))
        assertEquals("", simDisplay(-1, null))
        assertEquals("", simDisplay(-1, "  "))
    }

    @Test
    fun `a placeholder operator name is ignored`() = assertEquals("SIM 2", simDisplay(1, "Unknown"))

    @Test
    fun `the slot name comes from the strings`() {
        val strings = Strings { key -> if (key == TextKey.SIM_SLOT_NAME) "Karte {n}" else key.template }
        assertEquals("Jio · Karte 1", strings.simDisplay(0, "Jio"))
    }
}
