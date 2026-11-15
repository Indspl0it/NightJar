package dev.nightjar.payload.ipc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One payload → app call: method name, authority and the extras.
 *
 * <p>Uses a plain map instead of {@code android.os.Bundle} so the whole IPC pipeline runs in JVM tests;
 * {@link ContentResolverTransport} converts it to a Bundle on the device.
 *
 * <p>Thread: built and used by the IPC worker only.
 */
public final class IpcRequest {

    private final String method;
    private final String authority;
    private final Map<String, Object> extras = new LinkedHashMap<>();

    public IpcRequest(String method, String authority) {
        this.method = method;
        this.authority = authority;
    }

    /** Adds an extra; {@code null} values are skipped (the contract has no null extras). */
    public IpcRequest put(String key, Object value) {
        if (key != null && value != null) extras.put(key, value);
        return this;
    }

    public String method() {
        return method;
    }

    public String authority() {
        return authority;
    }

    public Map<String, Object> extras() {
        return Collections.unmodifiableMap(extras);
    }

    public Object get(String key) {
        return extras.get(key);
    }

    public boolean has(String key) {
        return extras.containsKey(key);
    }

    public String getString(String key) {
        Object v = extras.get(key);
        return v instanceof String ? (String) v : null;
    }

    @Override
    public String toString() {
        // Never dump extras: they can hold message content.
        return "IpcRequest{" + method + ", " + extras.size() + " extras}";
    }
}
