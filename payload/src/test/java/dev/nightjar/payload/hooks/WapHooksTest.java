package dev.nightjar.payload.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.payload.capture.Observation;
import dev.nightjar.payload.capture.Observations;
import dev.nightjar.payload.hook.HookCatalog;
import dev.nightjar.payload.hook.Param;
import dev.nightjar.payload.store.EventQueue;
import dev.nightjar.payload.testing.FakeClock;
import dev.nightjar.payload.testing.FakeWorker;
import org.junit.jupiter.api.Test;

/** HK-14 body: the port contract, including the originating address. */
class WapHooksTest {

    private static final byte[] PUSH = {0x01, 0x06, 0x02, 0x2C, (byte) 0xBE, 0x00};

    private final EventQueue<Observation> queue = new EventQueue<>();

    private WapHooks hooks() {
        return new WapHooks(new Observations(queue, new FakeWorker(), () -> {}), new FakeClock(0));
    }

    private java.util.List<String> details() {
        java.util.List<String> out = new java.util.ArrayList<>();
        Observation o;
        while ((o = queue.poll()) != null) {
            if (o.detail != null) out.add(o.detail);
        }
        return out;
    }

    /** {@code dispatchWapPdu(byte[] pdu, SmsBroadcastReceiver, InboundSmsHandler, String address, int subId, long)}. */
    private static Param param(String address) {
        return Param.of(HookCatalog.of("HK-14"),
                new Object[] {new Object(), PUSH, "receiver", "handler", address, 1, 42L}, int.class);
    }

    @Test
    void P18_theOriginatorReachesThePort() {
        WapHooks hooks = hooks();
        String[] seen = new String[1];
        hooks.setPort((pdu, contentType, originator, subId) -> {
            seen[0] = originator;
            return WapHooks.CONTINUE;
        });

        Param p = param("+919000000000");
        hooks.before(p);

        assertEquals("+919000000000", seen[0]);
        assertFalse(p.skipped(), "CONTINUE runs the original");
    }

    @Test
    void P18_aMissingAddressIsPassedAsNull() {
        WapHooks hooks = hooks();
        String[] seen = {"unset"};
        hooks.setPort((pdu, contentType, originator, subId) -> {
            seen[0] = originator;
            return WapHooks.CONTINUE;
        });

        hooks.before(param(null));

        assertNull(seen[0]);
    }

    @Test
    void aBlockingPortSkipsWithItsResult() {
        WapHooks hooks = hooks();
        hooks.setPort((pdu, contentType, originator, subId) -> 1);

        Param p = param("+919000000000");
        hooks.before(p);

        assertTrue(p.skipped());
        assertEquals(1, p.result());
    }

    @Test
    void aNullPduIsIgnored() {
        WapHooks hooks = hooks();
        hooks.setPort((pdu, contentType, originator, subId) -> 1);

        Param p = Param.of(HookCatalog.of("HK-14"),
                new Object[] {new Object(), null, "receiver", "handler", "+9190", 1, 42L}, int.class);
        hooks.before(p);

        assertFalse(p.skipped());
    }

    /** A push the firewall consumed is tagged by=nightjar. */
    @Test
    void aBlockIsTaggedByNightjar() {
        WapHooks hooks = hooks();
        hooks.setPort((pdu, contentType, originator, subId) -> 1);
        hooks.before(param("+919000000000"));
        java.util.List<String> details = details();
        assertEquals(1, details.size());
        assertTrue(details.get(0).endsWith("result=1 by=nightjar"), details.get(0));
    }

    /** The verdict travels in the wap.dispatch observation, so the correlator cannot seal between the two. */
    @Test
    void aBlockWithAVerdictIsOneObservation() {
        WapHooks hooks = hooks();
        hooks.setPort(new WapHooks.Port() {
            @Override
            public int beforeDispatch(byte[] pdu, String contentType, String originator, int subId) {
                return 1;
            }

            @Override
            public dev.nightjar.contract.Verdict lastVerdict() {
                return dev.nightjar.contract.Verdict.QUARANTINE;
            }

            @Override
            public long lastRuleId() {
                return 7L;
            }

            @Override
            public String lastClamp() {
                return "CANARY";
            }
        });
        hooks.before(param("+919000000000"));

        java.util.List<Observation> all = new java.util.ArrayList<>();
        Observation o;
        while ((o = queue.poll()) != null) all.add(o);
        assertEquals(1, all.size(), all.toString());
        Observation only = all.get(0);
        assertEquals(Observation.Kind.STAGE, only.kind);
        assertEquals(dev.nightjar.contract.TraceStage.WAP_DISPATCH, only.stage);
        assertEquals(dev.nightjar.contract.Verdict.QUARANTINE, only.verdict);
        assertEquals(7L, only.ruleId);
        assertEquals("CANARY", only.clampReason);
        assertTrue(only.detail.endsWith("result=1 by=nightjar"), only.detail);
    }

    /** The safety net also sends its MALFORMED block with the stage, in one observation. */
    @Test
    void aRecoveredCrashCarriesItsVerdict() {
        WapHooks hooks = hooks();
        Object[] args = {new Object(), PUSH, "receiver", "handler", "+919000000000", 1, 42L};
        hooks.recover(args, new IndexOutOfBoundsException());

        Observation only = queue.poll();
        assertEquals(dev.nightjar.contract.Verdict.BLOCK, only.verdict);
        assertEquals("MALFORMED", only.clampReason);
        assertEquals(dev.nightjar.contract.TraceStage.WAP_DISPATCH, only.stage);
        assertNull(queue.poll());
    }

    /** HK-14 is an AROUND hook; the after phase records the original's result without a by tag. */
    @Test
    void aPushThatContinuesIsRecordedOnceWithTheOriginalResult() {
        WapHooks hooks = hooks();
        Param p = param("+919000000000");
        hooks.before(p);
        assertTrue(details().isEmpty());
        p.setResult(2);
        hooks.after(p);
        java.util.List<String> details = details();
        assertEquals(1, details.size());
        assertTrue(details.get(0).endsWith("result=2"), details.get(0));
        assertFalse(details.get(0).contains("by="));
    }

    /** A parser exception of the original is consumed (by=crash) and the after phase does not record it again. */
    @Test
    void aRecoveredCrashIsRecordedOnceByCrash() {
        WapHooks hooks = hooks();
        Object[] args = {new Object(), PUSH, "receiver", "handler", "+919000000000", 1, 42L};
        Param p = param("+919000000000");
        hooks.before(p);
        assertEquals(1, hooks.recover(args, new ArrayIndexOutOfBoundsException(9)));
        p.setResult(1);
        hooks.after(p);
        java.util.List<String> details = details();
        assertEquals(1, details.size(), details.toString());
        assertTrue(details.get(0).contains("result=1 by=crash crash=ArrayIndexOutOfBoundsException"), details.get(0));
    }

    /** An exception that is not a parser error is a framework bug and is rethrown. */
    @Test
    void anOtherExceptionIsNotRecovered() {
        WapHooks hooks = hooks();
        Object[] args = {new Object(), PUSH, "receiver", "handler", "+919000000000", 1, 42L};
        assertNull(hooks.recover(args, new IllegalStateException("bug")));
        assertNull(hooks.recover(args, new NullPointerException()));
        assertTrue(details().isEmpty());
    }
}
