package dev.nightjar.decoder

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.fail
import java.io.File
import java.time.Duration
import kotlin.random.Random

/**
 * Adversarial-input tests.
 *
 * `Decoders.decodeBytes` wraps every decoder in `guarded()`, so "no exception escaped" proves
 * nothing on its own: a stack overflow, an unbounded allocation or a caught programming error all
 * hide behind that guard. Each test therefore runs under one hard time budget for all its decodes
 * (a budget per decode starts a thread per call and fails at random under the CPU cap), and
 * each tree is checked against the allocation bounds the decoders promise; single-bit mutations of
 * valid fixtures must additionally never surface a caught exception as a field.
 */
class FuzzTest {
    private val root = File("src/test/resources/corpus")
    private val seed = 0x4E49474854L
    private val budget = Duration.ofMinutes(3)
    private val maxNodes = 10_000
    private val maxText = 1 shl 20

    @Test
    fun `mutations stay bounded in time and size`() =
        withinBudget {
            val random = Random(seed)
            val seeds = seeds()
            assertTrue(seeds.isNotEmpty())
            for (kind in Decoders.knownKinds) {
                val bases = seeds.filter { it.kind == kind }
                if (bases.isEmpty()) continue
                repeat(MUTATIONS) {
                    val base = bases[random.nextInt(bases.size)].bytes
                    val mutated = mutate(base, random)
                    assertBudget(kind, decodeWithin(kind, mutated))
                }
            }
        }

    @Test
    fun `single bit flips of valid fixtures never surface an internal error`() =
        withinBudget {
            for (fixture in seeds().filterNot { it.name.startsWith("malformed") }) {
                for (index in fixture.bytes.indices) {
                    for (bit in 0 until 8) {
                        val mutated = fixture.bytes.copyOf()
                        mutated[index] = (mutated[index].toInt() xor (1 shl bit)).toByte()
                        val tree = decodeWithin(fixture.kind, mutated)
                        assertBudget(fixture.kind, tree)
                        internalError(tree)?.let { node ->
                            fail("${fixture.kind}/${fixture.name} bit $bit of byte $index exposed ${node.error}")
                        }
                    }
                }
            }
        }

    @Test
    fun `every truncation of every fixture is total`() =
        withinBudget {
            for (fixture in seeds()) {
                for (length in 0..fixture.bytes.size) {
                    val tree = decodeWithin(fixture.kind, fixture.bytes.copyOfRange(0, length))
                    assertBudget(fixture.kind, tree)
                    // VR01-D15: a read past the end gives no value node (a zero-filled field), only the error node.
                    pastEnd(tree, length)?.let { node ->
                        fail("${fixture.kind}/${fixture.name} cut to $length octets has ${node.name} at ${node.offset}+${node.length}")
                    }
                }
            }
        }

    @Test
    fun `random bytes are total for every decoder`() =
        withinBudget {
            val random = Random(seed + 1)
            for (kind in Decoders.knownKinds) {
                repeat(200) {
                    val bytes = ByteArray(random.nextInt(0, 64)) { random.nextInt(0, 256).toByte() }
                    assertBudget(kind, decodeWithin(kind, bytes))
                }
            }
        }

    /**
     * Amplification shapes from a 30,000-octet run of nested elements (stack depth) and a
     * large string table referenced many times (output amplification). Both must be rejected with a
     * bounded tree instead of being swallowed or allowed to grow without limit.
     */
    @Test
    fun `amplification bombs are rejected within the budget`() =
        withinBudget {
            for ((kind, bomb) in bombs()) {
                val tree = decodeWithin(kind, bomb)
                assertBudget(kind, tree)
                internalError(tree)?.let { node -> fail("$kind amplification bomb exposed ${node.error}") }
                assertTrue(hasError(tree), "$kind accepted an amplification bomb: ${FieldJson.toJson(tree).take(200)}")
            }
        }

    /** One preemptive time budget for the whole test: an endless loop fails it, a slow JIT pause does not. */
    private fun withinBudget(body: () -> Unit) = assertTimeoutPreemptively(budget) { body() }

    private fun decodeWithin(
        kind: String,
        bytes: ByteArray,
    ): Field = Decoders.decodeBytes(kind, bytes)

    private fun assertBudget(
        kind: String,
        tree: Field,
    ) {
        assertTrue(tree.offset >= 0 && tree.length >= 0, "$kind produced a negative range")
        assertTrue(nodeCount(tree) < maxNodes, "$kind produced more than $maxNodes nodes")
        assertTrue(textLength(tree) < maxText, "$kind produced more than $maxText characters of text")
    }

    /** A node produced by [guarded] whenever a decode threw (a programming error, never input). */
    private fun internalError(tree: Field): Field? =
        flatten(tree).firstOrNull { node ->
            val name = node.name.lowercase()
            val error = node.error?.lowercase()
            (error != null && "internal error" in error) ||
                "exception" in name ||
                "internal_error" in name
        }

    private fun hasError(tree: Field): Boolean = flatten(tree).any { it.error != null }

    /** The first value node (not an error node) whose octets go past the [size] octets of the input. */
    private fun pastEnd(
        tree: Field,
        size: Int,
    ): Field? = flatten(tree).firstOrNull { it.value != null && it.error == null && it.offset + it.length > size }

    private fun flatten(tree: Field): List<Field> {
        val out = ArrayList<Field>()
        val stack = ArrayDeque<Field>()
        stack.addLast(tree)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            out.add(node)
            for (child in node.children) stack.addLast(child)
        }
        return out
    }

    private fun nodeCount(tree: Field): Int {
        var count = 0
        val stack = ArrayDeque<Field>()
        stack.addLast(tree)
        while (stack.isNotEmpty() && count < maxNodes) {
            val node = stack.removeLast()
            count++
            for (child in node.children) stack.addLast(child)
        }
        return count
    }

    private fun textLength(tree: Field): Int {
        var total = 0
        val stack = ArrayDeque<Field>()
        stack.addLast(tree)
        while (stack.isNotEmpty() && total < maxText) {
            val node = stack.removeLast()
            total += (node.value as? String)?.length ?: 0
            for (child in node.children) stack.addLast(child)
        }
        return total
    }

    private fun mutate(
        base: ByteArray,
        random: Random,
    ): ByteArray {
        if (base.isEmpty()) return base
        val out = base.copyOf()
        when (random.nextInt(4)) {
            0 -> {
                out[random.nextInt(out.size)] = random.nextInt(0, 256).toByte()
            }

            1 -> {
                return out.copyOfRange(0, random.nextInt(0, out.size + 1))
            }

            2 -> {
                val count = 1 + random.nextInt(4)
                repeat(count) { out[random.nextInt(out.size)] = random.nextInt(0, 256).toByte() }
            }

            else -> {
                val index = random.nextInt(out.size)
                out[index] = (out[index].toInt() xor (1 shl random.nextInt(8))).toByte()
            }
        }
        return out
    }

    private data class Seed(
        val kind: String,
        val name: String,
        val bytes: ByteArray,
    )

    private fun seeds(): List<Seed> {
        val out = mutableListOf<Seed>()
        for (format in root.listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }) {
            for (hexFile in format.listFiles { file -> file.extension == "hex" }.orEmpty().sortedBy { it.name }) {
                val name = hexFile.nameWithoutExtension
                val kind = File(format, "$name.kind").readText().trim()
                out.add(Seed(kind, "${format.name}/$name", Hex.decode(hexFile.readText().trim())))
            }
        }
        return out
    }

    private fun bombs(): Map<String, ByteArray> {
        // Every element token (0x45: tag 0x05 with content) nests one level deeper. 0x40 is EXT_I_0, not a tag.
        val nested = wbxmlHeader(0x04, ByteArray(0)) + ByteArray(30_000) { 0x45 }
        // An 8 KB string table, referenced 2,000 times: each reference would copy the whole string.
        val table = ByteArray(8_192) { 'A'.code.toByte() } + byteArrayOf(0)
        val references = ByteArray(4_000) { if (it % 2 == 0) 0x83.toByte() else 0x00 }
        val amplified = wbxmlHeader(0x04, table) + byteArrayOf(0x45) + references + byteArrayOf(0x01)
        return Decoders.knownKinds
            .filter { it.startsWith("wbxml") }
            .associateWith { if (it == Decoders.WBXML) nested else amplified }
    }

    private fun wbxmlHeader(
        publicId: Int,
        table: ByteArray,
    ): ByteArray = byteArrayOf(0x03, publicId.toByte(), 0x6A) + uintvar(table.size) + table

    private fun uintvar(value: Int): ByteArray {
        val groups = ArrayList<Int>()
        var rest = value
        groups.add(rest and 0x7F)
        rest = rest ushr 7
        while (rest > 0) {
            groups.add((rest and 0x7F) or 0x80)
            rest = rest ushr 7
        }
        return groups.reversed().map { it.toByte() }.toByteArray()
    }

    private companion object {
        const val MUTATIONS = 10_000
    }
}
