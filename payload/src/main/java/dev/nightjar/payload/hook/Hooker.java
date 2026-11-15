package dev.nightjar.payload.hook;

import dev.nightjar.contract.HookState;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Runs one hook with the fail-open contract.
 *
 * <p><b>Callback shape.</b> The Xposed backend replaces the hooked method with {@link #call(Object[])}, which sequences
 * the whole invocation itself: {@code before} (BEFORE and AROUND) → skip? return → original through the
 * {@link Original} invoker → {@code after} (AFTER and AROUND) → result or rethrow.
 *
 * <p><b>Argument layout.</b> The backend passes {@code [this, p0, …]} for an instance target and {@code [p0, …]} for a
 * static one. The target {@link Member} decides which layout applies; {@link Param} is given the same flag so hook
 * bodies always see {@code [this-or-null, p0, …]}.
 *
 * <p>Failure rules: a {@code before} error is counted and the original runs as if no hook existed; an {@code after}
 * error is counted and the original result (or throwable) stands; an original throwable is rethrown unchanged in every
 * combination, unless the hook's {@link Shield} turns it into a result (HK-14). A hook that the registry has disabled
 * runs the original only. When the original cannot be called at all (a reflective failure), the hook is disabled at
 * once, so later calls go straight to the framework.
 *
 * <p>Thread: the hooked thread.
 */
public final class Hooker {
    private final HookSpec spec;
    private final HookCallback callback;
    private final HookRegistry registry;
    private final FaultInjector faults;
    private final Class<?> returnType;
    private final Member target;
    private final boolean staticTarget;
    private volatile Original original;
    private volatile Shield shield;

    /**
     * Calls the unhooked method (the Xposed API runs the original through its bridge). Arguments are the target's real
     * parameters, without the receiver.
     */
    public interface Original {
        Object invoke(Object receiver, Object[] params) throws Throwable;
    }

    /** Turns an exception of the original into a result, or null to rethrow it. */
    public interface Shield {
        Object recover(Object[] args, Throwable t);
    }

    public Hooker(HookSpec spec, HookCallback callback, HookRegistry registry, FaultInjector faults,
            Member target) {
        this.spec = spec;
        this.callback = callback;
        this.registry = registry;
        this.faults = faults == null ? FaultInjector.NONE : faults;
        this.target = target;
        this.staticTarget = target instanceof Method m && Modifier.isStatic(m.getModifiers());
        this.returnType = target instanceof Method m ? m.getReturnType() : Object.class;
    }

    public HookSpec spec() {
        return spec;
    }

    public Member target() {
        return target;
    }

    /** True when the hooked member is static (no {@code this} in the callback arguments). */
    public boolean staticTarget() {
        return staticTarget;
    }

    public void setShield(Shield shield) {
        this.shield = shield;
    }

    /** Installs the original-method invoker of the backend. Set before the hook goes live. */
    public void setOriginal(Original original) {
        this.original = original;
    }

    /**
     * The single callback the backend invokes (see the class comment).
     *
     * @param args {@code [this, p0, p1, …]}, or {@code [p0, …]} for a static target
     * @return the value the hooked method returns
     * @throws Throwable the original's throwable, unchanged
     */
    public Object call(Object[] args) throws Throwable {
        if (registry.state(spec.id()) == HookState.DISABLED) {
            // The hook is inert; never run the body again (the uninstall is queued on the
            // worker). The callback can still fire from a thread already inside the hook.
            return invokeOriginal(args);
        }
        boolean runBefore = spec.kind() != HookKind.AFTER;
        boolean runAfter = spec.kind() != HookKind.BEFORE;
        if (runBefore) {
            long t0 = System.nanoTime();
            Param p = new Param(spec, args, returnType, staticTarget);
            boolean hookerRan = false;
            try {
                faults.maybeThrow(spec.id(), FaultInjector.Phase.BEFORE);
                callback.before(p);
                hookerRan = true;
            } catch (Throwable t) {
                registry.onError(spec.id(), t);
                p.clearSkip();
            } finally {
                registry.recordLatency(spec.id(), System.nanoTime() - t0);
            }
            if (hookerRan && p.skipped()) return safeResult(p.result());
        }

        Object result;
        Throwable error = null;
        try {
            result = invokeOriginal(args);
        } catch (Throwable t) {
            result = null;
            error = t;
            Shield s = shield;
            if (s != null) {
                try {
                    Object recovered = s.recover(args, t);
                    if (recovered != null) {
                        result = recovered;
                        error = null;
                        registry.recovered(spec.id());
                    }
                } catch (Throwable ignored) {
                    // Keep the original throwable.
                }
            }
        }

        if (!runAfter) {
            if (error != null) throw error;
            return result;
        }

        long t1 = System.nanoTime();
        Param after = new Param(spec, args, returnType, staticTarget);
        after.setResultInternal(result);
        after.setThrowable(error);
        try {
            faults.maybeThrow(spec.id(), FaultInjector.Phase.AFTER);
            callback.after(after);
        } catch (Throwable t) {
            registry.onError(spec.id(), t);
        } finally {
            registry.recordLatency(spec.id(), System.nanoTime() - t1);
        }
        if (after.skipped()) result = safeResult(after.result());
        if (error != null) throw error;
        return result;
    }

    /**
     * Calls the original with the target's real argument layout.
     *
     * <p>No invoker means no installed hook (the JVM tests, {@link HookBackend#NONE}): there is nothing to call. A
     * reflective failure of the call itself is our failure, not the original's: it must not surface in telephony as if
     * the original threw, and it will repeat on every call, so the hook is disabled at once.
     */
    private Object invokeOriginal(Object[] args) throws Throwable {
        Original o = original;
        if (o == null) return null;
        Object receiver = staticTarget ? null : (args.length > 0 ? args[0] : null);
        int first = staticTarget ? 0 : 1;
        Object[] params = new Object[Math.max(0, args.length - first)];
        System.arraycopy(args, first, params, 0, params.length);
        try {
            return o.invoke(receiver, params);
        } catch (InvocationTargetException e) {
            throw e.getCause() == null ? e : e.getCause();
        } catch (IllegalAccessException | IllegalArgumentException | NullPointerException
                | ExceptionInInitializerError e) {
            registry.disable(spec.id(), e);
            return defaultValue(returnType);
        }
    }

    /** A skipped primitive-returning method must hand back a boxed value, never null (unboxing). */
    private Object safeResult(Object value) {
        return value == null ? defaultValue(returnType) : value;
    }

    static Object defaultValue(Class<?> type) {
        if (type == null || !type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    @Override
    public String toString() {
        return "Hooker{" + spec.id() + " " + (target == null ? "?" : target.getName()) + "}";
    }
}
