package dev.nightjar.payload.capture;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Multipart groups. Group key is
 * {@code (subId, originator, concatRef, total)}; parts are held by sequence number so an
 * out-of-order arrival still completes the set.
 *
 * <p>Parts join the group as soon as they are parsed, not at seal time: a part that is still
 * unsealed must be visible to the group, otherwise a normal two-part message is emitted twice.
 * Each part keeps its own event (its downstream stages join it by key); the group holds the
 * events so the seal can retire all of them at once with the consumer fate.
 *
 * <p>Thread: worker only.
 */
public final class MultipartGroups {
    /** Retained groups are capped so a hostile sender cannot grow the phone process. */
    public static final int MAX_GROUPS = 200;
    /** Retained PDU bytes across all groups. */
    public static final int MAX_BYTES = 1 << 20;

    /**
     * Group key. {@code radio} is the phone id the parts came in on, or the subscription id when the
     * phone id is unknown.
     */
    public record Key(int radio, String originator, int ref, int total) {}

    /** One group; {@code eventId} is assigned at the first part and kept across revisions. */
    public static final class Group {
        public final Key key;
        public final String eventId;
        public final TreeMap<Integer, byte[]> parts = new TreeMap<>();
        public final TreeMap<Integer, Long> arrivals = new TreeMap<>();
        /** Member events by sequence number, for the merge at seal time. */
        public final TreeMap<Integer, Event> events = new TreeMap<>();
        public long firstWallMs;
        public int revision = 0;
        /** True once the group was emitted as CL-MULTIPART-INCOMPLETE (24 h after the first part). */
        public boolean incompleteSealed;
        int bytes;

        Group(Key key, String eventId, long firstWallMs) {
            this.key = key;
            this.eventId = eventId;
            this.firstWallMs = firstWallMs;
        }

        public int total() {
            return key.total();
        }

        public int have() {
            return parts.size();
        }

        public boolean complete() {
            if (parts.size() < key.total()) return false;
            for (int i = 1; i <= key.total(); i++) {
                if (!parts.containsKey(i)) return false;
            }
            return true;
        }
    }

    private final Map<Key, Group> groups = new LinkedHashMap<>();
    private int bytes;

    /**
     * Adds the member event of one part, creating the group on the first part. Re-adding the same
     * sequence number keeps the first PDU.
     */
    public Group add(Key key, int seq, byte[] pdu, long tNs, String eventId, long wallMs, Event e) {
        Group g = groups.get(key);
        if (g == null) {
            g = new Group(key, eventId, wallMs);
            groups.put(key, g);
        }
        if (g.parts.putIfAbsent(seq, pdu == null ? new byte[0] : pdu) == null) {
            g.bytes += pdu == null ? 0 : pdu.length;
            bytes += pdu == null ? 0 : pdu.length;
        }
        g.arrivals.putIfAbsent(seq, tNs);
        if (e != null) g.events.putIfAbsent(seq, e);
        return g;
    }

    public Group get(Key key) {
        return groups.get(key);
    }

    public void remove(Key key) {
        Group g = groups.remove(key);
        if (g != null) bytes -= g.bytes;
    }

    public Collection<Group> all() {
        return groups.values();
    }

    public int size() {
        return groups.size();
    }

    public int bytes() {
        return bytes;
    }
}
