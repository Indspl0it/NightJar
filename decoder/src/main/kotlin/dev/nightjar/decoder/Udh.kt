package dev.nightjar.decoder

/** Concatenation information from UDH IE 0x00 or 0x08. */
data class ConcatInfo(
    val reference: Int,
    val total: Int,
    val sequence: Int,
    val sixteenBit: Boolean,
)

/** Application port addressing from UDH IE 0x04 or 0x05. */
data class PortInfo(
    val destination: Int,
    val originator: Int,
    val sixteenBit: Boolean,
)

/** Facts the TPDU decoder needs from the UDH without re-parsing the tree. */
data class UdhInfo(
    val totalOctets: Int,
    val concat: ConcatInfo?,
    val ports: PortInfo?,
    val lockingShift: Int,
    val singleShift: Int,
    val toolkitCommand: Boolean,
    val toolkitResponse: Boolean,
) {
    /** True for a valid concatenation element with more than one part (the `inspect` rule of `TpduParser`). */
    val multipart: Boolean get() = concat != null && concat.total > 1
}

/** User Data Header decoder. */
object Udh {
    /** IEI names of TS 23.040 9.2.3.24. The ranges are in [ieName]. */
    private val IE_NAMES =
        mapOf(
            0x00 to "concatenated_8bit",
            0x01 to "special_sms_indication",
            0x04 to "port_8bit",
            0x05 to "port_16bit",
            0x06 to "smsc_control_parameters",
            0x07 to "udh_source_indicator",
            0x08 to "concatenated_16bit",
            0x09 to "wcmp",
            0x0A to "ems_text_formatting",
            0x0B to "ems_predefined_sound",
            0x0C to "ems_user_defined_sound",
            0x0D to "ems_predefined_animation",
            0x0E to "ems_large_animation",
            0x0F to "ems_small_animation",
            0x10 to "ems_large_picture",
            0x11 to "ems_small_picture",
            0x12 to "ems_variable_picture",
            0x13 to "ems_user_prompt_indicator",
            0x14 to "ems_extended_object",
            0x15 to "ems_reused_extended_object",
            0x16 to "ems_compression_control",
            0x17 to "ems_object_distribution_indicator",
            0x18 to "ems_standard_wvg_object",
            0x19 to "ems_character_size_wvg_object",
            0x1A to "ems_extended_object_data_request",
            0x20 to "rfc822_email_header",
            0x21 to "hyperlink_format_element",
            0x22 to "reply_address_element",
            0x23 to "enhanced_voice_mail_information",
            0x24 to "national_language_single_shift",
            0x25 to "national_language_locking_shift",
            0x70 to "toolkit_security_header",
            0x71 to "toolkit_response_header",
        )

    private fun ieName(iei: Int): String =
        IE_NAMES[iei] ?: when (iei) {
            in 0x72..0x7F -> "toolkit_security_header_0x${hexByte(iei)}"
            in 0x80..0x9F -> "sme_to_sme_specific_0x${hexByte(iei)}"
            in 0xC0..0xDF -> "sc_specific_0x${hexByte(iei)}"
            else -> "reserved_0x${hexByte(iei)}"
        }

    /** Reserved IEIs: the same rule as `UdhParser.reservedIei` in `:pduparse` (AN-IE-RSV). */
    private fun reservedIei(iei: Int): Boolean =
        iei == 0x02 || iei == 0x03 || iei in 0x1B..0x1F || iei in 0x26..0x6F || iei in 0xA0..0xBF || iei >= 0xE0

    /** Number of fill bits between the UDH and the first 7-bit septet. */
    fun fillBits(udhOctets: Int): Int = (7 - ((udhOctets * 8) % 7)) % 7

    fun decode(reader: ByteReader): Field = decodeWithInfo(reader).first

    /**
     * Reads UDHL and the elements. An element that runs past the header is shown as raw hex with one error node
     * and the walk stops there, like `UdhParser`.
     */
    fun decodeWithInfo(reader: ByteReader): Pair<Field, UdhInfo> {
        val start = reader.absolutePos
        // No UDHL octet: the udh node gets no zero-filled value (VR01-D15).
        val udhl = reader.u8OrNull()
        val length = udhl ?: 0
        val totalOctets = 1 + length
        val body = reader.slice(length)
        val children = mutableListOf<Field>()
        val state = ElementState()
        while (body.hasMore) {
            val ieStart = body.absolutePos
            val iei = body.u8()
            val ieChildren = mutableListOf<Field>()
            ieChildren.add(field("name", ieName(iei), ieStart, 1))
            ieChildren.add(field("iei", hexByte(iei), ieStart, 1))
            if (reservedIei(iei)) ieChildren.add(field("reserved", true, ieStart, 1))
            if (!body.hasMore) {
                body.failAt(ieStart, "The information element 0x${hexByte(iei)} has no length octet.")
                children.add(group("information_element", ieStart, 1, ieChildren))
                break
            }
            val ieLength = body.u8()
            val dataStart = body.absolutePos
            ieChildren.add(field("length", ieLength, ieStart + 1, 1))
            if (ieLength > body.remaining) {
                val cut = body.takeRest()
                body.failAt(
                    dataStart,
                    "The information element 0x${hexByte(iei)} is longer than the header: " +
                        "$ieLength octets are declared and ${cut.size} are available.",
                )
                if (cut.isNotEmpty()) ieChildren.add(field("raw", hex(cut), dataStart, cut.size))
                children.add(group("information_element", ieStart, 2 + cut.size, ieChildren))
                break
            }
            val data = body.take(ieLength)
            state.read(iei, data, dataStart, ieChildren)
            children.add(group("information_element", ieStart, 2 + ieLength, ieChildren))
        }
        body.errorField()?.let { children.add(it) }
        val info =
            UdhInfo(
                totalOctets,
                state.concat,
                state.ports,
                state.locking,
                state.single,
                state.toolkitCommand,
                state.toolkitResponse,
            )
        return field("udh", udhl, start, reader.absolutePos - start, children) to info
    }

    /** Facts accumulated across the information elements of one UDH (3GPP TS 23.040). */
    private class ElementState {
        var concat: ConcatInfo? = null
        var ports: PortInfo? = null
        var locking: Int = 0
        var single: Int = 0
        var toolkitCommand: Boolean = false
        var toolkitResponse: Boolean = false

        fun read(
            iei: Int,
            data: ByteArray,
            dataStart: Int,
            children: MutableList<Field>,
        ) {
            when (iei) {
                0x00 -> {
                    concat8(data, dataStart, children)
                }

                0x08 -> {
                    concat16(data, dataStart, children)
                }

                0x04 -> {
                    port8(data, dataStart, children)
                }

                0x05 -> {
                    port16(data, dataStart, children)
                }

                0x01 -> {
                    specialSms(data, dataStart, children)
                }

                0x24 -> {
                    languageShift(data, dataStart, children)?.let { single = it }
                }

                0x25 -> {
                    languageShift(data, dataStart, children)?.let { locking = it }
                }

                0x70 -> {
                    toolkitCommand = true
                    toolkit(data, dataStart, children)
                }

                0x71 -> {
                    toolkitResponse = true
                    toolkit(data, dataStart, children)
                }

                0x07 -> {
                    source(data, dataStart, children)
                }

                else -> {
                    raw(data, dataStart, children)
                }
            }
        }

        private fun concat8(
            data: ByteArray,
            dataStart: Int,
            children: MutableList<Field>,
        ) {
            if (data.size != 3) {
                children.add(errorField(FieldNames.ERROR, dataStart, "The 8-bit concatenation element must have 3 octets."))
                raw(data, dataStart, children)
                return
            }
            val info = ConcatInfo(data[0].toInt() and 0xFF, data[1].toInt() and 0xFF, data[2].toInt() and 0xFF, false)
            children.add(field("reference", info.reference, dataStart, 1))
            children.add(field("total", info.total, dataStart + 1, 1))
            children.add(field("sequence", info.sequence, dataStart + 2, 1))
            accept(info, dataStart, children)
        }

        private fun concat16(
            data: ByteArray,
            dataStart: Int,
            children: MutableList<Field>,
        ) {
            if (data.size != 4) {
                children.add(errorField(FieldNames.ERROR, dataStart, "The 16-bit concatenation element must have 4 octets."))
                raw(data, dataStart, children)
                return
            }
            val reference = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            val info = ConcatInfo(reference, data[2].toInt() and 0xFF, data[3].toInt() and 0xFF, true)
            children.add(field("reference", info.reference, dataStart, 2))
            children.add(field("total", info.total, dataStart + 2, 1))
            children.add(field("sequence", info.sequence, dataStart + 3, 1))
            accept(info, dataStart, children)
        }

        /**
         * TS 23.040 9.2.3.24.1: Android ignores a concatenation element with total 0, sequence 0 or sequence above
         * total. Same rule as `UdhParser.validConcat` (AN-CONCAT-BAD).
         */
        private fun accept(
            info: ConcatInfo,
            dataStart: Int,
            children: MutableList<Field>,
        ) {
            if (info.total == 0 || info.sequence == 0 || info.sequence > info.total) {
                children.add(
                    errorField(FieldNames.ERROR, dataStart, "The concatenation values are not valid. Android ignores this element."),
                )
            } else {
                concat = info
            }
        }

        private fun port8(
            data: ByteArray,
            dataStart: Int,
            children: MutableList<Field>,
        ) {
            if (data.size != 2) {
                children.add(errorField(FieldNames.ERROR, dataStart, "The 8-bit port element must have 2 octets."))
                raw(data, dataStart, children)
                return
            }
            val info = PortInfo(data[0].toInt() and 0xFF, data[1].toInt() and 0xFF, false)
            ports = info
            children.add(field("destination_port", info.destination, dataStart, 1))
            children.add(field("originator_port", info.originator, dataStart + 1, 1))
        }

        private fun port16(
            data: ByteArray,
            dataStart: Int,
            children: MutableList<Field>,
        ) {
            if (data.size != 4) {
                children.add(errorField(FieldNames.ERROR, dataStart, "The 16-bit port element must have 4 octets."))
                raw(data, dataStart, children)
                return
            }
            val destination = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            val originator = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            val info = PortInfo(destination, originator, true)
            ports = info
            children.add(field("destination_port", info.destination, dataStart, 2))
            children.add(field("originator_port", info.originator, dataStart + 2, 2))
        }

        /**
         * TS 23.040 9.2.3.24.2. Octet 1: bit 7 store, bits 6-5 profile ID, bits 4-2 extended type, bits 1-0 basic
         * type. Octet 2: message count.
         */
        private fun specialSms(
            data: ByteArray,
            dataStart: Int,
            children: MutableList<Field>,
        ) {
            if (data.size != 2) {
                children.add(errorField(FieldNames.ERROR, dataStart, "The special SMS indication element must have 2 octets."))
                raw(data, dataStart, children)
                return
            }
            val octet = data[0].toInt() and 0xFF
            val basic =
                when (octet and 0x03) {
                    0 -> "voice"
                    1 -> "fax"
                    2 -> "email"
                    else -> "extended"
                }
            val extended =
                when ((octet shr 2) and 0x07) {
                    0 -> "none"
                    1 -> "video"
                    else -> "reserved"
                }
            children.add(field("store", (octet and 0x80) != 0, dataStart, 1))
            children.add(field("type", basic, dataStart, 1))
            children.add(field("extended_type", extended, dataStart, 1))
            children.add(field("profile", (octet shr 5) and 0x03, dataStart, 1))
            children.add(field("message_count", data[1].toInt() and 0xFF, dataStart + 1, 1))
        }

        /** TS 23.040 9.2.3.24.15/16: one octet, the national language identifier. */
        private fun languageShift(
            data: ByteArray,
            dataStart: Int,
            children: MutableList<Field>,
        ): Int? {
            if (data.size != 1) {
                children.add(errorField(FieldNames.ERROR, dataStart, "The national language element must have 1 octet."))
                raw(data, dataStart, children)
                return null
            }
            val code = data[0].toInt() and 0xFF
            children.add(field("language_code", code, dataStart, 1))
            children.add(field("language", Gsm7.languageName(Gsm7.languageIndex(code)), dataStart, 1))
            return code
        }

        /** TS 31.115 the command and response packet identifiers have no data. */
        private fun toolkit(
            data: ByteArray,
            dataStart: Int,
            children: MutableList<Field>,
        ) {
            if (data.isNotEmpty()) {
                children.add(errorField(FieldNames.ERROR, dataStart, "The toolkit packet identifier must have no data."))
                raw(data, dataStart, children)
            }
        }

        private fun source(
            data: ByteArray,
            dataStart: Int,
            children: MutableList<Field>,
        ) {
            if (data.isNotEmpty()) children.add(field("source", data[0].toInt() and 0xFF, dataStart, 1))
            if (data.size > 1) children.add(field("raw", hex(data, 1, data.size), dataStart + 1, data.size - 1))
        }

        private fun raw(
            data: ByteArray,
            dataStart: Int,
            children: MutableList<Field>,
        ) {
            if (data.isNotEmpty()) children.add(field("raw", hex(data), dataStart, data.size))
        }
    }
}
