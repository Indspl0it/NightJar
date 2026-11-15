package dev.nightjar.payload.ipc;

/**
 * L1 payload log. The app pulls E-level lines with {@code getLog} and pushes the same lines here (C.2
 * {@code log}); the worker advances {@code lastSeq} only after a successful call.
 *
 * <p>Thread: IPC worker.
 */
public interface LogSource {

    /** Sequence number of the newest line; {@code 0} when the ring is empty. */
    long latestSeq();

    /**
     * JSON array of {@code {seq, t, level, tag, msg}} for lines with {@code sinceSeq < seq <= upToSeq}. An empty array
     * when there are none. Messages are already masked.
     */
    String errorLinesJson(long sinceSeq, long upToSeq);

    /**
     * JSON array of every level (newest {@code max} lines) with {@code sinceSeq < seq <= upToSeq}: what the worker pushes
     * so the app's Payload tab shows the whole log. Defaults to the error lines for sources that keep nothing else.
     */
    default String linesJson(long sinceSeq, long upToSeq, int max) {
        return errorLinesJson(sinceSeq, upToSeq);
    }
}
