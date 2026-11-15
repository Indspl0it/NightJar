package dev.nightjar.payload.ipc;

/**
 * Exponential backoff for failed calls: 1 s, 2 s, 4 s … capped at 60 s, reset on success.
 *
 * <p>The clock is supplied by the caller so tests can drive it; the worker passes
 * {@code SystemClock.elapsedRealtime()}.
 *
 * <p>Thread: IPC worker only.
 */
public final class Backoff {

    /** First delay after a failure. */
    public static final long INITIAL_MS = 1_000L;

    /** Upper bound, however many calls fail in a row. */
    public static final long MAX_MS = 60_000L;

    private long nextDelayMs = INITIAL_MS;
    private int failures;

    /** Delay to wait before the next attempt; doubles the following delay up to {@link #MAX_MS}. */
    public long onFailure() {
        long delay = nextDelayMs;
        failures++;
        nextDelayMs = Math.min(MAX_MS, nextDelayMs * 2);
        return delay;
    }

    /** A call succeeded: the next failure starts again at {@link #INITIAL_MS}. */
    public void onSuccess() {
        nextDelayMs = INITIAL_MS;
        failures = 0;
    }

    /** Failures since the last success. */
    public int failures() {
        return failures;
    }

    /** Delay that {@link #onFailure()} would return now. */
    public long currentDelayMs() {
        return nextDelayMs;
    }

    public void reset() {
        onSuccess();
    }
}
