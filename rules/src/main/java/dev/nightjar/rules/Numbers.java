package dev.nightjar.rules;

/**
 * Originator/SMSC normalisation shared by the rule engine and the app's search: strip
 * spaces (also tab and no-break space), every {@code +}, then a leading {@code 00}. In a number-like value (only
 * digits, {@code +}, spaces and the separators {@code - ( ) .}) the separators go too, so {@code +91 90000-00210}
 * equals {@code +919000000210}. Letters and the separators of an alphanumeric id ({@code VM-AIRTEL}) are kept as
 * received; the engine compares letters ASCII case-insensitively.
 *
 * <p>Thread: any. {@link #normalize} allocates (app/worker use); {@link #normalizeInto} does not.
 */
public final class Numbers {
    private Numbers() {}

    /** Returns the normalised form, or {@code null} when {@code raw} is {@code null}. */
    public static String normalize(CharSequence raw) {
        if (raw == null) return null;
        char[] buf = new char[raw.length()];
        int n = normalizeInto(raw, buf);
        return new String(buf, 0, n);
    }

    /**
     * Writes the normalised form of {@code raw} into {@code out} (which must hold at least {@code raw.length()}
     * chars) and returns its length. Allocation-free.
     */
    public static int normalizeInto(CharSequence raw, char[] out) {
        int len = raw.length();
        boolean numberLike = true;
        for (int i = 0; i < len && numberLike; i++) {
            char c = raw.charAt(i);
            numberLike = (c >= '0' && c <= '9') || isSpaceOrPlus(c) || isSeparator(c);
        }
        int n = 0;
        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);
            if (isSpaceOrPlus(c) || (numberLike && isSeparator(c))) continue;
            out[n++] = c;
        }
        if (n >= 2 && out[0] == '0' && out[1] == '0') {
            System.arraycopy(out, 2, out, 0, n - 2);
            n -= 2;
        }
        return n;
    }

    private static boolean isSpaceOrPlus(char c) {
        return c == ' ' || c == '+' || c == '\t' || c == '\u00A0';
    }

    private static boolean isSeparator(char c) {
        return c == '-' || c == '(' || c == ')' || c == '.';
    }

    /** ASCII case fold used for all string comparisons in the engine. */
    static char fold(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c + ('a' - 'A')) : c;
    }

    static boolean equalsFold(char[] a, int aLen, char[] b) {
        if (aLen != b.length) return false;
        for (int i = 0; i < aLen; i++) {
            if (fold(a[i]) != fold(b[i])) return false;
        }
        return true;
    }

    static boolean startsWithFold(char[] a, int aLen, char[] prefix) {
        if (aLen < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (fold(a[i]) != fold(prefix[i])) return false;
        }
        return true;
    }

    static boolean equalsFold(CharSequence a, char[] b) {
        if (a.length() != b.length) return false;
        for (int i = 0; i < b.length; i++) {
            if (fold(a.charAt(i)) != fold(b[i])) return false;
        }
        return true;
    }

    static boolean startsWithFold(CharSequence a, char[] prefix) {
        if (a.length() < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (fold(a.charAt(i)) != fold(prefix[i])) return false;
        }
        return true;
    }

    /**
     * Case-insensitive {@code contains} without allocation. {@code needle} must already be lower-cased with {@link
     * Character#toLowerCase(char)}. Uses full Unicode simple case mapping per char ("case-insensitive").
     */
    static boolean containsIgnoreCase(CharSequence hay, char[] needle) {
        int n = needle.length;
        if (n == 0) return true;
        int max = hay.length() - n;
        for (int i = 0; i <= max; i++) {
            int j = 0;
            while (j < n && Character.toLowerCase(hay.charAt(i + j)) == needle[j]) j++;
            if (j == n) return true;
        }
        return false;
    }
}
