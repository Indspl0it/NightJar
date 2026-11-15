package dev.nightjar.payload.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.nightjar.contract.Hashes;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.contract.Transport;
import dev.nightjar.contract.Verdict;
import dev.nightjar.payload.store.OutboundEvent;
import dev.nightjar.payload.testing.FakeClock;
import dev.nightjar.payload.testing.FakeEnv;
import dev.nightjar.payload.testing.FakeSpool;
import dev.nightjar.payload.testing.FakeTokens;
import dev.nightjar.payload.testing.Pdu;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A discard MWI never reaches HK-08, so the HK-23 stage is its only verdict. A hold there must seal the event
 * as held (releasable), and a block must stay a block.
 */
class CorrelatorMwiTest {

    private FakeClock clock;
    private Correlator correlator;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(1_000_000_000L);
        FakeEnv env = new FakeEnv();
        correlator = new Correlator(clock, new Sealer(env::userUnlocked), new Counters(), env, new FakeSpool(),
                new FakeTokens(), uid -> new String[] {"com.example.injector"}, "session-1", "1.0.0", 7);
    }

    /** DCS 0xC8: message waiting group "discard", voicemail indicator on, no text. */
    private static byte[] discardMwiPdu() {
        return Pdu.deliver().dcs(0xC8).ud(new byte[0]).build();
    }

    private void radio(byte[] pdu) {
        correlator.accept(Observation.raw(Hashes.tpduKey(Hashes.stripSmsc(pdu)), Hashes.tpduKey(pdu), pdu, "3gpp",
                Transport.CS, 0, -1, -1, -1, false, clock.elapsedNs(), clock.wallMs()));
        clock.advanceMs(5);
    }

    private List<OutboundEvent> all() {
        List<OutboundEvent> out = new ArrayList<>(correlator.drainOutbox());
        clock.advanceMs(31_000);
        out.addAll(correlator.tick(clock.elapsedNs()));
        return out;
    }

    @Test
    void UX2_FW_06_aHeldDiscardMwiSealsAsHeld() {
        radio(discardMwiPdu());
        correlator.accept(Observation.stageWithVerdict(null, TraceStage.MWI_INDICATOR, clock.elapsedNs(),
                "suppressed", Verdict.QUARANTINE, -1L, null));

        List<OutboundEvent> out = all();
        assertEquals(1, out.size(), out.toString());
        assertEquals("FT-QUARANTINED", new JSONObject(out.get(0).json()).getString("fate"));
    }

    @Test
    void UX2_FW_06_aBlockedDiscardMwiStaysBlocked() {
        radio(discardMwiPdu());
        correlator.accept(Observation.stage(null, null, -1, TraceStage.MWI_INDICATOR, clock.elapsedNs(),
                "suppressed"));

        List<OutboundEvent> out = all();
        assertEquals(1, out.size(), out.toString());
        assertEquals("FT-BLOCKED", new JSONObject(out.get(0).json()).getString("fate"));
    }
}
