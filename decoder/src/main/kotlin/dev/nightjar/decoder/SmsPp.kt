package dev.nightjar.decoder

import dev.nightjar.pduparse.OtaParser
import dev.nightjar.pduparse.SimOtaSubtypes

/** TS 31.115 / TS 102 225 SMS-PP secured command and response packets. */
object SmsPp {
    private val TAR_RANGES: List<Triple<Int, Int, String>> =
        listOf(
            Triple(0x000000, 0x000000, "Issuer Security Domain"),
            // SRLabs SIMtester default TAR list (type WIB). Not normative: SmartTrust WIB 1.3 says the
            // operator sets the pull, push and admin TARs.
            Triple(0x000001, 0x000009, "Wireless Internet Browser (WIB)"),
            // TAR 'PSH', S@T low priority push (SIMalliance S@T 01.23 section 7). Citizen Lab "Bad Connection"
            // sample: TAR 505348, SPI 0x0001.
            Triple(0x505348, 0x505348, "S@T Browser (push)"),
            // TAR 'S@T', SRLabs SIMtester default TAR list (type SAT).
            Triple(0x534054, 0x534054, "S@T Browser"),
            Triple(0xB00000, 0xB00000, "Remote File Management (compact)"),
            Triple(0xB00002, 0xB0000F, "Remote File Management (compact)"),
            Triple(0xB00001, 0xB00001, "Remote File Management (31.116 compact)"),
            Triple(0xB00010, 0xB0001F, "Remote File Management (31.116 compact)"),
            Triple(0xB00020, 0xB0011F, "Remote File Management (expanded)"),
            Triple(0xB00120, 0xB0012F, "Remote File Management (31.116 expanded)"),
            Triple(0xB00130, 0xB0013F, "Remote File Management (31.116 expanded)"),
            Triple(0xB00140, 0xB001FF, "Remote File Management (31.116 expanded)"),
            Triple(0xB00200, 0xB0FFFF, "Remote File Management"),
            Triple(0xB10000, 0xB10005, "Visa Mobile Payment"),
            Triple(0xB10006, 0xB1FFFF, "Payment application"),
            Triple(0xB20000, 0xB200FF, "USAT Interpreter"),
            Triple(0xB20100, 0xB20100, "Issuer Security Domain (expanded)"),
            Triple(0xB20101, 0xB20101, "SCWS administrative agent"),
            Triple(0xB20102, 0xB20102, "SCWS application"),
            Triple(0xB20200, 0xB20200, "CAT_TP Multiplexing Application"),
            Triple(0xB20201, 0xB20201, "Controlling Authority Security Domain"),
            Triple(0xB20202, 0xB20202, "OMA BCAST audience measurement"),
            Triple(0xB20203, 0xB20203, "OMA LWM2M UICC application"),
            Triple(0xBFFF00, 0xBFFFFF, "Proprietary Toolkit Application"),
        )

    /** TS 31.115 / TS 102 225: the shortest command header (CHL) and response header (RHL). */
    private const val MIN_COMMAND_HEADER = 13
    private const val MIN_RESPONSE_HEADER = 10

    fun decode(bytes: ByteArray): Field = guarded("sms_pp", bytes) { decodeCommand(bytes, ByteReader(bytes)) }

    fun decode(hex: String): Field = guardedHex("sms_pp", hex) { decode(it) }

    fun decodeResponse(bytes: ByteArray): Field = guarded("sms_pp_response", bytes) { decodeResponse(bytes, ByteReader(bytes)) }

    fun decodeResponse(hex: String): Field = guardedHex("sms_pp_response", hex) { decodeResponse(it) }

    /** A command packet at absolute [baseOffset], for a tree nested in a TPDU. */
    internal fun decodeAt(
        bytes: ByteArray,
        baseOffset: Int,
    ): Field = guarded("sms_pp", bytes) { decodeCommand(bytes, ByteReader(bytes, baseOffset)) }

    /** A response packet at absolute [baseOffset], for a tree nested in a TPDU. */
    internal fun decodeResponseAt(
        bytes: ByteArray,
        baseOffset: Int,
    ): Field = guarded("sms_pp_response", bytes) { decodeResponse(bytes, ByteReader(bytes, baseOffset)) }

    private fun decodeCommand(
        bytes: ByteArray,
        reader: ByteReader,
    ): Field {
        val start = reader.absolutePos
        val children = mutableListOf<Field>()
        val lengths = commandLengths(bytes, reader, children)
        val spi1 = if (lengths != null) securityHeader(reader, children) else null
        if (lengths == null || spi1 == null) return finish(reader, start, "sms_pp", children)
        val (cpl, chl) = lengths
        val ciphered = (spi1 and 0x04) != 0
        val rcOffset = reader.absolutePos
        val rc = reader.take((chl - MIN_COMMAND_HEADER).coerceAtLeast(0))
        val rcChildren = mutableListOf(field("kind", rcName(spi1 and 0x03), rcOffset, rc.size))
        cipheredFlag(ciphered, rcOffset)?.let { rcChildren.add(it) }
        children.add(field("rc_cc_ds", hex(rc), rcOffset, rc.size, rcChildren))
        if (reader.failed) return finish(reader, start, "sms_pp", children)
        val securedOffset = reader.absolutePos
        val secured = reader.take((cpl - 1 - chl).coerceAtLeast(0).coerceAtMost(reader.remaining))
        val securedChildren = mutableListOf<Field>()
        cipheredFlag(ciphered, securedOffset)?.let { securedChildren.add(it) }
        // The S@T push and WIB command shapes and the SIM OTA subtypes of pduparse.
        val facts = OtaParser.parseCommand(bytes)
        if (facts.satPush()) securedChildren.add(field("shape", "S@T push", securedOffset, 0))
        if (facts.wibCommand()) securedChildren.add(field("shape", "WIB command", securedOffset, 0))
        children.add(field("secured_data", hex(secured), securedOffset, secured.size, securedChildren))
        children.add(field("sim_ota_subtypes", SimOtaSubtypes.codes(facts).joinToString(","), start, 0))
        trailing(reader)?.let { children.add(it) }
        return finish(reader, start, "sms_pp", children)
    }

    /** CPL and CHL with the length checks of pduparse. Returns (CPL, CHL), or null when the reader failed. */
    private fun commandLengths(
        bytes: ByteArray,
        reader: ByteReader,
        children: MutableList<Field>,
    ): Pair<Int, Int>? {
        val cplOffset = reader.absolutePos
        // A length past the end of the data gets no node (VR01-D15).
        val cpl = reader.u16OrNull() ?: return null
        children.add(field("cpl", cpl, cplOffset, 2))
        val chlOffset = reader.absolutePos
        val chl = reader.u8OrNull() ?: return null
        children.add(field("chl", chl, chlOffset, 1))
        // pduparse: AN-OTA-HDR (OtaParser).
        if (chl < MIN_COMMAND_HEADER) children.add(errorField(FieldNames.ERROR, chlOffset, "The CHL is less than $MIN_COMMAND_HEADER."))
        if (cpl != bytes.size - 2) children.add(errorField(FieldNames.ERROR, cplOffset, "The CPL is not the packet length minus 2."))
        if (cpl < 1 + chl) children.add(errorField(FieldNames.ERROR, cplOffset, "The CPL is less than the CHL plus 1."))
        return cpl to chl
    }

    /**
     * SPI, KIc, KID, TAR, CNTR and PCNTR of a command header (TS 102 225 5.1). Returns SPI1, or null when the reader
     * failed. After a failure no further field is added.
     */
    private fun securityHeader(
        reader: ByteReader,
        children: MutableList<Field>,
    ): Int? {
        val spiOffset = reader.absolutePos
        val spi1 = reader.u8()
        val spi2 = reader.u8()
        if (reader.failed) return null
        children.add(spiField(spi1, spi2, spiOffset))
        val ciphered = (spi1 and 0x04) != 0
        reader.octetNode(children) { kic, offset ->
            field("kic", hexByte(kic), offset, 1, listOf(field("algorithm", kicAlgorithm(kic), offset, 1)))
        }
        reader.octetNode(children) { kid, offset ->
            field("kid", hexByte(kid), offset, 1, listOf(field("algorithm", kidAlgorithm(kid, spi1), offset, 1)))
        }
        if (!reader.failed) tarField(reader)?.let { children.add(it) }
        if (!reader.failed) {
            // TS 102 225 5.1: with ciphering, CNTR, PCNTR, RC/CC/DS and the secured data are ciphertext.
            counterField(reader, ciphered)?.let { children.add(it) }
            reader.octetNode(children) { pcntr, offset -> paddingField(pcntr, offset, ciphered) }
        }
        return if (reader.failed) null else spi1
    }

    private fun decodeResponse(
        bytes: ByteArray,
        reader: ByteReader,
    ): Field {
        val start = reader.absolutePos
        val children = mutableListOf<Field>()
        val (rpl, rhl) = responseLengths(bytes, reader, children) ?: return finish(reader, start, "sms_pp_response", children)
        tarField(reader)?.let { children.add(it) }
        if (!reader.failed) {
            counterField(reader, ciphered = false)?.let { children.add(it) }
            reader.octetNode(children) { pcntr, offset -> paddingField(pcntr, offset, ciphered = false) }
        }
        reader.octetNode(children) { status, offset ->
            field("status", hexByte(status), offset, 1, listOf(field("meaning", responseStatus(status), offset, 1)))
        }
        if (reader.failed) return finish(reader, start, "sms_pp_response", children)
        val rcOffset = reader.absolutePos
        val rc = reader.take((rhl - MIN_RESPONSE_HEADER).coerceAtLeast(0))
        children.add(field("rc_cc_ds", hex(rc), rcOffset, rc.size))
        if (reader.failed) return finish(reader, start, "sms_pp_response", children)
        val additionalOffset = reader.absolutePos
        val additional = reader.take((rpl - 1 - rhl).coerceAtLeast(0).coerceAtMost(reader.remaining))
        children.add(field("additional_data", hex(additional), additionalOffset, additional.size))
        trailing(reader)?.let { children.add(it) }
        return finish(reader, start, "sms_pp_response", children)
    }

    /** RPL and RHL with the length checks of pduparse. Returns (RPL, RHL), or null when the reader failed. */
    private fun responseLengths(
        bytes: ByteArray,
        reader: ByteReader,
        children: MutableList<Field>,
    ): Pair<Int, Int>? {
        val rplOffset = reader.absolutePos
        // A length past the end of the data gets no node (VR01-D15).
        val rpl = reader.u16OrNull() ?: return null
        children.add(field("rpl", rpl, rplOffset, 2))
        val rhlOffset = reader.absolutePos
        val rhl = reader.u8OrNull() ?: return null
        children.add(field("rhl", rhl, rhlOffset, 1))
        if (rhl < MIN_RESPONSE_HEADER) children.add(errorField(FieldNames.ERROR, rhlOffset, "The RHL is less than $MIN_RESPONSE_HEADER."))
        if (rpl != bytes.size - 2) children.add(errorField(FieldNames.ERROR, rplOffset, "The RPL is not the packet length minus 2."))
        if (rpl < 1 + rhl) children.add(errorField(FieldNames.ERROR, rplOffset, "The RPL is less than the RHL plus 1."))
        return rpl to rhl
    }

    private fun finish(
        reader: ByteReader,
        start: Int,
        name: String,
        children: MutableList<Field>,
    ): Field {
        reader.errorField()?.let { children.add(it) }
        return field(name, null, start, reader.absolutePos - start, children)
    }

    /** Octets after the declared packet length. */
    private fun trailing(reader: ByteReader): Field? {
        if (!reader.hasMore) return null
        val offset = reader.absolutePos
        val rest = reader.takeRest()
        return field("trailing_data", hex(rest), offset, rest.size)
    }

    /**
     * TAR (3 octets), with the decoder name and the pduparse subtype of the TAR. Null when
     * no TAR octet is in the data (VR01-D15).
     */
    private fun tarField(reader: ByteReader): Field? {
        val offset = reader.absolutePos
        val tar = reader.take(3)
        if (tar.isEmpty()) return null
        if (tar.size < 3) return field("tar", hex(tar), offset, tar.size)
        val value = ((tar[0].toInt() and 0xFF) shl 16) or ((tar[1].toInt() and 0xFF) shl 8) or (tar[2].toInt() and 0xFF)
        val children =
            mutableListOf(
                field("value", "0x" + hexDigits(value.toLong(), 6), offset, 3),
                field("application", tarName(value), offset, 3),
            )
        SimOtaSubtypes.tarSubtype(value)?.let {
            children.add(field("subtype", it, offset, 3)) // contract-ok: decoder field label
        }
        return field("tar", hex(tar), offset, 3, children)
    }

    /**
     * CNTR (5 octets). Null when no CNTR octet is in the data. A cut counter has no value child: a number from fewer
     * than 5 octets is not the counter (VR01-D15).
     */
    private fun counterField(
        reader: ByteReader,
        ciphered: Boolean,
    ): Field? {
        val offset = reader.absolutePos
        val cntr = reader.take(5)
        if (cntr.isEmpty()) return null
        val children =
            when {
                ciphered -> listOf(field("ciphered", true, offset, cntr.size))
                cntr.size == 5 -> listOf(field("value", unsigned(cntr), offset, cntr.size))
                else -> emptyList()
            }
        return field("cntr", hex(cntr), offset, cntr.size, children)
    }

    private fun paddingField(
        pcntr: Int,
        offset: Int,
        ciphered: Boolean,
    ): Field =
        if (ciphered) {
            field("pcntr", hexByte(pcntr), offset, 1, listOf(field("ciphered", true, offset, 1)))
        } else {
            field("pcntr", pcntr, offset, 1)
        }

    private fun cipheredFlag(
        ciphered: Boolean,
        offset: Int,
    ): Field? = if (ciphered) field("ciphered", true, offset, 0) else null

    private fun spiField(spi1: Int, spi2: Int, offset: Int): Field {
        val rc = spi1 and 0x03
        val ciphering = (spi1 and 0x04) != 0
        val counter = (spi1 shr 3) and 0x03
        val por = spi2 and 0x03
        val porSecurity = (spi2 shr 2) and 0x03
        val porCiphering = (spi2 and 0x10) != 0
        // TS 102 225 5.1.1: SPI2 b6 = PoR sent as SMS-DELIVER-REPORT (0) or SMS-SUBMIT (1).
        val porTransport = if ((spi2 and 0x20) != 0) "SMS-SUBMIT" else "SMS-DELIVER-REPORT"
        val children =
            listOf(
                field("rc_cc_ds", rcName(rc), offset, 2),
                field("ciphering", ciphering, offset, 2),
                field("counter", counterName(counter), offset, 2),
                field("por", porName(por), offset, 2),
                field("por_security", rcName(porSecurity), offset + 1, 1),
                field("por_ciphering", porCiphering, offset + 1, 1),
                field("por_transport", porTransport, offset + 1, 1),
            )
        return field("spi", hexByte(spi1) + hexByte(spi2), offset, 2, children)
    }

    private fun rcName(value: Int): String =
        when (value) {
            0 -> "none"
            1 -> "redundancy-check"
            2 -> "cryptographic-checksum"
            else -> "digital-signature"
        }

    private fun counterName(value: Int): String =
        when (value) {
            0 -> "none"
            1 -> "available"
            2 -> "higher-than-last"
            else -> "one-higher-than-last"
        }

    private fun porName(value: Int): String =
        when (value) {
            0 -> "none"
            1 -> "required"
            2 -> "on-error"
            else -> "reserved"
        }

    /**
     * KIc (TS 102 225 5.1.2): b1-b2 algorithm, b3-b4 mode (bit positions 2-3, so shr 2), b5-b8 key
     * index.
     */
    private fun kicAlgorithm(kic: Int): String {
        val mode = (kic shr 2) and 0x03
        return when (kic and 0x03) {
            0 -> "implicit"
            1 -> desMode(mode, "DES-ECB")
            2 -> if (mode == 0) "AES-CBC" else "AES-reserved-mode"
            else -> "proprietary"
        }
    }

    /**
     * KID (TS 102 225 5.1.3), by the SPI1 security type: RC (b1-b2 01 CRC, 10 reserved; b3-b4 00 CRC16, 01 CRC32),
     * CC (DES modes as KIc with 11 reserved; AES 00 AES-CMAC) and DS (not coded by TS 102 225).
     */
    private fun kidAlgorithm(
        kid: Int,
        spi1: Int,
    ): String {
        val algorithm = kid and 0x03
        val mode = (kid shr 2) and 0x03
        return when (spi1 and 0x03) {
            1 -> {
                when (algorithm) {
                    0 -> {
                        "implicit"
                    }

                    1 -> {
                        when (mode) {
                            0 -> "CRC16"
                            1 -> "CRC32"
                            else -> "CRC-reserved-mode"
                        }
                    }

                    2 -> {
                        "reserved"
                    }

                    else -> {
                        "proprietary"
                    }
                }
            }

            2 -> {
                when (algorithm) {
                    0 -> "implicit"
                    1 -> desMode(mode, "DES-reserved-mode")
                    2 -> if (mode == 0) "AES-CMAC" else "AES-reserved-mode"
                    else -> "proprietary"
                }
            }

            3 -> {
                "digital-signature (algorithm bits $algorithm)"
            }

            else -> {
                "no-integrity"
            }
        }
    }

    /** DES modes of KIc and KID: 00 DES-CBC, 01 3DES 2 keys, 10 3DES 3 keys, 11 [last]. */
    private fun desMode(
        mode: Int,
        last: String,
    ): String =
        when (mode) {
            0 -> "DES-CBC"
            1 -> "3DES-2-key-outer-CBC"
            2 -> "3DES-3-key-outer-CBC"
            else -> last
        }

    private fun tarName(value: Int): String =
        TAR_RANGES.firstOrNull { value >= it.first && value <= it.second }?.third
            ?: if (value in 0x000001..0xAFFFFF || value in 0xC00000..0xFFFFFF) "issuer-specific" else "unknown"

    private fun unsigned(bytes: ByteArray): Long {
        var value = 0L
        for (b in bytes) value = (value shl 8) or (b.toInt() and 0xFF).toLong()
        return value
    }

    private fun responseStatus(status: Int): String =
        when (status) {
            0x00 -> "PoR OK"
            0x01 -> "RC/CC/DS failed"
            0x02 -> "CNTR low"
            0x03 -> "CNTR high"
            0x04 -> "CNTR blocked"
            0x05 -> "ciphering error"
            0x06 -> "unidentified security error"
            0x07 -> "insufficient memory"
            0x08 -> "more time"
            0x09 -> "TAR unknown"
            0x0A -> "insufficient security level"
            0x0B -> "actual response data to be sent using SMS-SUBMIT"
            0x0C -> "actual response data to be sent using USSD"
            else -> "reserved"
        }
}
