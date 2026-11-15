package dev.nightjar.decoder

/**
 * MSB-first bit reader for the bit-packed formats (OMA DM notification, OMA ULP in X.691 UPER). Never throws: a read
 * past the end sets [truncated], moves to the end and returns 0. Field offsets are absolute: [baseOffset] plus the
 * octet of the bit.
 */
internal class BitReader(
    private val data: ByteArray,
    private val baseOffset: Int = 0,
) {
    var bitPos: Int = 0
        private set

    var truncated: Boolean = false
        private set

    val remainingBits: Int get() = data.size * 8 - bitPos

    fun bit(): Int = bits(1).toInt()

    /** Reads [count] bits (at most 63) as an unsigned number. */
    fun bits(count: Int): Long {
        if (count <= 0) return 0
        if (count > remainingBits) {
            truncated = true
            bitPos = data.size * 8
            return 0
        }
        var value = 0L
        repeat(count) {
            val index = bitPos ushr 3
            val bitIndex = 7 - (bitPos and 7)
            value = (value shl 1) or ((data[index].toInt() ushr bitIndex) and 1).toLong()
            bitPos++
        }
        return value
    }

    fun octets(count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        if (count * 8 > remainingBits) {
            truncated = true
            bitPos = data.size * 8
            return ByteArray(0)
        }
        val out = ByteArray(count)
        for (i in out.indices) out[i] = bits(8).toByte()
        return out
    }

    /** Absolute offset of the octet that holds bit [bit]. */
    fun offsetOf(bit: Int): Int = baseOffset + bit / 8

    /** A node for the bits [startBit, endBit): it covers every octet that holds one of those bits. */
    fun field(
        name: String,
        value: Any?,
        startBit: Int,
        endBit: Int = bitPos,
        children: List<Field> = emptyList(),
    ): Field {
        val startByte = startBit / 8
        val endByte = (endBit + 7) / 8
        // The package-level node factory, not this member.
        return dev.nightjar.decoder.field(name, value, baseOffset + startByte, (endByte - startByte).coerceAtLeast(1), children)
    }

    fun group(
        name: String,
        startBit: Int,
        children: List<Field>,
    ): Field = field(name, null, startBit, bitPos, children)

    /** The error node for a cut message, at the end of the data. */
    fun truncatedError(): Field = errorField(FieldNames.ERROR, baseOffset + data.size, "The data ends before the end of the message.")
}
