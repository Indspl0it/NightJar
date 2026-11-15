package dev.nightjar.payload.ipc;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONTokener;

/**
 * The {@code acked} list of an {@code events} reply: strings {@code "eventId:revision"}.
 *
 * <p>Parsing is defensive: a malformed entry is ignored rather than failing the whole batch, because the event then
 * simply stays queued and is retried.
 *
 * <p>Thread: IPC worker only.
 */
public final class AckSet {

    private static final AckSet EMPTY = new AckSet(Collections.emptySet());

    private final Set<String> keys;

    private AckSet(Set<String> keys) {
        this.keys = keys;
    }

    public static AckSet empty() {
        return EMPTY;
    }

    /**
     * Parses the reply value.
     *
     * @param json JSON array of strings; {@code null}, blank or malformed input yields the empty set
     */
    public static AckSet parse(String json) {
        if (json == null || json.isBlank()) return EMPTY;
        Object parsed;
        try {
            parsed = new JSONTokener(json).nextValue();
        } catch (Exception e) {
            // JSONException (checked in the Android stub) or a RuntimeException.
            return EMPTY;
        }
        if (!(parsed instanceof JSONArray)) return EMPTY;
        JSONArray array = (JSONArray) parsed;
        Set<String> keys = new HashSet<>(array.length() * 2);
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (!(item instanceof String)) continue;
            String key = (String) item;
            // Require "<eventId>:<positive revision>" so a stray string cannot ack the wrong revision.
            int colon = key.lastIndexOf(':');
            if (colon <= 0 || colon == key.length() - 1) continue;
            if (!isDigits(key, colon + 1)) continue;
            keys.add(key);
        }
        return new AckSet(keys);
    }

    /** True when the app acknowledged exactly this event revision. */
    public boolean acked(String eventId, int revision) {
        return eventId != null && keys.contains(eventId + ":" + revision);
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public int size() {
        return keys.size();
    }

    private static boolean isDigits(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
