package dev.nightjar.decoder

/** Direction of a TPDU on the MS-SC interface (TS 23.040 9.2.3.1). */
enum class TpduDirection {
    MS_TO_SC,
    SC_TO_MS,
}

/** GSM 03.40 / TS 23.040 TPDU decoder. */
object Tpdu {
    /** TS 24.011 8.2.5.1: the RP-SC address has at most 11 octets after its length octet (`MAX_SMSC_OCTETS`). */
    private const val MAX_SMSC_OCTETS = 11

    /** WAP push port (WAP-259 WDP), the `WAP_PUSH_PORT` of `TpduParser`. */
    private const val WAP_PUSH_PORT = 2948

    /** SUPL INIT over SMS (OMA-TS-ULP): the port of the SUPL client on the SET. */
    private const val SUPL_PORT = 7275

    /** TP-PID (U)SIM data download (TS 23.040 9.2.3.9). */
    private const val PID_SIM_DATA_DOWNLOAD = 0x7F

    fun decode(
        bytes: ByteArray,
        direction: TpduDirection? = null,
    ): Field = guarded("tpdu", bytes) { decodeRadio(bytes, direction) }

    fun decode(
        hex: String,
        direction: TpduDirection? = null,
    ): Field = guardedHex("tpdu", hex) { decode(it, direction) }

    /**
     * The radio form `<smsc-len><smsc><tpdu>`, with the SMSC rule of `TpduParser.parseRadio`: a first
     * octet above 11, or one that covers the whole input, is not an SMSC length and the input is a bare TPDU. When
     * the read with the SMSC prefix fails and the message is not a status report, a bare read that does not fail
     * is used instead.
     */
    private fun decodeRadio(
        bytes: ByteArray,
        direction: TpduDirection?,
    ): Field {
        val smscLength = if (bytes.isNotEmpty()) bytes[0].toInt() and 0xFF else 0
        val prefix = bytes.size < 2 || (smscLength <= MAX_SMSC_OCTETS && 1 + smscLength < bytes.size)
        if (!prefix) return decodeTree(ByteReader(bytes), direction, smsc = false).field
        val withSmsc = decodeTree(ByteReader(bytes), direction, smsc = true)
        if (withSmsc.failed && smscLength != 0 && withSmsc.mti != 2) {
            val bare = decodeTree(ByteReader(bytes), direction, smsc = false)
            if (!bare.failed) return bare.field
        }
        return withSmsc.field
    }

    /** Decodes from [reader]; the first octet is the SMSC length. */
    fun decode(
        reader: ByteReader,
        direction: TpduDirection? = null,
    ): Field = decodeTree(reader, direction, smsc = true).field

    private class Tree(
        val field: Field,
        val failed: Boolean,
        val mti: Int,
    )

    private fun decodeTree(
        reader: ByteReader,
        direction: TpduDirection?,
        smsc: Boolean,
    ): Tree {
        val start = reader.absolutePos
        val children = mutableListOf<Field>()
        if (smsc) {
            children.add(Address.decodeSmsc(reader))
        } else {
            children.add(
                // No value: the app reads a non-blank "smsc" value as an SMSC address (RulePreview, DECODER-P01).
                field(
                    "smsc",
                    null,
                    start,
                    0,
                    listOf(field("note", "The input has no SMSC prefix. The TPDU starts at the first octet.", start, 0)),
                ),
            )
        }
        if (reader.failed) {
            reader.errorField()?.let { children.add(it) }
            return Tree(field("tpdu", null, start, reader.absolutePos - start, children), true, -1)
        }
        val firstOffset = reader.absolutePos
        val first = reader.u8()
        if (reader.failed) {
            reader.errorField()?.let { children.add(it) }
            return Tree(field("tpdu", null, start, reader.absolutePos - start, children), true, -1)
        }
        val mti = first and 0x03
        val mtiName = mtiName(mti, direction)
        children.add(firstOctet(first, mti, mtiName, direction, firstOffset))
        when {
            mti == 0 && direction == TpduDirection.MS_TO_SC -> {
                children.addAll(report(reader))
            }

            mti == 0 -> {
                children.addAll(decodeDeliver(reader, first))
            }

            mti == 1 && direction == TpduDirection.SC_TO_MS -> {
                children.addAll(report(reader))
            }

            mti == 1 -> {
                if (direction == null) {
                    // pduparse does not accept an SMS-SUBMIT on the inbound path.
                    children.add(field("note", "SMS-SUBMIT is not expected on the inbound path.", firstOffset, 1))
                }
                children.addAll(decodeSubmit(reader, first))
            }

            mti == 2 && direction == TpduDirection.MS_TO_SC -> {
                children.addAll(decodeCommand(reader))
            }

            mti == 2 -> {
                children.addAll(decodeStatusReport(reader, first))
            }

            else -> {
                children.add(errorField(FieldNames.ERROR, firstOffset, "The message type indicator 3 is reserved."))
                val remainderOffset = reader.absolutePos
                val remainder = reader.takeRest()
                if (remainder.isNotEmpty()) children.add(field("raw", hex(remainder), remainderOffset, remainder.size))
            }
        }
        val failed = reader.failed
        reader.errorField()?.let { children.add(it) }
        return Tree(field("tpdu", mtiName, start, reader.absolutePos - start, children), failed, mti)
    }

    /** TS 23.040 9.2.3.1: MTI 3 is reserved in both directions. */
    private fun mtiName(
        mti: Int,
        direction: TpduDirection?,
    ): String =
        when (mti) {
            0 -> if (direction == TpduDirection.MS_TO_SC) "SMS-DELIVER-REPORT" else "SMS-DELIVER"
            1 -> if (direction == TpduDirection.SC_TO_MS) "SMS-SUBMIT-REPORT" else "SMS-SUBMIT"
            2 -> if (direction == TpduDirection.MS_TO_SC) "SMS-COMMAND" else "SMS-STATUS-REPORT"
            else -> "reserved"
        }

    /** The first-octet bits of each TPDU type (TS 23.040 9.2.2). */
    private fun firstOctet(
        first: Int,
        mti: Int,
        mtiName: String,
        direction: TpduDirection?,
        offset: Int,
    ): Field {
        val children = mutableListOf<Field>()
        children.add(field("mti", mtiName, offset, 1))
        if (direction != null) children.add(field("direction", direction.name, offset, 1))

        fun flag(
            name: String,
            mask: Int,
        ) {
            children.add(field(name, (first and mask) != 0, offset, 1))
        }
        when {
            mti == 0 && direction == TpduDirection.MS_TO_SC -> {
                flag("udhi", 0x40)
            }

            mti == 0 -> {
                flag("mms", 0x04)
                flag("lp", 0x08)
                flag("sri", 0x20)
                flag("udhi", 0x40)
                flag("rp", 0x80)
            }

            mti == 1 && direction == TpduDirection.SC_TO_MS -> {
                flag("udhi", 0x40)
            }

            mti == 1 -> {
                flag("rd", 0x04)
                children.add(field("vpf", vpfName((first shr 3) and 0x03), offset, 1))
                flag("srr", 0x20)
                flag("udhi", 0x40)
                flag("rp", 0x80)
            }

            mti == 2 && direction == TpduDirection.MS_TO_SC -> {
                flag("srr", 0x20)
                flag("udhi", 0x40)
            }

            mti == 2 -> {
                flag("mms", 0x04)
                flag("lp", 0x08)
                flag("srq", 0x20)
                flag("udhi", 0x40)
            }

            else -> {}
        }
        return field("first_octet", hexByte(first), offset, 1, children)
    }

    private fun vpfName(vpf: Int): String =
        when (vpf) {
            0 -> "none"
            1 -> "enhanced"
            2 -> "relative"
            else -> "absolute"
        }

    /** Reads one octet, or null after a failure (: no field is invented after the data ends). */
    private fun ByteReader.octetOrNull(): Int? {
        if (failed) return null
        val value = u8()
        return if (failed) null else value
    }

    /**
     * SMS-DELIVER-REPORT and SMS-SUBMIT-REPORT (TS 23.040 9.2.2.1a, 9.2.2.2a). The body starts with TP-FCS for
     * RP-ERROR and with TP-PI for RP-ACK; the TPDU alone does not tell which, so the body is shown as raw octets
     * .
     */
    private fun report(reader: ByteReader): List<Field> {
        val offset = reader.absolutePos
        val body = reader.takeRest()
        val out = mutableListOf<Field>()
        out.add(
            field(
                "note",
                "The decoder does not decode the body of a report TPDU. It starts with TP-FCS (RP-ERROR) or TP-PI (RP-ACK).",
                offset,
                0,
            ),
        )
        if (body.isNotEmpty()) out.add(field("raw", hex(body), offset, body.size))
        return out
    }

    private fun decodeDeliver(
        reader: ByteReader,
        first: Int,
    ): List<Field> {
        val out = mutableListOf<Field>()
        out.add(Address.decodeTp(reader, "originator"))
        val pid = reader.octetNode(out, ::pidField)
        val dcs = reader.octetNode(out, Dcs::field)
        if (pid != null && dcs != null) {
            out.add(timestampField(reader, "scts"))
            if (!reader.failed) out.add(userData(reader, Dcs.decode(dcs), (first and 0x40) != 0, pid))
        }
        return out
    }

    private fun decodeSubmit(
        reader: ByteReader,
        first: Int,
    ): List<Field> {
        val out = mutableListOf<Field>()
        val mr = reader.octetNode(out, ::messageReferenceField)
        if (mr != null) out.add(Address.decodeTp(reader, "destination"))
        val pid = reader.octetNode(out, ::pidField)
        val dcs = reader.octetNode(out, Dcs::field)
        if (pid == null || dcs == null) return out
        when ((first shr 3) and 0x03) {
            0 -> {}

            1 -> {
                out.add(validityPeriodRaw(reader, "validity_period_enhanced"))
            }

            2 -> {
                validityPeriodRelative(reader)?.let { out.add(it) }
            }

            else -> {
                out.add(timestampField(reader, "validity_period_absolute"))
            }
        }
        if (!reader.failed) out.add(userData(reader, Dcs.decode(dcs), (first and 0x40) != 0, pid))
        return out
    }

    private fun decodeStatusReport(
        reader: ByteReader,
        first: Int,
    ): List<Field> {
        val out = mutableListOf<Field>()
        // Each step runs only while the reader has not failed, so the tree stops at the first failure.
        val mr = reader.octetNode(out, ::messageReferenceField)
        if (mr != null) out.add(Address.decodeTp(reader, "recipient"))
        if (!reader.failed) out.add(timestampField(reader, "scts"))
        if (!reader.failed) out.add(timestampField(reader, "discharge_time"))
        reader.octetNode(out) { status, offset ->
            field("status", hexByte(status), offset, 1, listOf(field("meaning", TpduMeanings.statusMeaning(status), offset, 1)))
        }
        statusReportParameters(reader, first, out)
        return out
    }

    /** TP-PI and the optional TP-PID, TP-DCS and TP-UD of an SMS-STATUS-REPORT. Adds nothing after a failure. */
    private fun statusReportParameters(
        reader: ByteReader,
        first: Int,
        out: MutableList<Field>,
    ) {
        val pi = readParameterIndicator(reader) ?: return
        out.add(pi.field)
        // TS 23.040 9.2.3.27: the PID, DCS and UDL flags are bits 0-2 of the first TP-PI octet.
        var dcsInfo = Dcs.decode(0)
        var pid = 0
        if ((pi.flags and 0x01) != 0) pid = reader.octetNode(out, ::pidField) ?: return
        if ((pi.flags and 0x02) != 0) dcsInfo = Dcs.decode(reader.octetNode(out, Dcs::field) ?: return)
        if ((pi.flags and 0x04) != 0) out.add(userData(reader, dcsInfo, (first and 0x40) != 0, pid))
    }

    /** SMS-COMMAND (TS 23.040 9.2.2.4): MR, PID, CT, MN, DA, CDL, CD. */
    private fun decodeCommand(reader: ByteReader): List<Field> {
        val out = mutableListOf<Field>()
        // After a failed read, octetNode adds nothing, so only the checks before DA and CD need a return.
        reader.octetNode(out, ::messageReferenceField)
        reader.octetNode(out, ::pidField)
        reader.octetNode(out) { commandType, offset ->
            val meaning = field("meaning", TpduMeanings.commandMeaning(commandType), offset, 1)
            field("command_type", hexByte(commandType), offset, 1, listOf(meaning))
        }
        reader.octetNode(out) { mn, offset -> field("message_number", mn, offset, 1) } ?: return out
        out.add(Address.decodeTp(reader, "destination"))
        val cdl = reader.octetNode(out) { length, offset -> field("command_data_length", length, offset, 1) } ?: return out
        val cdOffset = reader.absolutePos
        val cd = reader.take(cdl)
        out.add(field("command_data", hex(cd), cdOffset, cd.size))
        return out
    }

    private fun messageReferenceField(
        mr: Int,
        offset: Int,
    ): Field = field("message_reference", mr, offset, 1)

    private class ParameterIndicator(
        val field: Field,
        val flags: Int,
    )

    /**
     * TP-PI (TS 23.040 9.2.3.27): bit 7 of each octet says that another octet follows. The flags come from the first
     * octet. Bits 3-6 of the first octet are reserved; `TpduParser` ignores the PI when they are set, so the tree
     * marks them.
     */
    private fun readParameterIndicator(reader: ByteReader): ParameterIndicator? {
        if (!reader.hasMore || reader.failed) return null
        val offset = reader.absolutePos
        val firstPi = reader.u8()
        var octet = firstPi
        val raw = StringBuilder().append(hexByte(octet))
        var count = 1
        while ((octet and 0x80) != 0 && reader.hasMore && count < 5) {
            octet = reader.u8()
            raw.append(hexByte(octet))
            count++
        }
        val children =
            mutableListOf(
                field("pid", (firstPi and 0x01) != 0, offset, 1),
                field("dcs", (firstPi and 0x02) != 0, offset, 1),
                field("udl", (firstPi and 0x04) != 0, offset, 1),
            )
        if ((firstPi and 0x78) != 0) children.add(field("reserved_bits", hexByte(firstPi and 0x78), offset, 1))
        return ParameterIndicator(field("parameter_indicator", raw.toString(), offset, count, children), firstPi and 0x07)
    }

    /** TS 23.040 9.2.3.12.1. */
    private fun validityPeriodRelative(reader: ByteReader): Field? {
        val offset = reader.absolutePos
        val value = reader.octetOrNull() ?: return null
        val meaning =
            when {
                value <= 143 -> "${(value + 1) * 5} minutes"
                value <= 167 -> "${12 * 60 + (value - 143) * 30} minutes"
                value <= 196 -> "${value - 166} days"
                else -> "${value - 192} weeks"
            }
        return field("validity_period", hexByte(value), offset, 1, listOf(field("relative", meaning, offset, 1)))
    }

    private fun validityPeriodRaw(
        reader: ByteReader,
        name: String,
    ): Field {
        val start = reader.absolutePos
        val bytes = reader.take(7)
        val children = listOfNotNull(reader.errorField())
        return field(name, hex(bytes), start, bytes.size, children)
    }

    /** A 7-octet time stamp field (TP-SCTS, TP-DT, absolute TP-VP). The value is the ISO 8601 text when valid. */
    private fun timestampField(
        reader: ByteReader,
        name: String,
    ): Field {
        val start = reader.absolutePos
        val bytes = reader.take(7)
        if (bytes.size < 7) return field(name, null, start, bytes.size, listOfNotNull(reader.errorField()))
        val children = Timestamp.fields(bytes, start)
        return field(name, Timestamp.iso(children), start, 7, children)
    }

    /**
     * TP-UD (TS 23.040 9.2.3.16, 9.2.3.24). After the UDH, the user data is decoded further with the conditions of
     * `TpduParser.inspect`: a WAP push on port 2948 (any DCS, not multipart, DECODER-P04) gives a
     * nested WSP tree, a SIM OTA packet (UDH 0x70/0x71, octet coding, PID 0x7F or class 2, not multipart) a nested
     * SMS-PP tree and port 7275 (8-bit, not multipart) a nested SUPL tree.
     */
    private fun userData(
        reader: ByteReader,
        dcs: DcsInfo,
        udhi: Boolean,
        pid: Int,
    ): Field {
        val start = reader.absolutePos
        val children = mutableListOf<Field>()
        // No TP-UDL octet: only the error node (VR01-D15).
        val udl = reader.u8OrNull() ?: 0
        if (!reader.failed) children.add(field("udl", udl, start, 1))
        if (reader.failed) {
            reader.errorField()?.let { children.add(it) }
            return group("user_data", start, reader.absolutePos - start, children)
        }
        // TS 23.040 9.2.3.16: the UDL counts septets only for uncompressed GSM 7-bit.
        val septets = dcs.alphabet == Alphabet.GSM7 && !dcs.compressed
        var udhInfo: UdhInfo? = null
        if (udhi) {
            val udOctets = if (septets) (udl * 7 + 7) / 8 else udl
            val udhStart = reader.absolutePos
            val (udhField, info) = Udh.decodeWithInfo(reader)
            udhInfo = info
            children.add(udhField)
            if (!reader.failed && info.totalOctets > udOctets) {
                // pduparse flags AN-UDH-LEN for this.
                children.add(errorField(FieldNames.ERROR, udhStart, "The user data header is longer than the user data length (UDL)."))
            }
        }
        if (reader.failed) {
            reader.errorField()?.let { children.add(it) }
            return group("user_data", start, reader.absolutePos - start, children)
        }
        val textOffset = reader.absolutePos
        val data = if (septets) septetText(reader, udl, udhInfo, children) else octetText(reader, udl, udhInfo, dcs, children)
        // A WAP push goes to WapPushOverSms whatever the DCS (TpduParser.inspect, DECODER-P04).
        if (!reader.failed && udhInfo != null) nested(udhInfo, dcs, pid, data, textOffset)?.let { children.add(it) }
        reader.errorField()?.let { children.add(it) }
        return group("user_data", start, reader.absolutePos - start, children)
    }

    /** The GSM 7-bit text after the UDH (TS 23.038 6.1.2.1, fill bits of TS 23.040 9.2.3.24). Returns the text octets. */
    private fun septetText(
        reader: ByteReader,
        udl: Int,
        udhInfo: UdhInfo?,
        children: MutableList<Field>,
    ): ByteArray {
        val fill = if (udhInfo != null) Udh.fillBits(udhInfo.totalOctets) else 0
        val headerSeptets = if (udhInfo != null) (udhInfo.totalOctets * 8 + fill) / 7 else 0
        val textSeptets = (udl - headerSeptets).coerceAtLeast(0)
        val textBytes = Gsm7.packedOctets(textSeptets, fill)
        val textOffset = reader.absolutePos
        val data = reader.take(textBytes)
        val locking = udhInfo?.lockingShift?.let { Gsm7.languageIndex(it).coerceAtLeast(0) } ?: 0
        val single = udhInfo?.singleShift?.let { Gsm7.languageIndex(it) } ?: -1
        val text = Gsm7.decode(data, textSeptets, fill, locking, single)
        children.add(field("septets", textSeptets, textOffset, data.size))
        children.add(field("text", text, textOffset, data.size))
        return data
    }

    /** The octet user data after the UDH: compressed, UCS-2, reserved alphabet or 8-bit. Returns the data octets. */
    private fun octetText(
        reader: ByteReader,
        udl: Int,
        udhInfo: UdhInfo?,
        dcs: DcsInfo,
        children: MutableList<Field>,
    ): ByteArray {
        val headerOctets = udhInfo?.totalOctets ?: 0
        val dataLength = (udl - headerOctets).coerceAtLeast(0)
        val textOffset = reader.absolutePos
        val data = reader.take(dataLength)
        when {
            dcs.compressed -> {
                children.add(field("hex", hex(data), textOffset, data.size))
                children.add(field("note", "The text is compressed (TS 23.042). The decoder does not decompress it.", textOffset, 0))
            }

            dcs.alphabet == Alphabet.UCS2 -> {
                children.add(field("text", Gsm7.decodeUcs2(data), textOffset, data.size))
                children.add(field("hex", hex(data), textOffset, data.size))
            }

            dcs.alphabet == Alphabet.RESERVED -> {
                children.add(field("hex", hex(data), textOffset, data.size))
                children.add(field("alphabet", "reserved", textOffset, 0))
            }

            else -> {
                children.add(field("hex", hex(data), textOffset, data.size))
            }
        }
        return data
    }

    private fun nested(
        udh: UdhInfo,
        dcs: DcsInfo,
        pid: Int,
        data: ByteArray,
        offset: Int,
    ): Field? {
        if (udh.multipart || data.isEmpty()) return null
        val eightBit = dcs.alphabet == Alphabet.EIGHT_BIT && !dcs.compressed
        val octets = dcs.alphabet != Alphabet.GSM7 || dcs.compressed
        val forSim = pid == PID_SIM_DATA_DOWNLOAD || dcs.messageClass == 2
        return when {
            (udh.toolkitCommand || udh.toolkitResponse) && octets && forSim -> {
                if (udh.toolkitCommand) SmsPp.decodeAt(data, offset) else SmsPp.decodeResponseAt(data, offset)
            }

            udh.ports?.destination == WAP_PUSH_PORT -> {
                Wsp.decodeAt(data, offset)
            }

            udh.ports?.destination == SUPL_PORT && eightBit -> {
                Supl.decodeAt(data, offset)
            }

            else -> {
                null
            }
        }
    }

    private fun pidField(
        pid: Int,
        offset: Int,
    ): Field {
        val children = mutableListOf(field("meaning", TpduMeanings.pidMeaning(pid), offset, 1))
        if (TpduMeanings.reservedPid(pid)) children.add(field("reserved", true, offset, 1))
        return field("pid", hexByte(pid), offset, 1, children)
    }
}
