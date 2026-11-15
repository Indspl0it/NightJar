package dev.nightjar.payload.ipc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The app's reply to one {@link IpcRequest}. A {@code null} reply from the transport is not a reply at
 * all: {@link IpcWorker} treats it as {@link IpcException.Kind#REJECTED}.
 *
 * <p>Thread: IPC worker only.
 */
public final class IpcReply {

    private final Map<String, Object> values;

    public IpcReply(Map<String, Object> values) {
        this.values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Empty reply (a provider that answered without extras). */
    public static IpcReply empty() {
        return new IpcReply(Map.of());
    }

    public Map<String, Object> values() {
        return values;
    }

    public Object get(String key) {
        return values.get(key);
    }

    public String getString(String key) {
        Object v = values.get(key);
        return v instanceof String ? (String) v : null;
    }

    public int getInt(String key, int fallback) {
        Object v = values.get(key);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    public long getLong(String key, long fallback) {
        Object v = values.get(key);
        return v instanceof Number ? ((Number) v).longValue() : fallback;
    }

    public boolean getBoolean(String key, boolean fallback) {
        Object v = values.get(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    @Override
    public String toString() {
        return "IpcReply{" + values.size() + " keys}";
    }
}
