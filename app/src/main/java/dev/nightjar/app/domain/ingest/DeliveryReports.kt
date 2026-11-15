package dev.nightjar.app.domain.ingest

import dagger.Reusable
import dev.nightjar.app.data.db.MessageDao
import dev.nightjar.app.data.db.MessageEntity
import dev.nightjar.app.data.db.PduDao
import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Transport
import dev.nightjar.pduparse.TpduParser
import javax.inject.Inject

/** Result of a delivery report, decoded from TP-ST (TS 23.040 section 9.2.3.15). */
enum class DeliveryStatus(
    val wire: String,
) {
    DELIVERED("DELIVERED"),
    PENDING("PENDING"),
    FAILED("FAILED"),
    ;

    companion object {
        /**
         * 0x00 to 0x1F: the transfer is complete. 0x20 to 0x3F: a temporary error, the centre still tries. 0x40 to 0x7F:
         * an error, the centre does not try again. A reserved value (0x80 and above) has no result.
         */
        fun fromTpSt(status: Int): DeliveryStatus? =
            when (status) {
                in 0x00..0x1F -> DELIVERED
                in 0x20..0x3F -> PENDING
                in 0x40..0x7F -> FAILED
                else -> null
            }

        fun from(wire: String?): DeliveryStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The fields of an SMS-STATUS-REPORT that delivery-report matching needs. [recipient] holds TP-RA, or null when absent
 * or alphanumeric.
 */
data class StatusReportFacts(
    val messageReference: Int,
    val recipient: String?,
    val status: Int,
) {
    val deliveryStatus: DeliveryStatus? get() = DeliveryStatus.fromTpSt(status)

    companion object {
        /** TP-RA starts after the first octet and TP-MR: its length octet, its TOA, then the digits. */
        private const val RA_LENGTH_OFFSET = 2
        private const val RA_TOA_OFFSET = 3
        private const val RA_DIGITS_OFFSET = 4
        private const val TON_ALPHANUMERIC = 5

        /**
         * Reads TP-MR, TP-RA and TP-ST with the shared [TpduParser] (: one parser). [withSmsc] is true when [bytes]
         * start with the SMSC address (a radio PDU, CS) and false for a bare TPDU (IMS). Returns null when the bytes are
         * not a complete status report. Never throws.
         */
        fun read(
            bytes: ByteArray,
            withSmsc: Boolean,
        ): StatusReportFacts? {
            val facts = if (withSmsc) TpduParser.parseRadio(bytes) else TpduParser.parse(bytes)
            if (!facts.statusReport() || facts.messageReference() < 0 || facts.status() < 0) return null
            return StatusReportFacts(facts.messageReference(), recipient(facts.tpdu()), facts.status())
        }

        /** The TPDU parser does not keep TP-RA of a report, so it is decoded here with the parser's address decoder. */
        private fun recipient(tpdu: ByteArray): String? {
            if (tpdu.size <= RA_TOA_OFFSET) return null
            val length = tpdu[RA_LENGTH_OFFSET].toInt() and 0xFF
            val toa = tpdu[RA_TOA_OFFSET].toInt() and 0xFF
            if (((toa shr 4) and 0x07) == TON_ALPHANUMERIC) return null
            return TpduParser.decodeAddress(tpdu, RA_DIGITS_OFFSET, length, toa).takeIf { it.isNotEmpty() }
        }
    }
}

/**
 * Links a delivery report to the sent message it answers: same TP-MR, same destination, and a send time in the
 * 7 days before the report. When nothing matches, the report keeps `relatedMessageId = null` and still shows its status.
 */
@Reusable
class DeliveryReportLinker
    @Inject
    constructor(
        private val messages: MessageDao,
        private val pdus: PduDao,
    ) {
        /** Decodes the report [row] and stores the facts and the link (the sent message id, or null when none matches). */
        suspend fun link(row: MessageEntity) {
            // An IMS report is a bare TPDU; a CS report is the radio PDU with the SMSC address in front.
            val withSmsc = row.transport != Transport.IMS.wire()
            val facts = pdus.forMessage(row.id).firstNotNullOfOrNull { StatusReportFacts.read(it.bytes, withSmsc) } ?: return
            // The same normalization as the stored originatorNorm and destinationNorm.
            val recipient = KnownOriginatorMatcher.normalize(facts.recipient)
            val sent =
                messages
                    .sentWithReference(
                        Direction.OUT.wire(),
                        facts.messageReference,
                        row.receivedAtWall - WINDOW_MS,
                        row.receivedAtWall,
                    ).firstOrNull { sameNumber(it.destinationNorm, recipient) }
            messages.setReportFacts(
                id = row.id,
                relatedMessageId = sent?.id,
                tpMr = facts.messageReference,
                deliveryStatus = facts.deliveryStatus?.wire,
                destinationNorm = recipient,
            )
        }

        companion object {
            /** A report counts only for a message sent in the 7 days before it. */
            const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000

            /** Fewest digits that can match as the end of a longer number (national against international form). */
            private const val MIN_TAIL_DIGITS = 7

            /**
             * Two numbers are the same when they are equal after normalisation, or one is the tail of the other (a
             * national number against the same number with the country code). A number that is not known never matches.
             */
            fun sameNumber(
                a: String?,
                b: String?,
            ): Boolean {
                val x = a?.filter(Char::isDigit).orEmpty()
                val y = b?.filter(Char::isDigit).orEmpty()
                if (x.isEmpty() || y.isEmpty()) return false
                if (x == y) return true
                val shorter = if (x.length <= y.length) x else y
                val longer = if (x.length <= y.length) y else x
                return shorter.length >= MIN_TAIL_DIGITS && longer.endsWith(shorter)
            }
        }
    }
