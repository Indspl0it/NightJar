package dev.nightjar.decoder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Runs the versioned corpus: every `<format>/<name>.hex` must decode to exactly
 * `<name>.expected.json`, and every fixture flagged `malformed_*` must produce an error node.
 */
class CorpusTest {
    private val root = File("src/test/resources/corpus")

    /** The number of checked-in fixtures: a lost fixture fails the golden test. */
    private val fixtureCount = 54

    @Test
    fun `corpus is present`() {
        assertTrue(root.isDirectory, "missing corpus at ${root.absolutePath}")
        val formats =
            root
                .listFiles { file -> file.isDirectory }
                ?.map { it.name }
                ?.sorted()
                .orEmpty()
        assertTrue(
            formats.containsAll(listOf("tpdu", "udh", "wsp", "wbxml", "oma_cp", "oma_dm", "supl", "sms_pp", "cb")),
            formats.toString(),
        )
    }

    @Test
    fun `every fixture decodes to its golden json`() {
        var checked = 0
        for (format in root.listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }) {
            val cases = format.listFiles { file -> file.extension == "hex" }.orEmpty().sortedBy { it.name }
            assertTrue(cases.isNotEmpty(), "no fixtures in ${format.name}")
            for (hexFile in cases) {
                val name = hexFile.nameWithoutExtension
                val kind = File(format, "$name.kind").readText().trim()
                val hex = hexFile.readText().trim()
                val expected = File(format, "$name.expected.json").readText().trim()
                val tree = Decoders.decode(kind, hex)
                assertEquals(expected, FieldJson.toJson(tree), "fixture ${format.name}/$name")
                if (name.startsWith("malformed")) {
                    assertTrue(hasError(tree), "malformed fixture ${format.name}/$name produced no error node")
                }
                checked++
            }
        }
        assertTrue(checked >= fixtureCount, "only $checked of $fixtureCount fixtures")
    }

    @Test
    fun `every fixture decodes twice identically`() {
        for (format in root.listFiles { file -> file.isDirectory }.orEmpty()) {
            for (hexFile in format.listFiles { file -> file.extension == "hex" }.orEmpty()) {
                val name = hexFile.nameWithoutExtension
                val kind = File(format, "$name.kind").readText().trim()
                val hex = hexFile.readText().trim()
                assertEquals(
                    FieldJson.toJson(Decoders.decode(kind, hex)),
                    FieldJson.toJson(Decoders.decode(kind, hex)),
                    "fixture ${format.name}/$name is not deterministic",
                )
            }
        }
    }

    @Test
    fun `golden json round trips through the tree`() {
        val hex = File(root, "tpdu/deliver_7bit.hex").readText().trim()
        val tree = Decoders.decode("tpdu", hex)
        assertNotNull(tree.child("user_data/text"))
        assertEquals("Hello", tree.childValue("user_data/text"))
        assertTrue(FieldJson.toJson(tree).startsWith("{\"name\":\"tpdu\""))
    }

    private fun hasError(node: Field): Boolean =
        node.error != null || node.children.any { hasError(it) }
}
