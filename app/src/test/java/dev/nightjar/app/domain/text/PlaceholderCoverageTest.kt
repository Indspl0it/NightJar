package dev.nightjar.app.domain.text

import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.contract.ClassId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.File

/** No user-visible template keeps a `{...}` where a list or chip has no message to fill it from. */
class PlaceholderCoverageTest {
    private fun placeholders(text: String): Set<String> = Templates.unfilled(text).toSet()

    @Test
    fun `generic class names never contain a placeholder`() {
        val strings = MapStrings()
        for (classId in ClassId.entries) {
            val text = strings.genericTitle(ClassRegistry.of(classId).titleKey)
            assertFalse(text.contains('{'), "$classId: $text")
            assertFalse(text.isBlank(), "$classId")
        }
        // The expected titles. A port message names its port, not a generic hidden message.
        assertEquals("Data message to a port", strings.genericTitle(TextKey.TITLE_CL_DATA_PORT))
        assertEquals("Push message", strings.genericTitle(TextKey.TITLE_CL_WAP_OTHER))
        assertEquals("Cell broadcast", strings.genericTitle(TextKey.TITLE_CL_CB))
        assertEquals("Malformed WAP push", strings.genericTitle(TextKey.TITLE_CL_WAP_UNREADABLE))
        assertEquals("Malformed SMS", strings.genericTitle(TextKey.TITLE_CL_MALFORMED_SMS))
        assertEquals("Data message sent", strings.genericTitle(TextKey.TITLE_CL_MO_DATA))
    }

    @Test
    fun `generic subtype names never contain a placeholder`() {
        val strings = MapStrings()
        for (classId in ClassId.entries) {
            for (row in ClassRegistry.subtypes(classId)) {
                val text = strings.genericTitle(row.titleKey)
                assertFalse(text.contains('{'), "${row.titleKey}: $text")
                assertFalse(text.isBlank(), "${row.titleKey}")
            }
        }
    }

    /** Every `<string>` of every values file: the subtype, CB, pattern and anomaly strings live outside strings.xml too. */
    private val byName: Map<String, String> by lazy {
        val xml =
            File("src/main/res/values")
                .listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }
                .orEmpty()
                .joinToString("\n") { it.readText() }
        Regex("""<string\s+name="([a-z0-9_]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** The text Android shows for a resource value: the XML entities and the backslash escapes resolved. */
    private fun shown(raw: String): String =
        Regex("""\\u([0-9a-fA-F]{4})|\\(.)""")
            .replace(raw) { m ->
                val hex = m.groupValues[1]
                when {
                    hex.isNotEmpty() -> hex.toInt(16).toChar().toString()
                    m.groupValues[2] == "n" -> "\n"
                    else -> m.groupValues[2]
                }
            }.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")

    @Test
    fun `every TextKey has a string resource`() {
        // ResourceStrings falls back to the English template for a missing resource, which hides the gap.
        val missing = TextKey.entries.filter { it.resource !in byName }
        assertEquals(emptyList<TextKey>(), missing)
    }

    @Test
    fun `every TextKey template is the text of its resource`() {
        // The templates are the JVM-test copy of the resources: a difference means a test checks text the user never sees.
        for (key in TextKey.entries) {
            val res = byName[key.resource] ?: continue
            assertEquals(key.template, shown(res), "template of ${key.resource}")
        }
    }

    @Test
    fun `string resources use the same placeholders as the TextKey templates`() {
        for (key in TextKey.entries) {
            val res = byName[key.resource] ?: continue
            assertEquals(placeholders(key.template), placeholders(res), "placeholders of ${key.resource}")
        }
    }
}
