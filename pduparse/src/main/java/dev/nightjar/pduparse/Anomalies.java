package dev.nightjar.pduparse;

import dev.nightjar.contract.AnomalyCode;
import dev.nightjar.contract.ClassId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A set of {@link AnomalyCode} values packed into one {@code int}. Bit {@code n} is the code with ordinal
 * {@code n}; an {@code int} holds up to 32 codes ({@code AnomaliesTest} checks the contract) without allocation on the
 * hook path.
 *
 * <p>Thread: any (static helpers over immutable values).
 */
public final class Anomalies {
    private static final AnomalyCode[] CODES = AnomalyCode.values();

    /** The empty set. {@code AnomaliesTest} proves that every code fits in the 32 bits. */
    public static final int NONE = 0;

    private Anomalies() {}

    /** Returns {@code mask} with {@code code} added. */
    public static int with(int mask, AnomalyCode code) {
        return mask | (1 << code.ordinal());
    }

    public static boolean has(int mask, AnomalyCode code) {
        return (mask & (1 << code.ordinal())) != 0;
    }

    /** The codes in {@code mask}, in contract (enum) order. Unmodifiable. */
    public static List<AnomalyCode> list(int mask) {
        if (mask == NONE) return Collections.emptyList();
        List<AnomalyCode> out = new ArrayList<>(Integer.bitCount(mask));
        for (AnomalyCode c : CODES) {
            if (has(mask, c)) out.add(c);
        }
        return Collections.unmodifiableList(out);
    }

    /** The mask for {@code codes}. */
    public static int of(AnomalyCode... codes) {
        int mask = NONE;
        for (AnomalyCode c : codes) mask = with(mask, c);
        return mask;
    }

    /**
     * The code that forces a class, or {@code null}. A code that forces {@link ClassId#MALFORMED_SMS} wins over
     * every other code: known damage is the stricter class ({@link AnomalyCode#MTI} gives
     * {@link ClassId#UNREADABLE_SMS} only alone). After that, the first code in contract order: the enum lists the TPDU
     * codes first, then the WSP codes, then {@link AnomalyCode#OTA_HDR}.
     */
    public static AnomalyCode forcing(int mask) {
        AnomalyCode first = null;
        for (AnomalyCode c : CODES) {
            if (!has(mask, c) || c.forcedClass() == null) continue;
            if (c.forcedClass() == ClassId.MALFORMED_SMS) return c;
            if (first == null) first = c;
        }
        return first;
    }

    /**
     * True when {@code mask} has a code of known damage ({@link ClassId#MALFORMED_SMS}). {@link AnomalyCode#MTI} also
     * stops a full parse, but it is not in this set: it gives {@link ClassId#UNREADABLE_SMS}.
     */
    public static boolean malformed(int mask) {
        for (AnomalyCode c : CODES) {
            if (has(mask, c) && c.forcedClass() == ClassId.MALFORMED_SMS) return true;
        }
        return false;
    }

    /** Wire names joined with commas, for test messages and diagnostics (no message content). */
    public static String names(int mask) {
        StringBuilder sb = new StringBuilder();
        for (AnomalyCode c : list(mask)) {
            if (sb.length() > 0) sb.append(',');
            sb.append(c.wire());
        }
        return sb.toString();
    }
}
