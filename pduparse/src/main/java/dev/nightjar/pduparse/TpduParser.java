package dev.nightjar.pduparse;

import dev.nightjar.contract.AnomalyCode;
import dev.nightjar.contract.Hashes;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;

/**
 * Total parser for one inbound 3GPP SMS TPDU. Moved from the payload's {@code PduFacts}:
 * it reads the fields the classifiers and the correlator need and never throws. Malformed input gives
 * {@link TpduFacts#valid()} {@code false} with a parse error, and whatever could be read.
 *
 * <p>Parsing follows TS 23.040 §9.2.2 (SMS-DELIVER and SMS-STATUS-REPORT). User data text is not decoded here; that
 * stays in the app's decoder and, for rule text conditions, in the framework.
 *
 * <p>The same pass records the anomalies of the TPDU and, through
 * {@link UdhParser}, {@link OtaParser}, {@link WspParser} and {@link WbxmlScanner}, those of what the TPDU carries. A
 * hard anomaly (address cut, PDU too short, reserved MTI, UDL past the data, UDH past the user data) stops the checks
 * that depend on it.
 *
 * <p>Thread: any.
 */
public final class TpduParser {
    /** WAP push port (WAP-259 WDP, OMNA port registry). */
    public static final int WAP_PUSH_PORT = 2948;

    /** TS 23.040 an address has at most 20 semi-octets (10 octets of digits). */
    static final int MAX_ADDRESS_DIGITS = 20;
    /** SMS-DELIVER after TP-OA: PID, DCS, SCTS (7) and UDL. */
    private static final int DELIVER_TAIL = 10;
    /** SMS-STATUS-REPORT after TP-RA: SCTS (7), discharge time (7) and TP-ST. */
    private static final int STATUS_TAIL = 15;
    /** TS 24.011: at most 11 octets of SMSC address after the length octet (same bound as Hashes). */
    private static final int MAX_SMSC_OCTETS = 11;
    /** TS 23.040 at most 140 octets of user data. */
    private static final int MAX_UD_OCTETS = 140;
    /** TS 23.040 at most 160 septets of GSM 7-bit user data. */
    private static final int MAX_UD_SEPTETS = 160;
    /** TS 23.040 type of number 101 is alphanumeric (GSM 7-bit packed). */
    private static final int TON_ALPHANUMERIC = 5;
    /** TS 23.040 type of number 001 is international. */
    private static final int TON_INTERNATIONAL = 1;
    /** An address length octet holds at most 255 semi-octets. */
    private static final int MAX_SEMI_OCTETS = 255;
    /** GSM 7-bit escape to the extension table (TS 23.038). */
    private static final int GSM7_ESCAPE = 0x1B;
    /**
     * BCD digits 0x0 to 0xE of an address (TS 23.040: 1010 "*", 1011 "#", 1100 "a", 1101 "b", 1110 "c"),
     * the same set as the framework's {@code PhoneNumberUtils.BCD_EXTENDED_TYPE_CALLED_PARTY}. 0xF is the filler.
     */
    private static final String BCD_DIGITS = "0123456789*#abc";

    private TpduParser() {}

    /** Parses a bare TPDU (no SMSC prefix), as delivered by IMS. */
    public static TpduFacts parse(byte[] tpdu) {
        try {
            return parseInternal(tpdu, null);
        } catch (RuntimeException e) {
            // Unreachable by design (FuzzTest proves it); fail open.
            return error(e.getClass().getSimpleName());
        }
    }

    /**
     * Parses a radio PDU ({@code <smsc-len><smsc><tpdu>}). The SMSC prefix is removed with
     * {@link Hashes#stripSmsc(byte[])}; when the prefix is absent the whole array is parsed.
     */
    public static TpduFacts parseRadio(byte[] pdu) {
        try {
            return parseRadioUnguarded(pdu);
        } catch (RuntimeException e) {
            // Unreachable by design (FuzzTest proves it); fail open.
            return error(e.getClass().getSimpleName());
        }
    }

    static TpduFacts parseRadioUnguarded(byte[] pdu) {
        if (pdu == null) return parseInternal(null, null); // no octets: AN-PDU-SHORT, as parse(null)
        byte[] tpdu = Hashes.stripSmsc(pdu);
        TpduFacts f = parseInternal(tpdu, decodeSmsc(pdu));
        // Retry as a bare TPDU only when a prefix was stripped and the first read failed and cannot be a status report
        // (a wrongly stripped PDU is what the retry is for; a status report failing later is not). The bare read
        // replaces the first only when it has no hard anomaly, so a retry cannot hide a malformed TPDU. An SMSC
        // address read as a TPDU gives an address length of 0x81 or more (the TOA), which is AN-ADDR-LEN.
        if (tpdu != pdu && !f.valid() && !f.statusReport()) {
            TpduFacts bare = parseInternal(pdu, null);
            if (bare.valid() && !Anomalies.malformed(bare.anomalyMask())) return bare;
        }
        return f;
    }

    /**
     * The fail-open result when the parser met an exception (unreachable by design). It carries no anomaly code: the
     * parser found no known defect, it only could not read the PDU. The classifier gives CL-UNREADABLE-SMS, which can
     * be held and released, not CL-MALFORMED-SMS, which cannot.
     */
    private static TpduFacts error(String message) {
        TpduFacts.Builder b = new TpduFacts.Builder();
        b.parseError = message;
        return new TpduFacts(b);
    }

    static TpduFacts parseInternal(byte[] t, String smsc) {
        TpduFacts.Builder b = new TpduFacts.Builder();
        b.smsc = smsc;
        b.tpdu = t == null ? new byte[0] : t.clone();
        if (t == null || t.length < 2) {
            b.parseError = "short pdu";
            b.flag(AnomalyCode.PDU_SHORT);
            return new TpduFacts(b);
        }
        int first = t[0] & 0xFF;
        int mti = first & 0x03;
        b.firstOctet = first;
        b.mti = mti;
        b.udhi = (first & 0x40) != 0;
        b.statusReport = mti == 2;
        if (mti == 2) {
            statusReport(t, 1, b);
            return new TpduFacts(b);
        }
        if (mti != 0) {
            // TS 23.040 MTI 1 is SMS-SUBMIT-REPORT in this direction, which never arrives as an inbound
            // message, and MTI 3 is reserved. Both get AN-MTI, so the parse failure has a name (CL-UNREADABLE-SMS).
            b.parseError = "mti=" + mti;
            b.flag(AnomalyCode.MTI);
            return new TpduFacts(b);
        }
        deliver(t, b);
        return new TpduFacts(b);
    }

    /** SMS-DELIVER (TS 23.040). */
    private static void deliver(byte[] t, TpduFacts.Builder b) {
        int i = address(t, 1, b, true);
        if (i < 0) {
            b.parseError = "address";
            return;
        }
        if (i + 2 > t.length) {
            b.parseError = "header";
            b.flag(AnomalyCode.PDU_SHORT);
            return;
        }
        b.protocolId = t[i] & 0xFF;
        b.dcs = t[i + 1] & 0xFF;
        if (reservedPid(b.protocolId)) b.flag(AnomalyCode.PID_RSV);
        decodeDcs(b);
        b.timestampMs = decodeScts(t, i + 2);
        if (t.length - i < DELIVER_TAIL) b.flag(AnomalyCode.PDU_SHORT);
        i = skip(t, i + 2, 7);
        readUserData(t, i, b);
        b.valid = true;
    }

    /**
     * SMS-STATUS-REPORT (TS 23.040). TP-PI and everything it gates are optional: many
     * carriers send no TP-UD at all. TP-RA is the recipient, not an originator.
     */
    private static void statusReport(byte[] t, int start, TpduFacts.Builder b) {
        int i = start;
        if (i < t.length) b.messageReference = t[i++] & 0xFF;
        int ra = address(t, i, b, false);
        if (ra == -1) {
            // The RA header itself is missing: the former parser failed here too.
            b.parseError = "address";
            return;
        }
        boolean cut = ra < 0;
        i = cut ? -ra : ra;
        if (!cut && i + STATUS_TAIL > t.length) b.flag(AnomalyCode.PDU_SHORT);
        i = skip(t, i, 7); // SCTS
        i = skip(t, i, 7); // discharge time
        if (i < t.length) b.status = t[i++] & 0xFF;
        if (i < t.length) {
            // TS 23.040 the PID, DCS and UDL bits and the reserved bits 3 to 6 are in the first TP-PI
            // octet. Extension octets (bit 7) follow it and carry nothing that this parser reads. As in the
            // framework, the optional fields are read only when the reserved bits of the first octet are 0.
            int pi = t[i++] & 0xFF;
            int ext = pi;
            while ((ext & 0x80) != 0 && i < t.length) ext = t[i++] & 0xFF;
            if ((pi & 0x78) == 0) {
                if ((pi & 0x01) != 0 && i < t.length) {
                    b.protocolId = t[i++] & 0xFF;
                    if (reservedPid(b.protocolId)) b.flag(AnomalyCode.PID_RSV);
                }
                if ((pi & 0x02) != 0 && i < t.length) {
                    b.dcs = t[i++] & 0xFF;
                    decodeDcs(b);
                } else if ((pi & 0x04) != 0) {
                    // No TP-DCS: the default value 0x00 (GSM 7-bit) applies, as the framework keeps it.
                    b.dcs = 0;
                    decodeDcs(b);
                }
                if ((pi & 0x04) != 0) {
                    readUserData(t, i, b);
                    b.valid = true;
                    return;
                }
            }
        }
        b.valid = true;
    }

    /**
     * Reads the address at {@code t[i]} and returns the offset after it.
     *
     * <p>Returns −1 when the length and type octets are missing. When the digits run past the PDU, an SMS-DELIVER
     * (decode) returns −1 and a status report returns the negated end offset, because the former parser skipped TP-RA
     * without reading it.
     */
    private static int address(byte[] t, int i, TpduFacts.Builder b, boolean decode) {
        if (i + 2 > t.length) {
            b.flag(AnomalyCode.ADDR_TRUNC);
            return -1;
        }
        int length = t[i] & 0xFF;
        int toa = t[i + 1] & 0xFF;
        if (length > MAX_ADDRESS_DIGITS) b.flag(AnomalyCode.ADDR_LEN);
        if (reservedToa(toa)) b.flag(AnomalyCode.ADDR_TOA);
        int end = i + 2 + addressOctets(length, toa);
        if (end > t.length) {
            b.flag(AnomalyCode.ADDR_TRUNC);
            return decode ? -1 : -end;
        }
        if (decode) {
            b.originatorLength = length;
            b.originatorToa = toa;
            String addr = decodeAddress(t, i + 2, length, toa);
            if (addr != null && !addr.isEmpty()) b.originator = addr;
        }
        return end;
    }

    private static void readUserData(byte[] t, int i, TpduFacts.Builder b) {
        if (i >= t.length) return;
        int udl = t[i++] & 0xFF;
        b.udl = udl;
        int avail = t.length - i;
        boolean octets = b.encoding != Encoding.GSM7 || b.compressed;
        // TS 23.040 septets for uncompressed GSM 7-bit, octets for everything else. A septet UDL also
        // counts the UDH and its fill bits, so a correct UDH always fits inside the UDL.
        int need = octets ? udl : (udl * 7 + 7) / 8;
        boolean udlLong = need > avail;
        if (udlLong) {
            b.flag(AnomalyCode.UDL_LONG);
        } else if (avail > need) {
            b.flag(AnomalyCode.UDL_SHORT);
        }
        // TS 23.040 at most 140 octets or 160 septets. No radio SMS can carry more (a bare IMS TPDU can),
        // so a longer TP-UDL is malformed even when the data is present. The user data is still read.
        if (udl > (octets ? MAX_UD_OCTETS : MAX_UD_SEPTETS)) b.flag(AnomalyCode.UDL_LONG);
        int udOctets = Math.min(need, avail);
        b.userData = Arrays.copyOfRange(t, i, i + udOctets);
        boolean udhCut = false;
        if (b.udhi) {
            if (udOctets < 1 || 1 + (t[i] & 0xFF) > udOctets) {
                udhCut = true;
                if (!udlLong) b.flag(AnomalyCode.UDH_LEN);
            }
            if (udOctets > 0) b.udh = UdhParser.parse(b.userData, 0, udOctets);
            if (b.udh != null && !udlLong && !udhCut) b.anomalies |= b.udh.anomalyMask();
        }
        if (!udlLong && !udhCut) inspect(b);
    }

    /** Parses what the user data carries: a SIM OTA packet header, or a single-part WAP push and its WBXML body. */
    private static void inspect(TpduFacts.Builder b) {
        UdhFacts u = b.udh;
        if (u == null) return;
        byte[] ud = b.userData;
        int bodyOff = Math.min(ud.length, u.totalOctets());
        int bodyLen = ud.length - bodyOff;
        boolean octets = b.encoding != Encoding.GSM7 || b.compressed;
        boolean multipart = u.concatRef() >= 0 && u.concatTotal() > 1;
        // Step 1: a secured packet goes to the SIM with PID 0x7F (SIM data download) or in a class 2 message.
        boolean forSim = b.protocolId == TpduFacts.PID_USIM_DATA_DOWNLOAD || b.dcsClass == 2;
        // TS 31.115 in a concatenated packet only the first segment has the command packet identifier and the
        // header; later segments carry secured data only.
        boolean firstSegment = !multipart || u.concatSeq() == 1;
        if ((u.toolkitCommand() || u.toolkitResponse()) && octets && forSim && firstSegment) {
            b.ota = OtaParser.parse(ud, bodyOff, bodyLen, !u.toolkitCommand(), multipart);
            b.anomalies |= b.ota.anomalyMask();
        }
        // The framework (InboundSmsHandler) gives the user data after the UDH of a port 2948 message to
        // WapPushOverSms whatever the DCS, so the push checks run for every encoding. A later segment of a
        // concatenated push has no WSP header; the reassembled push is parsed by the WAP hook.
        if (u.destPort() == WAP_PUSH_PORT && !multipart) {
            WspFacts w = WspParser.parse(ud, bodyOff, bodyLen);
            b.wsp = w;
            b.anomalies |= w.anomalyMask();
            b.wbxml = WbxmlScanner.scanPushBody(w, ud, bodyOff);
            if (b.wbxml != null) b.anomalies |= b.wbxml.anomalyMask();
        }
    }

    /**
     * Octets occupied by the digits of an address field (TS 23.040): {@code ceil(length/2)}. The length
     * octet counts semi-octets for numeric and alphanumeric (TOA 0xD0) addresses alike, which is also how the
     * framework's {@code GsmSmsAddress} skips the field. {@code toa} does not change the result; it stays in the
     * signature for the payload's {@code PduFacts} API.
     */
    public static int addressOctets(int length, int toa) {
        return (length + 1) / 2;
    }

    /** TS 23.040 bit 7 of the TOA is always 1, and type of number 111 is reserved for extension. */
    static boolean reservedToa(int toa) {
        return (toa & 0x80) == 0 || (toa & 0x70) == 0x70;
    }

    /**
     * Reserved TP-PID values of TS 23.040 the telematic codes 0x2E, 0x2F and 0x33 to 0x37, the short message
     * types 0x49 to 0x5D and 0x60 to 0x7B, and the whole 0x80 to 0xBF range.
     */
    static boolean reservedPid(int pid) {
        if ((pid & 0xC0) == 0x80) return true;
        if (pid == 0x2E || pid == 0x2F || (pid >= 0x33 && pid <= 0x37)) return true;
        return (pid >= 0x49 && pid <= 0x5D) || (pid >= 0x60 && pid <= 0x7B);
    }

    private static int skip(byte[] t, int i, int n) {
        return Math.min(t.length, i + n);
    }

    /**
     * The SMSC address of a radio PDU, or null when there is none (length 0 or 1, more than 11 octets, or no TPDU
     * after it). The length octet counts the TOA and the digit octets; the 0xF filler of an odd digit count is skipped
     * by {@link #decodeAddress}.
     */
    private static String decodeSmsc(byte[] pdu) {
        if (pdu.length < 2) return null;
        int len = pdu[0] & 0xFF;
        if (len < 2 || len > MAX_SMSC_OCTETS || 1 + len >= pdu.length) return null;
        String smsc = decodeAddress(pdu, 2, (len - 1) * 2, pdu[1] & 0xFF);
        return smsc.isEmpty() ? null : smsc;
    }

    /**
     * Decodes a semi-octet or alphanumeric (type of number 101, GSM 7-bit packed) address, as the framework's
     * {@code GsmSmsAddress} does. Reads only inside {@code t}; a length above 255 is cut to 255. Never throws.
     *
     * <p>As in the framework, the digit count is not cut to {@code length}: for an odd length, a last high nibble that
     * is not the 0xF filler adds one digit.
     */
    public static String decodeAddress(byte[] t, int offset, int length, int toa) {
        if (length <= 0 || t == null || offset < 0 || offset >= t.length) return "";
        int n = Math.min(length, MAX_SEMI_OCTETS);
        int ton = (toa >> 4) & 0x07;
        if (ton == TON_ALPHANUMERIC) return unpackGsm7(t, offset, n * 4 / 7);
        int octets = Math.min((n + 1) / 2, t.length - offset);
        StringBuilder sb = new StringBuilder(octets * 2 + 1);
        if (ton == TON_INTERNATIONAL) sb.append('+');
        for (int k = 0; k < octets; k++) {
            int b = t[offset + k] & 0xFF;
            int lo = b & 0x0F;
            int hi = (b >> 4) & 0x0F;
            if (lo != 0x0F) sb.append(BCD_DIGITS.charAt(lo));
            if (hi != 0x0F) sb.append(BCD_DIGITS.charAt(hi));
        }
        return sb.toString();
    }

    /**
     * Unpacks GSM 7-bit septets with the default alphabet and its extension table, as the framework's
     * {@code GsmAlphabet.gsm7BitPackedToString} does: an escape followed by a code that the extension table does not
     * have gives the default character, and two escapes give a space.
     */
    private static String unpackGsm7(byte[] t, int offset, int septets) {
        StringBuilder sb = new StringBuilder(septets);
        int bits = 0;
        int acc = 0;
        int n = 0;
        boolean escape = false;
        for (int k = 0; k < (septets * 7 + 7) / 8 && offset + k < t.length; k++) {
            acc |= (t[offset + k] & 0xFF) << bits;
            bits += 8;
            while (bits >= 7 && n < septets) {
                int c = acc & 0x7F;
                acc >>>= 7;
                bits -= 7;
                n++;
                if (escape) {
                    escape = false;
                    sb.append(c == GSM7_ESCAPE ? ' ' : gsm7Extension(c));
                } else if (c == GSM7_ESCAPE) {
                    escape = true;
                } else {
                    sb.append(gsm7Char(c));
                }
            }
        }
        return sb.toString();
    }

    /** GSM 7-bit extension table (TS 23.038); a code without an entry gives the default character. */
    private static char gsm7Extension(int c) {
        return switch (c) {
            case 0x0A -> '\f';
            case 0x14 -> '^';
            case 0x28 -> '{';
            case 0x29 -> '}';
            case 0x2F -> '\\';
            case 0x3C -> '[';
            case 0x3D -> '~';
            case 0x3E -> ']';
            case 0x40 -> '|';
            case 0x65 -> '\u20AC';
            default -> gsm7Char(c);
        };
    }

    /**
     * GSM 03.38 default alphabet as a lookup string: index = septet, {@code '?'} = no character.
     * One constant instead of a 50-branch switch (dex budget).
     */
    private static final String GSM7 =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u0000ÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

    private static char gsm7Char(int c) {
        if (c < 0 || c >= GSM7.length()) return '?';
        char ch = GSM7.charAt(c);
        return ch == 0 ? '?' : ch;
    }

    /**
     * TP-DCS (TS 23.038). Records the alphabet, class, compression and message waiting facts, and flags
     * {@code AN-DCS-RSV} for the reserved groups 0x80 to 0xBF, the reserved alphabet 11, bit 3 of group 0xF0 and
     * bit 2 of the message waiting groups 0xC0 to 0xEF.
     */
    private static void decodeDcs(TpduFacts.Builder b) {
        int dcs = b.dcs;
        int group = dcs & 0xF0;
        if (group == 0xF0) {
            // Data coding / message class: bit 2 is the alphabet (GSM 7-bit or 8-bit), bit 3 is reserved.
            b.encoding = (dcs & 0x04) != 0 ? Encoding.EIGHT_BIT : Encoding.GSM7;
            b.dcsClass = dcs & 0x03;
            if ((dcs & 0x08) != 0) b.flag(AnomalyCode.DCS_RSV);
        } else if (group == 0xC0 || group == 0xD0 || group == 0xE0) {
            // TS 23.038 bit 3 = indication active, bits 1..0 == 0 = voicemail (else fax/email/other).
            b.messageWaitingGroup = true;
            b.mwiDontStore = group == 0xC0;
            b.encoding = group == 0xE0 ? Encoding.UCS2 : Encoding.GSM7;
            boolean active = (dcs & 0x08) != 0;
            boolean voicemail = (dcs & 0x03) == 0;
            // TS 23.038 bit 2 of these groups is reserved and set to 0.
            if ((dcs & 0x04) != 0) b.flag(AnomalyCode.DCS_RSV);
            b.mwiActive = active;
            b.mwiType = dcs & 0x03;
            b.mwiSet = voicemail && active;
            b.mwiClear = voicemail && !active;
        } else if ((dcs & 0x80) == 0) {
            // General data coding (00xx) and the same coding marked for automatic deletion (01xx).
            int alphabet = (dcs >> 2) & 0x03;
            b.encoding = alphabet(alphabet);
            if (alphabet == 3) b.flag(AnomalyCode.DCS_RSV);
            if ((dcs & 0x10) != 0) b.dcsClass = dcs & 0x03;
            b.compressed = (dcs & 0x20) != 0;
        } else {
            // 1000..1011: reserved coding groups.
            b.encoding = Encoding.GSM7;
            b.flag(AnomalyCode.DCS_RSV);
        }
    }

    private static Encoding alphabet(int a) {
        return switch (a) {
            case 0 -> Encoding.GSM7;
            case 1 -> Encoding.EIGHT_BIT;
            case 2 -> Encoding.UCS2;
            default -> Encoding.UNKNOWN;
        };
    }

    /**
     * SCTS: swapped semi-octets, local time with a quarter-hour offset. The offset sign is bit 3
     * of the timezone octet (TS 23.040); out-of-range BCD digits read as 0.
     */
    public static long decodeScts(byte[] t, int offset) {
        if (t == null || offset < 0 || offset > t.length - 7) return 0L;
        int year = bcd(t[offset]);
        int month = bcd(t[offset + 1]);
        int day = bcd(t[offset + 2]);
        int hour = bcd(t[offset + 3]);
        int minute = bcd(t[offset + 4]);
        int second = bcd(t[offset + 5]);
        int tz = t[offset + 6] & 0xFF;
        int quarterHours = bcd((byte) (tz & ~0x08));
        if ((tz & 0x08) != 0) quarterHours = -quarterHours;
        if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 59) return 0L;
        try {
            int fullYear = year >= 90 ? year + 1900 : year + 2000;
            return LocalDateTime.of(fullYear, month, day, hour, minute, second)
                    .toEpochSecond(ZoneOffset.UTC) * 1000L - quarterHours * 15L * 60L * 1000L;
        } catch (RuntimeException e) {
            // 31 February and the like: the field ranges above cannot see month lengths.
            return 0L;
        }
    }

    /** Swapped-nibble BCD digit pair; an out-of-range nibble reads as 0. */
    private static int bcd(byte b) {
        int v = 0;
        if (((b >> 4) & 0x0F) <= 9) v = (b >> 4) & 0x0F;
        if ((b & 0x0F) <= 9) v += (b & 0x0F) * 10;
        return v;
    }
}
