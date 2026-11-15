package dev.nightjar.payload.hook;

/**
 * What a hook does with one invocation. Implementations must be fast and must not throw:
 * the {@link Hooker} catches everything, counts it and continues with the original behaviour
 * .
 *
 * <p>Thread: the thread that called the hooked method (main looper, HAL binder, IMS binder, scan
 * thread). No I/O, no binder, no JSON, no locks.
 */
public interface HookCallback {
    /** Runs before the original. Use {@link Param#setResult(Object)} to skip it. */
    void before(Param p) throws Throwable;

    /** Runs after the original, with the result or the throwable already set. */
    default void after(Param p) throws Throwable {
        // Most hooks are before-hooks.
    }
}
