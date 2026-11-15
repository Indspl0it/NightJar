package dev.nightjar.payload;

import java.util.Map;

/**
 * Deterministic JSON writer. {@code org.json} serializes objects in hash order, so the
 * payload writes its documents itself: keys are emitted in the map's iteration order (always a
 * {@link java.util.TreeMap} for our documents). Strings are escaped here (RFC 8259), not by
 * {@code JSONObject.quote}: Android's {@code quote} writes {@code /} as {@code \/} and the {@code org.json} of the JVM
 * tests does not, so the device output would differ from the test fixtures.
 *
 * <p>Accepted values: {@code null} (and {@code JSONObject.NULL}), {@code String}, {@code Number}, {@code Boolean},
 * {@code Map} (in the map's iteration order), {@code Iterable} and {@code byte[]} (as Base64 text). Any other object
 * is written as its quoted {@code toString()}.
 *
 * <p>Thread: any; stateless.
 */
public final class Json {
    private Json() {}

    /** Serializes {@code value}; {@code null} becomes JSON {@code null}. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        append(sb, value);
        return sb.toString();
    }

    /** JSON has no NaN or Infinity; both become {@code null}. */
    private static void appendDouble(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            sb.append("null");
            return;
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
            return;
        }
        sb.append(d);
    }

    private static void append(StringBuilder sb, Object v) {
        if (v == null || v == org.json.JSONObject.NULL) {
            sb.append("null");
            return;
        }
        if (v instanceof String s) {
            quote(sb, s);
            return;
        }
        if (v instanceof Boolean) {
            sb.append(v);
            return;
        }
        if (v instanceof Double d) {
            appendDouble(sb, d);
            return;
        }
        if (v instanceof Float f) {
            appendDouble(sb, f.doubleValue());
            return;
        }
        if (v instanceof Number n) {
            sb.append(n);
            return;
        }
        if (v instanceof byte[] bytes) {
            // A byte array has no JSON form; emit its base64 spelling instead of `[B@1a2b`.
            quote(sb, java.util.Base64.getEncoder().encodeToString(bytes));
            return;
        }
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                append(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                append(sb, o);
            }
            sb.append(']');
            return;
        }
        // Unknown object: fall back to its text form, quoted.
        quote(sb, String.valueOf(v));
    }

    /**
     * RFC 8259 §7 string: quotation mark, reverse solidus and the control characters are escaped (the short forms
     * where JSON has them), plus U+2028 and U+2029 so the text is also a valid JavaScript string. Nothing else.
     */
    static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

}
