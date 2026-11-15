package dev.nightjar.payload;

import java.util.Map;
import java.util.TreeMap;

/**
 * Small JSON builder: a {@link TreeMap} of keys with a deterministic
 * {@link #json()} (see {@link Json}). Values that are {@code null} are dropped, which is how the
 * event DTO omits absent fields.
 *
 * <p>Thread: any; one builder per thread.
 */
public final class JsonBuilder {
    private final TreeMap<String, Object> map = new TreeMap<>();

    public JsonBuilder put(String key, Object value) {
        if (key != null && value != null && value != org.json.JSONObject.NULL) map.put(key, value);
        return this;
    }

    public JsonBuilder put(String key, long value) {
        return put(key, (Object) Long.valueOf(value));
    }

    public JsonBuilder put(String key, int value) {
        return put(key, (Object) Integer.valueOf(value));
    }

    public JsonBuilder put(String key, boolean value) {
        return put(key, (Object) Boolean.valueOf(value));
    }

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    /** The backing map, for callers that need to keep mutating it. */
    public Map<String, Object> map() {
        return map;
    }

    /** Deterministic JSON text, keys sorted. */
    public String json() {
        return Json.write(map);
    }

    @Override
    public String toString() {
        return json();
    }

}
