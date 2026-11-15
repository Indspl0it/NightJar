package dev.nightjar.payload.ipc;

/**
 * What the control binder can ask the payload to do. Implemented by the payload glue, which posts
 * release and self-test injection to the worker and never blocks the binder thread for longer than the call.
 *
 * <p>Thread: binder threads. Implementations must be thread-safe.
 */
public interface ControlTarget {

    void updateRules(String json);

    void updateSettings(String json);

    void setSafeMode(boolean on);

    void enableFirewall(boolean on);

    /** @return one of {@link dev.nightjar.contract.Control.ReleaseResult} */
    int release(String eventId, byte[][] pdus, String format, int subId);

    /**
     * Replays a stored message through the internal injection path.
     *
     * @return one of {@link dev.nightjar.contract.Control.ReleaseResult}
     */
    int replay(String eventId, byte[][] pdus, String format, int subId);

    /** @return a run id, or {@code ERR:<code>} per {@link dev.nightjar.contract.Control.SelfTestError} */
    String runSelfTest(String classesJson);

    void cancelSelfTest(String runId);

    /** JSON. */
    String hookHealthJson();

    /** JSON array of {@code {seq, t, level, tag, msg}} with {@code seq > sinceSeq}. */
    String logJson(long sinceSeq);

    /** {@code SystemClock.elapsedRealtime()}. */
    long pingMs();
}
