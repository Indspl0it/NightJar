package dev.nightjar.decoder

import java.nio.charset.StandardCharsets

/**
 * SUPL INIT (OMA ULP) decoder, ITU-T X.691 unaligned PER.
 *
 * The bit layout up to NotificationType is the same as in the payload
 * `WapContentType.suplNotificationType`, and the test vectors are the same.
 * The rest of Notification and of SUPLINIT (sLPAddress, qoP, sLPMode, mAC, keyIdentity) follows the OMA-TS-ULP
 * ASN.1 with the same X.691 rules:
 * - a SEQUENCE or CHOICE or ENUMERATED with `...` starts with one extension bit;
 * - a SEQUENCE then has one presence bit per OPTIONAL component, before the components;
 * - SIZE(lb..ub) gives `length - lb` in the bits that `ub - lb` needs;
 * - IA5String has 7 bits per character, the FQDN (64-character alphabet `-.0-9A-Za-z`) 6 bits per character.
 */
object Supl {
    /** PosMethod: 10 root values, then the SUPL 2.0 extension values (index after the extension bit). */
    private val POS_METHODS =
        listOf(
            "agpsSETassisted",
            "agpsSETbased",
            "agpsSETassistedpref",
            "agpsSETbasedpref",
            "autonomousGPS",
            "aFLT",
            "eCID",
            "eOTD",
            "oTDOA",
            "noPosition",
        )

    private val POS_METHOD_EXTENSIONS =
        listOf(
            "ver2-historicalDataRetrieval",
            "ver2-agnssSETassisted",
            "ver2-agnssSETbased",
            "ver2-agnssSETassistedpref",
            "ver2-agnssSETbasedpref",
            "ver2-autonomousGNSS",
            "ver2-sessioninfoquery",
        )

    private val NOTIFICATION_TYPES =
        listOf(
            "noNotificationNoVerification",
            "notificationOnly",
            "notificationAndVerficationAllowedNA",
            "notificationAndVerficationDeniedNA",
            "privacyOverride",
        )

    private val ENCODING_TYPES = listOf("ucs2", "gsmDefault", "utf8")

    private val FORMAT_INDICATORS =
        listOf("logicalName", "e-mailAddress", "msisdn", "url", "sipUrl", "min", "mdn", "iMSPublicidentity")

    private val MESSAGES =
        listOf(
            "msSUPLINIT",
            "msSUPLSTART",
            "msSUPLRESPONSE",
            "msSUPLPOSINIT",
            "msSUPLPOS",
            "msSUPLEND",
            "msSUPLAUTHREQ",
            "msSUPLAUTHRESP",
        )

    private val SET_IDS = listOf("msisdn", "mdn", "min", "imsi", "nai", "iPAddress")

    /** X.691 30.5.4: the FQDN alphabet in canonical order, so the index is the 6-bit value. */
    private const val FQDN_ALPHABET = "-.0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    fun decode(bytes: ByteArray): Field = guarded("supl_init", bytes) { build(bytes, 0) }

    fun decode(hex: String): Field = guardedHex("supl_init", hex) { decode(it) }

    /** A ULP PDU at absolute [baseOffset], for a tree nested in a TPDU or a push. */
    internal fun decodeAt(
        bytes: ByteArray,
        baseOffset: Int,
    ): Field = guarded("supl_init", bytes) { build(bytes, baseOffset) }

    private fun build(
        bytes: ByteArray,
        baseOffset: Int,
    ): Field {
        val reader = BitReader(bytes, baseOffset)
        val children = mutableListOf<Field>()
        val length = reader.bits(16).toInt()
        if (!reader.truncated) children.add(reader.field("length", length, 0))
        if (!reader.truncated) version(reader).let { if (!reader.truncated) children.add(it) }
        if (!reader.truncated) children.add(sessionId(reader))
        if (!reader.truncated) {
            val messageStart = reader.bitPos
            val extension = reader.bit() == 1
            val index = if (extension) smallNumber(reader) else reader.bits(3).toInt()
            val name = if (extension) "extension($index)" else MESSAGES[index]
            // No node after the end of the data: the root gets the one error node.
            if (!reader.truncated) {
                children.add(reader.field("message", name, messageStart))
                if (extension || index != 0) {
                    children.add(reader.field("note", "The decoder decodes only SUPL INIT.", messageStart))
                } else {
                    children.add(suplInit(reader))
                }
            }
        }
        if (reader.truncated) children.add(reader.truncatedError())
        return field("supl_init", null, baseOffset, bytes.size, children)
    }

    private fun version(reader: BitReader): Field {
        val start = reader.bitPos
        val major = reader.bits(8)
        val minor = reader.bits(8)
        val service = reader.bits(8)
        return reader.field("version", "$major.$minor.$service", start)
    }

    /** SessionID (no extension marker): presence of setSessionID and slpSessionID, then each part. */
    private fun sessionId(reader: BitReader): Field {
        val start = reader.bitPos
        val setPresent = reader.bit() == 1
        val slpPresent = reader.bit() == 1
        val children = mutableListOf<Field>()
        if (setPresent && !reader.truncated) {
            val setStart = reader.bitPos
            val sessionStart = reader.bitPos
            val session = reader.bits(16)
            // A field cut by the end of the data gets no node (VR01-D15).
            val setChildren = mutableListOf<Field>()
            if (!reader.truncated) setChildren.add(reader.field("session_id", session, sessionStart))
            if (!reader.truncated) setChildren.add(setId(reader))
            children.add(reader.group("set_session_id", setStart, setChildren))
        }
        if (slpPresent && !reader.truncated) {
            val slpStart = reader.bitPos
            val sessionStart = reader.bitPos
            val session = reader.octets(4)
            val slpChildren = mutableListOf<Field>()
            if (!reader.truncated) slpChildren.add(reader.field("session_id", hex(session), sessionStart))
            if (!reader.truncated) slpChildren.add(slpAddress(reader, "slp_id"))
            children.add(reader.group("slp_session_id", slpStart, slpChildren))
        }
        return reader.group("session_id", start, children)
    }

    /** SETId CHOICE with `...` (6 root alternatives). */
    private fun setId(reader: BitReader): Field {
        val start = reader.bitPos
        if (reader.bit() == 1) {
            val index = smallNumber(reader)
            skipOpenType(reader)
            return reader.field("set_id", if (reader.truncated) null else "extension($index)", start)
        }
        val index = reader.bits(3).toInt()
        val typeCut = reader.truncated
        val typeName = SET_IDS.getOrNull(index) ?: "invalid($index)"
        val valueStart = reader.bitPos
        val value: Any? =
            when (index) {
                0, 1, 3 -> {
                    hex(reader.octets(8))
                }

                2 -> {
                    hexDigits(reader.bits(34), 9)
                }

                4 -> {
                    val length = reader.bits(10).toInt() + 1
                    ia5(reader, length)
                }

                5 -> {
                    ipAddress(reader)
                }

                else -> {
                    null
                }
            }
        val children = mutableListOf<Field>()
        if (!typeCut) children.add(reader.field("type", typeName, start, valueStart))
        if (value != null && !reader.truncated) children.add(reader.field("value", value, valueStart))
        return reader.field("set_id", if (reader.truncated) null else value, start, reader.bitPos, children)
    }

    /** SLPAddress CHOICE with `...`: iPAddress or fQDN. */
    private fun slpAddress(
        reader: BitReader,
        name: String,
    ): Field {
        val start = reader.bitPos
        if (reader.bit() == 1) {
            val index = smallNumber(reader)
            skipOpenType(reader)
            return reader.field(name, if (reader.truncated) null else "extension($index)", start)
        }
        val children = mutableListOf<Field>()
        val value: String?
        val fqdnChoice = reader.bit() == 1
        val typeCut = reader.truncated
        if (!fqdnChoice) {
            if (!typeCut) children.add(reader.field("type", "ipAddress", start))
            value = ipAddress(reader)
        } else {
            if (!typeCut) children.add(reader.field("type", "fqdn", start))
            val length = reader.bits(8).toInt() + 1
            value = fqdn(reader, length)
        }
        return reader.field(name, if (reader.truncated) null else value, start, reader.bitPos, children)
    }

    /** IPAddress CHOICE (no extension): ipv4 OCTET STRING(4) or ipv6 OCTET STRING(16). */
    private fun ipAddress(reader: BitReader): String {
        val v6 = reader.bit() == 1
        val bytes = reader.octets(if (v6) 16 else 4)
        if (reader.truncated) return ""
        return if (v6) ipv6(bytes) else bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    private fun ipv6(bytes: ByteArray): String =
        (0 until 8).joinToString(":") {
            (((bytes[it * 2].toInt() and 0xFF) shl 8) or (bytes[it * 2 + 1].toInt() and 0xFF)).toString(16)
        }

    private fun fqdn(
        reader: BitReader,
        length: Int,
    ): String {
        val out = StringBuilder(length)
        repeat(length) { out.append(FQDN_ALPHABET.getOrElse(reader.bits(6).toInt()) { '?' }) }
        return out.toString()
    }

    private fun ia5(
        reader: BitReader,
        length: Int,
    ): String {
        val out = StringBuilder(length)
        repeat(length) { out.append(reader.bits(7).toInt().toChar()) }
        return out.toString()
    }

    /** SUPLINIT SEQUENCE with `...`: extension bit, 5 presence bits, then the components in order. */
    private fun suplInit(reader: BitReader): Field {
        val start = reader.bitPos
        val children = mutableListOf<Field>()
        val extension = reader.bit() == 1
        val notificationPresent = reader.bit() == 1
        val slpAddressPresent = reader.bit() == 1
        val qopPresent = reader.bit() == 1
        val macPresent = reader.bit() == 1
        val keyPresent = reader.bit() == 1
        if (reader.truncated) return reader.group("init", start, children)
        children.add(
            reader.field(
                "presence",
                null,
                start,
                reader.bitPos,
                listOf(
                    reader.field("extension", extension, start),
                    reader.field("notification", notificationPresent, start),
                    reader.field("slp_address", slpAddressPresent, start),
                    reader.field("qop", qopPresent, start),
                    reader.field("mac", macPresent, start),
                    reader.field("key_identity", keyPresent, start),
                ),
            ),
        )
        if (!reader.truncated) posMethod(reader).let { if (!reader.truncated) children.add(it) }
        if (notificationPresent && !reader.truncated) children.add(notification(reader))
        if (slpAddressPresent && !reader.truncated) children.add(slpAddress(reader, "slp_address"))
        if (qopPresent && !reader.truncated) children.add(qop(reader))
        if (!reader.truncated) {
            val modeStart = reader.bitPos
            val mode = if (reader.bit() == 0) "proxy" else "nonProxy"
            if (!reader.truncated) children.add(reader.field("slp_mode", mode, modeStart))
        }
        if (macPresent && !reader.truncated) {
            val macStart = reader.bitPos
            val mac = reader.octets(8)
            if (!reader.truncated) children.add(reader.field("mac", hex(mac), macStart))
        }
        if (keyPresent && !reader.truncated) {
            val keyStart = reader.bitPos
            val key = reader.octets(16)
            if (!reader.truncated) children.add(reader.field("key_identity", hex(key), keyStart))
        }
        if (extension && !reader.truncated) {
            // A SUPL 2.0 INIT carries ver2-SUPL-INIT-extension here: valid, but not decoded.
            children.add(reader.field("extension", "present (not decoded)", reader.bitPos))
        }
        return reader.group("init", start, children)
    }

    /** PosMethod ENUMERATED with `...` (10 root values). */
    private fun posMethod(reader: BitReader): Field {
        val start = reader.bitPos
        val name =
            if (reader.bit() == 1) {
                val index = smallNumber(reader)
                POS_METHOD_EXTENSIONS.getOrNull(index) ?: "extension($index)"
            } else {
                val index = reader.bits(4).toInt()
                POS_METHODS.getOrNull(index) ?: "invalid($index)"
            }
        return reader.field("pos_method", name, start)
    }

    /** Notification SEQUENCE with `...`: extension bit, 5 presence bits, then the components in order. */
    private fun notification(reader: BitReader): Field {
        val start = reader.bitPos
        reader.bit()
        val encodingPresent = reader.bit() == 1
        val requestorPresent = reader.bit() == 1
        val requestorTypePresent = reader.bit() == 1
        val clientPresent = reader.bit() == 1
        val clientTypePresent = reader.bit() == 1
        val children = mutableListOf<Field>()
        if (!reader.truncated) enumField(reader, "notification_type", NOTIFICATION_TYPES, 3).let { if (!reader.truncated) children.add(it) }
        var encoding: String? = null
        if (encodingPresent && !reader.truncated) {
            val node = enumField(reader, "encoding_type", ENCODING_TYPES, 2)
            if (!reader.truncated) {
                encoding = node.value?.toString()
                children.add(node)
            }
        }
        // requestorId and clientName: OCTET STRING SIZE(1..50), so 6 bits hold length - 1.
        if (requestorPresent && !reader.truncated) children.add(encodedString(reader, "requestor_id", encoding))
        if (requestorTypePresent && !reader.truncated) {
            addIfComplete(reader, children, enumField(reader, "requestor_id_type", FORMAT_INDICATORS, 3))
        }
        if (clientPresent && !reader.truncated) children.add(encodedString(reader, "client_name", encoding))
        if (clientTypePresent && !reader.truncated) {
            addIfComplete(reader, children, enumField(reader, "client_name_type", FORMAT_INDICATORS, 3))
        }
        return reader.group("notification", start, children)
    }

    /** An ENUMERATED with `...` and [rootBits] bits for the root values. */
    private fun enumField(
        reader: BitReader,
        name: String,
        names: List<String>,
        rootBits: Int,
    ): Field {
        val start = reader.bitPos
        val value =
            if (reader.bit() == 1) {
                "extension(${smallNumber(reader)})"
            } else {
                val index = reader.bits(rootBits).toInt()
                names.getOrNull(index) ?: "invalid($index)"
            }
        return reader.field(name, value, start)
    }

    private fun encodedString(
        reader: BitReader,
        name: String,
        encoding: String?,
    ): Field {
        val start = reader.bitPos
        val length = reader.bits(6).toInt() + 1
        val dataStart = reader.bitPos
        val data = reader.octets(length)
        if (reader.truncated) return reader.field(name, null, start)
        val children = mutableListOf(reader.field("length", length, start, dataStart))
        val text =
            when (encoding) {
                "utf8" -> String(data, StandardCharsets.UTF_8)
                "ucs2" -> String(data, StandardCharsets.UTF_16BE)
                else -> null
            }
        if (text != null) children.add(reader.field("text", text, dataStart))
        return reader.field(name, hex(data), start, reader.bitPos, children)
    }

    /** QoP SEQUENCE with `...`: horacc INTEGER(0..127), veracc, maxLocAge INTEGER(0..65535), delay INTEGER(0..7). */
    private fun qop(reader: BitReader): Field {
        val start = reader.bitPos
        reader.bit()
        val veraccPresent = reader.bit() == 1
        val agePresent = reader.bit() == 1
        val delayPresent = reader.bit() == 1
        val children = mutableListOf<Field>()
        qopNumber(reader, children, "horacc", 7)
        if (veraccPresent) qopNumber(reader, children, "veracc", 7)
        if (agePresent) qopNumber(reader, children, "max_loc_age", 16)
        if (delayPresent) qopNumber(reader, children, "delay", 3)
        return reader.group("qop", start, children)
    }

    /** One INTEGER of the QoP in [bits] bits. No node when the reader is or becomes truncated (VR01-D15). */
    private fun qopNumber(
        reader: BitReader,
        children: MutableList<Field>,
        name: String,
        bits: Int,
    ) {
        if (reader.truncated) return
        val start = reader.bitPos
        val value = reader.bits(bits)
        if (!reader.truncated) children.add(reader.field(name, value, start))
    }

    /** Adds [node] only when the reader did not reach the end while it read the node (VR01-D15). */
    private fun addIfComplete(
        reader: BitReader,
        children: MutableList<Field>,
        node: Field,
    ) {
        if (!reader.truncated) children.add(node)
    }

    /** X.691 10.6: a normally small non-negative whole number (an extension index). */
    private fun smallNumber(reader: BitReader): Int = if (reader.bit() == 0) reader.bits(6).toInt() else -1

    /** X.691 10.2 open type: a length determinant, then that many octets, skipped. */
    private fun skipOpenType(reader: BitReader) {
        val length =
            if (reader.bit() == 0) {
                reader.bits(7).toInt()
            } else if (reader.bit() == 0) {
                reader.bits(14).toInt()
            } else {
                // Fragmented lengths are not expected in a SUPL INIT; stop here.
                reader.bits(reader.remainingBits + 1)
                0
            }
        reader.octets(length)
    }
}
