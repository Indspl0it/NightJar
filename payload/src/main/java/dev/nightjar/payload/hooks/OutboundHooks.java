package dev.nightjar.payload.hooks;

import dev.nightjar.contract.Transport;
import dev.nightjar.payload.capture.Observation;
import dev.nightjar.payload.capture.Observations;
import dev.nightjar.payload.capture.OutboundLedger;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.payload.hook.Param;
import dev.nightjar.payload.outbound.MoEvent;
import dev.nightjar.payload.outbound.OutboundPolicies;
import dev.nightjar.payload.outbound.OutboundPolicy;
import dev.nightjar.payload.outbound.OutboundRequest;
import dev.nightjar.payload.outbound.OutboundRequests;

/**
 * Hook bodies for the outbound stages: HK-19, HK-20, HK-22 and HK-27.
 *
 * <p>Every body copies what it needs into one {@link MoEvent}, offers it to the worker and returns.
 * No I/O, no binder, no JSON, no locks; the only allocation is the observation, the
 * tracker's PDU copy and the request record. Nothing is written back to the caller's arguments
 * — the single exception is the outbound block, which skips the original and lets the gate fail
 * the caller's sent intent.
 *
 * <p>HK-19 is one body for all nine {@code SmsController#send*} entry points: the method name and
 * arity select the parameter index map in {@link OutboundRequests}. HK-20 hooks
 * {@code SMSDispatcher#sendRawPdu(SmsTracker[])} only, because that method calls {@code sendSms}
 * once per tracker — hooking both would record every send twice.
 *
 * <p>Threads: {@code SmsController} binder (HK-19), the dispatcher's handler thread (HK-20/27),
 * {@code CatService}'s handler (HK-22).
 */
public final class OutboundHooks {
    /** Most trackers one {@code sendRawPdu} array may carry into the queue (multipart + retries). */
    static final int MAX_TRACKERS = 16;

    private final Observations out;
    private final Clock clock;
    private final OutboundLedger ledger;
    private final OutboundPolicy policy;

    /** Uses the process-wide gate installed through {@link OutboundPolicies#install}. */
    public OutboundHooks(Observations out, Clock clock, OutboundLedger ledger) {
        this(out, clock, ledger, null);
    }

    /**
     * @param ledger the outbound pipeline; its {@code stk*} methods are the only hook-thread calls
     * @param policy the outbound gate, or null to read {@link OutboundPolicies#current} per send
     */
    public OutboundHooks(Observations out, Clock clock, OutboundLedger ledger, OutboundPolicy policy) {
        this.out = out;
        this.clock = clock;
        this.ledger = ledger;
        this.policy = policy;
    }

    // ------------------------------------------------------------------ HK-19

    /**
     * HK-19 {@code SmsController#send*}: one request for the ledger, and the outbound gate decision before the
     * original runs. A blocking decision skips the original; the gate has already failed the caller's
     * sent intent, so the app sees a normal radio failure.
     */
    public void beforeSend(Param p) {
        OutboundRequests.Map m = OutboundRequests.map(p.spec().methodName(), p.argCount());
        if (m == null) return;
        long t = clock.elapsedNs();
        OutboundRequest request = OutboundRequests.of(m, p, ledger != null && ledger.stkMarked(t));
        MoEvent e = MoEvent.request(request);
        OutboundPolicy.Decision d = decide(request);
        if (d.block()) {
            e.blocked = true;
            e.ruleId = d.ruleId();
            e.reason = d.reason();
            e.blockedPackage = d.pkg();
            e.blockedClass = d.classId();
            e.sentIntentFailed = d.sentIntentFailed();
        }
        out.offer(Observation.mo(e, t));
        if (d.block()) p.skip();
    }

    /** The gate's decision; a missing or throwing gate allows the send. */
    private OutboundPolicy.Decision decide(OutboundRequest request) {
        try {
            OutboundPolicy p = policy != null ? policy : OutboundPolicies.current();
            OutboundPolicy.Decision d = p == null ? null : p.decide(request);
            return d == null ? OutboundPolicy.Decision.ALLOW : d;
        } catch (Throwable t) {
            return OutboundPolicy.Decision.ALLOW;
        }
    }

    // ------------------------------------------------------------------ HK-20

    /**
     * HK-20 {@code SMSDispatcher#sendRawPdu(SmsTracker[])}: every tracker of the submitted array with
     * the PDU the dispatcher built. The concrete dispatcher decides the transport, and a
     * send that never passed {@code SmsController} (STK, phone internals) is captured here alone
     * .
     */
    public void beforeSendRawPdu(Param p) {
        Object submitted = p.arg(0);
        if (!(submitted instanceof Object[] trackers) || trackers.length == 0) return;
        long t = clock.elapsedNs();
        Transport transport = transportOf(p.thisObject());
        int n = Math.min(trackers.length, MAX_TRACKERS);
        for (int i = 0; i < n; i++) {
            MoEvent e = dispatchOf(trackers[i], transport);
            if (e != null) out.offer(Observation.mo(e, t));
        }
    }

    /** One tracker as the ledger sees it; null when the array slot is empty. */
    private static MoEvent dispatchOf(Object tracker, Transport transport) {
        if (tracker == null) return null;
        // The tracker object is the only identity both stages share: mMessageRef is still 0 here and
        // is only filled in from the radio response at HK-27.
        MoEvent e = MoEvent.dispatch(System.identityHashCode(tracker), transport);
        e.messageRef = Reflect.getInt(tracker, "mMessageRef", -1);
        Object pkg = Reflect.call(tracker, "getAppPackageName", null);
        e.pkg = pkg instanceof String s && !s.isEmpty() ? s : null;
        Object dest = Reflect.get(tracker, "mDestAddress", null);
        e.destAddr = dest instanceof String d && !d.isEmpty() ? d : null;
        Object data = Reflect.get(tracker, "mData", null);
        if (data instanceof java.util.Map<?, ?> m) {
            Object pdu = m.get("pdu");
            if (pdu instanceof byte[] b) e.pdu = b.clone();
            Object port = m.get("destPort");
            if (port instanceof Number num) e.destPort = num.intValue();
            // The tracker's "smsc" is the encoded SMSC (binary); the string field only carries a
            // textual override, and HK-19 already recorded the caller's.
            Object smsc = m.get("smsc");
            if (smsc instanceof String s) e.smsc = s;
        }
        e.persist = bool(tracker, "mPersistMessage");
        e.text = bool(tracker, "mIsText");
        e.vvm = bool(tracker, "mIsForVvm");
        e.subId = Reflect.getInt(tracker, "mSubId", -1);
        return e;
    }

    private static Boolean bool(Object target, String name) {
        Object v = Reflect.get(target, name, null);
        return v instanceof Boolean b ? b : null;
    }

    /** {@code SMSDispatcher#isIms()}, with the receiver's class as the fallback. */
    private static Transport transportOf(Object dispatcher) {
        Object ims = Reflect.call(dispatcher, "isIms", null);
        if (ims instanceof Boolean b) return b ? Transport.MO_IMS : Transport.MO_CS;
        return dispatcher != null && dispatcher.getClass().getName().contains("ImsSmsDispatcher")
                ? Transport.MO_IMS
                : Transport.MO_CS;
    }

    // ------------------------------------------------------------------ HK-22

    /**
     * HK-22 {@code CatService#sendStkSms}: mark the next phone-package send as SIM-toolkit
     * originated. The mark is taken here, on the hook thread, because the send it applies
     * to runs in the same call stack; the observation is the trace only.
     */
    public void beforeStkSend(Param p) {
        long t = clock.elapsedNs();
        if (ledger != null) ledger.noteStk(t);
        out.offer(Observation.mo(MoEvent.stk(), t));
    }

    // ------------------------------------------------------------------ HK-27

    /**
     * HK-27 {@code SMSDispatcher#handleSendComplete(AsyncResult)}: the tracker's outcome.
     * The tracker object is the identity HK-20 reported, so the ledger joins the two stages without
     * a key the framework may not have filled in yet.
     */
    public void afterSendComplete(Param p) {
        Object result = p.arg(0);
        if (result == null) return;
        Object tracker = Reflect.get(result, "userObj", null);
        if (tracker == null) return;
        boolean sent = Reflect.get(result, "exception", null) == null;
        MoEvent e = MoEvent.result(System.identityHashCode(tracker), sent);
        // handleSendComplete copies the response's ref onto the tracker before reporting, so the
        // event gets the real message reference here even though it was still 0 at HK-20.
        e.messageRef = Reflect.getInt(tracker, "mMessageRef", -1);
        if (!sent) {
            e.errorCode = Reflect.getInt(Reflect.get(result, "result", null), "mErrorCode", -1);
            // After a retryable error handleSendComplete schedules a retry and does not call onFailed. The
            // final failure comes from HK-27c, a retry that works from HK-27b.
            e.provisional = true;
        }
        out.offer(Observation.mo(e, clock.elapsedNs()));
    }

    // ------------------------------------------------------------------ HK-27b, HK-27c

    /**
     * HK-27b {@code SmsTracker#onSent(Context)}: the tracker itself reports success. Every transport calls it;
     * an IMS send never reaches HK-27. The tracker object is the identity HK-20 reported. For a CS send HK-27 also
     * reports the same tracker, and the ledger keeps the first result.
     */
    public void afterTrackerSent(Param p) {
        Object tracker = p.thisObject();
        if (tracker == null) return;
        MoEvent e = MoEvent.result(System.identityHashCode(tracker), true);
        e.messageRef = Reflect.getInt(tracker, "mMessageRef", -1);
        out.offer(Observation.mo(e, clock.elapsedNs()));
    }

    /**
     * HK-27c {@code SmsTracker#onFailed(Context, int error, int errorCode)}: the final failure of the tracker.
     * The error code is the network cause ({@code errorCode}) when the network gave one, else the
     * {@code SmsManager.RESULT_*} code ({@code error}).
     */
    public void afterTrackerFailed(Param p) {
        Object tracker = p.thisObject();
        if (tracker == null) return;
        MoEvent e = MoEvent.result(System.identityHashCode(tracker), false);
        e.messageRef = Reflect.getInt(tracker, "mMessageRef", -1);
        int network = p.arg(2) instanceof Number net ? net.intValue() : -1;
        int result = p.arg(1) instanceof Number res ? res.intValue() : -1;
        e.errorCode = network > 0 ? network : result;
        out.offer(Observation.mo(e, clock.elapsedNs()));
    }
}
