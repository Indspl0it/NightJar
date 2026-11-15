package dev.nightjar.payload.hook;


/**
 * One hook invocation. {@code args[0]} is {@code this} (null for a static method),
 * {@code args[1..n]} the parameters.
 *
 * <p>The backend passes no placeholder for a static target ({@code args = [p0, p1, …]}), so the
 * hook layer tells {@link Param} whether the target is static and this class hides the difference:
 * the view is always {@code [this-or-null, p0, p1, …]}.
 *
 * <p>{@link #setResult(Object)} skips the original and returns the value; {@link #skip()} skips a
 * void method. A result of the wrong type, or {@code -1} for a hook flagged {@code neverMinusOne},
 * is rejected: the {@link Hooker} counts the error and calls the original as if no hook existed
 * .
 *
 * <p>Thread: the hooked thread; one instance per invocation.
 */
public final class Param {
    private final HookSpec spec;
    private final Object[] args;
    private final Class<?> returnType;
    private final boolean staticTarget;
    private Object result;
    private Throwable throwable;
    private boolean skip;

    Param(HookSpec spec, Object[] args, Class<?> returnType) {
        this(spec, args, returnType, false);
    }

    Param(HookSpec spec, Object[] args, Class<?> returnType, boolean staticTarget) {
        this.spec = spec;
        this.args = args == null ? new Object[0] : args;
        this.returnType = returnType;
        this.staticTarget = staticTarget;
    }

    /**
     * Builds a {@link Param} for a diagnostic or test call of a hook body, {@code args = [this, p0…]}.
     *
     * <p>The real path constructs one per invocation inside {@link Hooker}; this factory exists so a hook body can be
     * exercised without an installed hook. For a static target use
     * {@link #of(HookSpec, Object[], Class, boolean)}.
     */
    public static Param of(HookSpec spec, Object[] args, Class<?> returnType) {
        return new Param(spec, args, returnType);
    }

    /** As {@link #of(HookSpec, Object[], Class)}; {@code staticTarget} true means {@code args = [p0, p1, …]}. */
    public static Param of(HookSpec spec, Object[] args, Class<?> returnType, boolean staticTarget) {
        return new Param(spec, args, returnType, staticTarget);
    }

    public String hookId() {
        return spec.id();
    }

    public HookSpec spec() {
        return spec;
    }

    /** {@code args[0]} (the receiver, or null for a static method). */
    public Object thisObject() {
        return staticTarget || args.length == 0 ? null : args[0];
    }

    /** Full native argument array, {@code [this, p0, p1…]} for an instance target. */
    public Object[] args() {
        return args;
    }

    /** Parameter {@code index} (0-based, excluding the receiver); null when out of range. */
    public Object arg(int index) {
        int i = staticTarget ? index : index + 1;
        return i < args.length ? args[i] : null;
    }

    public int argCount() {
        return staticTarget ? args.length : Math.max(0, args.length - 1);
    }

    /** True when the hooked method is static (the backend passes no {@code this}). */
    public boolean staticTarget() {
        return staticTarget;
    }

    /** The original result, for an after-hook. */
    public Object result() {
        return result;
    }

    /** The throwable the original raised, for an after-hook; null when it returned normally. */
    public Throwable throwable() {
        return throwable;
    }

    /**
     * Skips the original and returns {@code value}.
     *
     * @throws IllegalArgumentException when the value cannot be the method's return value, or is
     *     {@code -1} for a {@code neverMinusOne} hook
     */
    public void setResult(Object value) {
        if (spec.neverMinusOne() && Integer.valueOf(-1).equals(value)) {
            throw new IllegalArgumentException(spec.id() + " may never skip with -1");
        }
        if (!isCompatible(returnType, value)) {
            throw new IllegalArgumentException(
                    spec.id() + " cannot return " + (value == null ? "null" : value.getClass().getName())
                            + " from " + returnType.getName());
        }
        this.result = value;
        this.skip = true;
    }

    /** Skips a void original. */
    public void skip() {
        this.result = null;
        this.skip = true;
    }

    public boolean skipped() {
        return skip;
    }

    /** Clears the skip after an error, so the original runs. */
    void clearSkip() {
        skip = false;
        result = null;
    }

    void setResultInternal(Object value) {
        this.result = value;
    }

    void setThrowable(Throwable t) {
        this.throwable = t;
    }

    /** Boxing-aware assignability check. */
    static boolean isCompatible(Class<?> type, Object value) {
        if (type == null || type == void.class || type == Void.class) return value == null;
        if (value == null) return !type.isPrimitive();
        Class<?> boxed = box(type);
        return boxed.isInstance(value);
    }

    private static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == long.class) return Long.class;
        if (t == boolean.class) return Boolean.class;
        if (t == byte.class) return Byte.class;
        if (t == short.class) return Short.class;
        if (t == char.class) return Character.class;
        if (t == float.class) return Float.class;
        if (t == double.class) return Double.class;
        return t;
    }
}
