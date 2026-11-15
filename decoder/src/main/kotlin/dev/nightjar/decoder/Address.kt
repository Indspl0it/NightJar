package dev.nightjar.decoder

/** TP address helpers for SMS-DELIVER/SUBMIT/STATUS-REPORT. */
object Address {
    private val TON_NAMES =
        arrayOf(
            "unknown",
            "international",
            "national",
            "network-specific",
            "subscriber",
            "alphanumeric",
            "abbreviated",
            "reserved",
        )

    private val NPI_NAMES =
        mapOf(
            0 to "unknown",
            1 to "isdn/e164",
            3 to "data",
            4 to "telex",
            5 to "service-centre-specific",
            6 to "service-centre-specific",
            8 to "national",
            9 to "private",
            0xA to "ermes",
            0xF to "reserved",
        )

    fun tonName(ton: Int): String = TON_NAMES.getOrElse(ton and 0x07) { "reserved" }

    fun npiName(npi: Int): String = NPI_NAMES[npi and 0x0F] ?: "reserved"

    /**
     * Reads the SMSC address: one length octet (octets of TOA + value), then TOA, then value.
     * A zero length means "use the default SMSC". The caller checks the TS 24.011 bound (at most 11 octets).
     */
    fun decodeSmsc(reader: ByteReader, name: String = "smsc"): Field {
        val start = reader.absolutePos
        // No length octet: no zero-filled "none". The caller adds the error node (VR01-D15).
        val length = reader.u8OrNull() ?: return field(name, null, start, 0)
        if (length == 0) {
            return field(name, "none", start, 1, listOf(field("length", 0, start, 1)))
        }
        val value = reader.take(length)
        val children = mutableListOf<Field>()
        children.add(field("length", length, start, 1))
        if (value.isEmpty()) {
            reader.errorField()?.let { children.add(it) }
            return field(name, "", start, reader.absolutePos - start, children)
        }
        val toa = value[0].toInt() and 0xFF
        children.addAll(toaFields(toa, start + 1))
        val digits = decodeBcd(value, 1, value.size)
        children.add(field("digits", digits, start + 2, value.size - 1))
        reader.errorField()?.let { children.add(it) }
        return field(name, digits, start, reader.absolutePos - start, children)
    }

    /**
     * Reads a TP-OA/TP-DA/TP-RA: length in semi-octets, TOA, then the address value.
     *
     * The address is alphanumeric when the TOA high nibble is 0xD (type of number 101 and bit 7 set). This is the
     * test of `TpduParser.decodeAddress` in `:pduparse`, so the tree and the payload facts agree. A TOA
     * with bit 7 clear or type of number 111 is reserved (TS 23.040 9.1.2.5, `TpduParser.reservedToa`).
     */
    fun decodeTp(reader: ByteReader, name: String): Field {
        val start = reader.absolutePos
        val lengthSemiOctets = reader.u8OrNull()
        val children = mutableListOf<Field>()
        if (lengthSemiOctets != null) children.add(field("length", lengthSemiOctets, start, 1))
        if (lengthSemiOctets == null || reader.failed) {
            reader.errorField()?.let { children.add(it) }
            return field(name, "", start, reader.absolutePos - start, children)
        }
        val valueOctets = (lengthSemiOctets + 1) / 2
        val toa = reader.u8()
        if (reader.failed) {
            reader.errorField()?.let { children.add(it) }
            return field(name, "", start, reader.absolutePos - start, children)
        }
        val value = reader.take(valueOctets)
        val alphanumeric = (toa and 0xF0) == 0xD0
        children.addAll(toaFields(toa, start + 1))
        children.add(field("alphanumeric", alphanumeric, start + 1, 1))

        val text =
            if (alphanumeric) {
                val septets = (lengthSemiOctets * 4) / 7
                Gsm7.decodeAlphanumeric(value, septets)
            } else {
                decodeBcd(value, 0, value.size)
            }
        children.add(field("digits", text, start + 2, value.size))
        reader.errorField()?.let { children.add(it) }
        return field(name, text, start, reader.absolutePos - start, children)
    }

    private fun toaFields(
        toa: Int,
        offset: Int,
    ): List<Field> =
        buildList {
            add(field("toa", hexByte(toa), offset, 1))
            add(field("type_of_number", tonName((toa shr 4) and 0x07), offset, 1))
            add(field("numbering_plan", npiName(toa and 0x0F), offset, 1))
            if ((toa and 0x80) == 0 || (toa and 0x70) == 0x70) {
                add(errorField(FieldNames.ERROR, offset, "The type of address is reserved: bit 7 is 0 or the type of number is 111."))
            }
        }

    /**
     * Decodes swapped BCD semi-octets. A..E map to *, #, a, b, c (TS 23.040 9.1.2.3). The filler 0xF is skipped
     * wherever it is, like `TpduParser.decodeAddress`. No `+` is added: the type of number is its own node.
     */
    fun decodeBcd(value: ByteArray, from: Int, until: Int): String {
        val out = StringBuilder((until - from).coerceAtLeast(0) * 2)
        for (i in from until until.coerceAtMost(value.size)) {
            val octet = value[i].toInt() and 0xFF
            appendSemi(out, octet and 0x0F)
            appendSemi(out, (octet shr 4) and 0x0F)
        }
        return out.toString()
    }

    private fun appendSemi(out: StringBuilder, value: Int) {
        when (value) {
            in 0..9 -> {
                out.append(('0' + value))
            }

            0xA -> {
                out.append('*')
            }

            0xB -> {
                out.append('#')
            }

            0xC -> {
                out.append('a')
            }

            0xD -> {
                out.append('b')
            }

            0xE -> {
                out.append('c')
            }

            else -> {}
        }
    }

    /** BCD encoder used by the fixture generator. */
    fun encodeBcd(digits: String, semiOctets: Int = digits.length): ByteArray {
        val out = ByteArray((semiOctets + 1) / 2) { 0xFF.toByte() }
        var index = 0
        while (index < digits.length && index < semiOctets) {
            val nibble =
                when (val ch = digits[index]) {
                    in '0'..'9' -> ch - '0'
                    '*' -> 0xA
                    '#' -> 0xB
                    'a' -> 0xC
                    'b' -> 0xD
                    'c' -> 0xE
                    else -> 0xF
                }
            val byteIndex = index / 2
            val current = out[byteIndex].toInt() and 0xFF
            out[byteIndex] =
                if (index % 2 == 0) {
                    (current and 0xF0) or nibble
                } else {
                    (current and 0x0F) or (nibble shl 4)
                }.toByte()
            index++
        }
        return out
    }
}
