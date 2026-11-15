package dev.nightjar.payload.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Ipc;
import dev.nightjar.contract.Subtype;
import dev.nightjar.payload.store.OutboundEvent;
import dev.nightjar.payload.testing.FakeClock;
import dev.nightjar.payload.testing.FakeEnv;
import dev.nightjar.payload.testing.FakeSpool;
import dev.nightjar.payload.testing.FakeTokens;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A cell broadcast event carries the class and subtype of {@code pduparse.CbCategories}. */
class CbCorrelatorTest {

    private FakeClock clock;
    private Correlator correlator;

    @BeforeEach
    void setUp() {
        clock = new FakeClock(1_000_000_000L);
        FakeEnv env = new FakeEnv();
        correlator = new Correlator(clock, new Sealer(env::userUnlocked), new Counters(), env, new FakeSpool(),
                new FakeTokens(), uid -> new String[] {"com.example.injector"}, "session-1", "1.0.0", 7);
    }

    private JSONObject cb(int messageId, boolean emergency) throws Exception {
        byte[] pdu = {0x10, 0x00, (byte) (messageId >> 8), (byte) messageId, 0x0F, 0x11, 0x00, 0x00};
        correlator.accept(Observation.cb(pdu, 0x1000, messageId, 1, 1, emergency, 0, 1, 0, null,
                clock.elapsedNs(), clock.wallMs()));
        List<OutboundEvent> out = correlator.drainOutbox();
        assertEquals(1, out.size());
        return new JSONObject(out.get(0).json());
    }

    @Test
    void umtsPdusGetTheirOwnFormat() throws Exception {
        // The app decodes the stored PDU with the format the payload names.
        assertEquals(Observation.CB_GSM, cb(50, false).getString("format"));
        byte[] umts = new byte[90];
        umts[0] = 1; // message type CBS-Message (TS 23.041)
        umts[2] = 50;
        correlator.accept(Observation.cb(umts, 0x2000, 50, 1, 1, false, 0, 1, 0, null, clock.elapsedNs(),
                clock.wallMs()));
        assertEquals(Observation.CB_UMTS, new JSONObject(correlator.drainOutbox().get(0).json()).getString("format"));
        assertFalse(Observation.umtsCb(new byte[88]), "one GSM page is never UMTS");
    }

    @Test
    void emergencyBroadcastGetsItsLevel() throws Exception {
        JSONObject e = cb(4370, true);
        assertEquals(ClassId.CB.wire(), e.getString("finalClass"));
        assertEquals(Subtype.Cb.PRESIDENTIAL, e.getString(Ipc.EventKey.SUBTYPE));
    }

    @Test
    void etwsBroadcastGetsItsKind() throws Exception {
        assertEquals(Subtype.Cb.ETWS_BOTH, cb(4354, true).getString(Ipc.EventKey.SUBTYPE));
    }

    @Test
    void channel50IsAreaInformation() throws Exception {
        assertEquals(Subtype.Cb.AREA_INFO, cb(50, false).getString(Ipc.EventKey.SUBTYPE));
    }

    @Test
    void otherChannelKeepsItsNumber() throws Exception {
        JSONObject e = cb(221, false);
        assertEquals(Subtype.Cb.CHANNEL, e.getString(Ipc.EventKey.SUBTYPE));
        assertEquals(221, e.getJSONObject("cb").getInt("messageId"));
    }

    @Test
    void simDownloadIsCbSimWithoutSubtype() throws Exception {
        JSONObject e = cb(0x1000, false);
        assertEquals(ClassId.CB_SIM.wire(), e.getString("finalClass"));
        assertEquals(ClassId.CB_SIM.wire(), e.getString("provisionalClass"));
        assertFalse(e.has(Ipc.EventKey.SUBTYPE) && !e.isNull(Ipc.EventKey.SUBTYPE), e.toString());
    }

    @Test
    void finalClassifierKeepsTheCbClassAndSubtype() {
        Event ev = new Event();
        ev.cb = new Event.Cb(0x1000, 4396, 1, 1, true);
        FinalClassifier.apply(ev);
        assertEquals(ClassId.CB, ev.finalClass);
        assertEquals(Subtype.Cb.PUBLIC_SAFETY, ev.subtype);
        Event sim = new Event();
        sim.cb = new Event.Cb(0x1000, 0x1010, 1, 1, false);
        FinalClassifier.apply(sim);
        assertEquals(ClassId.CB_SIM, sim.finalClass);
        assertEquals(null, sim.subtype);
    }
}
