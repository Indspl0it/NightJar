package dev.nightjar.payload.ipc;

import dev.nightjar.contract.Control;
import dev.nightjar.contract.Ipc;
import dev.nightjar.payload.log.PLog;

/**
 * Semantics of every {@code IPayloadControl} method, free of Android types so it runs in JVM
 * tests. {@link ControlBinder} only marshals parcels and calls this.
 *
 * <p>Rules: nothing throws. Invalid arguments map to the documented result codes; an unexpected failure inside the
 * payload maps to a generic failure and is logged as W. A failing control call must never affect capture.
 *
 * <p>Thread: binder threads.
 */
public final class ControlHandler {

    private static final String TAG = "Control";
    private static final int MAX_RELEASE_PARTS = 32;
    /** {@code subId} -1: the default SMS subscription, resolved by the target. */
    static final int DEFAULT_SUB = -1;

    private final ControlTarget target;
    private final PLog log;

    public ControlHandler(ControlTarget target, PLog log) {
        this.target = target;
        this.log = log;
    }

    public void updateRules(String json) {
        if (json == null || json.isEmpty()) {
            warn("rules-empty", "The payload ignores an updateRules call without a body.");
            return;
        }
        // The binder path had no limit at all, so one oversized push could allocate the phone
        // process out of memory. Same budget as the handshake snapshot.
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > Ipc.MAX_RULES_BYTES) {
            warn("rules-oversize", "The payload ignores an updateRules call over the byte limit.");
            return;
        }
        try {
            target.updateRules(json);
        } catch (Throwable t) {
            warnFailure("updateRules", t);
        }
    }

    public void updateSettings(String json) {
        if (json == null || json.isEmpty()) {
            warn("settings-empty", "The payload ignores an updateSettings call without a body.");
            return;
        }
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > Ipc.MAX_SETTINGS_BYTES) {
            warn("settings-oversize", "The payload ignores an updateSettings call over the byte limit.");
            return;
        }
        try {
            target.updateSettings(json);
        } catch (Throwable t) {
            warnFailure("updateSettings", t);
        }
    }

    public void setSafeMode(boolean on) {
        try {
            target.setSafeMode(on);
        } catch (Throwable t) {
            warnFailure("setSafeMode", t);
        }
    }

    public void enableFirewall(boolean on) {
        try {
            target.enableFirewall(on);
        } catch (Throwable t) {
            warnFailure("enableFirewall", t);
        }
    }

    /**
     * Releases a quarantined message (05).
     *
     * @return {@link Control.ReleaseResult#INVALID_ARGUMENTS} for a malformed request, otherwise the target's result;
     *     an unexpected failure yields {@link Control.ReleaseResult#GENERIC_FAILURE}
     */
    public int release(String eventId, byte[][] pdus, String format, int subId) {
        if (!validRelease(eventId, pdus, format, subId)) {
            warn("release-invalid", "The payload refuses a release call: arguments are not valid.");
            return Control.ReleaseResult.INVALID_ARGUMENTS;
        }
        try {
            return target.release(eventId, pdus, format, subId);
        } catch (Throwable t) {
            warnFailure("release", t);
            return Control.ReleaseResult.GENERIC_FAILURE;
        }
    }

    /**
     * Replays a stored message through the internal injection path. The payload marks the new record
     * {@code flags.replay = true} and links it to {@code eventId}; nothing is transmitted.
     *
     * @return {@link Control.ReleaseResult#INVALID_ARGUMENTS} for a malformed request, otherwise the target's result;
     *     an unexpected failure yields {@link Control.ReleaseResult#GENERIC_FAILURE}
     */
    public int replay(String eventId, byte[][] pdus, String format, int subId) {
        if (!validRelease(eventId, pdus, format, subId)) {
            warn("replay-invalid", "The payload refuses a replay call: arguments are not valid.");
            return Control.ReleaseResult.INVALID_ARGUMENTS;
        }
        try {
            return target.replay(eventId, pdus, format, subId);
        } catch (Throwable t) {
            warnFailure("replay", t);
            return Control.ReleaseResult.GENERIC_FAILURE;
        }
    }

    /** @return a run id, or {@code ERR:<code>} */
    public String runSelfTest(String classesJson) {
        if (classesJson == null || classesJson.isEmpty()) {
            return Control.SelfTestError.PREFIX + Control.SelfTestError.INVALID;
        }
        try {
            String runId = target.runSelfTest(classesJson);
            if (runId == null || runId.isEmpty()) {
                return Control.SelfTestError.PREFIX + Control.SelfTestError.INVALID;
            }
            return runId;
        } catch (Throwable t) {
            warnFailure("runSelfTest", t);
            return Control.SelfTestError.PREFIX + Control.SelfTestError.INVALID;
        }
    }

    public void cancelSelfTest(String runId) {
        if (runId == null || runId.isEmpty()) return;
        try {
            target.cancelSelfTest(runId);
        } catch (Throwable t) {
            warnFailure("cancelSelfTest", t);
        }
    }

    public String hookHealthJson() {
        try {
            String json = target.hookHealthJson();
            return json == null ? "{}" : json;
        } catch (Throwable t) {
            warnFailure("getHookHealth", t);
            return "{}";
        }
    }

    public String logJson(long sinceSeq) {
        try {
            String json = target.logJson(sinceSeq);
            return json == null ? "[]" : json;
        } catch (Throwable t) {
            warnFailure("getLog", t);
            return "[]";
        }
    }

    public long pingMs() {
        try {
            return target.pingMs();
        } catch (Throwable t) {
            warnFailure("ping", t);
            return 0L;
        }
    }

    private void warn(String key, String message) {
        if (log != null) log.wLimited(key, TAG, message);
    }

    /** One W line per failing method and rate window, so a failing target cannot flood the log. */
    private void warnFailure(String method, Throwable t) {
        if (log != null) log.wLimited("fail-" + method, TAG, method + " failed: " + t.getClass().getSimpleName());
    }

    static boolean validRelease(String eventId, byte[][] pdus, String format, int subId) {
        if (eventId == null || eventId.isEmpty()) return false;
        if (format == null || format.isEmpty()) return false;
        if (subId < DEFAULT_SUB) return false;
        if (pdus == null || pdus.length == 0 || pdus.length > MAX_RELEASE_PARTS) return false;
        for (byte[] part : pdus) {
            if (part == null || part.length == 0) return false;
        }
        return true;
    }

    /** Oneway control codes; {@link ControlBinder} uses the same table. */
    public static boolean isOneway(int code) {
        switch (code) {
            case Control.Code.UPDATE_RULES:
            case Control.Code.UPDATE_SETTINGS:
            case Control.Code.SET_SAFE_MODE:
            case Control.Code.ENABLE_FIREWALL:
            case Control.Code.CANCEL_SELF_TEST:
                return true;
            default:
                return false;
        }
    }
}
