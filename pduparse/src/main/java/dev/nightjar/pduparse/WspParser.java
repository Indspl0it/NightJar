package dev.nightjar.pduparse;

import dev.nightjar.contract.AnomalyCode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Total parser for a WAP Push PDU (WAP-230). Moved from the payload's {@code WspMini}:
 * it extracts the transaction id, PDU type, content type and {@code X-Wap-Application-Id} and never throws.
 *
 * <p>Wire format is the framework's ({@code WapPushOverSms.dispatchWapPdu}/{@code WspTypeDecoder}):
 * {@code TID(1) PDU-type(1) HeadersLen(uintvar)} followed by the content type <em>value</em> and then the headers.
 * The content type is a short-integer ({@code 0x80|code}), a text string, or a value-length wrapped media type.
 * {@code HeadersLen} counts the content type and the headers together.
 *
 * <p>Header field names follow the framework's reading ({@code WspTypeDecoder.seekXWapApplicationId}), because
 * Nightjar reports what Android acts on: an octet 0x80 to 0xFF is a short-integer name, an octet 0 to 30 starts
 * a Long-integer name, and any other octet starts a text name. WAP-230 §8.4.2.6 calls the octets 1 to 31 and 127 shift
 * sequences; the framework does not, so this parser does not either. A Long-integer name with the value 0x2F is an
 * {@code X-Wap-Application-Id} field, as in the framework (which then reads the value one octet after the name start;
 * this parser reads it after the whole name).
 *
 * <p>Two passes over the same bytes: the lenient fact pass keeps {@code WspMini}'s results, and the strict pass reports
 * the anomalies {@code AN-WSP-TYPE}, {@code AN-WSP-HLEN}, {@code AN-WSP-CTYPE} and {@code AN-WSP-APPID}. The
 * first three stop the strict pass (the push cannot be read). Each step of a header walk moves forward, so a walk
 * ends at the header length; there is no field count limit that filler headers could use to hide a field.
 *
 * <p>Thread: any.
 */
public final class WspParser {
    /** WSP well-known header field: X-Wap-Application-Id (as a field name {@code 0x80|0x2F}). */
    public static final int HEADER_X_WAP_APPLICATION_ID = 0x2F;

    /** WSP Push PDU type. */
    public static final int PDU_TYPE_PUSH = 0x06;
    /** WSP Confirmed Push PDU type. */
    public static final int PDU_TYPE_CONFIRMED_PUSH = 0x07;

    /** Anomalies that make the push unreadable (: CL-WAP-UNREADABLE); the body is not checked after one. */
    public static final int UNREADABLE =
            Anomalies.of(AnomalyCode.WSP_TYPE, AnomalyCode.WSP_HLEN, AnomalyCode.WSP_CTYPE);

    /** OMA CP content-type parameters (WAP-183 ProvCont): SEC and MAC as well-known parameter codes. */
    private static final int PARAM_SEC = 0x11;
    private static final int PARAM_MAC = 0x12;

    /** Shortest length octet that quotes a following uintvar (WAP-230). */
    private static final int LENGTH_QUOTE = 31;
    /** Quote octet (WAP-230): a text string may start with it when its first character is 128 or more. */
    private static final int TEXT_QUOTE = 127;
    /** Longest Long-integer: Short-length is 0..30 (WAP-230). */
    private static final int MAX_SHORT_LENGTH = 30;

    private WspParser() {}

    /** Parses a WAP Push PDU. Never throws; {@link WspFacts#valid()} false means "unknown" to the classifier. */
    public static WspFacts parse(byte[] pdu) {
        try {
            return parseUnguarded(pdu);
        } catch (RuntimeException e) {
            // Unreachable by design (FuzzTest proves it); fail open.
            return failed(e.getClass().getSimpleName());
        }
    }

    /**
     * Parses the push at {@code pdu[off..off+len)}; offsets in the result are relative to {@code off}. A range that
     * does not start inside {@code pdu} gives {@code AN-WSP-HLEN}. Never throws.
     */
    public static WspFacts parse(byte[] pdu, int off, int len) {
        try {
            if (pdu == null || off < 0 || len < 0 || off > pdu.length) return failed("range");
            int n = Math.min(len, pdu.length - off);
            byte[] copy = new byte[n];
            System.arraycopy(pdu, off, copy, 0, n);
            return parseUnguarded(copy);
        } catch (RuntimeException e) {
            // Unreachable by design (FuzzTest proves it); fail open.
            return failed(e.getClass().getSimpleName());
        }
    }

    private static WspFacts failed(String error) {
        WspFacts f = new WspFacts();
        f.error = error;
        f.anomalies = Anomalies.with(Anomalies.NONE, AnomalyCode.WSP_HLEN);
        return f;
    }

    static WspFacts parseUnguarded(byte[] pdu) {
        WspFacts f = new WspFacts();
        f.anomalies = pdu == null ? Anomalies.with(Anomalies.NONE, AnomalyCode.WSP_TYPE) : scan(pdu);
        facts(pdu, f);
        return f;
    }

    // ------------------------------------------------------------------ fact pass (former WspMini)

    private static void facts(byte[] pdu, WspFacts f) {
        if (pdu == null || pdu.length < 3) {
            f.error = "short";
            return;
        }
        f.transactionId = pdu[0] & 0xFF;
        f.pduType = pdu[1] & 0xFF;
        long[] hl = uintvar(pdu, 2, pdu.length);
        if (hl == null) {
            f.error = "headerLength";
            return;
        }
        // The framework casts the uintvar to int (WapPushOverSms.decodeWapPdu). A 5-octet value that is negative as an
        // int makes it throw NegativeArraySizeException and kills the phone process, so it is reported as malformed.
        // Other bad lengths are left to the hook's exception shield.
        int headerLength = (int) hl[0];
        if (headerLength < 0) {
            f.error = "headerLength";
            return;
        }
        int i = (int) hl[1];
        f.headerLength = headerLength;
        f.headersOffset = i;
        long headersEnd = (long) i + headerLength;
        if (headersEnd <= pdu.length) {
            f.bodyOffset = (int) headersEnd;
            f.bodyLength = pdu.length - f.bodyOffset;
        }
        int end = (int) Math.min(pdu.length, headersEnd);

        Value ct = readContentType(pdu, i, f);
        if (ct == null) {
            f.error = "contentType";
            return;
        }
        f.contentTypeText = ct.text;
        f.contentTypeCode = ct.code;
        // The content type is read, so the push is readable (WspFacts#valid). A later field error stays in error().
        f.valid = true;
        i = ct.next;

        while (i < end) {
            long next = nameEnd(pdu, i, end);
            if (next > end) break;
            int field = nameField(pdu, i);
            i = (int) next;
            if (field == HEADER_X_WAP_APPLICATION_ID) {
                Value v = readIntegerOrText(pdu, i, end);
                if (v == null) {
                    f.error = "applicationId";
                    return;
                }
                f.applicationId = v.text != null ? v.text
                        : (v.code >= 0 ? "0x" + Integer.toHexString(v.code) : null);
                f.applicationIdCode = v.text != null ? -1 : v.code;
                i = v.next;
            } else {
                i = skipValue(pdu, i, end);
            }
        }
    }

    private record Value(int next, int code, String text) {}

    /**
     * Reads the content type: either a value-length wrapped media type (Content-general-form) or a constrained
     * encoding (short-integer well-known value, or extension-media text). OMA CP parameters are recorded in {@code f}.
     * As in {@code WspMini}, the value can be read up to the end of the PDU, not only of the headers.
     */
    private static Value readContentType(byte[] pdu, int i, WspFacts f) {
        if (i >= pdu.length) return null;
        int first = pdu[i] & 0xFF;
        if (first > LENGTH_QUOTE) return readMediaType(pdu, i, false);
        long length;
        int j;
        if (first < LENGTH_QUOTE) {
            length = first;
            j = i + 1;
        } else {
            long[] uv = uintvar(pdu, i + 1, pdu.length);
            if (uv == null) return null;
            length = uv[0];
            j = (int) uv[1];
        }
        int valueEnd = (int) Math.min(pdu.length, j + length);
        Value media = readMediaType(pdu, j, true);
        if (media == null || media.next > valueEnd) return null;
        parameters(pdu, media.next, valueEnd, f);
        return new Value(valueEnd, media.code, media.text);
    }

    /**
     * Reads the parameters that follow the media type inside the value (WAP-230 Typed-parameter or
     * Untyped-parameter) and records the OMA CP SEC and MAC parameters. Other parameters are skipped, so SEC is found
     * at any position.
     */
    private static void parameters(byte[] pdu, int p, int end, WspFacts f) {
        while (p < end) {
            long valueAt = nameEnd(pdu, p, end);
            if (valueAt > end) return;
            int name = nameField(pdu, p);
            int v = (int) valueAt;
            if (name == PARAM_SEC && v < end) {
                int sec = pdu[v] & 0xFF;
                f.secParam = sec >= 0x80 ? sec & 0x7F : sec;
            } else if (name == PARAM_MAC) {
                f.macParam = true;
            }
            p = skipValue(pdu, v, end);
        }
    }

    /** Reads a media type: short-integer, long-integer (when allowed) or extension-media text. */
    private static Value readMediaType(byte[] pdu, int i, boolean allowLong) {
        if (i >= pdu.length) return null;
        int v = pdu[i] & 0xFF;
        if (v >= 0x80) return new Value(i + 1, v & 0x7F, null);
        if (allowLong && v <= MAX_SHORT_LENGTH) {
            if (i + v >= pdu.length) return null;
            return new Value(i + 1 + v, longInteger(pdu, i + 1, v), null);
        }
        return readText(pdu, i, pdu.length);
    }

    /** Reads a NUL-terminated text string inside {@code [i, end)}, after an optional quote octet; null when cut. */
    private static Value readText(byte[] pdu, int i, int end) {
        int start = i;
        if (i < end && (pdu[i] & 0xFF) == TEXT_QUOTE) start = i + 1;
        int z = indexOfNul(pdu, start, end);
        if (z < 0) return null;
        return new Value(z + 1, -1, new String(pdu, start, z - start, StandardCharsets.UTF_8));
    }

    /**
     * Reads an Integer-value or a text string inside the headers, as the X-Wap-Application-Id field allows; null for
     * text that is not terminated before the end of the headers.
     */
    private static Value readIntegerOrText(byte[] pdu, int i, int end) {
        if (i >= end) return new Value(end, -1, null);
        int v = pdu[i] & 0xFF;
        if (v >= 0x80) return new Value(i + 1, v & 0x7F, null);
        if (v <= MAX_SHORT_LENGTH) {
            if (i + 1 + v > end) return new Value(end, -1, null);
            return new Value(i + 1 + v, longInteger(pdu, i + 1, v), null);
        }
        return readText(pdu, i, end);
    }

    /**
     * End of the field or parameter name at {@code d[i]} (framework reading, see the class comment). It can be past
     * {@code end} for a Long-integer name that is cut; it is always more than {@code i}.
     */
    private static long nameEnd(byte[] d, int i, int end) {
        int name = d[i] & 0xFF;
        if (name >= 0x80) return i + 1L;
        if (name <= MAX_SHORT_LENGTH) return i + 1L + name;
        int z = indexOfNul(d, i, end);
        return z < 0 ? end : z + 1L;
    }

    /** Well-known code of the name at {@code d[i]} (short or Long-integer), -1 for a text name. Needs a whole name. */
    private static int nameField(byte[] d, int i) {
        int name = d[i] & 0xFF;
        if (name >= 0x80) return name & 0x7F;
        if (name <= MAX_SHORT_LENGTH) return longInteger(d, i + 1, name);
        return -1;
    }

    /** The low 32 bits of the {@code n}-octet big-endian integer at {@code d[from]}, as the framework compares it. */
    private static int longInteger(byte[] d, int from, int n) {
        long v = 0;
        for (int k = 0; k < n; k++) v = (v << 8) | (d[from + k] & 0xFF);
        return (int) v;
    }

    /**
     * Skips one field value per WAP-230 §8.4.1.2 inside {@code [i, end)}. The result is more than {@code i} (or
     * {@code end} when {@code i} is at the end), so every walk moves forward.
     */
    private static int skipValue(byte[] d, int i, int end) {
        if (i >= end) return end;
        int v = d[i] & 0xFF;
        if (v <= MAX_SHORT_LENGTH) return (int) Math.min(end, (long) i + 1 + v);
        if (v == LENGTH_QUOTE) {
            long[] uv = uintvar(d, i + 1, end);
            return uv == null ? end : (int) Math.min(end, uv[1] + uv[0]);
        }
        if (v < 0x80) {
            int z = indexOfNul(d, i, end);
            return z < 0 ? end : z + 1;
        }
        return i + 1;
    }

    // ------------------------------------------------------------------ strict pass

    /** Returns the WSP anomaly mask of {@code d}. Stops at the first anomaly that makes the push unreadable. */
    static int scan(byte[] d) {
        int n = d.length;
        if (n < 2) return Anomalies.with(Anomalies.NONE, AnomalyCode.WSP_TYPE);
        int type = d[1] & 0xFF;
        if (type != PDU_TYPE_PUSH && type != PDU_TYPE_CONFIRMED_PUSH) {
            return Anomalies.with(Anomalies.NONE, AnomalyCode.WSP_TYPE);
        }
        if (n < 3) return Anomalies.with(Anomalies.NONE, AnomalyCode.WSP_HLEN);
        long[] hl = uintvar(d, 2, n);
        if (hl == null || hl[0] < 1 || hl[1] + hl[0] > n) {
            return Anomalies.with(Anomalies.NONE, AnomalyCode.WSP_HLEN);
        }
        int p = (int) hl[1];
        int hend = (int) (hl[1] + hl[0]);

        int q = contentTypeEnd(d, p, hend);
        if (q < 0) return Anomalies.with(Anomalies.NONE, AnomalyCode.WSP_CTYPE);

        int mask = Anomalies.NONE;
        while (q < hend) {
            long next = nameEnd(d, q, hend);
            if (next > hend) break;
            int field = nameField(d, q);
            q = (int) next;
            if (field == HEADER_X_WAP_APPLICATION_ID) {
                int end = applicationIdEnd(d, q, hend);
                if (end < 0) return Anomalies.with(mask, AnomalyCode.WSP_APPID);
                q = end;
            } else {
                q = skipValue(d, q, hend);
            }
        }
        return mask;
    }

    /** End of the content type value at {@code d[p]}, or −1 when it cannot be read inside the headers. */
    private static int contentTypeEnd(byte[] d, int p, int hend) {
        int b = d[p] & 0xFF;
        if (b >= 0x80) return p + 1;
        if (b > LENGTH_QUOTE) {
            int z = indexOfNul(d, p, hend);
            return z < 0 ? -1 : z + 1;
        }
        long length;
        int vs;
        if (b == LENGTH_QUOTE) {
            long[] uv = uintvar(d, p + 1, hend);
            if (uv == null) return -1;
            length = uv[0];
            vs = (int) uv[1];
        } else {
            length = b;
            vs = p + 1;
        }
        if (length < 1 || vs + length > hend) return -1;
        int ve = (int) (vs + length);
        int m = d[vs] & 0xFF;
        if (m >= 0x80) return ve;
        if (m <= MAX_SHORT_LENGTH) return m == 0 || vs + 1 + m > ve ? -1 : ve;
        if (m == LENGTH_QUOTE) return -1;
        return indexOfNul(d, vs, ve) < 0 ? -1 : ve;
    }

    /** End of an X-Wap-Application-Id value at {@code d[q]}, or −1 when it is not an Integer-value or a text. */
    private static int applicationIdEnd(byte[] d, int q, int hend) {
        if (q >= hend) return -1;
        int v = d[q] & 0xFF;
        if (v >= 0x80) return q + 1;
        if (v <= MAX_SHORT_LENGTH) return v == 0 || q + 1 + v > hend ? -1 : q + 1 + v;
        if (v == LENGTH_QUOTE) return -1;
        int z = indexOfNul(d, q, hend);
        return z < 0 ? -1 : z + 1;
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
        for (int i = Math.max(0, from); i < end; i++) {
            if (d[i] == 0) return i;
        }
        return -1;
    }

    /** Lower case, no parameters, trimmed; {@code null} stays {@code null}. */
    public static String normalize(String contentType) {
        if (contentType == null) return null;
        String s = contentType.trim().toLowerCase(Locale.ROOT);
        int semi = s.indexOf(';');
        return semi >= 0 ? s.substring(0, semi).trim() : s;
    }
}
