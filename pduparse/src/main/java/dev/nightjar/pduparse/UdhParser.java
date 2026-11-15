package dev.nightjar.pduparse;

import dev.nightjar.contract.AnomalyCode;

/**
 * Total parser for a User Data Header (3GPP TS 23.040). It never throws and never reads past the
 * region it is given.
 *
 * <p>Two walks over the same header:
 *
 * <ul>
 *   <li>The framework walk gives the facts. It follows AOSP {@code SmsHeader.fromByteArray}, because Nightjar reports
 *  what Android acts on: for IE 0x00, 0x01, 0x04, 0x05, 0x08, 0x24 and 0x25 it reads a fixed number of
 *       octets and ignores the IEDL; for every other IE it skips IEDL octets. A read past the header gives −1, as
 *       {@code ByteArrayInputStream.read()} does.
 *   <li>The spec walk follows the IEDL of each element and gives the anomalies.
 * </ul>
 *
 * <p>Anomalies: {@code AN-UDH-LEN} when UDHL runs past the region; when the header is complete, also
 * {@code AN-IE-OVERRUN} (an element runs past the header in either walk), {@code AN-IE-RSV}, {@code AN-IE-DUP} (two
 * port or two concatenation elements, or an element whose IEDL is not its fixed length: a port IE that
 * conflicts), {@code AN-PORT-ZERO} and {@code AN-CONCAT-BAD} (total 0, sequence 0, sequence above total, or an IEDL
 * that is not 3 or 4). With a wrong IEDL the two walks read different headers; the anomaly says so and the facts stay
 * the framework's. An incomplete header reports only {@code AN-UDH-LEN}: the elements of a cut header say nothing more.
 *
 * <p>Thread: any.
 */
public final class UdhParser {
    static final int IEI_CONCAT_8 = 0x00;
    static final int IEI_SPECIAL_SMS = 0x01;
    static final int IEI_PORT_8 = 0x04;
    static final int IEI_PORT_16 = 0x05;
    static final int IEI_CONCAT_16 = 0x08;
    static final int IEI_ENHANCED_VOICEMAIL = 0x23;
    static final int IEI_SINGLE_SHIFT = 0x24;
    static final int IEI_LOCKING_SHIFT = 0x25;
    static final int IEI_COMMAND_PACKET = 0x70;
    static final int IEI_RESPONSE_PACKET = 0x71;

    private UdhParser() {}

    /** Parses a header that starts with its UDHL octet at {@code ud[0]} and may run to the end of the array. */
    public static UdhFacts parse(byte[] ud) {
        return parse(ud, 0, ud == null ? 0 : ud.length);
    }

    /** Parses the header at {@code ud[off]} (the UDHL octet), reading at most {@code len} octets. Never throws. */
    public static UdhFacts parse(byte[] ud, int off, int len) {
        try {
            return parseUnguarded(ud, off, len);
        } catch (RuntimeException e) {
            // Unreachable by design (FuzzTest proves it); fail open with an empty header.
            UdhFacts f = new UdhFacts();
            f.anomalies = Anomalies.with(Anomalies.NONE, AnomalyCode.UDH_LEN);
            return f;
        }
    }

    static UdhFacts parseUnguarded(byte[] ud, int off, int len) {
        UdhFacts f = new UdhFacts();
        if (ud == null || off < 0 || len < 1 || off >= ud.length) {
            f.anomalies = Anomalies.with(f.anomalies, AnomalyCode.UDH_LEN);
            return f;
        }
        int limit = Math.min(len, ud.length - off);
        int udhl = ud[off] & 0xFF;
        f.headerLength = udhl;
        f.complete = 1 + udhl <= limit;
        int start = off + 1;
        int end = off + Math.min(limit, 1 + udhl);

        int mask = specWalk(f, ud, start, end);
        boolean overrun = frameworkWalk(f, ud, start, end);
        if (overrun) mask = Anomalies.with(mask, AnomalyCode.IE_OVERRUN);
        if (f.frameworkPort && f.destPort == 0) mask = Anomalies.with(mask, AnomalyCode.PORT_ZERO);
        f.anomalies = f.complete ? mask : Anomalies.with(Anomalies.NONE, AnomalyCode.UDH_LEN);
        return f;
    }

    /** The IEDL walk over {@code ud[start..end)}: returns the anomaly mask and counts the port elements. */
    private static int specWalk(UdhFacts f, byte[] ud, int start, int end) {
        int mask = Anomalies.NONE;
        int concatIes = 0;
        int p = start;
        while (p < end) {
            if (p + 2 > end) {
                // An IEI with no length octet.
                mask = Anomalies.with(mask, AnomalyCode.IE_OVERRUN);
                break;
            }
            int iei = ud[p] & 0xFF;
            int iedl = ud[p + 1] & 0xFF;
            int data = p + 2;
            if (data + iedl > end) {
                mask = Anomalies.with(mask, AnomalyCode.IE_OVERRUN);
                break;
            }
            if (reservedIei(iei)) mask = Anomalies.with(mask, AnomalyCode.IE_RSV);
            switch (iei) {
                case IEI_CONCAT_8 -> {
                    concatIes++;
                    if (iedl != 3 || !validConcat(ud[data + 1] & 0xFF, ud[data + 2] & 0xFF)) {
                        mask = Anomalies.with(mask, AnomalyCode.CONCAT_BAD);
                    }
                }
                case IEI_CONCAT_16 -> {
                    concatIes++;
                    if (iedl != 4 || !validConcat(ud[data + 2] & 0xFF, ud[data + 3] & 0xFF)) {
                        mask = Anomalies.with(mask, AnomalyCode.CONCAT_BAD);
                    }
                }
                case IEI_PORT_8 -> {
                    f.portIeCount++;
                    mask = fixedLength(mask, iedl, 2);
                }
                case IEI_PORT_16 -> {
                    f.portIeCount++;
                    mask = fixedLength(mask, iedl, 4);
                }
                case IEI_SPECIAL_SMS -> mask = fixedLength(mask, iedl, 2);
                case IEI_SINGLE_SHIFT, IEI_LOCKING_SHIFT -> mask = fixedLength(mask, iedl, 1);
                // TS 31.115 the command and response packet identifiers have no data.
                case IEI_COMMAND_PACKET, IEI_RESPONSE_PACKET -> mask = fixedLength(mask, iedl, 0);
                default -> {
                    // Other elements carry no fact the classifiers use.
                }
            }
            p = data + iedl;
        }
        if (f.portIeCount > 1 || concatIes > 1) mask = Anomalies.with(mask, AnomalyCode.IE_DUP);
        return mask;
    }

    /** {@code AN-IE-DUP} ("conflicts") when an element with a fixed length has another IEDL. */
    private static int fixedLength(int mask, int iedl, int fixed) {
        return iedl == fixed ? mask : Anomalies.with(mask, AnomalyCode.IE_DUP);
    }

    /** The framework accepts a concatenation element only with total and sequence not 0 and sequence up to total. */
    private static boolean validConcat(int total, int seq) {
        return total != 0 && seq != 0 && seq <= total;
    }

    /**
     * The framework walk over {@code ud[start..end)} (AOSP {@code SmsHeader.fromByteArray}): records the facts and
     * returns true when it reads past the header end, or meets an element that makes the framework throw.
     */
    private static boolean frameworkWalk(UdhFacts f, byte[] ud, int start, int end) {
        int p = start;
        while (p < end) {
            int id = ud[p++] & 0xFF;
            int length = read(ud, p++, end);
            switch (id) {
                case IEI_CONCAT_8 -> {
                    frameworkConcat(f, read(ud, p, end), read(ud, p + 1, end), read(ud, p + 2, end), false);
                    p += 3;
                }
                case IEI_CONCAT_16 -> {
                    int ref = (read(ud, p, end) << 8) | read(ud, p + 1, end);
                    frameworkConcat(f, ref, read(ud, p + 2, end), read(ud, p + 3, end), true);
                    p += 4;
                }
                case IEI_PORT_8 -> {
                    f.destPort = read(ud, p, end);
                    f.origPort = read(ud, p + 1, end);
                    f.port8Bit = true;
                    f.frameworkPort = true;
                    p += 2;
                }
                case IEI_PORT_16 -> {
                    f.destPort = (read(ud, p, end) << 8) | read(ud, p + 1, end);
                    f.origPort = (read(ud, p + 2, end) << 8) | read(ud, p + 3, end);
                    f.port8Bit = false;
                    f.frameworkPort = true;
                    p += 4;
                }
                case IEI_SINGLE_SHIFT -> f.singleShift = read(ud, p++, end);
                case IEI_LOCKING_SHIFT -> f.lockingShift = read(ud, p++, end);
                case IEI_SPECIAL_SMS -> {
                    f.specialSms = true;
                    f.specialSmsType = read(ud, p, end);
                    f.specialSmsCount = read(ud, p + 1, end);
                    p += 2;
                }
                default -> {
                    // new byte[length] with a length of -1 throws in the framework: Android drops the message.
                    if (length < 0) return true;
                    if (id == IEI_ENHANCED_VOICEMAIL) f.enhancedVoicemail = true;
                    if (id == IEI_COMMAND_PACKET) f.toolkitCommand = true;
                    if (id == IEI_RESPONSE_PACKET) f.toolkitResponse = true;
                    // read(data, 0, length) stops at the end of the header without an error; the element still
                    // runs past the header, which the loop end reports.
                    p += length;
                }
            }
        }
        return p > end;
    }

    /** {@code ud[p]} as an unsigned octet, or −1 past {@code end} (as {@code ByteArrayInputStream.read()}). */
    private static int read(byte[] ud, int p, int end) {
        return p < end ? ud[p] & 0xFF : -1;
    }

    /** A later valid concatenation element replaces an earlier one; an invalid one is ignored (framework rule). */
    private static void frameworkConcat(UdhFacts f, int ref, int total, int seq, boolean wide) {
        if (total == 0 || seq == 0 || seq > total) return;
        f.concatRef = ref;
        f.concatTotal = total;
        f.concatSeq = seq;
        f.concat16Bit = wide;
    }

    /**
     * Reserved IEI values of TS 23.040 0x02, 0x03 (not used, it reads as LF), 0x1B to 0x1F, 0x26 to 0x6F,
     * 0xA0 to 0xBF and 0xE0 to 0xFF.
     */
    static boolean reservedIei(int iei) {
        return iei == 0x02 || iei == 0x03
                || (iei >= 0x1B && iei <= 0x1F)
                || (iei >= 0x26 && iei <= 0x6F)
                || (iei >= 0xA0 && iei <= 0xBF)
                || iei >= 0xE0;
    }
}
