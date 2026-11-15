package dev.nightjar.decoder

import dev.nightjar.contract.CbChannels
import dev.nightjar.pduparse.CbCategories

/** Cell broadcast bearer format. */
enum class CbFormat {
    GSM,
    UMTS,
}

/** GSM/UMTS cell broadcast decoder. */
object CellBroadcast {
    /** Segment octets of one UMTS CBS page, before its length octet (TS 23.041 9.4.2.2). */
    private const val UMTS_PAGE_OCTETS = 82

    /** Number-of-Pages is a 4-bit counter in UMTS CBS; anything larger is malformed. */
    private const val MAX_UMTS_PAGES = 15

    /** Content octets of one GSM CBS page (TS 23.041 9.4.1.2). */
    private const val GSM_PAGE_OCTETS = 82

    /** Android reads a GSM page of at most 56 octets with an ETWS identifier as a primary notification. */
    private const val MAX_ETWS_PRIMARY_OCTETS = 56

    /** TS 23.041 9.4.1.2.1: the only UMTS CBS message type. */
    private const val UMTS_CBS_MESSAGE = 0x01

    /** Offset of the additional-language copy of a CMAS identifier (TS 23.041 9.4.1.2.2). */
    private const val CMAS_LANGUAGE_OFFSET = 13

    /** TS 23.038 clause 5, group 0010: the GSM 7-bit languages (Android SmsCbHeader: cs, he, ar, ru, is). */
    private val LANGUAGES_GROUP_2 = arrayOf("Czech", "Hebrew", "Arabic", "Russian", "Icelandic")

    private val LANGUAGES =
        arrayOf(
            "German",
            "English",
            "Italian",
            "French",
            "Spanish",
            "Dutch",
            "Swedish",
            "Danish",
            "Portuguese",
            "Finnish",
            "Norwegian",
            "Greek",
            "Turkish",
            "Hungarian",
            "Polish",
            "unspecified",
        )

    private val MESSAGE_IDS: Map<Int, String> =
        buildMap {
            put(1000, "LCS E-OTD assistance data")
            put(1001, "LCS DGPS correction data")
            put(1002, "LCS GPS ephemeris and clock correction")
            put(1003, "LCS GPS almanac and other data")
            put(4352, "ETWS earthquake warning")
            put(4353, "ETWS tsunami warning")
            put(4354, "ETWS earthquake and tsunami warning")
            put(4355, "ETWS test message")
            put(4356, "ETWS other emergency")
            put(4370, "CMAS Presidential / EU-Alert Level 1")
            put(4371, "CMAS Extreme (observed)")
            put(4372, "CMAS Extreme (likely)")
            put(4373, "CMAS Severe (extreme/expected/observed)")
            put(4374, "CMAS Severe (extreme/expected/likely)")
            put(4375, "CMAS Severe (severe/immediate/observed)")
            put(4376, "CMAS Severe (severe/immediate/likely)")
            put(4377, "CMAS Severe (severe/expected/observed)")
            put(4378, "CMAS Severe (severe/expected/likely)")
            put(4379, "CMAS Child Abduction Emergency (AMBER)")
            put(4380, "CMAS Required Monthly Test")
            put(4381, "CMAS Exercise")
            put(4382, "CMAS operator defined")
            put(4396, "CMAS public safety")
            put(4397, "CMAS public safety (additional language)")
            put(4398, "CMAS state or local test")
            put(4399, "CMAS state or local test (additional language)")
            put(4400, "CMAS geo-fencing trigger")
        }

    fun decode(bytes: ByteArray, format: CbFormat = CbFormat.GSM): Field =
        guarded("cell_broadcast", bytes) {
            when {
                format == CbFormat.GSM && isEtwsPrimary(bytes) -> decodeEtwsPrimary(ByteReader(bytes))
                format == CbFormat.GSM -> decodeGsm(ByteReader(bytes))
                else -> decodeUmts(ByteReader(bytes))
            }
        }

    fun decode(hex: String, format: CbFormat = CbFormat.GSM): Field =
        guardedHex("cell_broadcast", hex) { decode(it, format) }

    /**
     * The rule of the Android SmsCbHeader for the GSM format: an ETWS identifier (`id & 0xFFF8 ==
     * 0x1100`) and at most 56 octets. Only the GSM format has the message identifier in octets 2-3.
     */
    private fun isEtwsPrimary(bytes: ByteArray): Boolean {
        if (bytes.size < 6 || bytes.size > MAX_ETWS_PRIMARY_OCTETS) return false
        val id = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        return (id and 0xFFF8) == CbChannels.ETWS_FIRST
    }

    private fun decodeGsm(reader: ByteReader): Field {
        val start = reader.absolutePos
        val children = mutableListOf<Field>()
        serialNumber(reader)?.let { children.add(it) }
        messageIdentifier(reader)?.let { children.add(it) }
        val dcsOffset = reader.absolutePos
        val dcs = reader.u8()
        if (reader.failed) return finish(reader, start, "GSM", children)
        val dcsInfo = cbDcs(dcs)
        children.add(cbDcsField(dcs, dcsInfo, dcsOffset))
        pageParameter(reader)?.let { children.add(it) }
        if (reader.failed) return finish(reader, start, "GSM", children)
        val contentOffset = reader.absolutePos
        val content = reader.take(GSM_PAGE_OCTETS)
        children.add(contentField(content, contentOffset, dcsInfo))
        return finish(reader, start, "GSM", children)
    }

    private fun finish(
        reader: ByteReader,
        start: Int,
        format: String,
        children: MutableList<Field>,
    ): Field {
        reader.errorField()?.let { children.add(it) }
        return field("cell_broadcast", format, start, reader.absolutePos - start, children)
    }

    /**
     * UMTS CBS message (TS 23.041 9.4.2.2): `type, id, serial, dcs, nPages, {82 octets, length}*`.
     * Every page is decoded and its length octet applied, so padding never reaches the content.
     */
    private fun decodeUmts(reader: ByteReader): Field {
        val start = reader.absolutePos
        val children = mutableListOf<Field>()
        val typeOffset = reader.absolutePos
        val messageType = reader.u8()
        if (reader.failed) return finish(reader, start, "UMTS", children)
        children.add(field("message_type", hexByte(messageType), typeOffset, 1))
        if (messageType != UMTS_CBS_MESSAGE) {
            children.add(errorField(FieldNames.ERROR, typeOffset, "The message type is not CBS message (1). Android does not accept it."))
        }
        messageIdentifier(reader)?.let { children.add(it) }
        serialNumber(reader)?.let { children.add(it) }
        val dcsOffset = reader.absolutePos
        val dcs = reader.u8()
        if (reader.failed) return finish(reader, start, "UMTS", children)
        val dcsInfo = cbDcs(dcs)
        children.add(cbDcsField(dcs, dcsInfo, dcsOffset))
        val pagesOffset = reader.absolutePos
        val pages = reader.u8()
        if (reader.failed) return finish(reader, start, "UMTS", children)
        children.add(field("number_of_pages", pages, pagesOffset, 1))
        if (pages !in 1..MAX_UMTS_PAGES) {
            // A local error node, not a reader failure: the reader must go on and decode the capped pages (VR01-D01).
            children.add(errorField(FieldNames.ERROR, pagesOffset, "The number of pages $pages is not in the range 1 to $MAX_UMTS_PAGES."))
        }
        val pageCount = minOf(pages, MAX_UMTS_PAGES)
        val dataOffset = reader.absolutePos
        val pageFields = mutableListOf<Field>()
        val payload = ByteArray(pageCount * UMTS_PAGE_OCTETS)
        var used = 0
        for (index in 1..pageCount) {
            if (reader.failed) break
            val pageOffset = reader.absolutePos
            val block = reader.take(UMTS_PAGE_OCTETS)
            if (reader.failed) break
            val lengthOffset = reader.absolutePos
            val declared = reader.u8()
            if (reader.failed) break
            val segment = block.copyOf(minOf(declared, block.size))
            segment.copyInto(payload, used)
            used += segment.size
            val pageChildren =
                mutableListOf(
                    field("index", index, pageOffset, 1),
                    field("page_length", declared, lengthOffset, 1),
                    field("data", hex(segment), pageOffset, segment.size),
                )
            if (declared > UMTS_PAGE_OCTETS) {
                // The length octet covers at most the 82 octets of the page.
                pageChildren.add(errorField(FieldNames.ERROR, lengthOffset, "The page length $declared is more than $UMTS_PAGE_OCTETS."))
            }
            pageFields.add(group("page", pageOffset, reader.absolutePos - pageOffset, pageChildren))
        }
        children.add(group("pages", dataOffset, reader.absolutePos - dataOffset, pageFields))
        children.add(contentField(payload.copyOf(used), dataOffset, dcsInfo))
        return finish(reader, start, "UMTS", children)
    }

    private fun decodeEtwsPrimary(reader: ByteReader): Field {
        val start = reader.absolutePos
        val children = mutableListOf<Field>()
        serialNumber(reader)?.let { children.add(it) }
        messageIdentifier(reader)?.let { children.add(it) }
        val warningOffset = reader.absolutePos
        val warning = reader.take(2)
        if (reader.failed) return finish(reader, start, "ETWS-primary", children)
        children.add(warningType(warning, warningOffset))
        // TS 23.041 9.4.1.3.5: the warning security information is optional (Android: octets after 6 only if present).
        if (reader.hasMore) {
            val securityOffset = reader.absolutePos
            val security = reader.takeRest()
            val securityChildren = mutableListOf<Field>()
            if (security.size >= 7) {
                val stamp = Timestamp.fields(security.copyOfRange(0, 7), securityOffset)
                securityChildren.add(field("timestamp", Timestamp.iso(stamp), securityOffset, 7, stamp))
            }
            if (security.size > 7) {
                securityChildren.add(field("digital_signature", hex(security, 7, security.size), securityOffset + 7, security.size - 7))
            }
            children.add(field("warning_security_information", hex(security), securityOffset, security.size, securityChildren))
        }
        return finish(reader, start, "ETWS-primary", children)
    }

    /** The serial number, or null when the 2 octets are not all in the data (no zero-filled value, VR01-D15). */
    private fun serialNumber(reader: ByteReader): Field? {
        val offset = reader.absolutePos
        val value = reader.u16OrNull() ?: return null
        val gs = (value shr 14) and 0x03
        val messageCode = (value shr 4) and 0x03FF
        val update = value and 0x0F
        return field(
            "serial_number",
            "0x" + hexDigits(value.toLong(), 4),
            offset,
            2,
            listOf(
                field("geographical_scope", gs, offset, 2),
                field("message_code", messageCode, offset, 2),
                field("update_number", update, offset + 1, 1),
            ),
        )
    }

    /** The message identifier, or null when the 2 octets are not all in the data (VR01-D15). */
    private fun messageIdentifier(reader: ByteReader): Field? {
        val offset = reader.absolutePos
        val value = reader.u16OrNull() ?: return null
        val children =
            mutableListOf(
                field("hex", "0x" + hexDigits(value.toLong(), 4), offset, 2),
                field("channel", messageMeaning(value), offset, 2),
                // One emergency rule for the whole app: the contract CbChannels.
                field("emergency", CbChannels.isEmergency(value), offset, 2),
            )
        // The class subtype of pduparse CbCategories, the same table as the payload and the app.
        CbCategories.subtypeOf(value)?.let {
            children.add(field("subtype", it, offset, 2)) // contract-ok: decoder field label
        }
        return field("message_identifier", value, offset, 2, children)
    }

    /** TS 23.041 9.4.1.2.2. */
    private fun messageMeaning(value: Int): String =
        MESSAGE_IDS[value]
            ?: when (value) {
                CbCategories.AREA_INFO_CHANNEL -> "area information (operator allocated)"
                in 0..999 -> "operator allocated"
                in 1004..4095 -> "future standardization"
                in 4096..4223 -> "cell broadcast data download (clear)"
                in 4224..4351 -> "cell broadcast data download (secured)"
                in 4357..4359 -> "ETWS future extension"
                in 4360..4369 -> "future standardization"
                in 4383..4395 -> (MESSAGE_IDS[value - CMAS_LANGUAGE_OFFSET] ?: "CMAS") + " (additional language)"
                in 0xA000..0xAFFF -> "reserved for HPLMN"
                else -> "operator specific"
            }

    /**
     * TS 23.041 9.4.1.2.4: page (high nibble) of total (low nibble). Page 0, total 0 or page above total is read as
     * 1 of 1, like the Android SmsCbHeader. The raw nibbles stay in a child node.
     */
    private fun pageParameter(reader: ByteReader): Field? {
        val offset = reader.absolutePos
        val value = reader.u8OrNull() ?: return null
        val rawTotal = value and 0x0F
        val rawPage = (value shr 4) and 0x0F
        val invalid = rawPage == 0 || rawTotal == 0 || rawPage > rawTotal
        val page = if (invalid) 1 else rawPage
        val total = if (invalid) 1 else rawTotal
        val children =
            mutableListOf(
                field("page", page, offset, 1),
                field("total", total, offset, 1),
                field("page_n_of_m", "$page of $total", offset, 1),
            )
        if (invalid) {
            children.add(field("raw", "$rawPage of $rawTotal", offset, 1))
            children.add(field("note", "The page parameter is not valid. Android reads it as page 1 of 1.", offset, 1))
        }
        return field("page_parameter", "$page of $total", offset, 1, children)
    }

    private fun contentField(
        content: ByteArray,
        offset: Int,
        dcs: CbDcsInfo,
    ): Field {
        val children = mutableListOf<Field>()
        var body = content
        var bodyOffset = offset
        var fill = 0
        var udh: UdhInfo? = null
        if (dcs.hasUdh && content.isNotEmpty()) {
            // TS 23.038 clause 5, group 1001: the content starts with a user data header.
            val reader = ByteReader(content, offset)
            val (udhField, info) = Udh.decodeWithInfo(reader)
            children.add(udhField)
            udh = info
            val headerOctets = info.totalOctets.coerceAtMost(content.size)
            body = content.copyOfRange(headerOctets, content.size)
            bodyOffset = offset + headerOctets
            fill = Udh.fillBits(info.totalOctets)
        }
        when (dcs.alphabet) {
            Alphabet.GSM7 -> {
                val septets = Gsm7.maxSeptets(body.size, fill)
                val locking = udh?.lockingShift?.let { Gsm7.languageIndex(it).coerceAtLeast(0) } ?: 0
                val single = udh?.singleShift?.let { Gsm7.languageIndex(it) } ?: -1
                var text = Gsm7.decode(body, septets, fill, locking, single)
                if (dcs.languagePrefix) {
                    // TS 23.038 clause 5: 2 characters and a CR, so 3 septets in 3 octets.
                    val prefix = text.take(3)
                    children.add(field("language_indicator", prefix.replace("\r", ""), bodyOffset, minOf(3, body.size)))
                    text = text.drop(3)
                }
                children.add(field("text", text.trimEnd('\r'), bodyOffset, body.size))
            }

            Alphabet.UCS2 -> {
                val start = if (dcs.languagePrefix && body.size > 2) 2 else 0
                if (start == 2) {
                    children.add(field("language_indicator", Gsm7.decode(body, 2), bodyOffset, 2))
                }
                val text = Gsm7.decodeUcs2(body.copyOfRange(start, body.size)).trimEnd('\u0000', '\r', '\uFFFD')
                children.add(field("text", text, bodyOffset + start, body.size - start))
                children.add(field("hex", hex(body, start, body.size), bodyOffset + start, body.size - start))
            }

            Alphabet.EIGHT_BIT, Alphabet.RESERVED -> {
                children.add(field("hex", hex(body), bodyOffset, body.size))
            }
        }
        return field("content", null, offset, content.size, children)
    }

    private fun warningType(bytes: ByteArray, offset: Int): Field {
        if (bytes.size < 2) return errorField("warning_type", offset, "The warning type must have 2 octets.")
        // TS 23.041 9.4.1.3.4: b15..b9 warning type value, b8 emergency user alert, b7 popup.
        val value = (bytes[0].toInt() and 0xFE) shr 1
        val emergency = (bytes[0].toInt() and 0x01) != 0
        val popup = (bytes[1].toInt() and 0x80) != 0
        val name =
            when (value) {
                0 -> "earthquake"
                1 -> "tsunami"
                2 -> "earthquake-and-tsunami"
                3 -> "test"
                4 -> "other"
                else -> "reserved($value)"
            }
        return field(
            "warning_type",
            name,
            offset,
            2,
            listOf(
                field("value", value, offset, 1),
                field("emergency_user_alert", emergency, offset, 1),
                field("popup", popup, offset + 1, 1),
            ),
        )
    }

    private data class CbDcsInfo(
        val alphabet: Alphabet,
        val language: String?,
        val languagePrefix: Boolean,
        val messageClass: Int?,
        val compressed: Boolean,
        val hasUdh: Boolean,
        val reserved: Boolean = false,
        val note: String? = null,
    )

    /**
     * CBS data coding scheme (TS 23.038 clause 5), checked against the Android SmsCbHeader.DataCodingScheme
     * . A reserved coding is read as GSM 7-bit, as TS 23.038 asks.
     */
    private fun cbDcs(raw: Int): CbDcsInfo {
        val octet = raw and 0xFF
        val low = octet and 0x0F
        val gsm7 = CbDcsInfo(Alphabet.GSM7, null, languagePrefix = false, messageClass = null, compressed = false, hasUdh = false)
        return when (octet shr 4) {
            0x0 -> {
                gsm7.copy(language = LANGUAGES[low])
            }

            0x1 -> {
                when (low) {
                    0x0 -> gsm7.copy(languagePrefix = true)
                    0x1 -> gsm7.copy(alphabet = Alphabet.UCS2, languagePrefix = true)
                    else -> gsm7.copy(reserved = true)
                }
            }

            0x2 -> {
                if (low < LANGUAGES_GROUP_2.size) gsm7.copy(language = LANGUAGES_GROUP_2[low]) else gsm7.copy(reserved = true)
            }

            0x3 -> {
                gsm7.copy(reserved = true)
            }

            0x4, 0x5, 0x6, 0x7 -> {
                // General data coding: bit 5 compressed, bit 4 class meaning, bits 3-2 alphabet.
                val messageClass = if ((octet and 0x10) != 0) octet and 0x03 else null
                CbDcsInfo(
                    alphabet((octet shr 2) and 0x03),
                    null,
                    languagePrefix = false,
                    messageClass = messageClass,
                    compressed = (octet and 0x20) != 0,
                    hasUdh = false,
                    reserved = ((octet shr 2) and 0x03) == 3,
                )
            }

            0x9 -> {
                CbDcsInfo(
                    alphabet((octet shr 2) and 0x03),
                    null,
                    languagePrefix = false,
                    messageClass = octet and 0x03,
                    compressed = false,
                    hasUdh = true,
                    reserved = ((octet shr 2) and 0x03) == 3,
                )
            }

            0xE -> {
                gsm7.copy(alphabet = Alphabet.EIGHT_BIT, note = "The coding is defined by the WAP Forum.")
            }

            0xF -> {
                CbDcsInfo(
                    if ((octet and 0x04) != 0) Alphabet.EIGHT_BIT else Alphabet.GSM7,
                    null,
                    languagePrefix = false,
                    // Class 00 is "no message class" in the CBS coding (unlike TP-DCS).
                    messageClass = (octet and 0x03).takeIf { it != 0 },
                    compressed = false,
                    hasUdh = false,
                    reserved = (octet and 0x08) != 0,
                )
            }

            else -> {
                gsm7.copy(reserved = true)
            }
        }
    }

    private fun cbDcsField(raw: Int, info: CbDcsInfo, offset: Int): Field {
        val children =
            mutableListOf(
                field("alphabet", info.alphabet.label, offset, 1),
                field("compressed", info.compressed, offset, 1),
                field("has_udh", info.hasUdh, offset, 1),
            )
        info.language?.let { children.add(field("language", it, offset, 1)) }
        info.messageClass?.let { children.add(field("message_class", it, offset, 1)) }
        if (info.reserved) children.add(field("reserved", true, offset, 1))
        info.note?.let { children.add(field("note", it, offset, 1)) }
        return field("dcs", hexByte(raw), offset, 1, children)
    }

    private fun alphabet(bits: Int): Alphabet =
        when (bits) {
            0 -> Alphabet.GSM7
            1 -> Alphabet.EIGHT_BIT
            2 -> Alphabet.UCS2
            else -> Alphabet.RESERVED
        }
}
