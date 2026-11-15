package dev.nightjar.payload.ipc;

/**
 * Hook health as built by the hook registry, plus the drop counter. Sent in {@code hello} and on every
 * change.
 *
 * <p>Thread: IPC worker.
 */
public interface HookHealthSource {

    /** JSON object. */
    String hookHealthJson();

    /** Events lost to the buffer cap since boot ({@code hookHealth.droppedCount}). */
    long droppedCount();
}
