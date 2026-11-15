package dev.nightjar.payload.hook;

/**
 * Deliberate hook failures for unit tests: the tests use it to make a hook throw and prove that it fails open. The
 * APK always uses {@link #NONE}.
 *
 * <p>Thread: the hooked thread (it throws from inside the hook body).
 */
public interface FaultInjector {
    /** Hook phase, matching the mode strings {@code throw-before}/{@code throw-after}/{@code throw-both}. */
    enum Phase {
        BEFORE,
        AFTER
    }

    /** Throws when this hook is configured to fail in {@code phase}. */
    void maybeThrow(String hookId, Phase phase) throws Throwable;

    /** No failures (release build). */
    FaultInjector NONE = (hookId, phase) -> {};
}
