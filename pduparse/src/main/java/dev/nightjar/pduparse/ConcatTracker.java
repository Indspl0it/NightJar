package dev.nightjar.pduparse;

import dev.nightjar.contract.AnomalyCode;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Detects {@code AN-CONCAT-DUP}: the same sequence number received twice for one concatenation reference.
 * One SMS cannot show this, so it is the only stateful check of the parser library.
 *
 * <p>The key is {@code (sender, reference, total)}. Memory is bounded: at most {@code maxGroups} groups, the oldest
 * group goes first, and a group older than {@code windowMs} starts again. A hostile sender cannot grow it.
 *
 * <p>Thread: not thread-safe. Confine one instance to one thread (the payload worker); it takes no lock.
 */
public final class ConcatTracker {
    /** Default window: a multipart message that is not complete after 24 hours is dropped (CL-MULTIPART-INCOMPLETE). */
    public static final long DEFAULT_WINDOW_MS = 24L * 60 * 60 * 1000;
    /** Default number of groups kept. */
    public static final int DEFAULT_MAX_GROUPS = 200;

    private static final class Group {
        final long firstMs;
        final long[] seen = new long[4]; // sequence numbers 1..255

        Group(long firstMs) {
            this.firstMs = firstMs;
        }
    }

    private final int maxGroups;
    private final long windowMs;
    private final LinkedHashMap<String, Group> groups = new LinkedHashMap<>();

    public ConcatTracker() {
        this(DEFAULT_MAX_GROUPS, DEFAULT_WINDOW_MS);
    }

    public ConcatTracker(int maxGroups, long windowMs) {
        this.maxGroups = Math.max(1, maxGroups);
        this.windowMs = Math.max(0, windowMs);
    }

    /**
     * Records one part and returns true when the same part was already recorded inside the window.
     *
     * @param sender the originator as received (kept only in memory, never logged)
     */
    public boolean seen(String sender, int ref, int total, int seq, long nowMs) {
        if (seq < 1 || seq > 255 || total < 1) return false;
        String key = (sender == null ? "" : sender) + '\u0000' + ref + '\u0000' + total;
        Group g = groups.get(key);
        if (g != null && nowMs - g.firstMs > windowMs) {
            groups.remove(key);
            g = null;
        }
        if (g == null) {
            evict();
            g = new Group(nowMs);
            groups.put(key, g);
        }
        int word = seq >>> 6;
        long bit = 1L << (seq & 63);
        boolean dup = (g.seen[word] & bit) != 0;
        g.seen[word] |= bit;
        return dup;
    }

    /** Returns {@link Anomalies} with {@code AN-CONCAT-DUP} added when this part repeats one already seen. */
    public int check(TpduFacts facts, long nowMs) {
        if (facts == null) return Anomalies.NONE;
        int mask = facts.anomalyMask();
        if (facts.concatRef() < 0) return mask;
        if (seen(facts.originator(), facts.concatRef(), facts.concatTotal(), facts.concatSeq(), nowMs)) {
            mask = Anomalies.with(mask, AnomalyCode.CONCAT_DUP);
        }
        return mask;
    }

    public int size() {
        return groups.size();
    }

    public void clear() {
        groups.clear();
    }

    private void evict() {
        Iterator<Map.Entry<String, Group>> it = groups.entrySet().iterator();
        while (groups.size() >= maxGroups && it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
