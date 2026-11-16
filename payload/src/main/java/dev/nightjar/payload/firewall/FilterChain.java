package dev.nightjar.payload.firewall;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure list algebra for HK-R2: prepend our filter, keep every existing filter and its order, and stay
 * idempotent so the constructor hook and the startup sweep cannot install twice.
 *
 * <p>Framework-free so it is unit-tested on the JVM; {@link FilterInstaller} only adds reflection and the framework
 * call.
 *
 * <p>Thread: the handler's looper.
 */
public final class FilterChain {

    private FilterChain() {}

    /** True when the chain already starts with a filter of {@code type}. */
    public static boolean startsWith(List<?> existing, Class<?> type) {
        return existing != null && !existing.isEmpty() && existing.get(0) != null
                && existing.get(0).getClass() == type;
    }

    /**
     * Returns the chain to install: {@code filter} followed by {@code existing}, or {@code existing} itself when our
     * filter is already first.
     */
    public static List<Object> prepend(List<?> existing, Object filter) {
        if (filter == null) return existing == null ? List.of() : new ArrayList<>(existing);
        if (startsWith(existing, filter.getClass())) return new ArrayList<>(existing);
        List<Object> out = new ArrayList<>((existing == null ? 0 : existing.size()) + 1);
        out.add(filter);
        if (existing != null) out.addAll(existing);
        return out;
    }

    /**
     * The value of the first field {@code name} in the class hierarchy of {@code owner}: the list, an empty list when
     * the field holds null, or {@code null} when the field is absent, unreadable or not a list. A {@code null} result
     * means "do not install": a new chain built without the stock filters would drop them.
     */
    public static List<?> read(Object owner, String name) {
        if (owner == null || name == null) return null;
        Class<?> k = owner.getClass();
        while (k != null) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                Object value = f.get(owner);
                if (value == null) return List.of();
                return value instanceof List<?> list ? list : null;
            } catch (NoSuchFieldException e) {
                k = k.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
