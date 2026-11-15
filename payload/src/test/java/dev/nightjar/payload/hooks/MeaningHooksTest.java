package dev.nightjar.payload.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.payload.capture.Observation;
import dev.nightjar.payload.capture.Observations;
import dev.nightjar.payload.hook.HookCatalog;
import dev.nightjar.payload.hook.HookSpec;
import dev.nightjar.payload.hook.Param;
import dev.nightjar.payload.store.EventQueue;
import dev.nightjar.payload.testing.FakeClock;
import dev.nightjar.payload.testing.FakeWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Argument positions of the meaning hooks against the AOSP signatures and the OPlus firmware audit. */
class MeaningHooksTest {

    /** {@code InboundSmsTracker} stand-in. */
    public static final class FakeTracker {
        private final byte[] pdu;

        FakeTracker(byte[] pdu) {
            this.pdu = pdu;
        }

        public byte[] getPdu() {
            return pdu;
        }
    }

    /** {@code CarrierServicesSmsFilterCallback} stand-in. */
    public static final class FakeCallback {
        public final FakeTracker mTracker;

        FakeCallback(FakeTracker tracker) {
            this.mTracker = tracker;
        }
    }

    /** {@code SmsBroadcastReceiver} stand-in. */
    public static final class FakeReceiver {
        public final FakeTracker mInboundSmsTracker;

        FakeReceiver(FakeTracker tracker) {
            this.mInboundSmsTracker = tracker;
        }
    }

    private static final byte[] PDU = {0x02, (byte) 0x91, 0x21, 0x04, 0x0B, (byte) 0x91, 0x11, 0x22, 0x33};

    private EventQueue<Observation> queue;
    private MeaningHooks hooks;
    private OplusHooks oplus;
    private WapHooks wap;

    @BeforeEach
    void setUp() {
        FakeClock clock = new FakeClock(1_000_000_000L);
        queue = new EventQueue<>(16);
        Observations observations = new Observations(queue, new FakeWorker(), () -> {});
        hooks = new MeaningHooks(observations, clock);
        oplus = new OplusHooks(observations, clock);
        wap = new WapHooks(observations, clock);
    }

    private static Param param(String id, Object[] args, Class<?> returns) {
        return Param.of(HookCatalog.of(id), args, returns);
    }

    @Test
    void carrierFilterCompleteOnlyTreatsTheDropBitAsConsumed() {
        FakeCallback cb = new FakeCallback(new FakeTracker(PDU));
        hooks.beforeCarrierFilterComplete(param("HK-11", new Object[] {cb, 2}, void.class));
        Observation notConsumed = queue.poll();
        assertNotNull(notConsumed);
        assertEquals("consumed=false", notConsumed.detail);

        hooks.beforeCarrierFilterComplete(param("HK-11", new Object[] {cb, 1}, void.class));
        assertEquals("consumed=true", queue.poll().detail);
    }

    /** The redirect stage is keyed by the PDUs and names no package (the call does not give one). */
    @Test
    void oemRedirectUsesThePdusAndNamesNoPackage() {
        Object[] args = {this, new byte[][] {PDU}, "3gpp", 0, null, false, 1, 0L, true};
        oplus.beforeRedirect(param("HK-31", args, void.class));
        Observation o = queue.poll();
        assertNotNull(o);
        assertEquals(CaptureHooks.keyFor(PDU), o.key);
        assertEquals("blocked=true", o.detail);
    }

    @Test
    void oemRedirectIgnoresUnblockedDispatch() {
        Object[] args = {this, new byte[][] {PDU}, "3gpp", 0, null, false, 1, 0L, false};
        oplus.beforeRedirect(param("HK-31", args, void.class));
        assertNull(queue.poll());
    }

    /** The key comes from the extra named pdus; a WAP intent with header and data byte arrays has none. */
    @Test
    void intentKeyComesOnlyFromThePdusExtra() {
        java.util.Map<String, Object> sms = new java.util.HashMap<>();
        sms.put("header", new byte[] {0x01, 0x02});
        sms.put("pdus", new Object[] {PDU});
        assertEquals(CaptureHooks.keyFor(PDU), MeaningHooks.keyFromIntent(new FakeIntent(sms)));

        java.util.Map<String, Object> wap = new java.util.HashMap<>();
        wap.put("header", new byte[] {0x01, 0x02});
        wap.put("data", new byte[] {0x03, 0x04});
        assertNull(MeaningHooks.keyFromIntent(new FakeIntent(wap)));
        assertNull(MeaningHooks.keyFromIntent(new FakeIntent(null)));
    }

    /** An SMSC-prefixed PDU from getPdu() is read with the radio framing, so the branch is right. */
    @Test
    void radioSpecificReadsTheSmscPrefixedPdu() {
        // SMSC 07 91 19 89 00 00 00 F0, then SMS-DELIVER, OA +919000000000 (synthetic), PID 0x40 (type 0).
        byte[] radio = {
                0x07, (byte) 0x91, 0x19, (byte) 0x89, 0x00, 0x00, 0x00, (byte) 0xF0,
                0x04, 0x0C, (byte) 0x91, 0x19, 0x09, 0x00, 0x00, 0x00, 0x00,
                0x40, 0x00, 0x52, 0x11, 0x13, 0x21, 0x43, 0x00, 0x00, 0x00};
        FakeTracker message = new FakeTracker(radio);
        Param p = param("HK-07", new Object[] {this, message, 0, 0}, int.class);
        p.setResult(1);
        hooks.afterRadioSpecific(p);
        Observation o = queue.poll();
        assertNotNull(o);
        assertEquals("branch=type0", o.detail);
    }

    /** CONTRIBUTING: a throwing body leaves the original result unchanged (HK-07 through the real Hooker). */
    @Test
    void aThrowingBodyLeavesTheOriginalResult() throws Throwable {
        dev.nightjar.payload.hook.HookRegistry registry = new dev.nightjar.payload.hook.HookRegistry();
        HookSpec spec = HookCatalog.of("HK-07");
        registry.register(spec);
        dev.nightjar.payload.hook.Hooker hooker = new dev.nightjar.payload.hook.Hooker(spec,
                new dev.nightjar.payload.hook.HookCallback() {
                    @Override
                    public void before(Param p) {
                        // HK-07 is an after-hook.
                    }

                    @Override
                    public void after(Param p) {
                        throw new IllegalStateException("boom");
                    }
                }, registry, dev.nightjar.payload.hook.FaultInjector.NONE, null);
        hooker.setOriginal((receiver, params) -> 4);
        assertEquals(4, hooker.call(new Object[] {this, new FakeTracker(PDU), 0, 0}));
    }

    /** {@code Intent} stand-in: {@code mExtras.mMap}. */
    public static final class FakeIntent {
        public final FakeBundle mExtras;

        FakeIntent(java.util.Map<String, Object> map) {
            this.mExtras = map == null ? null : new FakeBundle(map);
        }
    }

    /** {@code Bundle} stand-in. */
    public static final class FakeBundle {
        public final java.util.Map<String, Object> mMap;

        FakeBundle(java.util.Map<String, Object> map) {
            this.mMap = map;
        }
    }

    @Test
    void wapDispatchIsKeyedByTheReceiversTracker() {
        FakeReceiver receiver = new FakeReceiver(new FakeTracker(PDU));
        byte[] wapPayload = {0x01, 0x06, 0x03, 0x01, 0x02, 0x03};
        HookSpec spec = HookCatalog.of("HK-14");
        Object[] args = {this, wapPayload, receiver, null, "AX-HDFCBK", 1, 5L};
        Param withTrackerCall = Param.of(spec, args, int.class);
        wap.before(withTrackerCall);
        assertNull(queue.poll(), "a push that continues is recorded once, in the after phase");
        withTrackerCall.setResult(1);
        wap.after(withTrackerCall);
        Observation withTracker = queue.poll();
        assertNotNull(withTracker);
        assertTrue(withTracker.detail.startsWith("ct="));

        Object[] noTracker = {this, wapPayload, new Object(), null, "AX-HDFCBK", 1, 5L};
        Param noTrackerCall = Param.of(spec, noTracker, int.class);
        wap.before(noTrackerCall);
        noTrackerCall.setResult(1);
        wap.after(noTrackerCall);
        Observation fallback = queue.poll();
        assertNotNull(fallback);
        assertTrue(!withTracker.key.equals(fallback.key), "tracker key differs from the payload-hash fallback");
    }
}
