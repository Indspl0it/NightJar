package dev.nightjar.decoder

/** Hex helpers. Decoding is lenient about separators, encoding is canonical upper-case. */
object Hex {
    private val SEPARATORS = charArrayOf(' ', '\t', '\n', '\r', ':', '-', '_')

    /**
     * Returns null when [text] contains a character that is not an ASCII hex digit or a separator, or has an odd
     * number of digits. Only `0-9`, `a-f` and `A-F` are digits: other Unicode digits are refused.
     */
    fun decodeOrNull(text: String): ByteArray? {
        val digits = StringBuilder(text.length)
        for (ch in text) {
            if (ch in SEPARATORS) continue
            if (nibble(ch) < 0) return null
            digits.append(ch)
        }
        if (digits.length % 2 != 0) return null
        val out = ByteArray(digits.length / 2)
        var index = 0
        for (i in out.indices) {
            out[i] = ((nibble(digits[index]) shl 4) or nibble(digits[index + 1])).toByte()
            index += 2
        }
        return out
    }

    private fun nibble(ch: Char): Int =
        when (ch) {
            in '0'..'9' -> ch - '0'
            in 'a'..'f' -> ch - 'a' + 10
            in 'A'..'F' -> ch - 'A' + 10
            else -> -1
        }

    fun decode(text: String): ByteArray = decodeOrNull(text) ?: ByteArray(0)

    fun encode(bytes: ByteArray): String = hex(bytes)

    fun encode(bytes: ByteArray, from: Int, until: Int): String = hex(bytes, from, until)
}
