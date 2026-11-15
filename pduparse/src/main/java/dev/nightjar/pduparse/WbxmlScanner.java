package dev.nightjar.pduparse;

import dev.nightjar.contract.AnomalyCode;
import java.nio.charset.StandardCharsets;

/**
 * Total structure check of a WBXML document (WAP-192). One linear pass over the tokens: no recursion, no tree,
 * no text decoding, so a nesting or string-table bomb costs one step per input octet.
 *
 * <p>Anomalies: {@code AN-WBXML-VER} for a version above 1.3, {@code AN-WBXML-STR} when the string table runs past the
 * data or a reference points outside it, {@code AN-WBXML-TOK} for a token that cannot appear where it is (an END with
 * no open element, an unknown tag of a known document type, a cut token, an element that is never closed). The scan
 * stops at the first {@code VER}, at a string table that runs past the data, and at the first {@code TOK}.
 *
 * <p>The tag check uses the document type of the public identifier: a well-known id (WAP-192), or a string id
 * from the string table (for example {@code -//WAPFORUM//DTD SI 1.0//EN}). When the id names no known type, the
 * document type of the push content type applies ({@link #scanPushBody}), so a literal or unknown public id cannot
 * switch the check off.
 *
 * <p>Thread: any.
 */
public final class WbxmlScanner {
    private static final int SWITCH_PAGE = 0x00;
    private static final int END = 0x01;
    private static final int ENTITY = 0x02;
    private static final int STR_I = 0x03;
    private static final int LITERAL = 0x04;
    private static final int EXT_I_0 = 0x40;
    private static final int EXT_I_2 = 0x42;
    private static final int PI = 0x43;
    private static final int LITERAL_C = 0x44;
    private static final int EXT_T_0 = 0x80;
    private static final int EXT_T_2 = 0x82;
    private static final int STR_T = 0x83;
    private static final int LITERAL_A = 0x84;
    private static final int EXT_0 = 0xC0;
    private static final int EXT_2 = 0xC2;
    private static final int OPAQUE = 0xC3;
    private static final int LITERAL_AC = 0xC4;

    /** Highest WBXML version this scanner knows: 0x03 is WBXML 1.3. */
    private static final int MAX_VERSION = 0x03;
    /** WBXML 1.0 has no charset field (WAP-192: the charset came with version 1.1). */
    private static final int VERSION_1_0 = 0x00;

    /** Well-known public identifiers (WAP-192, OMNA) of the document types with a tag check. */
    static final int PUBLIC_ID_SI = 0x05;
    static final int PUBLIC_ID_SL = 0x06;
    static final int PUBLIC_ID_CO = 0x07;
    static final int PUBLIC_ID_PROV = 0x0B;

    private WbxmlScanner() {}

    /**
     * True when a push with this content type (lower case, no parameters) carries a WBXML body: the compiled WAP types
     * ({@code sic}, {@code slc}, {@code coc}, {@code wmlc}) and every {@code …wbxml} type.
     */
    public static boolean isWbxml(String contentType) {
        if (contentType == null) return false;
        return contentType.endsWith("wbxml")
                || contentType.equals("application/vnd.wap.sic")
                || contentType.equals("application/vnd.wap.slc")
                || contentType.equals("application/vnd.wap.coc")
                || contentType.equals("application/vnd.wap.wmlc");
    }

    /**
     * Scans the body of a parsed push when the push is readable (no {@link WspParser#UNREADABLE} code) and its
     * content type is a WBXML type; else returns null. {@code base} is the offset of the push in {@code buf}.
     */
    public static WbxmlFacts scanPushBody(WspFacts push, byte[] buf, int base) {
        if (push == null || buf == null || (push.anomalyMask() & WspParser.UNREADABLE) != 0) return null;
        String contentType = push.contentType();
        if (push.bodyOffset() < 0 || !isWbxml(contentType)) return null;
        long off = (long) base + push.bodyOffset();
        if (off < 0 || off > buf.length) off = -1; // scan() then reports AN-WBXML-TOK
        return scan(buf, (int) off, push.bodyLength(), documentType(contentType));
    }

    /**
     * The public identifier that a push content type implies (WAP-167 SI, WAP-168 SL, WAP-175 CO, OMA CP), or 0 when
     * the type has no tag check.
     */
    static int documentType(String contentType) {
        if (contentType == null) return 0;
        return switch (contentType) {
            case "application/vnd.wap.sic" -> PUBLIC_ID_SI;
            case "application/vnd.wap.slc" -> PUBLIC_ID_SL;
            case "application/vnd.wap.coc" -> PUBLIC_ID_CO;
            case "application/vnd.wap.connectivity-wbxml" -> PUBLIC_ID_PROV;
            default -> 0;
        };
    }

    public static WbxmlFacts scan(byte[] doc) {
        return scan(doc, 0, doc == null ? 0 : doc.length);
    }

    /** Scans the document at {@code d[off..off+len)}. Never throws. */
    public static WbxmlFacts scan(byte[] d, int off, int len) {
        return scan(d, off, len, 0);
    }

    /** As {@link #scan(byte[], int, int)}, with the document type to use when the public id names none. */
    static WbxmlFacts scan(byte[] d, int off, int len, int fallbackType) {
        try {
            return scanUnguarded(d, off, len, fallbackType);
        } catch (RuntimeException e) {
            // Unreachable by design (FuzzTest proves it); fail open.
            WbxmlFacts f = new WbxmlFacts();
            f.anomalies = Anomalies.with(Anomalies.NONE, AnomalyCode.WBXML_TOK);
            return f;
        }
    }

    static WbxmlFacts scanUnguarded(byte[] d, int off, int len, int fallbackType) {
        WbxmlFacts f = new WbxmlFacts();
        if (d == null || off < 0 || off > d.length || len < 0) {
            f.anomalies = Anomalies.with(Anomalies.NONE, AnomalyCode.WBXML_TOK);
            return f;
        }
        int end = (int) Math.min(d.length, (long) off + len);
        if (end <= off) return fail(f, AnomalyCode.WBXML_TOK);
        f.version = d[off] & 0xFF;
        if (f.version > MAX_VERSION) return fail(f, AnomalyCode.WBXML_VER);
        boolean hasCharset = f.version != VERSION_1_0;
        // Version, public id, charset (not in 1.0) and string table length: one octet each at least.
        if (end - off < (hasCharset ? 4 : 3)) return fail(f, AnomalyCode.WBXML_TOK);
        int p = off + 1;
        long[] v = uintvar(d, p, end);
        if (v == null) return fail(f, AnomalyCode.WBXML_TOK);
        f.publicId = v[0];
        p = (int) v[1];
        long publicIdIndex = -1;
        if (f.publicId == 0) {
            // Public id 0: the id is a string table index that follows.
            v = uintvar(d, p, end);
            if (v == null) return fail(f, AnomalyCode.WBXML_TOK);
            publicIdIndex = v[0];
            p = (int) v[1];
        }
        if (hasCharset) {
            v = uintvar(d, p, end);
            if (v == null) return fail(f, AnomalyCode.WBXML_TOK);
            f.charset = v[0];
            p = (int) v[1];
        }
        v = uintvar(d, p, end);
        if (v == null) return fail(f, AnomalyCode.WBXML_STR);
        f.stringTableLength = v[0];
        p = (int) v[1];
        if (p + f.stringTableLength > end) return fail(f, AnomalyCode.WBXML_STR);
        long table = f.stringTableLength;
        int tableStart = p;
        p += (int) table;

        int mask = Anomalies.NONE;
        long docType = f.publicId;
        if (publicIdIndex >= 0) {
            if (publicIdIndex >= table) {
                mask = Anomalies.with(mask, AnomalyCode.WBXML_STR);
            } else {
                docType = publicIdOf(d, tableStart + (int) publicIdIndex, p);
            }
        }
        long tags = knownTags(docType);
        if (tags == 0) tags = knownTags(fallbackType);
        int depth = 0;
        boolean attributes = false;
        while (p < end) {
            int b = d[p++] & 0xFF;
            if (b == SWITCH_PAGE) {
                if (p >= end) return fail(f, mask, AnomalyCode.WBXML_TOK);
                p++;
            } else if (b == END) {
                if (attributes) {
                    attributes = false;
                } else {
                    if (depth == 0) return fail(f, mask, AnomalyCode.WBXML_TOK);
                    depth--;
                }
            } else if (b == ENTITY || (b >= EXT_T_0 && b <= EXT_T_2)) {
                v = uintvar(d, p, end);
                if (v == null) return fail(f, mask, AnomalyCode.WBXML_TOK);
                p = (int) v[1];
            } else if (b == STR_I || (b >= EXT_I_0 && b <= EXT_I_2)) {
                int z = indexOfNul(d, p, end);
                if (z < 0) return fail(f, mask, AnomalyCode.WBXML_TOK);
                p = z + 1;
            } else if (b == PI) {
                // A processing instruction is an attribute start, values and END.
                attributes = true;
            } else if (b == STR_T) {
                v = uintvar(d, p, end);
                if (v == null) return fail(f, mask, AnomalyCode.WBXML_TOK);
                if (v[0] >= table) mask = Anomalies.with(mask, AnomalyCode.WBXML_STR);
                p = (int) v[1];
            } else if (b >= EXT_0 && b <= EXT_2) {
                // EXT_0..2 carry no data.
            } else if (b == OPAQUE) {
                v = uintvar(d, p, end);
                if (v == null || v[1] + v[0] > end) return fail(f, mask, AnomalyCode.WBXML_TOK);
                p = (int) (v[1] + v[0]);
            } else if (b == LITERAL || b == LITERAL_C || b == LITERAL_A || b == LITERAL_AC) {
                // In the attribute code space only LITERAL (an attribute name) can appear.
                if (attributes && b != LITERAL) return fail(f, mask, AnomalyCode.WBXML_TOK);
                v = uintvar(d, p, end);
                if (v == null) return fail(f, mask, AnomalyCode.WBXML_TOK);
                if (v[0] >= table) mask = Anomalies.with(mask, AnomalyCode.WBXML_STR);
                p = (int) v[1];
                if (!attributes) {
                    startElement(f, LITERAL);
                    if ((b & 0x80) != 0) attributes = true;
                    if ((b & 0x40) != 0) depth++;
                }
            } else if (!attributes) {
                int tag = b & 0x3F;
                if (tags != 0 && (tags & (1L << tag)) == 0) return fail(f, mask, AnomalyCode.WBXML_TOK);
                startElement(f, tag);
                if ((b & 0x80) != 0) attributes = true;
                if ((b & 0x40) != 0) depth++;
            }
            // Else: an attribute start (below 0x80) or an attribute value token (0x80 and above), one octet.
        }
        if (attributes || depth != 0) mask = Anomalies.with(mask, AnomalyCode.WBXML_TOK);
        f.anomalies = mask;
        return f;
    }

    /**
     * The well-known id of the formal public identifier string at {@code d[from..)} inside the string table (ending
     * at {@code tableEnd}), or 0 when it names no document type with a tag check.
     */
    private static long publicIdOf(byte[] d, int from, int tableEnd) {
        int z = indexOfNul(d, from, tableEnd);
        if (z < 0) return 0;
        return switch (new String(d, from, z - from, StandardCharsets.ISO_8859_1)) {
            case "-//WAPFORUM//DTD SI 1.0//EN" -> PUBLIC_ID_SI;
            case "-//WAPFORUM//DTD SL 1.0//EN" -> PUBLIC_ID_SL;
            case "-//WAPFORUM//DTD CO 1.0//EN" -> PUBLIC_ID_CO;
            case "-//WAPFORUM//DTD PROV 1.0//EN" -> PUBLIC_ID_PROV;
            default -> 0;
        };
    }

    private static void startElement(WbxmlFacts f, int tag) {
        if (f.rootTag < 0) f.rootTag = tag;
        f.elements++;
    }

    /**
     * Tag tokens of the document types the push classifiers read, as a bit set over tokens 0..63; 0 means "do not
     * check". SI (0x05): si, indication, info, item. SL (0x06): sl. CO (0x07): co, invalidate-object,
     * invalidate-service. Provisioning (0x0B): wap-provisioningdoc, characteristic, parm (code pages 0 and 1).
     */
    static long knownTags(long publicId) {
        if (publicId == PUBLIC_ID_SI) return bits(5, 8);
        if (publicId == PUBLIC_ID_SL) return bits(5, 5);
        if (publicId == PUBLIC_ID_CO || publicId == PUBLIC_ID_PROV) return bits(5, 7);
        return 0;
    }

    private static long bits(int from, int to) {
        long out = 0;
        for (int t = from; t <= to; t++) out |= 1L << t;
        return out;
    }

    private static WbxmlFacts fail(WbxmlFacts f, AnomalyCode code) {
        return fail(f, Anomalies.NONE, code);
    }

    private static WbxmlFacts fail(WbxmlFacts f, int mask, AnomalyCode code) {
        f.anomalies = Anomalies.with(mask, code);
        return f;
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

    private static int indexOfNul(byte[] d, int from, int end) {
        for (int i = from; i < end; i++) {
            if (d[i] == 0) return i;
        }
        return -1;
    }
}
