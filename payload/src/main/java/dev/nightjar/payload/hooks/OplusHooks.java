package dev.nightjar.payload.hooks;

import dev.nightjar.payload.capture.Observation;
import dev.nightjar.payload.capture.Observations;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.payload.hook.Param;

/**
 * OnePlus meaning hooks HK-30…HK-32. All three are resolved through the class loader of
 * {@code InboundSmsHandler.mImpl} and are {@code NOT_APPLICABLE} on AOSP builds.
 *
 * <p>HK-30 runs before the filter chain, so an OEM drop can only be recorded, never prevented
 * (firmware audit 2026-11-05).
 *
 * <p>Thread: phone main looper (HK-30/31), scan thread (HK-32).
 */
public final class OplusHooks {
    private final Observations out;
    private final Clock clock;

    public OplusHooks(Observations out, Clock clock) {
        this.out = out;
        this.clock = clock;
    }

    /** HK-30 after-hook {@code OplusInboundSmsHandlerImpl#oemCheck(...)}: false = consumed. */
    public void afterOemCheck(Param p) {
        boolean consumed = p.result() instanceof Boolean b && !b;
        if (!consumed) return;
        out.offer(Observation.stage(MeaningHooks.keyFromTracker(p.arg(0)), dev.nightjar.contract.TraceStage.OEM_CHECK,
                clock.elapsedNs(), "reason=oemCheck"));
    }

    /**
     * HK-31 before-hook {@code romDispatchSmsDeliveryIntent(…, boolean blocked)}.
     *
     * <p>The call does not name the package that gets the message, and the OPlus build can choose it. So the stage
     * carries no package and the event keeps the package that an earlier stage found, or none.
     */
    public void beforeRedirect(Param p) {
        boolean blocked = p.arg(p.argCount() - 1) instanceof Boolean b && b;
        if (!blocked) return;
        // Argument 0 is the byte[][] PDUs and argument 1 the format string (firmware audit row 17).
        String key = p.arg(0) instanceof byte[][] pdus && pdus.length > 0 && pdus[0] != null
                ? CaptureHooks.keyFor(pdus[0]) : null;
        out.offer(Observation.stage(key, dev.nightjar.contract.TraceStage.OEM_REDIRECT,
                clock.elapsedNs(), "blocked=true"));
    }

    /** HK-32 after-hook {@code OplusSmsBroadcastUndelivered#scanRawTable}: 1-day orphan purge. */
    public void afterScanRawTable(Param p) {
        out.offer(Observation.stage(null, dev.nightjar.contract.TraceStage.OEM_SCAN_RAW_TABLE,
                clock.elapsedNs(), null));
    }
}
