package dev.nightjar.payload.hooks;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached reflective field reads used by hook bodies. Only field reads: the values
 * ({@code mPhoneId}, {@code mPhone}, {@code mImpl}) are not on any public stub, and the classes
 * live in another class loader.
 *
 * <p>Every accessor is total: a missing field yields the caller's default, never an exception.
 * Thread: any; the cache is concurrent and reads never allocate after the first call.
 */
final class Reflect {
    private static final ConcurrentHashMap<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, java.lang.reflect.Method> METHODS =
            new ConcurrentHashMap<>();
    private static final Field MISSING;

    static {
        Field f;
        try {
            f = Object.class.getDeclaredField("__nightjar_missing__");
        } catch (NoSuchFieldException e) {
            f = null;
        }
        MISSING = f;
    }

    private Reflect() {}

    /** The field on {@code owner} or its superclasses, or null. */
    static Field field(Class<?> owner, String name) {
        if (owner == null || name == null) return null;
        String key = owner.getName() + '#' + name;
        Field cached = FIELDS.get(key);
        if (cached != null) return cached == MISSING ? null : cached;
        Field found = null;
        Class<?> k = owner;
        while (k != null) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                found = f;
                break;
            } catch (NoSuchFieldException | RuntimeException ignored) {
                k = k.getSuperclass();
            }
        }
        FIELDS.put(key, found == null ? MISSING : found);
        return found;
    }

    /** Reads a field from {@code target}; {@code fallback} on any error. */
    static Object get(Object target, String name, Object fallback) {
        if (target == null) return fallback;
        try {
            Field f = field(target.getClass(), name);
            return f == null ? fallback : f.get(target);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Reads an int field; {@code fallback} on any error. */
    static int getInt(Object target, String name, int fallback) {
        Object v = get(target, name, null);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    /** Walks a field chain ({@code mRil.mPhoneId}); {@code fallback} on any error. */
    static Object getPath(Object target, String... names) {
        Object cur = target;
        for (String n : names) {
            cur = get(cur, n, null);
            if (cur == null) return null;
        }
        return cur;
    }

    /**
     * Calls a no-argument method, caching the {@link java.lang.reflect.Method} per class and name so a
     * hook thread does not allocate a lookup per invocation. {@code fallback} on any error.
     */
    static Object call(Object target, String method, Object fallback) {
        if (target == null || method == null) return fallback;
        try {
            String key = target.getClass().getName() + '#' + method;
            java.lang.reflect.Method cached = METHODS.get(key);
            if (cached == null) {
                cached = target.getClass().getMethod(method);
                cached.setAccessible(true);
                java.lang.reflect.Method previous = METHODS.putIfAbsent(key, cached);
                if (previous != null) cached = previous;
            }
            return cached.invoke(target);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Reads an int from {@link #call}; {@code fallback} on any error. */
    static int callInt(Object target, String method, int fallback) {
        Object v = call(target, method, null);
        return v instanceof Number n ? n.intValue() : fallback;
    }
}
