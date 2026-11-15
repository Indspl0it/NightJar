package dev.nightjar.app.domain.cb

import dev.nightjar.contract.CbChannels
import dev.nightjar.decoder.Decoders
import dev.nightjar.decoder.Field

/**
 * One received CB page, read from the stored PDU through the shared `:decoder`.
 *
 * The payload pushes one event per page and never joins them (HK-17 is a per-message hook); reassembly is an app
 * concern and uses the same decoder the Raw tab shows, so what the user reads and what the pipeline grouped
 * cannot diverge.
 *
 * @param serial raw `serial_number` octets: geographic scope (bits 15-14), message code (bits 13-4), update number (3-0)
 * @param page 1-based page index, 1 when the PDU has no page parameter (ETWS primary notification)
 * @param pages total page count, 1 when the PDU has no page parameter
 */
data class CbPage(
    val serial: Int,
    val messageId: Int,
    val page: Int,
    val pages: Int,
    val emergency: Boolean,
    /** Decoded body of this page, or the upper-case hex when the DCS is 8-bit. */
    val text: String,
    val format: String,
) {
    /** 3GPP TS 23.041 §9.4.1.2.2 serial-number sub-field: the update counter, bits 3-0. */
    val updateNumber: Int get() = serial and 0x0F

    /** Serial-number message code, bits 13-4. */
    val messageCode: Int get() = (serial shr 4) and 0x3FF

    /** Serial-number geographical scope, bits 15-14. */
    val geographicalScope: Int get() = (serial shr 14) and 0x03

    /**
     * Identity of the logical broadcast (de-duplication key, without the subscription): the same
     * (serial, message identifier, update number) is one message even when it arrives on two pages.
     */
    val messageKey: CbMessageKey get() = CbMessageKey(serial, messageId, updateNumber)

    companion object {
        /** An undecodable or absent PDU: callers keep the row but cannot group it. */
        val UNKNOWN =
            CbPage(serial = -1, messageId = -1, page = 1, pages = 1, emergency = false, text = "", format = Decoders.CELL_BROADCAST)

        /**
         * Decodes one stored CB PDU. [format] is the stored `pdu.format` (`cb` for GSM, `cb_umts` for UMTS). A PDU
         * with the UMTS layout is read as UMTS also when the format is missing or wrong, because a GSM page
         * cannot have that size. Anything else is read as GSM, which is what the radio hands HK-17 on both reference
         * builds. A PDU shorter than the 6-octet header is [UNKNOWN] (VR01-DOM09).
         */
        fun of(
            bytes: ByteArray,
            format: String?,
        ): CbPage {
            // The GSM page, the ETWS primary notification and the UMTS message all start with 6 header octets.
            if (bytes.size < HEADER_OCTETS) return UNKNOWN
            val umts = format.equals(Decoders.CELL_BROADCAST_UMTS, ignoreCase = true) || hasUmtsLayout(bytes)
            val kind = if (umts) Decoders.CELL_BROADCAST_UMTS else Decoders.CELL_BROADCAST
            val tree = runCatching { Decoders.decodeBytes(kind, bytes) }.getOrNull() ?: return UNKNOWN
            return of(tree, kind)
        }

        /**
         * Reads the header fields out of a decoder tree (kept separate so fixtures can drive it directly).
         *
         * A UMTS CBS message (TS 23.041) holds all its pages in one PDU, and the decoder joins them. It is
         * then page 1 of 1, so the assembler does not wait for pages that never arrive.
         *
         * A tree without a `dcs` or `warning_type` node is [UNKNOWN]: the header was cut off, and the decoder can fill
         * the missing octets of the message identifier and the serial number with zeros (VR01-DOM09).
         */
        fun of(
            tree: Field,
            format: String = Decoders.CELL_BROADCAST,
        ): CbPage {
            if (tree.child("dcs") == null && tree.child("warning_type") == null) return UNKNOWN
            val messageId = intOf(tree.childValue("message_identifier")) ?: return UNKNOWN
            val serial = intOf(tree.childValue("serial_number")) ?: serialFromParts(tree)
            if (serial == null) return UNKNOWN
            val whole = format.equals(Decoders.CELL_BROADCAST_UMTS, ignoreCase = true)
            val page = if (whole) 1 else intOf(tree.childValue("page_parameter/page")) ?: 1
            val pages = if (whole) 1 else intOf(tree.childValue("page_parameter/total")) ?: 1
            val text = tree.textValue("content/text") ?: tree.textValue("content/hex") ?: ""
            return CbPage(
                serial = serial,
                messageId = messageId,
                page = page.coerceAtLeast(1),
                pages = pages.coerceAtLeast(1),
                emergency = tree.childValue("message_identifier/emergency") == true || CbChannels.isEmergency(messageId),
                text = text,
                format = format,
            )
        }

        /**
         * TS 23.041 a UMTS CBS message starts with the message type 1 and has at least one 83-octet page
         * after its 6 header octets. A GSM page has at most [GSM_PAGE_OCTETS] octets.
         */
        private fun hasUmtsLayout(bytes: ByteArray): Boolean = bytes.size > GSM_PAGE_OCTETS && bytes[0].toInt() == UMTS_CBS_MESSAGE

        /** The decoder renders `serial_number` as `0x1234`; the sub-fields survive a formatting change. */
        private fun serialFromParts(tree: Field): Int? {
            val code = intOf(tree.childValue("serial_number/message_code")) ?: return null
            val update = intOf(tree.childValue("serial_number/update_number")) ?: 0
            val scope = intOf(tree.childValue("serial_number/geographical_scope")) ?: 0
            return (scope shl 14) or (code shl 4) or update
        }

        /** TS 23.041 the fixed header of every CB PDU layout. */
        private const val HEADER_OCTETS = 6

        /** Octets of one GSM CBS page: 6 header octets and 82 content octets. */
        private const val GSM_PAGE_OCTETS = 88

        /** TS 23.041 the only UMTS CBS message type. */
        private const val UMTS_CBS_MESSAGE = 0x01

        private fun intOf(value: Any?): Int? =
            when (value) {
                is Int -> value
                is Long -> value.toInt()
                is String -> value.removePrefix("0x").removePrefix("0X").toIntOrNull(16) ?: value.toIntOrNull()
                else -> null
            }
    }
}

/** De-duplication key: (serial, message identifier, update number), plus the subscription at the call site. */
data class CbMessageKey(
    val serial: Int,
    val messageId: Int,
    val updateNumber: Int,
)
