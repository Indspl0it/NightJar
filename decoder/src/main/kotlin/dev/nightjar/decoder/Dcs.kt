package dev.nightjar.decoder

/** Character set indicated by TP-DCS (TS 23.038 clause 4). */
enum class Alphabet(val label: String) {
    GSM7("GSM 7-bit"),
    EIGHT_BIT("8-bit"),
    UCS2("UCS-2"),
    RESERVED("reserved"),
}

/** Message Waiting Indication group details. */
data class Mwi(
    val discard: Boolean,
    val active: Boolean,
    val type: String,
)

/** Decoded TP-DCS. */
data class DcsInfo(
    val raw: Int,
    val group: String,
    val alphabet: Alphabet,
    val compressed: Boolean,
    val messageClass: Int?,
    val autoDelete: Boolean,
    val mwi: Mwi?,
    val reserved: Boolean,
)

/** TP-DCS decoder. */
object Dcs {
    fun decode(raw: Int): DcsInfo {
        val octet = raw and 0xFF
        return when (octet shr 4) {
            0x0, 0x1, 0x2, 0x3 -> general(octet, "general", autoDelete = false)
            0x4, 0x5, 0x6, 0x7 -> general(octet, "automatic-deletion", autoDelete = true)
            0x8, 0x9, 0xA, 0xB -> reserved(octet)
            0xC -> mwiGroup(octet, discard = true, alphabet = Alphabet.GSM7)
            0xD -> mwiGroup(octet, discard = false, alphabet = Alphabet.GSM7)
            0xE -> mwiGroup(octet, discard = false, alphabet = Alphabet.UCS2)
            else -> dataCodingClass(octet)
        }
    }

    private fun general(octet: Int, group: String, autoDelete: Boolean): DcsInfo {
        val alphabet = alphabetFor((octet shr 2) and 0x03)
        val compressed = (octet and 0x20) != 0
        val messageClass = if ((octet and 0x10) != 0) octet and 0x03 else null
        // TS 23.038 clause 4: alphabet 11 is reserved (pduparse flags AN-DCS-RSV for it too).
        return DcsInfo(octet, group, alphabet, compressed, messageClass, autoDelete, null, reserved = alphabet == Alphabet.RESERVED)
    }

    private fun reserved(octet: Int): DcsInfo =
        DcsInfo(
            raw = octet,
            group = "reserved",
            alphabet = Alphabet.GSM7,
            compressed = false,
            messageClass = null,
            autoDelete = false,
            mwi = null,
            reserved = true,
        )

    private fun mwiGroup(octet: Int, discard: Boolean, alphabet: Alphabet): DcsInfo {
        val type =
            when (octet and 0x03) {
                0 -> "voicemail"
                1 -> "fax"
                2 -> "email"
                else -> "other"
            }
        val mwi = Mwi(discard = discard, active = (octet and 0x08) != 0, type = type)
        return DcsInfo(octet, if (discard) "mwi-discard" else "mwi-store", alphabet, false, null, false, mwi, false)
    }

    private fun dataCodingClass(octet: Int): DcsInfo {
        val alphabet = if ((octet and 0x04) != 0) Alphabet.EIGHT_BIT else Alphabet.GSM7
        return DcsInfo(
            raw = octet,
            group = "data-coding-class",
            alphabet = alphabet,
            compressed = false,
            messageClass = octet and 0x03,
            autoDelete = false,
            mwi = null,
            // TS 23.038 clause 4: bit 3 of group 1111 is reserved (pduparse flags AN-DCS-RSV).
            reserved = (octet and 0x08) != 0,
        )
    }

    private fun alphabetFor(bits: Int): Alphabet =
        when (bits) {
            0 -> Alphabet.GSM7
            1 -> Alphabet.EIGHT_BIT
            2 -> Alphabet.UCS2
            else -> Alphabet.RESERVED
        }

    fun field(raw: Int, offset: Int): Field {
        val info = decode(raw)
        val children =
            buildList {
                add(field("group", info.group, offset, 1))
                add(field("alphabet", info.alphabet.label, offset, 1))
                add(field("compressed", info.compressed, offset, 1))
                info.messageClass?.let { add(field("message_class", it, offset, 1)) }
                add(field("auto_delete", info.autoDelete, offset, 1))
                if (info.reserved) add(field("reserved", true, offset, 1))
                info.mwi?.let { mwi ->
                    add(
                        group(
                            "mwi",
                            offset,
                            1,
                            listOf(
                                field("discard", mwi.discard, offset, 1),
                                field("active", mwi.active, offset, 1),
                                field("type", mwi.type, offset, 1),
                            ),
                        ),
                    )
                }
            }
        return field("dcs", hexByte(raw), offset, 1, children)
    }
}
