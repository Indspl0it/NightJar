package dev.nightjar.payload.capture;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Direction;
import dev.nightjar.contract.Fate;
import dev.nightjar.contract.FateDetail;
import dev.nightjar.contract.Masker;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.contract.Transport;
import dev.nightjar.contract.Verdict;
import dev.nightjar.payload.config.Settings;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.payload.env.Env;
import dev.nightjar.payload.outbound.MoEvent;
import dev.nightjar.payload.outbound.OutboundClassification;
import dev.nightjar.payload.outbound.OutboundKind;
import dev.nightjar.payload.outbound.OutboundRequest;
import dev.nightjar.payload.store.OutboundEvent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The outbound pipeline (T05).
 *
 * <p>Stages, all driven from {@link Observation.Kind#MO} observations:
 * <ol>
 *   <li><b>request</b> (HK-19) — the caller's arguments, classified by
 *       {@link OutboundClassification} and parked in a short queue;
 *   <li><b>dispatch</b> (HK-20) — the tracker's PDU, linked to the oldest parked request with the
 *       same package and destination within {@link #LINK_WINDOW_NS}; no match means the phone
 *  itself sent it, so the package becomes {@value #INTERNAL_PACKAGE};
 *   <li><b>result</b> (HK-27, or HK-27b/27c for every transport, IMS included) — {@code FT-SENT} or
 *       {@code FT-SEND-FAILED} with the error code; the first result of a tracker wins. An HK-27 failure is
 *       provisional, because Android can retry the tracker: it waits for HK-27b/27c and is final at the timeout. Else
 *  {@code FT-SEND-UNCONFIRMED} after {@link #RESULT_TIMEOUT_NS} without one. A request
 *       with no dispatch is {@code FT-NOT-SENT}.
 * </ol>
 *
 * <p>The STK mark (HK-22) is written and read on the <em>hook</em> thread, because the outbound
 * gate needs it while the send is still in the caller's stack: {@link #noteStk(long)} and
 * {@link #stkMarked(long)} are lock-free and allocation-free. Everything else is worker-only.
 *
 * <p>{@code outgoing.enabled = false} records nothing at all: the request, dispatch
 * and result stages all return before touching any state, and a mark left over from before the
 * change is ignored.
 *
 * <p>A multipart send produces one tracker per part and therefore one event per part; the parts
 * share the request's method, package and destination and each carries its own PDU.
 *
 * <p>Thread: worker only, except the two {@code stk*} methods.
 */
public final class OutboundLedger {
    /** HK-19 → HK-20 linkage window. */
    public static final long LINK_WINDOW_NS = 5_000_000_000L;
    /** HK-22 → the next phone-package send. */
    public static final long STK_WINDOW_NS = 2_000_000_000L;
    /** HK-20 → HK-27 without a result. */
    public static final long RESULT_TIMEOUT_NS = 60_000_000_000L;
    /** Calling package reported for a send that bypassed {@code SmsController}. */
    public static final String INTERNAL_PACKAGE = "android.phone.internal";
    /** The phone process's own package, which is what {@code CatService} passes to {@code SmsController}. */
    public static final String PHONE_PACKAGE = "com.android.phone";

    /** At most this many requests wait for a dispatch; older ones are sealed as unknown. */
    static final int MAX_PENDING_REQUESTS = 16;
    /** At most this many dispatched trackers wait for a result. */
    static final int MAX_IN_FLIGHT = 64;

    private final Clock clock;
    private final Env env;
    private final Counters counters;
    private final Supplier<Settings> settings;
    private final String sessionId;
    private final AtomicLong stkMarkNs = new AtomicLong(-1L);

    private final ArrayList<Pending> requests = new ArrayList<>(MAX_PENDING_REQUESTS);
    private final Map<Integer, InFlight> inFlight = new LinkedHashMap<>(16, 0.75f, false);
    private final List<OutboundEvent> outbox = new ArrayList<>();

    /** A request waiting for its dispatcher PDU. */
    private record Pending(OutboundRequest request, boolean stk, ClassId classId, boolean counterOnly,
            boolean body, long tNs) {}

    /**
     * A dispatched tracker waiting for its result. {@code failedError} is the error code of a provisional HK-27 failure
     * , {@link #NO_FAILURE} while the tracker has none.
     */
    private record InFlight(Event event, long dispatchedAtNs, int failedError) {
        InFlight(Event event, long dispatchedAtNs) {
            this(event, dispatchedAtNs, NO_FAILURE);
        }

        boolean failed() {
            return failedError != NO_FAILURE;
        }
    }

    /** {@link InFlight#failedError} of a tracker with no provisional failure. */
    private static final int NO_FAILURE = Integer.MIN_VALUE;

    /**
     * @param settings the live {@code settings.json} snapshot (worker-side reads only)
     * @param sessionId the session id written into every event
     */
    public OutboundLedger(Clock clock, Env env, Counters counters, Supplier<Settings> settings,
            String sessionId) {
        this.clock = clock;
        this.env = env == null ? new NoEnv() : env;
        this.counters = counters == null ? new Counters() : counters;
        this.settings = settings == null ? () -> Settings.DEFAULT : settings;
        this.sessionId = sessionId;
    }

    // ------------------------------------------------------------------ hook thread (HK-22)

    /** HK-22: the SIM toolkit asked to send an SMS at {@code tNs}. */
    public void noteStk(long tNs) {
        stkMarkNs.set(tNs);
    }

    /** True when an STK mark is still fresh at {@code tNs} (: 2 s). */
    public boolean stkMarked(long tNs) {
        long mark = stkMarkNs.get();
        return mark >= 0 && tNs >= mark && tNs - mark <= STK_WINDOW_NS;
    }

    /** Consumes the mark when the send belongs to the phone process; returns whether it applied. */
    private boolean consumeStk(String callingPackage, long tNs) {
        if (!isPhonePackage(callingPackage) || !stkMarked(tNs)) return false;
        stkMarkNs.set(-1L);
        return true;
    }

    /** True when {@code pkg} is the phone process (or unknown, which is the phone's own call). */
    private static boolean isPhonePackage(String pkg) {
        return pkg == null || pkg.isEmpty() || PHONE_PACKAGE.equals(pkg)
                || INTERNAL_PACKAGE.equals(pkg);
    }

    // ------------------------------------------------------------------ worker: stages

    /**
     * Worker-side entry point for one {@link MoEvent}: the worker hands every
     * {@link Observation.Kind#MO} observation here. Never throws.
     */
    public void accept(MoEvent e, long tNs) {
        if (e == null) return;
        try {
            if (!settings.get().outgoingEnabled()) return;
            switch (e.type) {
                case REQUEST -> onRequest(e, tNs);
                case DISPATCH -> onDispatch(e, tNs);
                case RESULT -> onResult(e, tNs);
                default -> {
                    // STK: the mark itself is taken on the hook thread; the observation is a trace only.
                }
            }
        } catch (RuntimeException ignored) {
            // Fail open: an outbound observation never reaches telephony.
        }
    }

    private void onRequest(MoEvent e, long tNs) {
        OutboundRequest r = e.request;
        if (r == null) return;
        boolean stk = r.stk() || consumeStk(r.callingPackage(), tNs);
        OutboundClassification.Result c = classify(r, stk);
        Pending p = new Pending(r, stk, c.classId(), c.counterOnly(), c.body(), tNs);
        if (e.blocked) {
            // The gate skipped the send, so no dispatcher will ever see it.
            Event event = newEvent(p, null, tNs, Transport.MO_CS);
            event.verdict = Verdict.BLOCK;
            if (e.ruleId >= 0) event.ruleId = e.ruleId;
            event.fate = Fate.BLOCKED;
            // Separate keys for the app (sender package, class, sent-intent result); null values are dropped.
            event.fateDetail(FateDetail.REASON, e.reason == null ? FateDetail.OUTBOUND_BLOCK : e.reason);
            event.fateDetail(FateDetail.PACKAGE, e.blockedPackage);
            event.fateDetail(FateDetail.CLASS, e.blockedClass == null ? null : e.blockedClass.wire());
            event.fateDetail(FateDetail.SENT_INTENT_FAILED, e.sentIntentFailed);
            emit(event, false, tNs);
            return;
        }
        if (requests.size() >= MAX_PENDING_REQUESTS) sealOldestRequest(tNs);
        requests.add(p);
    }

    private void onDispatch(MoEvent e, long tNs) {
        Pending linked = takeRequest(e.pkg, e.destAddr, tNs);
        Pending p;
        if (linked != null) {
            p = linked;
        } else {
            // Nothing in SmsController saw this send, so the phone itself issued it.
            OutboundRequest r = new OutboundRequest(
                    "dispatcher",
                    kindOf(e),
                    e.pkg == null || e.pkg.isEmpty() ? INTERNAL_PACKAGE : e.pkg,
                    null,
                    e.subId,
                    e.destAddr,
                    e.smsc,
                    e.destPort,
                    -1,
                    -1,
                    e.persist,
                    false,
                    -1,
                    null,
                    null);
            boolean stk = consumeStk(r.callingPackage(), tNs);
            OutboundClassification.Result c = classify(r, stk);
            p = new Pending(r, stk, c.classId(), c.counterOnly(), c.body(), tNs);
        }
        if (p.counterOnly()) {
            // The user's own message (default app) or an unlogged CL-MO-VISIBLE.
            counters.increment(clock.wallMs(), p.request().subId(), Direction.OUT, p.classId());
            return;
        }
        Transport transport = e.transport == null ? Transport.MO_CS : e.transport;
        makeRoom(e.trackerId, tNs);
        inFlight.put(e.trackerId, new InFlight(newEvent(p, e, tNs, transport), tNs));
    }

    private void onResult(MoEvent e, long tNs) {
        if (!e.sent && e.provisional) {
            onProvisionalFailure(e, tNs);
            return;
        }
        InFlight f = inFlight.remove(e.trackerId);
        if (f == null) return; // A tracker we never saw dispatched: nothing to update.
        Event event = f.event();
        if (e.messageRef >= 0) event.messageRef = e.messageRef;
        if (e.sent) {
            event.fate = Fate.SENT;
            event.addTrace(TraceStage.MO_RESULT, tNs, "result=sent");
        } else {
            markFailed(event, e.errorCode, tNs);
        }
        event.lastTNs = Math.max(event.lastTNs, tNs);
        emit(event, false, tNs);
    }

    /**
     * HK-27 reported a failure, and Android may still retry the tracker. The tracker stays in flight: HK-27b
     * (the retry worked) or HK-27c (the final failure) decides, else the timeout seals it {@code FT-SEND-FAILED} with
     * this error code. The timeout stays the one of the dispatch, so a failure never waits longer than a lost result.
     */
    private void onProvisionalFailure(MoEvent e, long tNs) {
        InFlight f = inFlight.get(e.trackerId);
        if (f == null) return; // Already sealed (HK-27c reports before HK-27 returns), or never dispatched.
        if (e.messageRef >= 0) f.event().messageRef = e.messageRef;
        f.event().addTrace(TraceStage.MO_RESULT, tNs, "result=failed error=" + e.errorCode + " final=false");
        f.event().lastTNs = Math.max(f.event().lastTNs, tNs);
        inFlight.put(e.trackerId, new InFlight(f.event(), f.dispatchedAtNs(), e.errorCode));
    }

    /** {@code FT-SEND-FAILED} with the error code when there is one. */
    private static void markFailed(Event event, int errorCode, long tNs) {
        event.fate = Fate.SEND_FAILED;
        if (errorCode >= 0) event.fateDetail(FateDetail.ERROR_CODE, errorCode);
        event.addTrace(TraceStage.MO_RESULT, tNs, "result=failed error=" + errorCode);
    }

    // ------------------------------------------------------------------ timers

    /**
     * Seals pending requests and in-flight trackers that ran out of time. With {@code outgoing.enabled = false} the
     * ones recorded before the switch are dropped without an event (: nothing is recorded).
     */
    public List<OutboundEvent> tick(long nowNs) {
        if (!settings.get().outgoingEnabled()) {
            requests.clear();
            inFlight.clear();
            return drainOutbox();
        }
        for (int i = requests.size() - 1; i >= 0; i--) {
            Pending p = requests.get(i);
            if (nowNs - p.tNs() >= LINK_WINDOW_NS) {
                requests.remove(i);
                sealRequestOnly(p, nowNs);
            }
        }
        if (!inFlight.isEmpty()) {
            Iterator<Map.Entry<Integer, InFlight>> it = inFlight.entrySet().iterator();
            while (it.hasNext()) {
                InFlight f = it.next().getValue();
                if (nowNs - f.dispatchedAtNs() < RESULT_TIMEOUT_NS) continue;
                it.remove();
                seal(f, "result=timeout", nowNs);
            }
        }
        return drainOutbox();
    }

    /**
     * Keeps at most {@link #MAX_IN_FLIGHT} dispatched trackers waiting for a result. A
     * repeated id means the tracker it replaces will never see its result, so it is sealed as
     * unconfirmed instead of being overwritten; when the map is full the oldest entry goes the same way.
     */
    private void makeRoom(int trackerId, long tNs) {
        InFlight repeated = inFlight.remove(trackerId);
        if (repeated != null) {
            seal(repeated, "result=replaced", tNs);
            return;
        }
        if (inFlight.size() < MAX_IN_FLIGHT) return;
        Iterator<Map.Entry<Integer, InFlight>> it = inFlight.entrySet().iterator();
        if (!it.hasNext()) return;
        InFlight oldest = it.next().getValue();
        it.remove();
        seal(oldest, "result=evicted", tNs);
    }

    /**
     * A dispatched tracker whose result will never be seen: {@code FT-SEND-UNCONFIRMED}. After a
     * provisional HK-27 failure and no later result, the send failed: {@code FT-SEND-FAILED}.
     */
    private void seal(InFlight f, String detail, long tNs) {
        if (f.failed()) {
            // The trace already has the mo.result step of the failure.
            f.event().fate = Fate.SEND_FAILED;
            if (f.failedError() >= 0) f.event().fateDetail(FateDetail.ERROR_CODE, f.failedError());
        } else {
            f.event().fate = Fate.SEND_UNCONFIRMED;
        }
        f.event().addTrace(TraceStage.SEAL_TIMEOUT, tNs, detail);
        f.event().lastTNs = Math.max(f.event().lastTNs, tNs);
        emit(f.event(), false, tNs);
    }

    /** Sealed outbound events since the last call. */
    public List<OutboundEvent> drainOutbox() {
        if (outbox.isEmpty()) return List.of();
        List<OutboundEvent> out = new ArrayList<>(outbox);
        outbox.clear();
        return out;
    }

    // ------------------------------------------------------------------ classification helpers

    private OutboundClassification.Result classify(OutboundRequest r, boolean stk) {
        Settings s = settings.get();
        boolean defaultApp = env.isDefaultSmsApp(r.callingPackage());
        return OutboundClassification.classify(r.kind(), stk, r.persist(), defaultApp,
                s.outgoingLogVisible());
    }

    /** The class a dispatch-only send would have, from the tracker's own flags. */
    private static OutboundKind kindOf(MoEvent e) {
        if (Boolean.TRUE.equals(e.vvm)) return OutboundKind.VVM;
        if (e.destPort >= 0) return OutboundKind.DATA;
        if (Boolean.FALSE.equals(e.text)) return OutboundKind.RAW;
        return OutboundKind.TEXT;
    }

    /** The oldest pending request that matches {@code pkg}/{@code dest} within the link window. */
    private Pending takeRequest(String pkg, String dest, long tNs) {
        for (int i = 0; i < requests.size(); i++) {
            Pending p = requests.get(i);
            if (tNs - p.tNs() > LINK_WINDOW_NS) continue;
            if (!packageMatches(p.request().callingPackage(), pkg)) continue;
            if (!destMatches(p.request().destAddr(), dest)) continue;
            requests.remove(i);
            return p;
        }
        return null;
    }

    /** An unresolved package on the request (the framework fills it in) matches any tracker package. */
    private static boolean packageMatches(String requested, String actual) {
        if (requested == null || requested.isEmpty()) return true;
        return actual != null && requested.equals(actual);
    }

    /**
     * The tracker's destination is the network portion of the requested address, so compare digits.
     * A request without a destination (a stored message: the address lives in the content URI)
     * matches any destination of the same package.
     */
    private static boolean destMatches(String requested, String actual) {
        if (requested == null || requested.isEmpty()) return true;
        String a = digits(requested);
        String b = digits(actual);
        if (a.isEmpty() || b.isEmpty()) return false;
        return a.equals(b) || a.endsWith(b) || b.endsWith(a);
    }

    private static String digits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ sealing

    private void sealOldestRequest(long nowNs) {
        if (requests.isEmpty()) return;
        sealRequestOnly(requests.remove(0), nowNs);
    }

    /**
     * A request whose dispatch was never seen: Android refused it before {@code SMSDispatcher#sendRawPdu} (HK-20), so
     * it was not sent: {@code FT-NOT-SENT}.
     */
    private void sealRequestOnly(Pending p, long nowNs) {
        Event event = newEvent(p, null, nowNs, Transport.MO_CS);
        event.fate = Fate.NOT_SENT;
        event.fateDetail(FateDetail.NO_DISPATCH, true);
        event.addTrace(TraceStage.SEAL_TIMEOUT, nowNs, "result=none");
        emit(event, p.counterOnly(), nowNs);
    }

    private Event newEvent(Pending p, MoEvent dispatch, long tNs, Transport transport) {
        OutboundRequest r = p.request();
        Event e = new Event();
        e.eventId = UUID.randomUUID().toString();
        e.sessionId = sessionId;
        e.direction = Direction.OUT;
        e.transport = transport;
        e.format = "3gpp";
        e.subId = r.subId();
        e.slot = env.slot(e.subId);
        e.carrierName = env.carrierName(e.subId);
        e.callingPackage = r.callingPackage();
        e.attributionTag = r.attributionTag();
        e.method = r.method();
        e.persistFlag = r.persist();
        e.destination = r.destAddr() != null ? r.destAddr()
                : dispatch == null ? null : dispatch.destAddr;
        e.smsc = r.scAddr() != null ? r.scAddr() : dispatch == null ? null : dispatch.smsc;
        e.destPort = r.destPort() >= 0 ? r.destPort() : dispatch == null ? -1 : dispatch.destPort;
        e.provisionalClass = p.classId();
        e.predictedClass = p.classId();
        e.finalClass = p.classId();
        e.receivedAtWall = clock.wallMs();
        e.createdAtNs = tNs;
        e.firstTNs = tNs;
        e.lastTNs = tNs;
        e.payloadVersion = env.payloadVersion();
        e.buildFingerprint = env.buildFingerprint();
        e.env.roaming = env.roaming(e.subId);
        e.env.userUnlocked = env.userUnlocked();
        e.env.bootCount = env.bootCount();
        if (dispatch != null) {
            e.messageRef = dispatch.messageRef;
            if (dispatch.pdu != null && p.body()) e.addPdu(dispatch.pdu, tNs);
        }
        e.addTrace(TraceStage.MO_REQUEST, tNs, requestDetail(r));
        if (p.stk()) e.addTrace(TraceStage.MO_STK, tNs, "origin=stk");
        if (dispatch != null) {
            int len = dispatch.pdu == null ? 0 : dispatch.pdu.length;
            e.addTrace(TraceStage.MO_DISPATCH, tNs,
                    "len=" + len + " transport=" + transport.wire()
                            + (dispatch.messageRef >= 0 ? " ref=" + dispatch.messageRef : ""));
        }
        return e;
    }

    private static String requestDetail(OutboundRequest r) {
        StringBuilder b = new StringBuilder(48);
        b.append("method=").append(r.method());
        if (r.destAddr() != null) b.append(" dest=").append(Masker.maskNumber(r.destAddr()));
        if (r.destPort() >= 0) b.append(" port=").append(r.destPort());
        if (r.textLen() >= 0) b.append(" textLen=").append(r.textLen());
        if (r.dataLen() >= 0) b.append(" dataLen=").append(r.dataLen());
        if (r.persist() != null) b.append(" persist=").append(r.persist());
        return b.toString();
    }

    private void emit(Event e, boolean counterOnly, long tNs) {
        if (e.finalClass == null) e.finalClass = e.provisionalClass;
        e.addTrace(TraceStage.FATE, tNs, e.fate == null ? null : e.fate.wire());
        if (counterOnly) {
            counters.increment(e.receivedAtWall > 0 ? e.receivedAtWall : clock.wallMs(), e.subId,
                    Direction.OUT, e.finalClass);
            return;
        }
        String json = EventDto.toJson(e);
        // The PDUs are already in the JSON (Base64): the UTF-8 size of the line is the whole cost.
        outbox.add(OutboundEvent.of(e.eventId, e.revision, json));
    }

    // ------------------------------------------------------------------ test seams

    int pendingRequests() {
        return requests.size();
    }

    int inFlight() {
        return inFlight.size();
    }

    /** An environment that knows nothing; used when the runtime passes null (tests). */
    private static final class NoEnv implements Env {
        @Override
        public String buildFingerprint() {
            return "";
        }

        @Override
        public String payloadVersion() {
            return "";
        }

        @Override
        public String apexVersionsJson() {
            return "{}";
        }

        @Override
        public int bootCount() {
            return -1;
        }

        @Override
        public boolean userUnlocked() {
            return true;
        }

        @Override
        public String carrierName(int subId) {
            return "";
        }

        @Override
        public int slot(int subId) {
            return -1;
        }

        @Override
        public boolean roaming(int subId) {
            return false;
        }
    }
}
