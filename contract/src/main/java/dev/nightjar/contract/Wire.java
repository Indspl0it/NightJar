package dev.nightjar.contract;

/** Lookup helpers for {@link WireEnum} values. */
public final class Wire {
    private Wire() {}

    /**
     * The constants of each enum type, read once. {@link Class#getEnumConstants()} returns a new copy on each call,
     * and {@link #parse} also runs on payload paths.
     */
    private static final ClassValue<Object[]> CONSTANTS = new ClassValue<>() {
        @Override
        protected Object[] computeValue(Class<?> type) {
            return type.getEnumConstants();
        }
    };

    /** Returns the constant whose wire name equals {@code wire}, or {@code fallback} if none does. */
    public static <E extends Enum<E> & WireEnum> E parse(Class<E> type, String wire, E fallback) {
        if (wire == null) return fallback;
        for (Object o : CONSTANTS.get(type)) {
            E e = type.cast(o);
            if (e.wire().equals(wire)) return e;
        }
        return fallback;
    }
}
