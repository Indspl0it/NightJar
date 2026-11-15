package dev.nightjar.decoder

/**
 * One node of a decoded byte-ranged field tree.
 *
 * [offset] is always relative to the first byte handed to the decoder, so the UI can link a tree
 * node to the hex view. [length] is the number of bytes covered by the node. Error nodes have
 * a zero length and sit at the byte where decoding failed.
 *
 * [value] is deliberately untyped: String, Int, Long or Boolean. Raw byte strings are rendered as
 * upper-case hex by the decoders. [children] keep decode order; the canonical JSON writer keeps it.
 */
data class Field(
    val name: String,
    val value: Any? = null,
    val offset: Int,
    val length: Int,
    val children: List<Field> = emptyList(),
    val error: String? = null,
) {
    fun child(path: String): Field? {
        var node: Field = this
        for (part in path.split('/')) {
            if (part.isEmpty()) continue
            node = node.children.firstOrNull { it.name == part } ?: return null
        }
        return node
    }

    fun childValue(path: String): Any? = child(path)?.value

    fun textValue(path: String): String? = child(path)?.value?.toString()
}

/** Creates a value node. */
fun field(
    name: String,
    value: Any?,
    offset: Int,
    length: Int,
    children: List<Field> = emptyList(),
): Field = Field(name = name, value = value, offset = offset, length = length, children = children)

/** Creates a container node without a scalar value. */
fun group(
    name: String,
    offset: Int,
    length: Int,
    children: List<Field>,
): Field = Field(name = name, offset = offset, length = length, children = children)

/** Creates an error node at [offset]. */
fun errorField(
    name: String,
    offset: Int,
    message: String,
): Field = Field(name = name, value = message, offset = offset, length = 0, error = message)

/** Upper-case hex digits; no `String.format`, so the output never depends on the default locale. */
private const val HEX_DIGITS = "0123456789ABCDEF"

/** Formats an octet as two upper-case hex digits. */
internal fun hexByte(value: Int): String {
    val v = value and 0xFF
    return charArrayOf(HEX_DIGITS[v shr 4], HEX_DIGITS[v and 0x0F]).concatToString()
}

/** Formats the low [digits] hex digits of [value] in upper case, for example `hexDigits(0x1234, 4)` = "1234". */
internal fun hexDigits(
    value: Long,
    digits: Int,
): String {
    val out = CharArray(digits)
    for (i in 0 until digits) out[digits - 1 - i] = HEX_DIGITS[((value ushr (i * 4)) and 0x0F).toInt()]
    return out.concatToString()
}

/** Formats a byte array as upper-case hex without separators. */
internal fun hex(bytes: ByteArray, from: Int = 0, until: Int = bytes.size): String {
    val end = until.coerceIn(from, bytes.size)
    if (from >= end) return ""
    val out = StringBuilder((end - from) * 2)
    for (i in from until end) out.append(hexByte(bytes[i].toInt()))
    return out.toString()
}

/** Formats [value] with at least [width] decimal digits and a leading zero pad. ASCII digits only. */
internal fun pad(
    value: Int,
    width: Int,
): String {
    val text = kotlin.math.abs(value).toString()
    val padded = if (text.length >= width) text else "0".repeat(width - text.length) + text
    return if (value < 0) "-$padded" else padded
}

/** A recorded read failure. [offset] is absolute in the original input. */
data class ReaderFailure(val offset: Int, val message: String)

/**
 * Bounds-checked reader that never throws.
 *
 * A failed read records the first [ReaderFailure] and returns a neutral value. A reader and every
 * [slice] taken from it share one failure record, so one malformed PDU gives one failure. Decoders
 * append [errorField] to the tree; it returns the node only once, so the failure shows as exactly
 * one error node at the level that reports it first.
 */
class ByteReader private constructor(
    private val data: ByteArray,
    val baseOffset: Int,
    private val start: Int,
    private val limit: Int,
    private val state: FailureState,
) {
    constructor(data: ByteArray, baseOffset: Int = 0) : this(data, baseOffset, 0, data.size, FailureState())

    /** The failure record that a reader shares with its slices. */
    private class FailureState {
        var failure: ReaderFailure? = null
        var reported: Boolean = false
    }

    var pos: Int = start
        private set

    val failure: ReaderFailure? get() = state.failure

    /** True after the first failed read of this reader or of a reader that shares its failure record. */
    val failed: Boolean get() = state.failure != null

    val remaining: Int get() = limit - pos

    val absolutePos: Int get() = baseOffset + pos

    val hasMore: Boolean get() = pos < limit

    fun u8(): Int {
        if (pos >= limit) {
            fail(END_OF_DATA)
            return 0
        }
        return data[pos++].toInt() and 0xFF
    }

    fun peek(): Int {
        if (pos >= limit) {
            fail(END_OF_DATA)
            return 0
        }
        return data[pos].toInt() and 0xFF
    }

    fun u16be(): Int = (u8() shl 8) or u8()

    /**
     * Like [u8], but null when the octet is past the end. A neutral 0 must not reach the tree as a field value
     * (VR01-D15). The position and the failure record change as for [u8].
     */
    fun u8OrNull(): Int? {
        val past = pos >= limit
        val value = u8()
        return if (past) null else value
    }

    /** Like [u16be], but null when one of the two octets is past the end (VR01-D15). */
    fun u16OrNull(): Int? {
        val past = remaining < 2
        val value = u16be()
        return if (past) null else value
    }

    /** Like [uintvar], but null when the integer has no last octet or more than 5 octets (VR01-D15). */
    fun uintvarOrNull(): Long? {
        var value = 0L
        var count = 0
        while (true) {
            if (pos >= limit) {
                fail("The multi-byte integer has no last octet.")
                return null
            }
            val octet = u8()
            value = (value shl 7) or (octet and 0x7F).toLong()
            count++
            if ((octet and 0x80) == 0) return value
            if (count >= 5) {
                fail("The multi-byte integer has more than 5 octets.")
                return null
            }
        }
    }

    fun take(count: Int): ByteArray {
        if (count < 0) {
            fail("The read length is negative.")
            return ByteArray(0)
        }
        if (count > remaining) {
            fail(truncated(count))
            val out = data.copyOfRange(pos, limit)
            pos = limit
            return out
        }
        val out = data.copyOfRange(pos, pos + count)
        pos += count
        return out
    }

    fun takeRest(): ByteArray = take(remaining)

    fun skip(count: Int) {
        if (count < 0) {
            fail("The skip length is negative.")
            return
        }
        if (count > remaining) {
            fail(truncated(count))
            pos = limit
            return
        }
        pos += count
    }

    /**
     * Reads [count] bytes as a bounded child reader. The child's offsets are absolute and it shares the failure
     * record. On truncation the child covers only the available bytes. A negative [count] gives an empty child.
     */
    fun slice(count: Int): ByteReader {
        if (count < 0) {
            fail("The read length is negative.")
            return ByteReader(data, baseOffset, pos, pos, state)
        }
        val available = if (count <= remaining) count else remaining
        if (count > remaining) fail(truncated(count))
        val child = ByteReader(data, baseOffset, pos, pos + available, state)
        pos += available
        return child
    }

    /** Reads an unsigned multi-byte integer (WSP uintvar, WBXML mb_u_int32): at most 5 octets. 0 when it fails. */
    fun uintvar(): Long = uintvarOrNull() ?: 0L

    fun fail(message: String) {
        if (state.failure == null) state.failure = ReaderFailure(baseOffset + pos, message)
    }

    fun failAt(offset: Int, message: String) {
        if (state.failure == null) state.failure = ReaderFailure(offset, message)
    }

    /** The error node of the recorded failure, or null when there is none or another level already reported it. */
    fun errorField(name: String = FieldNames.ERROR): Field? {
        val f = state.failure ?: return null
        if (state.reported) return null
        state.reported = true
        return errorField(name, f.offset, f.message)
    }

    private fun truncated(count: Int): String = "The data is too short: $count bytes are necessary and $remaining bytes are available."

    private companion object {
        const val END_OF_DATA = "The data ends before the field."
    }
}

/**
 * Reads one octet and adds the node that [node] makes from the octet and its absolute offset to [out]. When the reader
 * has already failed or the read fails, nothing is added and the result is null. A sequence of these calls therefore
 * stops at the first failure without a return after each field (VR01-D04).
 */
internal inline fun ByteReader.octetNode(
    out: MutableList<Field>,
    node: (value: Int, offset: Int) -> Field,
): Int? {
    if (failed) return null
    val offset = absolutePos
    val value = u8()
    if (failed) return null
    out.add(node(value, offset))
    return value
}
