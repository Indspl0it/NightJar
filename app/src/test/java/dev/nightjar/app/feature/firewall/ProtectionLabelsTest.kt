package dev.nightjar.app.feature.firewall

import dev.nightjar.app.R
import dev.nightjar.app.feature.rules.classLabelRes
import dev.nightjar.contract.ClassId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** The Protection rows use short type names, and no two rows read the same. */
class ProtectionLabelsTest {
    /** Resource id to resource name, from the generated R class. */
    private val nameOf: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    /** Resource name to text, from every values file. */
    private val textOf: Map<String, String> by lazy {
        val xml =
            File("src/main/res/values")
                .listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }
                .orEmpty()
                .joinToString("\n") { it.readText() }
        Regex("""<string\s+name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun text(classId: ClassId): String = textOf.getValue(nameOf.getValue(protectionLabelRes(classId)))

    @Test
    fun `silent and flash rows use the short names`() {
        assertEquals("Silent SMS", text(ClassId.SILENT))
        assertEquals("Flash SMS", text(ClassId.FLASH))
    }

    @Test
    fun `every other type keeps the shared label`() {
        ClassId.entries
            .filter { it != ClassId.SILENT && it != ClassId.FLASH }
            .forEach { assertEquals(classLabelRes(it), protectionLabelRes(it), it.name) }
    }

    @Test
    fun `no two Protection rows have the same name`() {
        val duplicates = ClassId.entries.groupBy { text(it) }.filterValues { it.size > 1 }
        assertEquals(emptyMap<String, List<ClassId>>(), duplicates)
    }
}
