package dev.nightjar.decoder

/**
 * GSM 7-bit default alphabet, extension table and TS 23.038 national language tables.
 *
 * Septets are unpacked LSB-first. A UDH shifts the first septet by 1..6 fill bits; callers pass
 * [fillBits] explicitly because the value is derived from the UDH length.
 */
object Gsm7 {
    const val ESCAPE = 0x1B

    /** Maps a National Language Identifier octet (1..13) to a table index, or -1 when reserved. */
    fun languageIndex(code: Int): Int = if (code in 1..13) code else -1

    fun languageName(index: Int): String =
        Gsm7Tables.LANGUAGE_NAMES.getOrElse(index) { "unknown($index)" }

    fun tableChar(table: Int, septet: Int): Char {
        val text = Gsm7Tables.LOCKING.getOrElse(table) { "" }
        if (text.isEmpty()) return Gsm7Tables.LOCKING[0].getOrElse(septet and 0x7F) { '\uFFFD' }
        return text.getOrElse(septet and 0x7F) { '\uFFFD' }
    }

    fun shiftChar(table: Int, septet: Int): Char {
        val text = Gsm7Tables.SINGLE_SHIFT.getOrElse(table) { Gsm7Tables.SINGLE_SHIFT[0] }
        return text.getOrElse(septet and 0x7F) { '\uFFFD' }
    }

    /**
     * The character of an escape followed by [septet]. TS 23.038 6.2.1.1: when the extension table has no
     * character at that code, the receiver shows the character of the (locking-shift) table for the code
     * . ESC ESC is reserved for a further extension table and shows a space. In the tables a space
     * or U+FFFF marks a code that has no character (the extension tables have no real space).
     */
    fun extensionChar(
        shiftTable: Int,
        lockingTable: Int,
        septet: Int,
    ): Char {
        if (septet == ESCAPE) return ' '
        val ch = shiftChar(shiftTable, septet)
        return if (ch == ' ' || ch == '\uFFFF') tableChar(lockingTable, septet) else ch
    }

    /**
     * Decodes [septetCount] septets from [data] starting [fillBits] bits into the first octet.
     *
     * [lockingTable] is a table index (0 default, 1..13 national). [singleShiftTable] is -1 when no
     * National Language Single Shift IE is present.
     */
    fun decode(
        data: ByteArray,
        septetCount: Int,
        fillBits: Int = 0,
        lockingTable: Int = 0,
        singleShiftTable: Int = -1,
    ): String {
        if (septetCount <= 0) return ""
        val available = maxSeptets(data.size, fillBits)
        val count = minOf(septetCount, available)
        val out = StringBuilder(count)
        var index = 0
        while (index < count) {
            val septet = septetAt(data, index, fillBits)
            index++
            if (septet == ESCAPE) {
                if (index >= count) {
                    out.append(' ')
                    break
                }
                val shifted = septetAt(data, index, fillBits)
                index++
                out.append(extensionChar(singleShiftTable.coerceAtLeast(0), lockingTable, shifted))
            } else {
                out.append(tableChar(lockingTable, septet))
            }
        }
        return out.toString()
    }

    /** Decodes an alphanumeric TP-OA/TP-DA address (always the default alphabet). */
    fun decodeAlphanumeric(data: ByteArray, septetCount: Int): String =
        decode(data, septetCount, fillBits = 0, lockingTable = 0, singleShiftTable = -1)

    fun septetAt(data: ByteArray, index: Int, fillBits: Int): Int {
        val bit = fillBits + index * 7
        val byteIndex = bit / 8
        if (byteIndex >= data.size) return 0
        val shift = bit % 8
        val low = data[byteIndex].toInt() and 0xFF
        val high = if (byteIndex + 1 < data.size) data[byteIndex + 1].toInt() and 0xFF else 0
        return ((low ushr shift) or (high shl (8 - shift))) and 0x7F
    }

    fun maxSeptets(octets: Int, fillBits: Int): Int {
        if (octets <= 0) return 0
        val bits = octets * 8 - fillBits
        return if (bits <= 0) 0 else bits / 7
    }

    /** Packs septets LSB-first, optionally leaving [fillBits] low bits of the first octet zero. */
    fun pack(septets: IntArray, fillBits: Int = 0): ByteArray {
        if (septets.isEmpty()) return ByteArray(0)
        val totalBits = fillBits + septets.size * 7
        val out = ByteArray((totalBits + 7) / 8)
        var bit = fillBits
        for (septet in septets) {
            val byteIndex = bit / 8
            val shift = bit % 8
            val value = septet and 0x7F
            out[byteIndex] = (out[byteIndex].toInt() or ((value shl shift) and 0xFF)).toByte()
            if (shift > 1 && byteIndex + 1 < out.size) {
                out[byteIndex + 1] = (out[byteIndex + 1].toInt() or (value ushr (8 - shift))).toByte()
            }
            bit += 7
        }
        return out
    }

    /** Number of octets needed to carry [septetCount] septets plus [fillBits] fill bits. */
    fun packedOctets(septetCount: Int, fillBits: Int = 0): Int {
        if (septetCount <= 0) return 0
        return (fillBits + septetCount * 7 + 7) / 8
    }

    /** UCS-2 (big-endian) with a replacement character for an odd trailing octet. */
    fun decodeUcs2(data: ByteArray): String {
        val chars = CharArray(data.size / 2)
        for (i in chars.indices) {
            chars[i] = (((data[i * 2].toInt() and 0xFF) shl 8) or (data[i * 2 + 1].toInt() and 0xFF)).toChar()
        }
        val text = String(chars)
        return if (data.size % 2 == 0) text else text + '\uFFFD'
    }

    /** Encodes a string into GSM 7-bit default-alphabet septets, substituting '?' when unmappable. */
    fun encodeDefault(text: String): IntArray {
        val septets = ArrayList<Int>(text.length)
        for (ch in text) {
            val direct = Gsm7Tables.LOCKING[0].indexOf(ch)
            if (direct >= 0 && ch != '\uFFFF') {
                septets.add(direct)
            } else {
                val extended = Gsm7Tables.SINGLE_SHIFT[0].indexOf(ch)
                if (extended >= 0 && ch != '\uFFFF') {
                    septets.add(ESCAPE)
                    septets.add(extended)
                } else {
                    septets.add(0x3F)
                }
            }
        }
        return septets.toIntArray()
    }
}
