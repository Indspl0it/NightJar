package dev.nightjar.payload.env;

/**
 * The single payload worker. One {@code HandlerThread("Nightjar")} on device; a
 * manual queue in tests.
 *
 * <p>Everything that may block — correlation, sealing, JSON, IPC, snapshot parsing, spool I/O —
 * runs here. Hook threads only enqueue.
 */
public interface Worker {
    /** Posts {@code r} for immediate execution on the worker thread. Never blocks. */
    void post(Runnable r);

    /** Posts {@code r} after {@code delayMs}; later posts with a shorter delay may run first. */
    void postDelayed(Runnable r, long delayMs);

    /** Cancels a pending {@link #postDelayed(Runnable, long)}. */
    void cancel(Runnable r);

    /** True when called from the worker thread. */
    boolean isWorkerThread();
}
