package dev.nightjar.payload.testing;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Builds SMS TPDUs for tests (TS 23.040). Only what the payload reads is populated.
 *
 * <pre>
 * deliver(): [smsc][0x04][OA][PID][DCS][SCTS 7][UDL][UD]
 * </pre>
 *
 * <p>An SMS-DELIVER without {@link Builder#ud} carries the synthetic text "Test", so the default message is CL-NORMAL
 * and not CL-EMPTY. TP-UDL counts septets when the parser reads the DCS as GSM 7-bit and octets otherwise
 * (TS 23.040), so a built PDU has no length anomaly.
 */
public final class Pdu {
    private Pdu() {}

    /** Builder for one SMS-DELIVER. */
    public static final class Builder {
        private String smsc = "+919000000000";
        private String originator = "+919870000210";
        private int pid;
        private int dcs = 0x00;
        private int[] scts = {26, 11, 5, 12, 0, 0, 22}; // yy MM dd HH mm ss tz(quarter hours)
        private byte[] ud;
        private byte[] udh;
        private boolean statusReport;
        private boolean srPdu = true;
        private int status;

        public Builder smsc(String v) {
            smsc = v;
            return this;
        }

        public Builder originator(String v) {
            originator = v;
            return this;
        }

        public Builder pid(int v) {
            pid = v;
            return this;
        }

        public Builder dcs(int v) {
            dcs = v;
            return this;
        }

        public Builder ud(byte[] v) {
            ud = v;
            return this;
        }

        /** Concatenation IE (0x00) plus a 16-bit port IE with the given dest and orig ports. */
        public Builder udh(int concatRef, int total, int seq, int destPort, int origPort) {
            byte[] concat = {(byte) 0x00, 3, (byte) concatRef, (byte) total, (byte) seq};
            byte[] port = destPort >= 0
                    ? new byte[] {0x05, 4,
                        (byte) (destPort >> 8), (byte) destPort,
                        (byte) (origPort >> 8), (byte) origPort}
                    : null;
            return udh(concat, port);
        }

        /** Concatenation IE (0x00) plus a 16-bit port IE; the originator port is distinct. */
        public Builder udh(int concatRef, int total, int seq, int destPort) {
            return udh(concatRef, total, seq, destPort, 9200);
        }

        /** Concatenation IE (0x00) plus an 8-bit port IE (IE 4). */
        public Builder udh8(int concatRef, int total, int seq, int destPort, int origPort) {
            byte[] concat = {(byte) 0x00, 3, (byte) concatRef, (byte) total, (byte) seq};
            byte[] port = destPort >= 0
                    ? new byte[] {0x04, 2, (byte) destPort, (byte) origPort}
                    : null;
            return udh(concat, port);
        }

        private Builder udh(byte[] concat, byte[] port) {
            int len = concat.length + (port == null ? 0 : port.length);
            udh = new byte[1 + len];
            udh[0] = (byte) len;
            System.arraycopy(concat, 0, udh, 1, concat.length);
            if (port != null) System.arraycopy(port, 0, udh, 1 + concat.length, port.length);
            return this;
        }

        public Builder udhBytes(byte[] v) {
            udh = v;
            return this;
        }

        public Builder statusReport() {
            statusReport = true;
            return this;
        }

        /** TP-ST of a status report (TS 23.040); 0x00 by default. */
        public Builder status(int v) {
            status = v;
            return this;
        }

        /** Status report without the optional TP-PI/TP-UD tail (what most carriers send). */
        public Builder statusReportNoPdu() {
            statusReport = true;
            srPdu = false;
            return this;
        }

        public byte[] build() {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            writeSmsc(out, smsc);
            int fo = statusReport ? 0x02 : 0x04;
            if (udh != null) fo |= 0x40;
            out.write(fo);
            if (statusReport) {
                out.write(0x00); // message reference
                writeAddress(out, originator);
                writeScts(out, scts);
                writeScts(out, scts);
                out.write(status);
                if (!srPdu) return out.toByteArray();
            } else {
                writeAddress(out, originator);
                out.write(pid);
                out.write(dcs);
                writeScts(out, scts);
            }
            byte[] data = ud != null ? ud : statusReport ? new byte[0] : DEFAULT_TEXT;
            byte[] body = udh == null ? data : concat(udh, data);
            // floor(8n/7) septets need exactly n octets: ceil(floor(8n/7) * 7 / 8) == n.
            int udl = septets(dcs) ? body.length * 8 / 7 : body.length;
            out.write(udl);
            out.write(body, 0, body.length);
            return out.toByteArray();
        }

        private static byte[] concat(byte[] a, byte[] b) {
            byte[] out = Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, out, a.length, b.length);
            return out;
        }
    }

    /** "Test" as packed GSM 7-bit septets. */
    private static final byte[] DEFAULT_TEXT = packGsm7("Test");

    /**
     * True when the parser reads the user data as GSM 7-bit septets: the same DCS groups as {@code TpduParser}
     * (TS 23.038). Compressed text and the 8-bit and UCS-2 alphabets count octets.
     */
    static boolean septets(int dcs) {
        int group = dcs & 0xF0;
        if (group == 0xF0) return (dcs & 0x04) == 0;
        if (group == 0xC0 || group == 0xD0) return true;
        if (group == 0xE0) return false;
        if ((dcs & 0x80) == 0) return ((dcs >> 2) & 0x03) == 0 && (dcs & 0x20) == 0;
        return true; // reserved groups 0x80 to 0xBF: the parser reads them as GSM 7-bit
    }

    public static Builder deliver() {
        return new Builder();
    }

    /** A bare TPDU (no SMSC prefix), as IMS delivers it. */
    public static byte[] bare(byte[] radioPdu) {
        return dev.nightjar.contract.Hashes.stripSmsc(radioPdu);
    }

    private static void writeSmsc(java.io.ByteArrayOutputStream out, String smsc) {
        if (smsc == null || smsc.isEmpty()) {
            out.write(0x00);
            return;
        }
        boolean plus = smsc.startsWith("+");
        String digits = plus ? smsc.substring(1) : smsc;
        out.write((digits.length() + 1) / 2 + 1);
        out.write(plus ? 0x91 : 0x81);
        writeDigits(out, digits);
    }

    private static void writeAddress(java.io.ByteArrayOutputStream out, String address) {
        if (address != null && !address.isEmpty() && Character.isLetter(address.charAt(0))) {
            // Alphanumeric TP-OA: the length octet counts semi-octets, i.e. 7 bits per character
            // (TS 23.040).
            byte[] packed = packGsm7(address);
            out.write((address.length() * 7 + 3) / 4);
            out.write(0xD0);
            out.write(packed, 0, packed.length);
            return;
        }
        boolean plus = address != null && address.startsWith("+");
        String digits = plus ? address.substring(1) : address;
        out.write(digits.length());
        out.write(plus ? 0x91 : 0x81);
        writeDigits(out, digits);
    }

    private static void writeDigits(java.io.ByteArrayOutputStream out, String digits) {
        for (int i = 0; i < digits.length(); i += 2) {
            int lo = digits.charAt(i) - '0';
            int hi = i + 1 < digits.length() ? digits.charAt(i + 1) - '0' : 0x0F;
            out.write((hi << 4) | lo);
        }
    }

    private static void writeScts(java.io.ByteArrayOutputStream out, int[] v) {
        for (int x : v) out.write(((x % 10) << 4) | (x / 10));
    }

    private static byte[] packGsm7(String text) {
        byte[] src = text.getBytes(StandardCharsets.US_ASCII);
        int outLen = (src.length * 7 + 7) / 8;
        byte[] out = new byte[outLen];
        int bits = 0;
        int acc = 0;
        int o = 0;
        for (byte b : src) {
            acc |= (b & 0x7F) << bits;
            bits += 7;
            while (bits >= 8) {
                out[o++] = (byte) (acc & 0xFF);
                acc >>>= 8;
                bits -= 8;
            }
        }
        if (bits > 0 && o < out.length) out[o] = (byte) (acc & 0xFF);
        return out;
    }
}
