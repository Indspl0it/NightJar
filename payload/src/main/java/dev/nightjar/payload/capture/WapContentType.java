package dev.nightjar.payload.capture;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Subtype;
import dev.nightjar.pduparse.WspFacts;
import java.util.Locale;

/**
 * WAP content type → class and subtype. Every type that no family
 * claims is {@link ClassId#WAP_OTHER} with the content type as its subtype; WML ({@code text/vnd.wap.wml},
 * {@code application/vnd.wap.wmlc}) is one of them.
 *
 * <p>The subtype rules that read the push body ({@link #subtype}) never throw and read only inside the given range. A
 * push without a body (a reassembled multipart push that only the WAP hook saw) gets a subtype only when the content
 * type alone gives it.
 *
 * <p>Thread: any.
 */
public final class WapContentType {
    public static final String CT_WBXML = "application/vnd.wap.connectivity-wbxml";
    /** The text form of OMA CP (WAP-183 ProvCont). */
    public static final String CT_CONNECTIVITY_XML = "text/vnd.wap.connectivity-xml";
    public static final String CT_SYNCML = "application/vnd.syncml.notification";
    public static final String CT_SYNCML_DM_WBXML = "application/vnd.syncml.dm+wbxml";
    public static final String CT_SYNCML_DM_XML = "application/vnd.syncml.dm+xml";
    /** OMA DS 1.2 server alerted sync notification (IANA registration by OMA). */
    public static final String CT_SYNCML_DS = "application/vnd.syncml.ds.notification";
    public static final String CT_SUPL = "application/vnd.omaloc-supl-init";
    public static final String CT_MMS = "application/vnd.wap.mms-message";
    public static final String CT_SIC = "application/vnd.wap.sic";
    public static final String CT_SLC = "application/vnd.wap.slc";
    public static final String CT_SI_TEXT = "text/vnd.wap.si";
    public static final String CT_SL_TEXT = "text/vnd.wap.sl";
    /** Application code 0x32 of {@code vnd.wap.coc}; the framework's own string. */
    public static final String CT_CO = "application/vnd.wap.coc";
    /** The unencoded form carriers send for the same family (application code 0x31). */
    public static final String CT_CO_TEXT = "text/vnd.wap.co";
    public static final String CT_WML = "text/vnd.wap.wml";
    public static final String CT_WMLC = "application/vnd.wap.wmlc";
    public static final String CT_EMN_WBXML = "application/vnd.wap.emn+wbxml";
    public static final String CT_EMN_XML = "text/vnd.wap.emn+xml";
    public static final String CT_DRM_RIGHTS_WBXML = "application/vnd.oma.drm.rights+wbxml";
    public static final String CT_DRM_RIGHTS_XML = "application/vnd.oma.drm.rights+xml";
    public static final String CT_VCARD = "text/x-vcard";
    public static final String CT_VCALENDAR = "text/x-vcalendar";
    public static final String CT_IMPS_CIR = "application/vnd.wv.csp.cir";

    /** What the WAP hook writes as {@code ct=} when the push content type cannot be read (WapHooks). */
    public static final String CT_UNKNOWN = "unknown";

    /** WSP push application id {@code x-wap-application:push.syncml} (OMNA registry code 0x05): SyncML DS. */
    static final int APP_ID_PUSH_SYNCML = 0x05;
    static final String APP_ID_PUSH_SYNCML_TEXT = "x-wap-application:push.syncml";

    /** OMA-TS-MMS_ENC X-Mms-Message-Type is the first header (0x0C | 0x80). */
    private static final int MMS_MESSAGE_TYPE = 0x8C;
    private static final int MMS_NOTIFICATION_IND = 0x82;
    private static final int MMS_DELIVERY_IND = 0x86;
    private static final int MMS_READ_ORIG_IND = 0x88;

    /** WAP-167 SI attribute start token {@code action="delete"} on code page 0. */
    private static final int SI_ACTION_DELETE = 0x09;

    /** Notification type marker when SUPL INIT carries no notification element. */
    static final int SUPL_NO_NOTIFICATION = 8;

    private WapContentType() {}

    /** Maps a normalized content type (lower case, no parameters) to a class. */
    public static ClassId classify(String contentType) {
        if (contentType == null || contentType.isEmpty()) return ClassId.WAP_OTHER;
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.equals(CT_MMS)) return ClassId.WAP_MMS;
        if (ct.equals(CT_SIC) || ct.equals(CT_SI_TEXT) || ct.equals(CT_SLC) || ct.equals(CT_SL_TEXT)) {
            return ClassId.WAP_SISL;
        }
        if (ct.equals(CT_CO) || ct.equals(CT_CO_TEXT)) return ClassId.WAP_CO;
        if (ct.equals(CT_WBXML) || ct.equals(CT_CONNECTIVITY_XML)) return ClassId.WAP_CP;
        if (ct.equals(CT_SYNCML_DS)) return ClassId.WAP_DS;
        if (ct.equals(CT_SYNCML) || ct.startsWith(CT_SYNCML + ".") || ct.equals(CT_SYNCML_DM_WBXML)
                || ct.equals(CT_SYNCML_DM_XML)) {
            return ClassId.WAP_DM;
        }
        if (ct.equals(CT_SUPL)) return ClassId.WAP_SUPL;
        if (ct.equals(CT_EMN_WBXML) || ct.equals(CT_EMN_XML)) return ClassId.WAP_EMN;
        if (ct.equals(CT_DRM_RIGHTS_WBXML) || ct.equals(CT_DRM_RIGHTS_XML)) return ClassId.WAP_DRM;
        if (ct.equals(CT_VCARD) || ct.equals(CT_VCALENDAR)) return ClassId.WAP_VCARD;
        if (ct.equals(CT_IMPS_CIR)) return ClassId.WAP_IMPS;
        return ClassId.WAP_OTHER;
    }

    /**
     * As {@link #classify(String)}, and a SyncML notification for the DS push application ({@code push.syncml}) is
     * {@link ClassId#WAP_DS}, not device management.
     */
    public static ClassId classify(String contentType, WspFacts push) {
        ClassId c = classify(contentType);
        if (c == ClassId.WAP_DM && push != null && CT_SYNCML.equals(lower(contentType))
                && (push.applicationIdCode() == APP_ID_PUSH_SYNCML
                        || APP_ID_PUSH_SYNCML_TEXT.equals(lower(push.applicationId())))) {
            return ClassId.WAP_DS;
        }
        return c;
    }

    /**
     * The subtype of a push of class {@code c}, or {@code null} when the rule needs a body that is not there.
     *
     * @param push the parsed push, or null when only the content type is known
     * @param buf the array that holds the push (the SMS user data)
     * @param pushBase offset of the push in {@code buf}
     */
    public static String subtype(ClassId c, String contentType, WspFacts push, byte[] buf, int pushBase) {
        if (c == null) return null;
        String ct = lower(contentType);
        try {
            switch (c) {
                case WAP_MMS: {
                    int at = bodyStart(push, buf, pushBase);
                    if (at < 0 || push.bodyLength() < 2 || (buf[at] & 0xFF) != MMS_MESSAGE_TYPE) return null;
                    int type = buf[at + 1] & 0xFF;
                    if (type == MMS_NOTIFICATION_IND) return Subtype.WapMms.NOTIFY;
                    if (type == MMS_DELIVERY_IND) return Subtype.WapMms.DELIVERY_REPORT;
                    if (type == MMS_READ_ORIG_IND) return Subtype.WapMms.READ_REPORT;
                    return null;
                }
                case WAP_SISL: {
                    if (CT_SLC.equals(ct) || CT_SL_TEXT.equals(ct)) return Subtype.WapSisl.SL;
                    int at = bodyStart(push, buf, pushBase);
                    if (at < 0) return null;
                    boolean delete = CT_SIC.equals(ct)
                            ? siDelete(buf, at, push.bodyLength())
                            : textSiDelete(buf, at, push.bodyLength());
                    return delete ? Subtype.WapSisl.SI_DELETE : Subtype.WapSisl.SI;
                }
                case WAP_CP: {
                    if (push == null) return null;
                    return switch (push.secParam()) {
                        case 0 -> Subtype.WapCp.NETWPIN;
                        case 1 -> Subtype.WapCp.USERPIN;
                        case 2 -> Subtype.WapCp.USERNETWPIN;
                        case 3 -> Subtype.WapCp.USERPINMAC;
                        // OMA-WAP-TS-ProvCont no SEC parameter means no authentication.
                        case -1 -> Subtype.WapCp.NOSEC;
                        default -> null;
                    };
                }
                case WAP_DM:
                    return CT_SYNCML_DM_WBXML.equals(ct) || CT_SYNCML_DM_XML.equals(ct)
                            ? Subtype.WapDm.BOOTSTRAP
                            : Subtype.WapDm.NOTIFY;
                case WAP_SUPL: {
                    int at = bodyStart(push, buf, pushBase);
                    if (at < 0) return null;
                    return suplSubtype(suplNotificationType(buf, at, push.bodyLength()));
                }
                case WAP_VCARD:
                    return CT_VCALENDAR.equals(ct) ? Subtype.WapVcard.VCAL : Subtype.WapVcard.VCARD;
                case WAP_OTHER:
                    return ct;
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            // Unreachable by design: every read is bounded. Fail open with no subtype.
            return null;
        }
    }

    /** 0 and 4 SILENT, 1 NOTIFY, 2 and 3 VERIFY. */
    static String suplSubtype(int type) {
        switch (type) {
            case 0:
            case 4:
                return Subtype.WapSupl.SILENT;
            case 1:
                return Subtype.WapSupl.NOTIFY;
            case 2:
            case 3:
                return Subtype.WapSupl.VERIFY;
            case SUPL_NO_NOTIFICATION:
                // No notification element: nothing tells the phone to notify the user, so the cautious reading.
                return Subtype.WapSupl.SILENT;
            default:
                return null;
        }
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    /** Absolute offset of the push body in {@code buf}, or −1 when there is none. */
    private static int bodyStart(WspFacts push, byte[] buf, int pushBase) {
        if (push == null || buf == null || pushBase < 0 || push.bodyOffset() < 0 || push.bodyLength() <= 0) return -1;
        long at = (long) pushBase + push.bodyOffset();
        if (at + push.bodyLength() > buf.length) return -1;
        return (int) at;
    }

    // ------------------------------------------------------------------ SI action

    /**
     * True when a WBXML SI document has the attribute {@code action="delete"}. One linear pass over the tokens (the same
     * token classes as {@code WbxmlScanner}); any cut token ends the scan with false.
     */
    static boolean siDelete(byte[] d, int off, int len) {
        int end = (int) Math.min(d.length, (long) off + len);
        int p = off + 1; // version
        long[] v = uintvar(d, p, end);
        if (v == null) return false;
        p = (int) v[1];
        if (v[0] == 0) {
            v = uintvar(d, p, end); // public id as a string table index
            if (v == null) return false;
            p = (int) v[1];
        }
        v = uintvar(d, p, end); // charset
        if (v == null) return false;
        v = uintvar(d, (int) v[1], end); // string table length
        if (v == null || v[1] + v[0] > end) return false;
        p = (int) (v[1] + v[0]);
        boolean attributes = false;
        int page = 0;
        while (p < end) {
            int b = d[p++] & 0xFF;
            if (b == 0x00) { // SWITCH_PAGE
                if (p >= end) return false;
                page = d[p++] & 0xFF;
            } else if (b == 0x01) { // END
                attributes = false;
            } else if (b == 0x02 || (b >= 0x80 && b <= 0x83)) { // ENTITY, EXT_T_n, STR_T
                v = uintvar(d, p, end);
                if (v == null) return false;
                p = (int) v[1];
            } else if (b == 0x03 || (b >= 0x40 && b <= 0x42)) { // STR_I, EXT_I_n
                while (p < end && d[p] != 0) p++;
                if (p >= end) return false;
                p++;
            } else if (b == 0xC3) { // OPAQUE
                v = uintvar(d, p, end);
                if (v == null || v[1] + v[0] > end) return false;
                p = (int) (v[1] + v[0]);
            } else if (b == 0x04 || b == 0x44 || b == 0x84 || b == 0xC4) { // LITERAL forms
                v = uintvar(d, p, end);
                if (v == null) return false;
                p = (int) v[1];
                if (!attributes && (b & 0x80) != 0) attributes = true;
            } else if (b == 0x43) { // PI
                attributes = true;
            } else if (b >= 0xC0 && b <= 0xC2) { // EXT_n
                continue;
            } else if (!attributes) {
                if ((b & 0x80) != 0) attributes = true;
            } else if (page == 0 && b == SI_ACTION_DELETE) {
                return true;
            }
        }
        return false;
    }

    private static final byte[] ACTION_DELETE_DQ = ascii("action=\"delete\"");
    private static final byte[] ACTION_DELETE_SQ = ascii("action='delete'");

    /** True when a text SI document has {@code action="delete"} (WAP-167). */
    static boolean textSiDelete(byte[] d, int off, int len) {
        int end = (int) Math.min(d.length, (long) off + len);
        return indexOf(d, off, end, ACTION_DELETE_DQ) || indexOf(d, off, end, ACTION_DELETE_SQ);
    }

    private static boolean indexOf(byte[] d, int from, int end, byte[] needle) {
        outer:
        for (int i = from; i + needle.length <= end; i++) {
            for (int k = 0; k < needle.length; k++) {
                if (d[i + k] != needle[k]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** Reads a uintvar of at most 5 octets inside {@code [i, end)}; {@code {value, next}} or {@code null}. */
    private static long[] uintvar(byte[] d, int i, int end) {
        long value = 0;
        for (int octets = 0; octets < 5; octets++) {
            if (i >= end) return null;
            int b = d[i++] & 0xFF;
            value = (value << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) return new long[] {value, i};
        }
        return null;
    }

    // ------------------------------------------------------------------ SUPL INIT

    /** Unaligned PER bit reader over {@code [off, end)}; a read past the end sets {@link #cut}. */
    private static final class Bits {
        private final byte[] d;
        private final long end;
        private long pos;
        boolean cut;

        Bits(byte[] d, int off, int end) {
            this.d = d;
            this.pos = (long) off * 8;
            this.end = (long) end * 8;
        }

        long read(int n) {
            if (n <= 0) return 0;
            if (cut || pos + n > end) {
                cut = true;
                return 0;
            }
            long v = 0;
            for (int i = 0; i < n; i++, pos++) {
                v = (v << 1) | ((d[(int) (pos >>> 3)] >>> (7 - (int) (pos & 7))) & 1);
            }
            return v;
        }

        void skip(long n) {
            if (cut || pos + n > end) {
                cut = true;
                return;
            }
            pos += n;
        }
    }

    /**
     * SUPL INIT notification type (OMA-TS-ULP ULP-PDU, ITU-T X.691 unaligned PER): 0 to 4, {@link
     * #SUPL_NO_NOTIFICATION} when the element is absent, or −1 when the data is not a readable SUPL INIT. The field
     * order and the extension bits follow the shared layout of the payload and the decoder ({@code Supl.kt}): length,
     * version, SessionID, UlpMessage (index 0), SUPLINIT presence bits, posMethod, then Notification. The reader stops
     * after the notification type.
     */
    static int suplNotificationType(byte[] d, int off, int len) {
        Bits r = new Bits(d, off, (int) Math.min(d.length, (long) off + len));
        r.skip(16 + 24); // length, version
        boolean hasSet = r.read(1) == 1;
        boolean hasSlp = r.read(1) == 1;
        if (hasSet) {
            r.skip(16); // sessionId
            if (r.read(1) == 1) {
                skipOpenType(r); // SETId extension alternative
            } else {
                switch ((int) r.read(3)) {
                    case 0, 1, 3 -> r.skip(64); // msisdn, mdn, imsi
                    case 2 -> r.skip(34); // min
                    case 4 -> r.skip((r.read(10) + 1) * 7); // nai: IA5String(SIZE (1..1000))
                    case 5 -> skipIpAddress(r);
                    default -> {
                        return -1;
                    }
                }
            }
        }
        if (hasSlp) {
            r.skip(32); // sessionID
            skipSlpAddress(r);
        }
        if (r.read(1) != 0 || r.read(3) != 0) return -1; // not msSUPLINIT
        r.skip(1); // SUPLINIT extension bit: additions come after the root components
        boolean notification = r.read(1) == 1;
        r.skip(4); // sLPAddress, qoP, mAC, keyIdentity present
        if (r.read(1) == 1) {
            if (r.read(1) == 1) return -1; // posMethod extension value larger than 63
            r.skip(6);
        } else if (r.read(4) > 9) {
            return -1; // posMethod outside the root values
        }
        if (r.cut) return -1;
        if (!notification) return SUPL_NO_NOTIFICATION;
        r.skip(1 + 5); // Notification extension bit and its 5 presence bits
        if (r.read(1) == 1) return -1; // notificationType extension value: unknown meaning
        int type = (int) r.read(3);
        return r.cut || type > 4 ? -1 : type;
    }

    /** IPAddress ::= CHOICE {ipv4Address OCTET STRING (SIZE (4)), ipv6Address OCTET STRING (SIZE (16))}. */
    private static void skipIpAddress(Bits r) {
        r.skip(r.read(1) == 0 ? 32 : 128);
    }

    /** SLPAddress ::= CHOICE {iPAddress, fQDN VisibleString (64 characters, SIZE (1..255)), ...}. */
    private static void skipSlpAddress(Bits r) {
        if (r.read(1) == 1) {
            skipOpenType(r);
        } else if (r.read(1) == 0) {
            skipIpAddress(r);
        } else {
            r.skip((r.read(8) + 1) * 6);
        }
    }

    /**
     * Skips an extension alternative of a CHOICE (X.691): a normally small index, then an open type with a
     * length determinant. A form this reader does not support marks the data as cut.
     */
    private static void skipOpenType(Bits r) {
        if (r.read(1) == 1) {
            r.cut = true;
            return;
        }
        r.skip(6); // index
        long octets;
        if (r.read(1) == 0) {
            octets = r.read(7);
        } else if (r.read(1) == 0) {
            octets = r.read(14);
        } else {
            r.cut = true; // fragmented length: never seen in a SUPL INIT
            return;
        }
        r.skip(octets * 8);
    }
}
