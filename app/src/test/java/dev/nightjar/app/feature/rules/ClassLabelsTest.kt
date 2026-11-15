package dev.nightjar.app.feature.rules

import dev.nightjar.contract.ClassId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Every row in the severity and firewall lists needs its own label; six "Sent by apps" rows were indistinguishable. */
class ClassLabelsTest {
    @Test
    fun `every class has a label`() {
        // classLabelRes reads a map: a class without an entry would throw here, not at a user's screen.
        ClassId.entries.forEach { classLabelRes(it) }
    }

    @Test
    fun `every class has a distinct label`() {
        val byLabel = ClassId.entries.groupBy { classLabelRes(it) }
        val duplicates = byLabel.filterValues { it.size > 1 }
        assertEquals(emptyMap<Int, List<ClassId>>(), duplicates)
    }
}
