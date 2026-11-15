package dev.nightjar.payload.env;

/**
 * Monotonic and wall time, injectable so the capture pipeline runs on the JVM.
 *
 * <p>{@link #elapsedNs()} is the only clock used for durations, timeouts and trace offsets
 * ({@code SystemClock.elapsedRealtimeNanos()} on device). {@link #wallMs()} is recorded once per
 * event for display. Thread: any.
 */
public interface Clock {
    /** Monotonic nanoseconds since boot. Never decreases. */
    long elapsedNs();

    /** Wall-clock milliseconds since the epoch. */
    long wallMs();

    /** Monotonic milliseconds since boot. */
    default long elapsedMs() {
        return elapsedNs() / 1_000_000L;
    }
}
