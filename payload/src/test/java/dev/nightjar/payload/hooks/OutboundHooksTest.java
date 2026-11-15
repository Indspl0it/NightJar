package dev.nightjar.payload.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.FateDetail;
import dev.nightjar.payload.capture.Counters;
import dev.nightjar.payload.capture.Observation;
import dev.nightjar.payload.capture.Observations;
import dev.nightjar.payload.capture.OutboundLedger;
import dev.nightjar.payload.config.Settings;
import dev.nightjar.payload.firewall.OutboundGate;
import dev.nightjar.payload.hook.HookCatalog;
import dev.nightjar.payload.hook.HookSpec;
import dev.nightjar.payload.hook.Param;
import dev.nightjar.payload.outbound.OutboundPolicies;
import dev.nightjar.payload.store.EventQueue;
import dev.nightjar.payload.store.OutboundEvent;
import dev.nightjar.payload.testing.FakeClock;
import dev.nightjar.payload.testing.FakeEnv;
import dev.nightjar.payload.testing.FakeWorker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The outbound pipeline driven from the hook bodies and the catalog specs, not
 * from the ledger directly.
 *
 * <p>The only fakes are the framework objects a hook reads (the tracker, the {@code AsyncResult},
 * the dispatcher) and the worker loop: {@link #drain()} performs what {@code PayloadRuntime} does —
 * hand every {@code Kind#MO} observation to the ledger.
 */
class OutboundHooksTest {
    private static final String SPY = "com.example.spy";
    private static final String PHONE = "com.android.phone";
    private static final String DEST = "+15551234567";
    private static final byte[] PDU = {1, 2, 3};

    private final List<Integer> failedIntents = new ArrayList<>();
    private EventQueue<Observation> queue;
    private FakeWorker worker;
    private FakeClock clock;
    private OutboundLedger ledger;
    private OutboundHooks hooks;

    @BeforeEach
    void setUp() {
        queue = new EventQueue<>();
        worker = new FakeWorker();
        clock = new FakeClock(1_000_000_000L);
        ledger = new OutboundLedger(clock, new FakeEnv(), new Counters(), () -> Settings.DEFAULT,
                "session-1");
        hooks = new OutboundHooks(new Observations(queue, worker, () -> {}), clock, ledger);
        OutboundGate.setEmergencyNumbers(OutboundGate::isDefaultEmergency);
    }

    @AfterEach
    void tearDown() {
        OutboundGate.setSender(null);
        OutboundGate.setCallerPackages(null);
        OutboundGate.setEmergencyNumbers(null);
        OutboundPolicies.reset();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void aHiddenDataSendIsRecordedOnceWithItsPackageAndResult() {
        run("HK-19c", 1, SPY, null, DEST, null, 9, new byte[] {1, 2, 3}, new Object(), null);
        drain();
        assertTrue(ledger.drainOutbox().isEmpty(), "a request alone is not a sealed event");

        FakeTracker tracker = new FakeTracker(SPY, DEST, PDU, 9);
        run("HK-20", (Object) new FakeTracker[] {tracker});
        drain();
        assertTrue(ledger.drainOutbox().isEmpty(), "nothing is sealed before the tracker reports");

        tracker.mMessageRef = 42; // what handleSendComplete copies back from the radio response
        run("HK-27", new AsyncResult(tracker, null, null));
        drain();
        List<OutboundEvent> out = ledger.drainOutbox();

        assertEquals(1, out.size(), "one send is one event");
        String json = out.get(0).json();
        assertTrue(json.contains("\"callingPackage\":\"" + SPY + "\""), json);
        assertTrue(json.contains("\"finalClass\":\"CL-MO-DATA\""), json);
        assertTrue(json.contains("\"method\":\"sendDataForSubscriber\""), json);
        assertTrue(json.contains("\"destination\":\"" + DEST + "\""), json);
        assertTrue(json.contains("\"fate\":\"FT-SENT\""), json);
        assertTrue(json.contains("\"transport\":\"MO_CS\""), json);
        assertTrue(json.contains("\"pdus\":[\"AQID\"]"), json);
        assertTrue(json.contains("\"messageRef\":42"), json);
        assertTrue(json.contains("mo.dispatch"), json);
        assertTrue(json.contains("mo.result"), json);
    }

    @Test
    void aFailedSendRecordsTheResultCode() {
        run("HK-19c", 1, SPY, null, DEST, null, -1, new byte[] {9}, new Object(), null);
        FakeTracker tracker = new FakeTracker(SPY, DEST, PDU, -1);
        run("HK-20", (Object) new FakeTracker[] {tracker});
        drain();
        run("HK-27", new AsyncResult(tracker, new FakeResponse(7), new IllegalStateException()));
        drain();
        // Android may still retry the tracker, so an HK-27 failure alone seals nothing yet.
        assertTrue(ledger.drainOutbox().isEmpty(), "an HK-27 failure is not final");

        clock.advanceMs(OutboundLedger.RESULT_TIMEOUT_NS / 1_000_000L);
        List<OutboundEvent> out = ledger.tick(clock.elapsedNs());
        assertEquals(1, out.size());
        String json = out.get(0).json();
        assertTrue(json.contains("\"fate\":\"FT-SEND-FAILED\""), json);
        assertTrue(json.contains("\"errorCode\":7"), json);
    }

    /** A retry that works after an HK-27 failure is a sent message, not a failed one. */
    @Test
    void aRetryThatWorksAfterAFailureIsSent() {
        FakeTracker tracker = new FakeTracker(SPY, DEST, PDU, 9);
        run("HK-20", (Object) new FakeTracker[] {tracker});
        drain();
        run("HK-27", new AsyncResult(tracker, new FakeResponse(7), new IllegalStateException()));
        drain();
        assertTrue(ledger.drainOutbox().isEmpty());

        runOn("HK-27b", tracker, new Object());
        run("HK-27", new AsyncResult(tracker, null, null));
        drain();
        List<OutboundEvent> out = ledger.drainOutbox();
        assertEquals(1, out.size());
        String json = out.get(0).json();
        assertTrue(json.contains("\"fate\":\"FT-SENT\""), json);
        assertFalse(json.contains("\"errorCode\""), json);
    }

    /** The final CS failure: HK-27c reports inside handleSendComplete, before HK-27. One event, HK-27c's code. */
    @Test
    void aFinalCsFailureIsOneEventWithTheTrackerCode() {
        FakeTracker tracker = new FakeTracker(SPY, DEST, PDU, 9);
        run("HK-20", (Object) new FakeTracker[] {tracker});
        drain();
        runOn("HK-27c", tracker, new Object(), 5, 38);
        run("HK-27", new AsyncResult(tracker, new FakeResponse(7), new IllegalStateException()));
        drain();
        List<OutboundEvent> out = ledger.drainOutbox();
        assertEquals(1, out.size());
        String json = out.get(0).json();
        assertTrue(json.contains("\"fate\":\"FT-SEND-FAILED\""), json);
        assertTrue(json.contains("\"errorCode\":38"), json);
        clock.advanceMs(OutboundLedger.RESULT_TIMEOUT_NS / 1_000_000L);
        assertTrue(ledger.tick(clock.elapsedNs()).isEmpty(), "nothing is left in flight");
    }

    @Test
    void anStkOriginatedSendIsMarkedAsSuch() {
        run("HK-22");
        run("HK-19a", 1, PHONE, null, DEST, null, "hello", new Object(), new Object(), true, 0L,
                false, false);
        drain();

        FakeTracker tracker = new FakeTracker(PHONE, DEST, PDU, -1);
        run("HK-20", (Object) new FakeTracker[] {tracker});
        drain();
        run("HK-27", new AsyncResult(tracker, null, null));
        drain();

        List<OutboundEvent> out = ledger.drainOutbox();
        assertEquals(1, out.size());
        String json = out.get(0).json();
        assertTrue(json.contains("\"finalClass\":\"CL-MO-STK\""), json);
        assertTrue(json.contains("\"stage\":\"mo.stk\""), json);
        assertTrue(json.contains("origin=stk"), json);
    }

    @Test
    void theGateSkipsAHiddenSendAndTheLedgerSealsItAsBlocked() {
        OutboundGate.setSender((intent, code) -> failedIntents.add(code));
        hooks = new OutboundHooks(new Observations(queue, worker, () -> {}), clock, ledger,
                new OutboundGate(() -> new OutboundGate.Policy(true, false, Set.of(SPY))));

        Param p = run("HK-19c", 1, SPY, null, DEST, null, 9, new byte[] {1}, new Object(), null);
        assertTrue(p.skipped(), "the original send must not run");
        assertEquals(List.of(1), failedIntents, "the caller sees a generic failure");
        Observation o = queue.poll();
        assertNotNull(o);
        assertEquals(SPY, o.mo.blockedPackage);
        assertEquals(ClassId.MO_DATA, o.mo.blockedClass);
        assertEquals(Boolean.TRUE, o.mo.sentIntentFailed);
        ledger.accept(o.mo, o.tNs);
        drain();

        List<OutboundEvent> out = ledger.drainOutbox();
        assertEquals(1, out.size());
        String json = out.get(0).json();
        assertTrue(json.contains("\"fate\":\"FT-BLOCKED\""), json);
        assertTrue(json.contains("\"action\":\"BLOCK\""), json);
        // fateDetail is an object with one key per fact, not one combined string.
        org.json.JSONObject detail = new org.json.JSONObject(json).getJSONObject("fateDetail");
        assertEquals(FateDetail.OUTBOUND_BLOCK, detail.getString(FateDetail.REASON));
        assertEquals(SPY, detail.getString(FateDetail.PACKAGE));
        assertEquals(ClassId.MO_DATA.wire(), detail.getString(FateDetail.CLASS));
        assertTrue(detail.getBoolean(FateDetail.SENT_INTENT_FAILED));
    }

    /** A visible persisted text from a blocked package is skipped and reaches the app as FT-BLOCKED. */
    @Test
    void aVisibleTextFromABlockedPackageIsSkippedAndRecordedAsBlocked() {
        OutboundGate.setSender((intent, code) -> failedIntents.add(code));
        hooks = new OutboundHooks(new Observations(queue, worker, () -> {}), clock, ledger,
                new OutboundGate(() -> new OutboundGate.Policy(true, false, Set.of(SPY))));

        Param p = run("HK-19a", 1, SPY, null, DEST, null, "test", new Object(), null, true, 0L, false, false);
        assertTrue(p.skipped(), "the original send must not run");
        assertEquals(List.of(1), failedIntents);
        drain();
        List<OutboundEvent> out = ledger.drainOutbox();
        assertEquals(1, out.size(), "a blocked visible send is a full event, not a counter");
        assertTrue(out.get(0).json().contains("\"fate\":\"FT-BLOCKED\""), out.get(0).json());
    }

    /** A text to an emergency number goes out, also from a blocked package. */
    @Test
    void aTextToAnEmergencyNumberFromABlockedPackageIsNotSkipped() {
        OutboundGate.setSender((intent, code) -> failedIntents.add(code));
        hooks = new OutboundHooks(new Observations(queue, worker, () -> {}), clock, ledger,
                new OutboundGate(() -> new OutboundGate.Policy(true, false, Set.of(SPY))));

        Param p = run("HK-19a", 1, SPY, null, "112", null, "test", new Object(), null, true, 0L, false, false);
        assertFalse(p.skipped());
        assertTrue(failedIntents.isEmpty());
    }

    /** A gate that throws allows the send; the original runs unchanged and the request is still recorded. */
    @Test
    void aThrowingGateNeverSkipsTheSend() {
        hooks = new OutboundHooks(new Observations(queue, worker, () -> {}), clock, ledger, request -> {
            throw new IllegalStateException("synthetic");
        });

        Param p = run("HK-19c", 1, SPY, null, DEST, null, 9, new byte[] {1}, new Object(), null);
        assertFalse(p.skipped(), "the original send must run");
        drain();
        assertTrue(ledger.drainOutbox().stream().noneMatch(e -> e.json().contains("FT-BLOCKED")));
    }

    @Test
    void anAllowedServiceIsNotSkipped() {
        hooks = new OutboundHooks(new Observations(queue, worker, () -> {}), clock, ledger,
                new OutboundGate(() -> new OutboundGate.Policy(true, false, Set.of(PHONE))));

        Param p = run("HK-19c", 1, SPY, null, DEST, null, 9, new byte[] {1}, new Object(), null);
        assertFalse(p.skipped());
    }

    @Test
    void theInFlightCapSealsTheOldestTrackerInsteadOfOverwritingIt() {
        for (int batch = 0; batch < 4; batch++) {
            run("HK-20", new Object[] {trackers(16)});
        }
        drain();
        assertTrue(ledger.drainOutbox().isEmpty(), "64 trackers still fit");

        run("HK-20", new Object[] {trackers(1)});
        drain();
        List<OutboundEvent> evicted = ledger.drainOutbox();
        assertEquals(1, evicted.size(), "the 65th tracker evicts exactly one");
        assertTrue(evicted.get(0).json().contains("\"fate\":\"FT-SEND-UNCONFIRMED\""), evicted.get(0).json());
        assertTrue(evicted.get(0).json().contains("result=evicted"), evicted.get(0).json());

        clock.advanceMs(OutboundLedger.RESULT_TIMEOUT_NS / 1_000_000L + 1L);
        assertEquals(64, ledger.tick(clock.elapsedNs()).size(), "the map never holds more than 64");
    }

    /** A request that never reaches SMSDispatcher#sendRawPdu was not sent. */
    @Test
    void aRequestWithoutADispatchIsNotSent() {
        run("HK-19c", 1, SPY, null, DEST, null, 9, new byte[] {1, 2, 3}, new Object(), null);
        drain();
        clock.advanceMs(OutboundLedger.LINK_WINDOW_NS / 1_000_000L);
        List<OutboundEvent> out = ledger.tick(clock.elapsedNs());
        assertEquals(1, out.size());
        String json = out.get(0).json();
        assertTrue(json.contains("\"fate\":\"FT-NOT-SENT\""), json);
        assertTrue(json.contains("\"noDispatch\":true"), json);
    }

    /** A dispatched send with no result is unconfirmed after the timeout. */
    @Test
    void aDispatchWithoutAResultIsUnconfirmedAtTheTimeout() {
        run("HK-19c", 1, SPY, null, DEST, null, 9, new byte[] {1, 2, 3}, new Object(), null);
        run("HK-20", (Object) new FakeTracker[] {new FakeTracker(SPY, DEST, PDU, 9)});
        drain();
        assertTrue(ledger.drainOutbox().isEmpty());
        clock.advanceMs(OutboundLedger.RESULT_TIMEOUT_NS / 1_000_000L);
        List<OutboundEvent> out = ledger.tick(clock.elapsedNs());
        assertEquals(1, out.size());
        String json = out.get(0).json();
        assertTrue(json.contains("\"fate\":\"FT-SEND-UNCONFIRMED\""), json);
        assertTrue(json.contains("result=timeout"), json);
    }

    /** An IMS send reports to the tracker only (no handleSendComplete); HK-27b gives the real FT-SENT. */
    @Test
    void anImsSendIsSentWhenTheTrackerReports() {
        run("HK-19c", 1, SPY, null, DEST, null, 9, new byte[] {1, 2, 3}, new Object(), null);
        FakeTracker tracker = new FakeTracker(SPY, DEST, PDU, 9);
        run("HK-20", (Object) new FakeTracker[] {tracker});
        drain();

        tracker.mMessageRef = 17;
        runOn("HK-27b", tracker, new Object());
        drain();

        List<OutboundEvent> out = ledger.drainOutbox();
        assertEquals(1, out.size());
        String json = out.get(0).json();
        assertTrue(json.contains("\"fate\":\"FT-SENT\""), json);
        assertTrue(json.contains("\"messageRef\":17"), json);
        assertTrue(json.contains("\"destination\":\"" + DEST + "\""), json);
        assertTrue(json.contains("\"callingPackage\":\"" + SPY + "\""), json);
    }

    /** HK-27c records the final failure; the network cause wins over the SmsManager result code. */
    @Test
    void anImsFailureRecordsItsCause() {
        FakeTracker noService = new FakeTracker(SPY, DEST, PDU, 9);
        FakeTracker network = new FakeTracker(SPY, "+15557654321", PDU, 9);
        run("HK-20", (Object) new FakeTracker[] {noService, network});
        drain();

        runOn("HK-27c", noService, new Object(), 4, -1);
        runOn("HK-27c", network, new Object(), 1, 42);
        drain();

        List<OutboundEvent> out = ledger.drainOutbox();
        assertEquals(2, out.size());
        assertTrue(out.get(0).json().contains("\"fate\":\"FT-SEND-FAILED\""), out.get(0).json());
        assertTrue(out.get(0).json().contains("\"errorCode\":4"), out.get(0).json());
        assertTrue(out.get(1).json().contains("\"errorCode\":42"), out.get(1).json());
    }

    /** A CS send reports through both handleSendComplete and the tracker: still one event, the first result. */
    @Test
    void aCsSendReportedTwiceIsOneEvent() {
        FakeTracker tracker = new FakeTracker(SPY, DEST, PDU, 9);
        run("HK-20", (Object) new FakeTracker[] {tracker});
        drain();
        runOn("HK-27b", tracker, new Object());
        run("HK-27", new AsyncResult(tracker, null, null));
        drain();

        List<OutboundEvent> out = ledger.drainOutbox();
        assertEquals(1, out.size());
        assertTrue(out.get(0).json().contains("\"fate\":\"FT-SENT\""), out.get(0).json());
    }

    /** Fail open: arguments of the wrong type record nothing wrong and never throw; the original result stays. */
    @Test
    void trackerResultBodiesNeverThrowOnOddArguments() {
        FakeTracker tracker = new FakeTracker(SPY, DEST, PDU, 9);
        run("HK-20", (Object) new FakeTracker[] {tracker});
        drain();
        Param p = runOn("HK-27c", tracker, null, "x", null);
        assertEquals(null, p.result());
        drain();
        String json = ledger.drainOutbox().get(0).json();
        assertTrue(json.contains("\"fate\":\"FT-SEND-FAILED\""), json);
        assertFalse(json.contains("\"errorCode\""), json);
        Param none = runOn("HK-27b", null, new Object());
        assertEquals(null, none.result());
        drain();
        assertTrue(ledger.drainOutbox().isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    /** Runs HK-27b or HK-27c with {@code target} as the hooked tracker ({@code this}); {@code args} are the parameters. */
    private Param runOn(String hookId, Object target, Object... args) {
        HookSpec spec = HookCatalog.of(hookId);
        assertNotNull(spec, hookId);
        Object[] full = new Object[args.length + 1];
        full[0] = target;
        System.arraycopy(args, 0, full, 1, args.length);
        Param p = Param.of(spec, full, void.class);
        if ("HK-27b".equals(hookId)) hooks.afterTrackerSent(p);
        else hooks.afterTrackerFailed(p);
        return p;
    }

    /**
     * Runs one hook body the way {@code PayloadRuntime.Dispatcher} does: the catalog spec supplies
     * the method name and the body, {@code args} is {@code [this, p0…]}.
     */
    private Param run(String hookId, Object... args) {
        HookSpec spec = HookCatalog.of(hookId);
        assertNotNull(spec, hookId);
        Object[] full = new Object[args.length + 1];
        full[0] = this;
        System.arraycopy(args, 0, full, 1, args.length);
        Param p = Param.of(spec, full, void.class);
        switch (hookId) {
            case "HK-20" -> hooks.beforeSendRawPdu(p);
            case "HK-22" -> hooks.beforeStkSend(p);
            case "HK-27" -> hooks.afterSendComplete(p);
            default -> hooks.beforeSend(p);
        }
        return p;
    }

    /** The worker step: every outbound observation reaches the ledger. */
    private void drain() {
        Observation o;
        while ((o = queue.poll()) != null) {
            if (o.kind == Observation.Kind.MO) ledger.accept(o.mo, o.tNs);
        }
    }

    private static FakeTracker[] trackers(int n) {
        FakeTracker[] out = new FakeTracker[n];
        for (int i = 0; i < n; i++) out[i] = new FakeTracker(SPY, "+1555000" + i, PDU, 9);
        return out;
    }

    /** {@code SMSDispatcher.SmsTracker} as the HK-20/27 bodies read it (fields only, no framework). */
    private static final class FakeTracker {
        public final String mDestAddress;
        public final HashMap<String, Object> mData = new HashMap<>();
        public int mMessageRef;
        public int mSubId = 1;
        private boolean mPersistMessage = true;
        private boolean mIsText = true;
        private boolean mIsForVvm;
        private String mAppPackageName;

        FakeTracker(String pkg, String dest, byte[] pdu, int destPort) {
            this.mAppPackageName = pkg;
            this.mDestAddress = dest;
            this.mData.put("pdu", pdu.clone());
            if (destPort >= 0) {
                this.mData.put("destPort", destPort);
                mIsText = false;
            }
        }

        public String getAppPackageName() {
            return mAppPackageName;
        }
    }

    /** {@code android.os.AsyncResult} as {@code handleSendComplete} receives it. */
    private static final class AsyncResult {
        public final Object userObj;
        public final Object result;
        public final Throwable exception;

        AsyncResult(Object userObj, Object result, Throwable exception) {
            this.userObj = userObj;
            this.result = result;
            this.exception = exception;
        }
    }

    /** {@code SmsResponse}, whose {@code mErrorCode} is the RIL error the result carries. */
    private static final class FakeResponse {
        public final int mErrorCode;
        public final int mMessageRef = 3;

        FakeResponse(int errorCode) {
            this.mErrorCode = errorCode;
        }
    }

    /** sendRawPduForSubscriber parameter 3 is the SMSC string (Pixel decomp), not the destination. */
    @Test
    void rawPduParameterThreeIsTheSmsc() {
        dev.nightjar.payload.outbound.OutboundRequests.Map raw =
                dev.nightjar.payload.outbound.OutboundRequests.map("sendRawPduForSubscriber", 7);
        assertNotNull(raw);
        assertEquals(-1, raw.dest());
        assertEquals(3, raw.sc());
        assertEquals(4, raw.data());
    }
}
