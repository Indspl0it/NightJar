package dev.nightjar.payload.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.FateDetail;
import dev.nightjar.contract.Hashes;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.contract.Transport;
import dev.nightjar.contract.Verdict;
import dev.nightjar.payload.firewall.VerdictRecord;
import dev.nightjar.payload.store.OutboundEvent;
import dev.nightjar.payload.testing.FakeClock;
import dev.nightjar.payload.testing.FakeEnv;
import dev.nightjar.payload.testing.FakeSpool;
import dev.nightjar.payload.testing.FakeTokens;
import dev.nightjar.payload.testing.Pdu;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One WAP push is one event. The radio stages (slot, sender, port, SMSC) and the HK-14 stage
 * (content type, verdict, result) end on the same event, and a canary push keeps its self-test flags.
 */
class CorrelatorWapTest {

    /** Port IE 0x05: destination 2948, origin 9200. */
    private static final byte[] WAP_UDH = {6, 0x05, 4, 0x0B, (byte) 0x84, 0x23, (byte) 0xF0};
    /** WSP push header: TID, PDU type push, header length, content type SI (0xAE), then the body. */
    private static final byte[] WSP = {0x01, 0x06, 0x01, (byte) 0xAE, 0x02, 0x05, 0x6A, 0x00};
    private static final String CT = "application/vnd.wap.sic";

    private FakeClock clock;
    private FakeTokens tokens;
    private Correlator correlator;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(1_000_000_000L);
        FakeEnv env = new FakeEnv();
        tokens = new FakeTokens();
        correlator = new Correlator(clock, new Sealer(env::userUnlocked), new Counters(), env, new FakeSpool(),
                tokens, uid -> new String[] {"com.example.injector"}, "session-1", "1.0.0", 7);
    }

    private static byte[] wapPdu() {
        return Pdu.deliver().dcs(0x04).udhBytes(WAP_UDH).ud(WSP).build();
    }

    private static String key(byte[] pdu) {
        return Hashes.tpduKey(Hashes.stripSmsc(pdu));
    }

    private void radio(byte[] pdu) {
        correlator.accept(Observation.raw(key(pdu), Hashes.tpduKey(pdu), pdu, "3gpp", Transport.CS, 0, -1, -1, -1,
                false, clock.elapsedNs(), clock.wallMs()));
        clock.advanceMs(5);
        correlator.accept(Observation.dispatch(key(pdu), Hashes.tpduKey(pdu), 0, 0, 1, -1, clock.elapsedNs()));
        clock.advanceMs(30);
    }

    /** Everything emitted now and after the 30 s timeout. */
    private List<OutboundEvent> all() {
        List<OutboundEvent> out = new ArrayList<>(correlator.drainOutbox());
        clock.advanceMs(31_000);
        out.addAll(correlator.tick(clock.elapsedNs()));
        return out;
    }

    private static boolean hasStage(JSONObject event, TraceStage stage, String marker) {
        JSONArray trace = event.getJSONArray("trace");
        for (int i = 0; i < trace.length(); i++) {
            JSONObject step = trace.getJSONObject(i);
            if (!stage.wire().equals(step.optString("stage"))) continue;
            if (marker == null || step.optString("detail", "").contains(marker)) return true;
        }
        return false;
    }

    @Test
    void aBlockedPushIsOneEventWithTheRadioFactsAndTheContentType() {
        byte[] pdu = wapPdu();
        radio(pdu);
        correlator.accept(Observation.stageWithVerdict(key(pdu), TraceStage.WAP_DISPATCH, clock.elapsedNs(),
                "ct=" + CT + " result=1 by=nightjar", Verdict.BLOCK, 7L, null));

        List<OutboundEvent> out = all();
        assertEquals(1, out.size(), out.toString());
        JSONObject e = new JSONObject(out.get(0).json());
        assertEquals("FT-BLOCKED", e.getString("fate"));
        assertEquals(0, e.getInt("slot"));
        assertEquals(2948, e.getInt("destPort"));
        assertEquals(CT, e.getString("contentType"));
        assertFalse(e.isNull("originator"));
        assertFalse(e.isNull("smsc"));
        assertTrue(hasStage(e, TraceStage.WAP_DISPATCH, "result=1"), e.toString());
        assertTrue(hasStage(e, TraceStage.FW_HEAD_FILTER, "rule=7"), e.toString());
    }

    /** The diagnostics sequence: a verdict, then the wap.dispatch stage ~30 ms later. Still one event. */
    @Test
    void aStageAfterTheVerdictSealedThePushOpensNoSecondEvent() {
        byte[] pdu = wapPdu();
        radio(pdu);
        correlator.accept(Observation.verdict(key(pdu), null, -1, Verdict.QUARANTINE, -1L, null, clock.elapsedNs()));
        clock.advanceMs(30);
        correlator.accept(Observation.stage(key(pdu), TraceStage.WAP_DISPATCH, clock.elapsedNs(),
                "ct=" + CT + " result=1 by=nightjar"));

        List<OutboundEvent> out = all();
        assertEquals(1, out.size(), out.toString());
        JSONObject e = new JSONObject(out.get(0).json());
        assertEquals("FT-QUARANTINED", e.getString("fate"));
        assertEquals(0, e.getInt("slot"));
    }

    @Test
    void aPushThatContinuesGetsTheAppThatReceivedIt() {
        byte[] pdu = wapPdu();
        radio(pdu);
        // dispatchWapPdu sends the intent from inside HK-14: HK-13a has no key, and the stage comes before wap.dispatch.
        correlator.accept(Observation.stage(null, TraceStage.HANDLER_DISPATCH_INTENT, clock.elapsedNs(),
                "action=android.provider.Telephony.WAP_PUSH_DELIVER pkg=com.example.mms"));
        assertTrue(correlator.drainOutbox().isEmpty(), "the intent alone does not seal the push");
        correlator.accept(Observation.stage(key(pdu), TraceStage.WAP_DISPATCH, clock.elapsedNs(),
                "ct=" + CT + " result=-1"));

        List<OutboundEvent> out = all();
        assertEquals(1, out.size(), out.toString());
        JSONObject e = new JSONObject(out.get(0).json());
        assertEquals("FT-APP", e.getString("fate"));
        assertEquals("com.example.mms", e.getJSONObject("fateDetail").getString(FateDetail.PACKAGE));
    }

    /** Android deletes a push whose WSP header does not decode; that is a known fate, not FT-UNKNOWN. */
    @Test
    void aPushThatAndroidCannotDecodeIsSystem() {
        byte[] pdu = wapPdu();
        radio(pdu);
        correlator.accept(Observation.stage(key(pdu), TraceStage.WAP_DISPATCH, clock.elapsedNs(),
                "ct=unknown result=2"));

        List<OutboundEvent> out = all();
        assertEquals(1, out.size());
        assertEquals("FT-SYSTEM", new JSONObject(out.get(0).json()).getString("fate"));
    }

    /** A WAP canary is one event with its flags and the wap.dispatch stage, so the self-test can pass it. */
    @Test
    void aWapCanaryKeepsItsFlagsAndItsStage() {
        byte[] pdu = wapPdu();
        tokens.add(key(pdu), new InjectionTokens.Match(InjectionTokens.Kind.CANARY, null, "run-1", "CL-WAP-SISL"));
        correlator.accept(Observation.dispatch(key(pdu), Hashes.tpduKey(pdu), pdu, 0, 2, 1, -1, clock.elapsedNs()));
        clock.advanceMs(20);
        correlator.accept(Observation.stageWithVerdict(key(pdu), TraceStage.WAP_DISPATCH, clock.elapsedNs(),
                "ct=" + CT + " result=1 by=nightjar", Verdict.BLOCK, -1L, VerdictRecord.CANARY));

        List<OutboundEvent> out = all();
        assertEquals(1, out.size(), out.toString());
        JSONObject e = new JSONObject(out.get(0).json());
        JSONObject flags = e.getJSONObject("flags");
        assertTrue(flags.getBoolean("selfTest"));
        assertEquals("run-1", flags.getString("runId"));
        assertEquals("CL-WAP-SISL", flags.getString("canaryClass"));
        assertEquals("FT-BLOCKED", e.getString("fate"));
        assertTrue(hasStage(e, TraceStage.WAP_DISPATCH, "result=1"), e.toString());
    }
}
