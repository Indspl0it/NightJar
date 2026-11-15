package dev.nightjar.payload.hooks;

import dev.nightjar.contract.Hashes;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.payload.capture.Observation;
import dev.nightjar.payload.capture.Observations;
import dev.nightjar.payload.capture.WspMini;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.payload.hook.Param;

/**
 * HK-14 {@code WapPushOverSms#dispatchWapPdu(byte[] pdu, SmsBroadcastReceiver, InboundSmsHandler,
 * String address, int subId, long messageId)} — around, skip ¬−1.
 *
 * <p>The capture side parses the WSP header and records {@code wap.dispatch} with the content type;
 * the enforcement side is a {@link Port} implemented by the firewall package. A block
 * returns {@code 1} (consumed) and never {@code -1} (broadcast sent), which is why the hook is
 * flagged {@code neverMinusOne}.
 *
 * <p>Each push gets one {@code wap.dispatch} stage with its result and who decided it:
 * {@code by=nightjar} when the firewall consumed it in the before phase, {@code by=crash} when
 * {@link #recover} consumed it after the original threw, and no {@code by} when the after phase records
 * the original's own result (WapPushManager consumed it, or the broadcast went out).
 *
 * <p>The originating address ({@code address}, parameter 3) is part of the port contract: rules with
 * an originator condition, and the known-carrier test, need the sender the framework resolved.
 *
 * <p>Thread: phone main looper.
 */
public final class WapHooks {
    /** Enforcement seam, implemented by {@code firewall.WapEnforcer}. */
    public interface Port {
        /** {@code 1} to consume the PDU (block), or {@link #CONTINUE} to run the original. */
        int beforeDispatch(byte[] pdu, String contentType, String originator, int subId);

        /** The verdict behind the last non-{@link #CONTINUE} answer on this thread, for the trace; null when unknown. */
        default dev.nightjar.contract.Verdict lastVerdict() {
            return null;
        }

        /** Rule id behind {@link #lastVerdict()}, -1 when none. */
        default long lastRuleId() {
            return -1L;
        }

        /** Clamp reason behind {@link #lastVerdict()} ({@code CANARY} for a self-test), or null. */
        default String lastClamp() {
            return null;
        }

        Port NONE = (pdu, contentType, originator, subId) -> CONTINUE;
    }

    /** Sentinel: run the original. */
    public static final int CONTINUE = Integer.MIN_VALUE;

    /** Parameter index of {@code address} in {@code dispatchWapPdu}. */
    private static final int ADDRESS_ARG = 3;

    /** Parameter index of {@code subId}. */
    private static final int SUB_ID_ARG = 4;

    private final Observations out;
    private final Clock clock;
    private volatile Port port = Port.NONE;
    /** Set by {@link #recover} so the after phase of the same call does not record a second stage. */
    private final ThreadLocal<Boolean> recovered = new ThreadLocal<>();

    public WapHooks(Observations out, Clock clock) {
        this.out = out;
        this.clock = clock;
    }

    public void setPort(Port port) {
        this.port = port == null ? Port.NONE : port;
    }

    public void before(Param p) {
        recovered.remove();
        byte[] pdu = p.arg(0) instanceof byte[] b ? b : null;
        if (pdu == null) return;
        String originator = p.arg(ADDRESS_ARG) instanceof String s ? s : null;
        int subId = p.arg(SUB_ID_ARG) instanceof Number n ? n.intValue() : -1;
        WspMini.Result wsp = WspMini.parse(pdu);
        String contentType = wsp.contentType();
        String key = keyOf(p, pdu);
        int decision = port.beforeDispatch(pdu, contentType, originator, subId);
        if (decision == CONTINUE) return; // the after phase records the original's result
        String ct = contentType == null ? "unknown" : contentType;
        long now = clock.elapsedNs();
        // The verdict and the dispatch stage go in one observation. As two observations, whichever came first
        // sealed the event: a verdict first sealed it without the wap.dispatch stage, and the stage then opened a
        // second event for the same push (slot -1, no sender, no port). A stage first sealed it as consumed before the
        // verdict arrived. Every blocked or held push was stored twice, and the WAP self-test canaries failed.
        String detail = "ct=" + ct + " result=" + decision + " by=nightjar";
        dev.nightjar.contract.Verdict verdict = port.lastVerdict();
        out.offer(verdict == null
                ? Observation.stage(key, TraceStage.WAP_DISPATCH, now, detail)
                : Observation.stageWithVerdict(key, TraceStage.WAP_DISPATCH, now, detail, verdict,
                        port.lastRuleId(), port.lastClamp()));
        p.setResult(decision);
    }

    /**
     * Safety net for the original {@code dispatchWapPdu}: when it throws on a PDU the pre-check let through, the
     * exception would reach the SMS state machine and kill the phone process. The push is consumed instead and the
     * verdict is recorded as a malformed block. {@code args} is {@code [this, pdu, receiver, handler, address, ...]}.
     *
     * <p>Only the exceptions a parser throws on a malformed PDU are recovered (index, argument, negative size and
     * buffer underflow errors). Any other exception is a framework bug and is rethrown unchanged. The
     * {@link dev.nightjar.payload.hook.Hooker} counts each recovery in the hook health.
     *
     * @return the value {@code dispatchWapPdu} returns for a consumed push, or null to rethrow
     */
    public Object recover(Object[] args, Throwable t) {
        if (!isParserException(t) || args == null || args.length < 2) return null;
        recovered.set(Boolean.TRUE);
        try {
            byte[] pdu = args[1] instanceof byte[] b ? b : null;
            String key = null;
            if (args.length > 2) {
                key = MeaningHooks.keyFromTracker(Reflect.get(args[2], "mInboundSmsTracker", null));
            }
            if (key == null && pdu != null) key = Hashes.tpduKey(pdu);
            // One observation, as in before(): the stage and the verdict belong to the same event.
            out.offer(Observation.stageWithVerdict(key, TraceStage.WAP_DISPATCH, clock.elapsedNs(),
                    "ct=unknown result=1 by=crash crash=" + t.getClass().getSimpleName(),
                    dev.nightjar.contract.Verdict.BLOCK, -1L, "MALFORMED"));
        } catch (Throwable ignored) {
            // Recording is best effort; the push is consumed either way.
        }
        return Integer.valueOf(1);
    }

    /** The exception types a WSP or WBXML parser throws on a malformed PDU. */
    static boolean isParserException(Throwable t) {
        return t instanceof IndexOutOfBoundsException || t instanceof IllegalArgumentException
                || t instanceof NegativeArraySizeException || t instanceof java.nio.BufferUnderflowException;
    }

    /** After phase: records the original result ({@code -1} broadcast sent, {@code 1} consumed by WapPushManager). */
    public void after(Param p) {
        if (recovered.get() != null) {
            // recover() already recorded this call (by=crash).
            recovered.remove();
            return;
        }
        byte[] pdu = p.arg(0) instanceof byte[] b ? b : null;
        if (pdu == null) return;
        if (!(p.result() instanceof Number n)) return;
        WspMini.Result wsp = WspMini.parse(pdu);
        out.offer(Observation.stage(keyOf(p, pdu), TraceStage.WAP_DISPATCH,
                clock.elapsedNs(), "ct=" + (wsp.contentType() == null ? "unknown" : wsp.contentType())
                        + " result=" + n.intValue()));
    }

    /**
     * Correlation key for the stage. The PDU argument is the reassembled WAP payload, whose hash never matches the
     * radio PDU the capture stage keyed on; the receiver (argument 1) holds the tracker the message came from.
     */
    private static String keyOf(Param p, byte[] wapPdu) {
        String key = MeaningHooks.keyFromTracker(Reflect.get(p.arg(1), "mInboundSmsTracker", null));
        return key != null ? key : Hashes.tpduKey(wapPdu);
    }
}
