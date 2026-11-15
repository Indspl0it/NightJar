package dev.nightjar.payload.ipc;

import dev.nightjar.payload.log.PLog;

/**
 * Caller check for the control binder: only the app uid resolved at handshake may transact.
 *
 * <p>Before the first handshake there is no app uid, so every transaction is rejected — the binder is handed to the app
 * inside {@code hello}, which happens after the uid is known. Rejections are logged at W, first and every tenth
 * .
 *
 * <p>Thread: binder threads.
 */
public final class CallerGate {

    private static final String TAG = "ControlCaller";

    private final PLog log;
    private volatile int appUid = -1;

    public CallerGate(PLog log) {
        this.log = log;
    }

    /** Records the uid that owns the app package; called by the handshake. */
    public void setAppUid(int uid) {
        this.appUid = uid;
    }

    public int appUid() {
        return appUid;
    }

    /** @return true when {@code callingUid} is the app */
    public boolean allowed(int callingUid) {
        int expected = appUid;
        if (expected >= 0 && callingUid == expected) return true;
        if (log != null) log.wLimited("control-caller", TAG, "control call rejected from a foreign uid");
        return false;
    }

    public void reset() {
        appUid = -1;
    }
}
