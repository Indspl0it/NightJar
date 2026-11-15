package dev.nightjar.pduparse;

import dev.nightjar.contract.AnomalyCode;

/**
 * Total parser for the header of a SIM OTA secured packet in an SMS (3GPP TS 31.115, ETSI TS 102 225).
 *
 * <pre>
 * command:  CPL(2) CHL(1) SPI(2) KIc(1) KID(1) TAR(3) CNTR(5) PCNTR(1) [RC/CC/DS] [secured data]
 * response: RPL(2) RHL(1) TAR(3) CNTR(5) PCNTR(1) status(1) [RC/CC/DS] [data]
 * </pre>
 *
 * <p>{@code AN-OTA-HDR} when the header is cut or its lengths do not agree: CHL below 13 (RHL below 10), a header
 * longer than the packet, a header past the data, or a packet length that is not the data length. In the first
 * segment of a concatenated packet the data continues in the next segments, so the packet length can be larger.
 *
 * <p>For a whole command packet without ciphering and without a header anomaly, the parser also checks whether the
 * secured data has the shape of an S@T push or a WIB command (step 4). It reads only the first octets and keeps
 * only the two flags. It never decrypts.
 *
 * <p>Thread: any.
 */
public final class OtaParser {
    /** SPI(2) + KIc + KID + TAR(3) + CNTR(5) + PCNTR. */
    static final int MIN_COMMAND_HEADER = 13;
    /** TAR(3) + CNTR(5) + PCNTR + status. */
    static final int MIN_RESPONSE_HEADER = 10;
    /** SIMalliance S@T 01.23 tag of the Push command. */
    static final int SAT_PUSH_TAG = 0x42;
    /** First octet after the length of a WIB command in the public samples (see {@link OtaFacts#wibCommand}). */
    static final int WIB_COMMAND_FIRST = 0x10;

    private OtaParser() {}

    /**
     * Parses the packet at {@code b[off..off+len)}: the user data after the UDH.
     *
     * @param response true for a response packet (IE 0x71)
     * @param multipart true when the SMS is one segment of a concatenated packet
     */
    public static OtaFacts parse(byte[] b, int off, int len, boolean response, boolean multipart) {
        try {
            return parseUnguarded(b, off, len, response, multipart);
        } catch (RuntimeException e) {
            // Unreachable by design (FuzzTest proves it); fail open.
            OtaFacts f = new OtaFacts();
            f.response = response;
            f.anomalies = Anomalies.with(Anomalies.NONE, AnomalyCode.OTA_HDR);
            return f;
        }
    }

    /** Parses a whole command packet that is not split over several SMS. */
    public static OtaFacts parseCommand(byte[] b) {
        return parse(b, 0, b == null ? 0 : b.length, false, false);
    }

    static OtaFacts parseUnguarded(byte[] b, int off, int len, boolean response, boolean multipart) {
        OtaFacts f = new OtaFacts();
        f.response = response;
        if (b == null || off < 0 || off > b.length || len < 0) len = 0;
        int n = b == null ? 0 : (int) Math.min(len, (long) b.length - off);
        if (n < 3) {
            f.anomalies = Anomalies.with(Anomalies.NONE, AnomalyCode.OTA_HDR);
            return f;
        }
        int pl = ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
        int hl = b[off + 2] & 0xFF;
        f.packetLength = pl;
        f.headerLength = hl;
        boolean lengthOk = multipart ? pl >= n - 2 : pl == n - 2;
        int min = response ? MIN_RESPONSE_HEADER : MIN_COMMAND_HEADER;
        if (!lengthOk || hl < min || hl > pl - 1 || 3 + hl > n) {
            f.anomalies = Anomalies.with(Anomalies.NONE, AnomalyCode.OTA_HDR);
        }
        int p = off + 3;
        int end = off + n;
        if (!response) {
            if (p + 2 > end) return f;
            f.spi = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
            p += 2;
            if (p + 2 > end) return f;
            f.kic = b[p] & 0xFF;
            f.kid = b[p + 1] & 0xFF;
            p += 2;
        }
        if (p + 3 > end) return f;
        f.tar = ((b[p] & 0xFF) << 16) | ((b[p + 1] & 0xFF) << 8) | (b[p + 2] & 0xFF);
        p += 3;
        if (p + 5 > end) return f;
        long counter = 0;
        for (int k = 0; k < 5; k++) counter = (counter << 8) | (b[p + k] & 0xFF);
        f.counter = counter;
        p += 5;
        if (p + 1 > end) return f;
        f.paddingCounter = b[p++] & 0xFF;
        if (response && p + 1 <= end) f.status = b[p] & 0xFF;
        if (!response && !multipart && f.anomalies == Anomalies.NONE && !f.ciphered()) {
            // Step 4: only the shape of the first octets is read. The secured data is not kept.
            int data = off + 3 + hl;
            f.satPush = satPushShape(b, data, end);
            f.wibCommand = wibCommandShape(b, data, end);
        }
        return f;
    }

    /** SIMalliance S@T 01.23 Push tag 0x42, then a BER-TLV length (1 to 3 octets) up to the packet end. */
    static boolean satPushShape(byte[] b, int p, int end) {
        if (p + 2 > end || (b[p] & 0xFF) != SAT_PUSH_TAG) return false;
        int first = b[p + 1] & 0xFF;
        int len;
        int lenOctets;
        if (first < 0x80) {
            len = first;
            lenOctets = 1;
        } else if (first == 0x81 && p + 3 <= end) {
            len = b[p + 2] & 0xFF;
            lenOctets = 2;
        } else if (first == 0x82 && p + 4 <= end) {
            len = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
            lenOctets = 3;
        } else {
            return false;
        }
        return len > 0 && p + 1 + lenOctets + len == end;
    }

    /** WIB command shape: a two-octet length up to the packet end, then 0x10 (see {@link OtaFacts#wibCommand}). */
    static boolean wibCommandShape(byte[] b, int p, int end) {
        if (p + 3 > end) return false;
        int len = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
        return len == end - p - 2 && (b[p + 2] & 0xFF) == WIB_COMMAND_FIRST;
    }
}
