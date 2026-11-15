package dev.nightjar.decoder

import java.time.YearMonth

/**
 * The 7-octet time stamp of TS 23.040 9.2.3.11 (TP-SCTS, TP-DT, absolute TP-VP). The ETWS primary notification
 * uses the same format (TS 23.041 9.4.1.3.3). One function for both.
 *
 * Each octet holds two swapped BCD digits. The time zone is in quarters of an hour; bit 3 of the last octet is
 * the sign. The year rule is the one of `TpduParser.decodeScts` in `:pduparse`: YY >= 90 is 19YY, else 20YY.
 */
internal object Timestamp {
    /** The child nodes of a time stamp of [bytes] at absolute [offset]. Call it only with 7 octets. */
    fun fields(
        bytes: ByteArray,
        offset: Int,
    ): List<Field> {
        val out = mutableListOf<Field>()
        val yy = digits(bytes[0])
        val year = yy?.let { if (it >= 90) 1900 + it else 2000 + it }
        val month = digits(bytes[1])
        val day = digits(bytes[2])
        val hour = digits(bytes[3])
        val minute = digits(bytes[4])
        val second = digits(bytes[5])
        out.add(part("year", year, bytes[0], offset))
        out.add(part("month", month, bytes[1], offset + 1))
        out.add(part("day", day, bytes[2], offset + 2))
        out.add(part("hour", hour, bytes[3], offset + 3))
        out.add(part("minute", minute, bytes[4], offset + 4))
        out.add(part("second", second, bytes[5], offset + 5))
        val tzOctet = bytes[6].toInt() and 0xFF
        val units = (tzOctet shr 4) and 0x0F
        val quarters = if (units <= 9) (tzOctet and 0x07) * 10 + units else null
        val signed = quarters?.let { if ((tzOctet and 0x08) != 0) -it else it }
        val tz =
            signed?.let {
                val abs = kotlin.math.abs(it)
                (if (it < 0) "-" else "+") + pad(abs / 4, 2) + ":" + pad((abs % 4) * 15, 2)
            }
        out.add(part("timezone_quarters", signed, bytes[6], offset + 6))
        if (tz != null) out.add(field("timezone", tz, offset + 6, 1))
        val date = date(year, month, day)
        val clock = clock(hour, minute, second)
        val iso = if (date != null && clock != null && tz != null) date + "T" + clock + tz else null
        if (iso != null) {
            out.add(field("iso8601", iso, offset, 7))
        } else {
            out.add(errorField(FieldNames.ERROR, offset, "The time stamp is not a valid date and time."))
        }
        return out
    }

    /** The ISO 8601 text of [fields], or null when the time stamp is not valid. */
    fun iso(fields: List<Field>): String? = fields.firstOrNull { it.name == "iso8601" }?.value?.toString()

    private fun part(
        name: String,
        value: Int?,
        octet: Byte,
        offset: Int,
    ): Field = field(name, value ?: "invalid BCD 0x${hexByte(octet.toInt())}", offset, 1)

    /** Swapped BCD: low nibble = tens, high nibble = units. Null when a nibble is above 9. */
    private fun digits(octet: Byte): Int? {
        val v = octet.toInt() and 0xFF
        val tens = v and 0x0F
        val units = (v shr 4) and 0x0F
        if (tens > 9 || units > 9) return null
        return tens * 10 + units
    }

    /** YYYY-MM-DD, or null when a part is not BCD or the date does not exist (VR01-D04: no exception as control flow). */
    private fun date(
        year: Int?,
        month: Int?,
        day: Int?,
    ): String? {
        if (year == null || month == null || day == null) return null
        if (month !in 1..12 || day !in 1..YearMonth.of(year, month).lengthOfMonth()) return null
        return pad(year, 4) + "-" + pad(month, 2) + "-" + pad(day, 2)
    }

    /** HH:MM:SS, or null when a part is not BCD or out of range. */
    private fun clock(
        hour: Int?,
        minute: Int?,
        second: Int?,
    ): String? {
        if (hour == null || minute == null || second == null) return null
        if (hour > 23 || minute > 59 || second > 59) return null
        return pad(hour, 2) + ":" + pad(minute, 2) + ":" + pad(second, 2)
    }
}
