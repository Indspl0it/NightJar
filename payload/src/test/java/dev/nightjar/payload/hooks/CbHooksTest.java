package dev.nightjar.payload.hooks;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.Fate;
import dev.nightjar.contract.Tier;
import dev.nightjar.payload.capture.Observation;
import dev.nightjar.payload.capture.Observations;
import dev.nightjar.payload.config.SettingsHolder;
import dev.nightjar.payload.hook.HookCallback;
import dev.nightjar.payload.hook.HookCatalog;
import dev.nightjar.payload.hook.HookRegistry;
import dev.nightjar.payload.hook.HookSpec;
import dev.nightjar.payload.hook.Hooker;
import dev.nightjar.payload.hook.Param;
import dev.nightjar.payload.store.EventQueue;
import dev.nightjar.payload.testing.FakeClock;
import dev.nightjar.payload.testing.FakeEnv;
import dev.nightjar.payload.testing.FakeWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HK-17 capture, header parsing, {@code cb.log=false} and the emergency rule.
 *
 * <p>The hook is driven through {@link Hooker#call(Object[])} with fake framework objects, so the test
 * covers the same reflective path the phone process takes: {@code Message.what}, an {@code AsyncResult}
 * in {@code Message.obj}, and {@code this$0.mPhone.getPhoneId()} on the anonymous handler.
 */
class CbHooksTest {

    /** {@code android.os.Message} stand-in. */
    public static final class FakeMessage {
        public int what;
        public Object obj;
    }

    /** {@code android.os.AsyncResult} stand-in: the PDU is {@code result}. */
    public static final class FakeAsyncResult {
        public Object result;

        FakeAsyncResult(Object result) {
            this.result = result;
        }
    }

    public static final class FakePhone {
        private final int phoneId;

        FakePhone(int phoneId) {
            this.phoneId = phoneId;
        }

        public int getPhoneId() {
            return phoneId;
        }
    }

    /** {@code CellBroadcastServiceManager} stand-in; the handler's {@code this$0} points here. */
    public static final class FakeManager {
        public final Object mPhone;

        FakeManager(Object phone) {
            this.mPhone = phone;
        }
    }

    /** {@code CellBroadcastServiceManager$1} stand-in. */
    public static final class FakeHandler {
        @SuppressWarnings("checkstyle:MemberName")
        public final FakeManager this$0;

        FakeHandler(FakeManager manager) {
            this.this$0 = manager;
        }
    }

    /** serial 0x1234, channel 4370 (CMAS Presidential), dcs 0x0F, page parameter "2 of 3". */
    private static final byte[] CMAS_PAGE_2_OF_3 = {
            0x12, 0x34, 0x11, 0x12, 0x0F, 0x23, 0x41, 0x42, 0x43
    };

    /** serial 0x1234, channel 4352 (ETWS earthquake), warning type where the page parameter sits. */
    private static final byte[] ETWS_PRIMARY = {
            0x12, 0x34, 0x11, 0x00, (byte) 0x80, (byte) 0x80, 0x01
    };

    private FakeClock clock;
    private FakeEnv env;
    private EventQueue<Observation> queue;
    private Observations observations;
    private SettingsHolder settings;
    private CbHooks hooks;
    private FakeHandler handler;
    private HookRegistry registry;

    @BeforeEach
    void setUp() throws Throwable {
        clock = new FakeClock(1_000_000_000L);
        env = new FakeEnv();
        env.setSubForPhone(0, 7);
        env.setSlot(7, 0);
        queue = new EventQueue<>(64);
        observations = new Observations(queue, new FakeWorker(), () -> {});
        settings = new SettingsHolder();
        hooks = new CbHooks(observations, clock, env, settings);
        handler = new FakeHandler(new FakeManager(new FakePhone(0)));
        registry = new HookRegistry();
        registry.register(HookCatalog.of("HK-17"));
    }

    /** Runs the before-hook exactly as the Xposed backend would, through the real {@link Hooker}. */
    private void invoke(FakeMessage msg) throws Throwable {
        HookSpec spec = HookCatalog.of("HK-17");
        Hooker hooker = new Hooker(spec, new HookCallback() {
            @Override
            public void before(Param p) {
                hooks.before(p);
            }

            @Override
            public void after(Param p) {
                // HK-17 is a before-hook only.
            }
        }, registry, dev.nightjar.payload.hook.FaultInjector.NONE, null);
        hooker.setOriginal((receiver, params) -> {
            originalCalls++;
            return null;
        });
        hooker.call(new Object[] {handler, msg});
    }

    /** Calls of the original handleMessage through {@link #invoke}. */
    private int originalCalls;

    /**
     * A UMTS CB message (TS 23.041): message type 1, message id, serial, dcs, one page of 82 octets plus
     * its length octet. Longer than one GSM page (88 octets).
     */
    private static byte[] umts(int messageId, int serial) {
        byte[] pdu = new byte[7 + 83];
        pdu[0] = 1;
        pdu[1] = (byte) (messageId >> 8);
        pdu[2] = (byte) messageId;
        pdu[3] = (byte) (serial >> 8);
        pdu[4] = (byte) serial;
        pdu[5] = 0x0F;
        pdu[6] = 1;
        pdu[pdu.length - 1] = 10;
        return pdu;
    }

    private static FakeMessage gsmMessage(byte[] pdu) {
        FakeMessage msg = new FakeMessage();
        msg.what = 0;
        msg.obj = new FakeAsyncResult(pdu.clone());
        return msg;
    }

    private Observation poll() {
        return queue.poll();
    }

    @Test
    void catalogDeclaresHk17AsAnAnonymousHandlerHook() throws Throwable {
        HookSpec spec = HookCatalog.of("HK-17");
        assertNotNull(spec);
        assertEquals(Tier.CAPTURE_OPTIONAL, spec.tier());
        assertEquals(HookSpec.ResolverKind.ANONYMOUS, spec.resolver());
        assertEquals("android.os.Handler", spec.superName());
        assertEquals("handleMessage", spec.methodName());
        assertEquals(1, spec.arity());
    }

    @Test
    void asyncResultPduIsCopiedWithSerialChannelAndPage() throws Throwable {
        FakeMessage msg = gsmMessage(CMAS_PAGE_2_OF_3);
        invoke(msg);

        Observation o = poll();
        assertNotNull(o, "a CB observation must be pushed");
        assertEquals(Observation.Kind.CB, o.kind);
        assertArrayEquals(CMAS_PAGE_2_OF_3, o.pdu);
        assertEquals(0x1234, o.cbSerial);
        assertEquals(4370, o.cbMessageId);
        assertEquals(2, o.cbPage);
        assertEquals(3, o.cbPages);
        assertTrue(o.cbEmergency);
        assertEquals(0, o.phoneId, "phoneId comes from this$0.mPhone");
        assertEquals(7, o.subId, "subId is resolved from the phone id");
        assertEquals(0, o.slot);
        assertEquals("cb", o.format);
        assertEquals(dev.nightjar.contract.Transport.CB, o.transport);
        assertEquals(1_000_000_000L, o.tNs);
        assertEquals(1_762_330_931_204L, o.wallMs);
        assertNull(o.fate, "a captured, unblocked broadcast is not sealed here");
        assertNull(poll());
    }

    @Test
    void pduBytesAreCopiedNotAliased() throws Throwable {
        byte[] pdu = CMAS_PAGE_2_OF_3.clone();
        FakeMessage msg = gsmMessage(pdu);
        invoke(msg);
        pdu[2] = 0x7F;
        pdu[3] = 0x7F;
        Observation o = poll();
        assertNotNull(o);
        assertEquals(4370, o.cbMessageId);
        assertArrayEquals(CMAS_PAGE_2_OF_3, o.pdu);
    }

    @Test
    void etwsPrimaryIsASinglePage() throws Throwable {
        invoke(gsmMessage(ETWS_PRIMARY));
        Observation o = poll();
        assertNotNull(o);
        assertEquals(4352, o.cbMessageId);
        assertTrue(o.cbEmergency);
        assertEquals(1, o.cbPage);
        assertEquals(1, o.cbPages);
    }

    @Test
    void bareByteArrayObjectIsAccepted() throws Throwable {
        FakeMessage msg = new FakeMessage();
        msg.what = 0;
        msg.obj = CMAS_PAGE_2_OF_3.clone();
        invoke(msg);
        Observation o = poll();
        assertNotNull(o);
        assertEquals(4370, o.cbMessageId);
    }

    @Test
    void otherMessageKindsAreIgnored() throws Throwable {
        FakeMessage cdma = gsmMessage(CMAS_PAGE_2_OF_3);
        cdma.what = 1;
        invoke(cdma);
        assertNull(poll(), "what != 0 is a CDMA broadcast and is not captured");

        FakeMessage noObject = new FakeMessage();
        noObject.what = 0;
        invoke(noObject);
        assertNull(poll());

        FakeMessage shortPdu = gsmMessage(new byte[] {0x12, 0x34});
        invoke(shortPdu);
        assertNull(poll(), "a PDU without a message identifier cannot be reported");
    }

    @Test
    void cbLogFalseSuppressesTheEvent() throws Throwable {
        settings.apply("{\"cb\":{\"log\":false}}");
        invoke(gsmMessage(CMAS_PAGE_2_OF_3));
        assertNull(poll(), "cb.log=false must produce no CB events");

        settings.apply("{\"cb\":{\"log\":true}}");
        invoke(gsmMessage(CMAS_PAGE_2_OF_3));
        assertNotNull(poll(), "cb.log=true captures again");
    }

    @Test
    void emergencyChannelsAreNeverOfferedToTheFirewall() throws Throwable {
        boolean[] offered = {false};
        hooks.setPort((messageId, emergency, subId) -> {
            offered[0] = true;
            return true;
        });
        FakeMessage msg = gsmMessage(CMAS_PAGE_2_OF_3);
        invoke(msg);
        assertFalse(offered[0], "G-01: an emergency channel must not reach the port");
        Observation o = poll();
        assertNotNull(o);
        assertNull(o.fate, "an emergency broadcast is never blocked");
    }

    @Test
    void nonEmergencyChannelCanBeBlockedAndIsStillLogged() throws Throwable {
        byte[] weather = {0x12, 0x34, 0x04, 0x00, 0x0F, 0x11, 0x41};
        hooks.setPort((messageId, emergency, subId) -> messageId == 1024 && !emergency);
        FakeMessage msg = gsmMessage(weather);
        invoke(msg);
        Observation o = poll();
        assertNotNull(o, "a blocked broadcast is still captured");
        assertEquals(1024, o.cbMessageId);
        assertEquals(Fate.BLOCKED, o.fate);
    }

    @Test
    void cbLogFalseStillEnforcesTheBlock() throws Throwable {
        settings.apply("{\"cb\":{\"log\":false}}");
        boolean[] offered = {false};
        hooks.setPort((messageId, emergency, subId) -> {
            offered[0] = true;
            return true;
        });
        invoke(gsmMessage(new byte[] {0x12, 0x34, 0x04, 0x00, 0x0F, 0x11, 0x41}));
        assertTrue(offered[0], "logging off must not disable the firewall");
        assertNull(poll(), "but nothing is captured");
    }

    /** A UMTS CMAS message reads the id and serial at the UMTS offsets and is emergency. */
    @Test
    void umtsCmasMessageIsReadAtTheUmtsOffsets() throws Throwable {
        boolean[] offered = {false};
        hooks.setPort((messageId, emergency, subId) -> {
            offered[0] = true;
            return true;
        });
        invoke(gsmMessage(umts(4370, 0x2345)));
        Observation o = poll();
        assertNotNull(o);
        assertEquals(4370, o.cbMessageId);
        assertEquals(0x2345, o.cbSerial);
        assertTrue(o.cbEmergency);
        assertEquals(1, o.cbPage);
        assertEquals(1, o.cbPages, "one UMTS message carries all its pages");
        assertEquals(Observation.CB_UMTS, o.format);
        assertFalse(offered[0], "G-01: an emergency channel must not reach the port");
        assertEquals(1, originalCalls);
    }

    /** A UMTS message on a non-emergency channel can be blocked like a GSM page. */
    @Test
    void umtsNonEmergencyMessageCanBeBlocked() throws Throwable {
        hooks.setPort((messageId, emergency, subId) -> messageId == 1024);
        invoke(gsmMessage(umts(1024, 1)));
        Observation o = poll();
        assertNotNull(o);
        assertEquals(1024, o.cbMessageId);
        assertFalse(o.cbEmergency);
        assertEquals(Fate.BLOCKED, o.fate);
        assertEquals(0, originalCalls, "a blocked broadcast skips the original");
    }

    /** A PDU longer than a GSM page that is not a UMTS message counts as emergency. */
    @Test
    void anUnclearLayoutIsNeverBlocked() throws Throwable {
        boolean[] offered = {false};
        hooks.setPort((messageId, emergency, subId) -> {
            offered[0] = true;
            return true;
        });
        byte[] pdu = umts(1024, 1);
        pdu[0] = 2;
        invoke(gsmMessage(pdu));
        Observation o = poll();
        assertNotNull(o);
        assertTrue(o.cbEmergency);
        assertFalse(offered[0]);
        assertNull(o.fate);
        assertEquals(1, originalCalls);
    }

    /** A page index above the page total counts as one page, as in the framework. */
    @Test
    void aPageIndexAboveTheTotalIsOnePage() throws Throwable {
        invoke(gsmMessage(new byte[] {0x12, 0x34, 0x04, 0x00, 0x0F, 0x32, 0x41}));
        Observation o = poll();
        assertNotNull(o);
        assertEquals(1, o.cbPage);
        assertEquals(1, o.cbPages);
    }

    /** CONTRIBUTING: when the hook body throws, the original handleMessage runs unchanged. */
    @Test
    void aThrowingPortLetsTheOriginalRun() throws Throwable {
        hooks.setPort((messageId, emergency, subId) -> {
            throw new IllegalStateException("boom");
        });
        invoke(gsmMessage(new byte[] {0x12, 0x34, 0x04, 0x00, 0x0F, 0x11, 0x41}));
        assertEquals(1, originalCalls, "the catch-all runs the original");
    }
}
