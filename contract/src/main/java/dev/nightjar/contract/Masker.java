package dev.nightjar.contract;

/**
 * The shared masker. Every log line, trace detail and diagnostics field that
 * could hold a phone number or message text goes through here.
 *
 * <p>Thread-safe and allocation-light; usable from any thread.
 */
public final class Masker {
    private Masker() {}

    /** Bullet used for hidden digits. A fixed count hides the number's length. */
    static final String HIDDEN = "•••••";

    /** Numbers with at most this many digits are service/short codes and are kept as they are. */
    static final int SHORT_CODE_MAX_DIGITS = 6;

    /** One-digit country codes (NANP and Russia/Kazakhstan). */
    private static final String CC1 = "17";

    /** Two-digit country codes (ITU-T E.164 list). Everything else that is assigned has three digits. */
    private static final int[] CC2 = {
        20, 27, 30, 31, 32, 33, 34, 36, 39, 40, 41, 43, 44, 45, 46, 47, 48, 49, 51, 52, 53, 54, 55, 56, 57, 58,
        60, 61, 62, 63, 64, 65, 66, 81, 82, 84, 86, 90, 91, 92, 93, 94, 95, 98,
    };

    /**
     * Masks a phone number or sender id.
     *
     * <ul>
     *   <li>{@code +<cc><subscriber>}: keeps {@code +}, the country code and the last 3 digits ({@code +91•••••210}).
     *   <li>National numbers longer than a short code: keeps the last 3 digits ({@code •••••210}).
     *   <li>Short codes (≤ 6 digits): kept.
     *   <li>Alphanumeric sender ids: kept if ≤ 11 characters, containing a letter, and at most 3 digits in total;
     *       otherwise only the last 3 characters are kept.
     * </ul>
     *
     * <p>Digits of every script count (Arabic-Indic, fullwidth and so on). Control characters in a kept value become
     * {@code ?}, so a sender id cannot start a new log line.
     *
     * @return the masked value; {@code "null"} for null and {@code ""} for an empty input
     */
    public static String maskNumber(String value) {
        if (value == null) return "null";
        String s = value.trim();
        if (s.isEmpty()) return "";

        boolean plus = s.charAt(0) == '+';
        String digits = digitsOnly(plus ? s.substring(1) : s);
        boolean numeric = isNumberLike(s);

        if (!numeric) {
            if (s.length() <= 11 && hasLetter(s) && digitCount(s) <= 3) return printable(s);
            return HIDDEN + printable(tail(s, 3));
        }
        if (!plus && digits.length() <= SHORT_CODE_MAX_DIGITS) return digits;
        if (digits.length() <= 3) return (plus ? "+" : "") + HIDDEN;
        if (plus) {
            int cc = countryCodeLength(digits);
            if (digits.length() <= cc + 3) return "+" + digits.substring(0, Math.min(cc, digits.length())) + HIDDEN;
            return "+" + digits.substring(0, cc) + HIDDEN + tail(digits, 3);
        }
        return HIDDEN + tail(digits, 3);
    }

    /** Masks message text. Always returns {@code «n chars»} (code points); never any content. */
    public static String maskText(CharSequence text) {
        if (text == null) return "«null»";
        int n = Character.codePointCount(text, 0, text.length());
        return "«" + n + (n == 1 ? " char»" : " chars»");
    }

    static int countryCodeLength(String digits) {
        if (digits.isEmpty()) return 0;
        if (CC1.indexOf(digits.charAt(0)) >= 0) return 1;
        if (digits.length() >= 2) {
            int two = (digits.charAt(0) - '0') * 10 + (digits.charAt(1) - '0');
            for (int c : CC2) if (c == two) return 2;
        }
        return Math.min(3, digits.length());
    }

    private static boolean isNumberLike(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isDigit(c) || c == '+' || c == ' ' || c == '-' || c == '(' || c == ')' || c == '.')) return false;
        }
        return true;
    }

    private static String digitsOnly(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            // Character.digit also reads non-ASCII decimal digits, which isNumberLike accepts.
            int d = Character.digit(s.charAt(i), 10);
            if (d >= 0) b.append((char) ('0' + d));
        }
        return b.toString();
    }

    private static boolean hasLetter(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) return true;
        return false;
    }

    private static int digitCount(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) n++;
        return n;
    }

    private static String printable(String s) {
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                if (b == null) b = new StringBuilder(s);
                b.setCharAt(i, '?');
            }
        }
        return b == null ? s : b.toString();
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
