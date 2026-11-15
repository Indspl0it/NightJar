package dev.nightjar.payload.inject;

import dev.nightjar.contract.ClassId;
import dev.nightjar.payload.capture.WapContentType;
import java.util.Arrays;
import java.util.Locale;

/**
 * Builds the Appendix F canary TPDUs.
 *
 * <p>Every canary is an SMS-DELIVER from the alphanumeric originator {@code NJSELFTEST} with SMSC {@code +00000000000}
 * and an 8-byte random nonce in the user data. The text never starts with the VVM prefix {@code //}.
 *
 * <p>The PDU is a complete radio PDU (SMSC prefix included) so it can be handed to
 * {@code SmsMessage.createFromPdu(pdu, "3gpp")} and to HK-R1 unchanged.
 *
 * <p>Thread: any (no shared state).
 */
public final class CanaryFactory {

    /** Originator of every canary (Appendix F). */
    public static final String ORIGINATOR = "NJSELFTEST";

    /** SMSC of every canary (Appendix F). */
    public static final String SMSC = "+00000000000";

    /** Nonce length (Appendix F). */
    public static final int NONCE_BYTES = 8;

    /** Destination port of the data-port canary (Appendix F). */
    public static final int CANARY_PORT = 16999;

    /** WAP push port (TS 23.040 application port addressing). */
    public static final int WAP_PORT = 2948;

    private static final byte[] SMSC_PREFIX = {
        0x07, (byte) 0x91, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xF0,
    };

    /** The eight Appendix F rows, with what they need and where they are intercepted. */
    public enum Canary {
        FLASH("Flash", ClassId.FLASH, "HK-08", Interception.HEAD_FILTER),
        DATA_PORT("Data port", ClassId.DATA_PORT, "HK-08", Interception.HEAD_FILTER),
        WAP_SI("WAP SI", ClassId.WAP_SISL, "HK-14", Interception.WAP),
        WAP_CP("WAP CP", ClassId.WAP_CP, "HK-14", Interception.WAP),
        MWI("MWI", ClassId.MWI, "HK-23", Interception.MWI),
        SILENT("Silent", ClassId.SILENT, "HK-06,HK-07", Interception.NONE),
        SIM_DD("SIM DD", ClassId.SIM_DD, "HK-15", Interception.USIM_DD),
        REPLACE("Replace", ClassId.REPLACE, "HK-08", Interception.HEAD_FILTER);

        private final String label;
        private final ClassId classId;
        private final String requiredHook;
        private final Interception interception;

        Canary(String label, ClassId classId, String requiredHook, Interception interception) {
            this.label = label;
            this.classId = classId;
            this.requiredHook = requiredHook;
            this.interception = interception;
        }

        public String label() {
            return label;
        }

        public ClassId classId() {
            return classId;
        }

        /** Hook(s) that must be LIVE before this canary may be injected (Appendix F). */
        public String requiredHook() {
            return requiredHook;
        }

        public Interception interception() {
            return interception;
        }

        /** Parses a canary name: the enum name in any case, or the class wire name with its {@code CL-} prefix. */
        public static Canary parse(String name) {
            if (name == null) return null;
            String s = name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            if (s.startsWith("CL_")) s = s.substring(3);
            for (Canary c : values()) if (c.name().equals(s)) return c;
            return null;
        }
    }

    /** Where the payload stops a canary (Appendix F "Intercepted at"). */
    public enum Interception {
        /** HK-08 drop through HK-R3. */
        HEAD_FILTER,
        /** HK-14 returns 1. */
        WAP,
        /** HK-15 returns 1. */
        USIM_DD,
        /** HK-23 suppresses the indicator update. */
        MWI,
        /** Android itself discards the message. */
        NONE
    }

    /** One built canary. */
    public record Pdu(Canary canary, byte[] radioPdu, byte[] nonce) {}

    private CanaryFactory() {}

    /** Builds a canary with a fresh random nonce. */
    public static Pdu build(Canary canary) {
        return build(canary, randomNonce());
    }

    /** Builds a canary with the given 8-byte nonce. */
    public static Pdu build(Canary canary, byte[] nonce) {
        if (canary == null) throw new IllegalArgumentException("canary == null");
        byte[] n = nonce == null ? randomNonce() : Arrays.copyOf(nonce, NONCE_BYTES);
        byte[] tpdu;
        switch (canary) {
            case FLASH:
                tpdu = deliver(0x00, 0x10, false, text("NJ ", n));
                break;
            case REPLACE:
                tpdu = deliver(0x41, 0x00, false, text("NJ ", n));
                break;
            case SILENT:
                tpdu = deliver(0x40, 0x00, false, text("NJ ", n));
                break;
            case MWI:
                tpdu = deliver(0x00, 0xC8, false, text("1", n));
                break;
            case SIM_DD:
                tpdu = deliver(0x7F, 0xF6, false, commandPacket(n));
                break;
            case DATA_PORT:
                tpdu = deliver(0x00, 0x04, true, withUdh(ports(CANARY_PORT, CANARY_PORT), n));
                break;
            case WAP_SI:
                tpdu = deliver(0x00, 0x04, true, withUdh(ports(WAP_PORT, WAP_PORT),
                        wapPush(WapContentType.CT_SIC, siWbxml(n))));
                break;
            case WAP_CP:
                tpdu = deliver(0x00, 0x04, true, withUdh(ports(WAP_PORT, WAP_PORT),
                        wapPush(WapContentType.CT_WBXML, cpWbxml(n))));
                break;
            default:
                throw new IllegalArgumentException("unsupported canary");
        }
        byte[] radio = concat(SMSC_PREFIX, tpdu);
        return new Pdu(canary, radio, n);
    }

    /** Random nonce. */
    public static byte[] randomNonce() {
        byte[] n = new byte[NONCE_BYTES];
        new java.security.SecureRandom().nextBytes(n);
        return n;
    }

    // ------------------------------------------------------------------ envelope

    /** User data with its TP-UDL: septets for 7-bit, octets otherwise (TS 23.040). */
    private record Ud(byte[] bytes, int udl) {}

    private static byte[] deliver(int pid, int dcs, boolean udhi, Ud ud) {
        return deliver(pid, dcs, udhi, ud.bytes(), ud.udl());
    }

    private static byte[] deliver(int pid, int dcs, boolean udhi, byte[] ud) {
        return deliver(pid, dcs, udhi, ud, ud.length);
    }

    private static byte[] deliver(int pid, int dcs, boolean udhi, byte[] ud, int udl) {
        byte[] oa = alphaAddress(ORIGINATOR);
        byte[] out = new byte[1 + oa.length + 1 + 1 + 7 + 1 + ud.length];
        int i = 0;
        out[i++] = (byte) (udhi ? 0x44 : 0x04); // SMS-DELIVER, UDHI bit 6
        System.arraycopy(oa, 0, out, i, oa.length);
        i += oa.length;
        out[i++] = (byte) pid;
        out[i++] = (byte) dcs;
        // SCTS: fixed 2026-11-05 00:00:00 +00 — canaries are identified by their token, not by time.
        byte[] scts = {0x62, 0x11, 0x50, 0x00, 0x00, 0x00, 0x00};
        System.arraycopy(scts, 0, out, i, scts.length);
        i += scts.length;
        out[i++] = (byte) udl;
        System.arraycopy(ud, 0, out, i, ud.length);
        return out;
    }

    /** Alphanumeric address: 10 characters packed into 9 octets, address length 18 semi-octets. */
    static byte[] alphaAddress(String value) {
        byte[] septets = toSeptets(value);
        byte[] packed = pack(septets, 0);
        byte[] out = new byte[2 + packed.length];
        out[0] = (byte) (packed.length * 2);
        out[1] = (byte) 0xD0;
        System.arraycopy(packed, 0, out, 2, packed.length);
        return out;
    }

    /** 7-bit user data: "NJ " plus the nonce in upper-case hex, so every character stays in the default alphabet. */
    private static Ud text(String prefix, byte[] nonce) {
        String s = prefix + hex(nonce);
        byte[] septets = toSeptets(s);
        return new Ud(pack(septets, 0), septets.length);
    }

    /** Dummy SIM command packet (never reaches the SIM: HK-15 intercepts it). */
    private static byte[] commandPacket(byte[] nonce) {
        byte[] out = new byte[4 + nonce.length];
        out[0] = (byte) 0x81; // SMS-PP download envelope tag
        out[1] = 0x00;
        out[2] = 0x00;
        out[3] = 0x00;
        System.arraycopy(nonce, 0, out, 4, nonce.length);
        return out;
    }

    /** 16-bit application port addressing IE. */
    static byte[] ports(int destPort, int srcPort) {
        return new byte[] {
            0x05, 0x04,
            (byte) ((destPort >> 8) & 0xFF), (byte) (destPort & 0xFF),
            (byte) ((srcPort >> 8) & 0xFF), (byte) (srcPort & 0xFF),
        };
    }

    /** TP-UD with the UDH length prefix. */
    static byte[] withUdh(byte[] udh, byte[] payload) {
        byte[] out = new byte[1 + udh.length + payload.length];
        out[0] = (byte) udh.length;
        System.arraycopy(udh, 0, out, 1, udh.length);
        System.arraycopy(payload, 0, out, 1 + udh.length, payload.length);
        return out;
    }

    /**
     * WSP Push (WAP-230, as the framework decodes it): transaction id, PDU type 0x06, the
     * header length, the content type as a short-integer (or text) and then the body.
     */
    static byte[] wapPush(String contentType, byte[] body) {
        byte[] ct;
        int code = wellKnownCode(contentType);
        if (code >= 0) {
            ct = new byte[] {(byte) (0x80 | code)};
        } else {
            byte[] text = contentType.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            ct = Arrays.copyOf(text, text.length + 1); // NUL terminator
        }
        int headerLength = ct.length;
        byte[] out = new byte[3 + headerLength + body.length];
        int i = 0;
        out[i++] = 0x01; // transaction id
        out[i++] = 0x06; // Push
        out[i++] = (byte) headerLength;
        System.arraycopy(ct, 0, out, i, ct.length);
        i += ct.length;
        System.arraycopy(body, 0, out, i, body.length);
        return out;
    }

    /** Well-known WSP media code for a content type, −1 when it has none. */
    private static int wellKnownCode(String contentType) {
        if (WapContentType.CT_SIC.equals(contentType)) return 0x2E;
        if (WapContentType.CT_WBXML.equals(contentType)) return 0x36;
        return -1;
    }

    /** Minimal SI WBXML: {@code <si><indication href="NJ<nonce>"/></si>}. */
    static byte[] siWbxml(byte[] nonce) {
        byte[] uri = ("NJ" + hex(nonce)).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] out = new byte[8 + uri.length + 4];
        int i = 0;
        out[i++] = 0x02; // WBXML 1.2
        out[i++] = 0x05; // public id: SI 1.0
        out[i++] = 0x6A; // UTF-8
        out[i++] = 0x00; // no string table
        out[i++] = 0x45; // <si>
        out[i++] = (byte) 0xC6; // <indication>
        out[i++] = 0x0C; // href
        out[i++] = 0x03; // inline string
        System.arraycopy(uri, 0, out, i, uri.length);
        i += uri.length;
        out[i++] = 0x00; // end of string
        out[i++] = 0x01; // end of the attribute list
        out[i++] = 0x01; // </indication>
        out[i++] = 0x01; // </si>
        return out;
    }

    /** Minimal connectivity WBXML: a {@code NAPDEF} named {@code NJCANARY} carrying the nonce. */
    static byte[] cpWbxml(byte[] nonce) {
        byte[] name = ("NJCANARY" + hex(nonce)).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] out = new byte[8 + name.length + 4];
        int i = 0;
        out[i++] = 0x02; // WBXML 1.2
        out[i++] = 0x0B; // public id: WAP provisioning 1.0
        out[i++] = 0x6A;
        out[i++] = 0x00;
        out[i++] = 0x45; // <wap-provisioningdoc>
        out[i++] = (byte) 0xC6; // <characteristic>
        out[i++] = 0x0C; // type
        out[i++] = 0x03;
        System.arraycopy(name, 0, out, i, name.length);
        i += name.length;
        out[i++] = 0x00; // end of string
        out[i++] = 0x01; // end of the attribute list
        out[i++] = 0x01; // </characteristic>
        out[i++] = 0x01; // </wap-provisioningdoc>
        return out;
    }

    // ------------------------------------------------------------------ 7-bit

    /** GSM 7-bit default alphabet, indexed by septet. */
    private static final String BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001bÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡"
            + "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

    static byte[] toSeptets(String text) {
        byte[] out = new byte[text.length()];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int idx = BASIC.indexOf(c);
            out[i] = (byte) (idx >= 0 ? idx : BASIC.indexOf('?'));
        }
        return out;
    }

    /** Packs septets into octets, least significant bit first, after {@code fillBits} padding bits. */
    static byte[] pack(byte[] septets, int fillBits) {
        int bits = fillBits + septets.length * 7;
        byte[] out = new byte[(bits + 7) / 8];
        int pos = fillBits;
        for (byte s : septets) {
            int v = s & 0x7F;
            for (int b = 0; b < 7; b++) {
                if ((v & (1 << b)) != 0) {
                    out[pos >> 3] |= (byte) (1 << (pos & 7));
                }
                pos++;
            }
        }
        return out;
    }

    static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte x : bytes) {
            b.append(Character.toUpperCase(Character.forDigit((x >> 4) & 0xF, 16)));
            b.append(Character.toUpperCase(Character.forDigit(x & 0xF, 16)));
        }
        return b.toString();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
