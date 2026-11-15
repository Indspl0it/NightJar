package dev.nightjar.payload.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.AnomalyCode;
import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Direction;
import dev.nightjar.contract.Fate;
import dev.nightjar.contract.Ipc;
import dev.nightjar.contract.Subtype;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.contract.Transport;
import dev.nightjar.contract.Verdict;
import dev.nightjar.pduparse.Anomalies;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** The event model and its IPC serialization. */
class EventDtoTest {

    private static Event sample() {
        Event e = new Event();
        e.eventId = "11111111-1111-1111-1111-111111111111";
        e.sessionId = "22222222-2222-2222-2222-222222222222";
        e.revision = 1;
        e.direction = Direction.IN;
        e.transport = Transport.IMS;
        e.source = 1;
        e.subId = 2;
        e.slot = 0;
        e.carrierName = "Verizon";
        e.format = "3gpp";
        e.receivedAtWall = 1_762_330_931_204L;
        e.receivedAtElapsedNs = 81_234_567_890_123L;
        e.ackResult = 1;
        e.provisionalClass = ClassId.DATA_PORT;
        e.finalClass = ClassId.CARRIER;
        e.verdict = Verdict.ALLOW;
        e.fate = Fate.CARRIER;
        e.fateDetail("package", "com.verizon.messaging.vzmsgs");
        e.addPdu(new byte[] {1, 2, 3}, 5_000_000L);
        e.addTrace(TraceStage.RADIO_IMS_ON_SMS_RECEIVED, 5_000_000L, "token=7");
        e.addTrace(TraceStage.FILTER_CARRIER, 9_000_000L, "consumed=true");
        e.flags.selfTest = false;
        e.env.roaming = false;
        e.payloadVersion = "1.0.0";
        e.buildFingerprint = "google/akita/akita:17/CP3A.261005.005/16271449";
        return e;
    }

    @Test
    void serializesEveryC3Field() throws Exception {
        JSONObject o = new JSONObject(sample().toJson());
        assertEquals("11111111-1111-1111-1111-111111111111", o.getString("eventId"));
        assertEquals(1, o.getInt("revision"));
        assertEquals("IN", o.getString("direction"));
        assertEquals("IMS", o.getString("transport"));
        assertEquals(1, o.getInt("source"));
        assertEquals(2, o.getInt("subId"));
        assertEquals(0, o.getInt("slot"));
        assertEquals("Verizon", o.getString("carrierName"));
        assertEquals("3gpp", o.getString("format"));
        assertEquals(1_762_330_931_204L, o.getLong("receivedAtWall"));
        assertEquals(81_234_567_890_123L, o.getLong("receivedAtElapsedNs"));
        assertEquals(1, o.getInt("ackResult"));
        assertEquals("CL-DATA-PORT", o.getString("provisionalClass"));
        assertEquals("CL-CARRIER", o.getString("finalClass"));
        assertEquals("ALLOW", o.getJSONObject("verdict").getString("action"));
        assertEquals("FT-CARRIER", o.getString("fate"));
        assertEquals("com.verizon.messaging.vzmsgs",
                o.getJSONObject("fateDetail").getString("package"));
        assertEquals("1.0.0", o.getString("payloadVersion"));
        assertTrue(o.has("env"));
        assertTrue(o.has("flags"));
        assertTrue(o.has("trace"));
        assertTrue(o.has("pdus"));
    }

    @Test
    void nullFieldsAreOmitted() throws Exception {
        Event e = new Event();
        e.eventId = "x";
        e.sessionId = "s";
        e.finalClass = ClassId.NORMAL;
        e.fate = Fate.UNKNOWN;
        JSONObject o = new JSONObject(e.toJson());
        assertFalse(o.has("carrierName"));
        assertFalse(o.has("originator"));
        assertFalse(o.has("destPort"));
        assertFalse(o.has("ackResult"));
        assertFalse(o.has("ruleId"));
        assertFalse(o.has("clampReason"));
        assertFalse(o.has("concat"));
        assertFalse(o.has("pdus"));
        assertFalse(o.has("parts"));
    }

    @Test
    void pdusAreUnwrappedBase64() throws Exception {
        Event e = sample();
        JSONArray pdus = new JSONObject(e.toJson()).getJSONArray("pdus");
        assertEquals(1, pdus.length());
        assertTrue(pdus.getString(0).equals(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3})));
    }

    @Test
    void keysAreSortedAtEveryLevel() {
        String json = sample().toJson();
        int a = json.indexOf("\"ackResult\"");
        int b = json.indexOf("\"buildFingerprint\"");
        int c = json.indexOf("\"eventId\"");
        assertTrue(a >= 0 && b > a && c > b, json);
        int f = json.indexOf("\"fate\"");
        int g = json.indexOf("\"finalClass\"");
        assertTrue(f >= 0 && g > f, json);
    }

    @Test
    void traceOffsetsAreRelativeToTheFirstStage() throws Exception {
        JSONArray trace = new JSONObject(sample().toJson()).getJSONArray("trace");
        assertEquals(0L, trace.getJSONObject(0).getLong("tNs"));
        assertEquals(4_000_000L, trace.getJSONObject(1).getLong("tNs"));
    }

    @Test
    void verdictCarriesTheClampFields() throws Exception {
        Event e = sample();
        e.verdict = Verdict.ALLOW;
        e.ruleId = 7;
        e.clampedFrom = Verdict.BLOCK;
        e.clampReason = "SAFE_MODE";
        JSONObject v = new JSONObject(e.toJson()).getJSONObject("verdict");
        assertEquals("ALLOW", v.getString("action"));
        assertEquals(7, v.getLong("ruleId"));
        assertEquals("BLOCK", v.getString("clampedFrom"));
        assertEquals("SAFE_MODE", v.getString("clampReason"));
    }

    @Test
    void subtypeExtrasAndAnomaliesAreLeftOutWhenEmpty() throws Exception {
        JSONObject o = new JSONObject(sample().toJson());
        assertFalse(o.has(Ipc.EventKey.SUBTYPE));
        assertFalse(o.has(Ipc.EventKey.EXTRA_SUBTYPES));
        assertFalse(o.has(Ipc.EventKey.ANOMALIES));
    }

    @Test
    void serializesSubtypeExtrasAndAnomalies() throws Exception {
        Event e = sample();
        e.finalClass = ClassId.SIM_DD;
        e.subtype = Subtype.SimOta.SAT;
        e.extraSubtypes.add(Subtype.SimOta.NOSEC);
        e.extraSubtypes.add(ClassId.FLASH.wire() + ":" + Subtype.Flash.CLASS0);
        e.anomalyMask = Anomalies.with(Anomalies.with(Anomalies.NONE, AnomalyCode.IE_DUP), AnomalyCode.ADDR_TOA);
        JSONObject o = new JSONObject(e.toJson());
        assertEquals(Subtype.SimOta.SAT, o.getString(Ipc.EventKey.SUBTYPE));
        JSONArray extras = o.getJSONArray(Ipc.EventKey.EXTRA_SUBTYPES);
        assertEquals(2, extras.length());
        assertEquals(Subtype.SimOta.NOSEC, extras.getString(0), "rule order is kept");
        assertEquals("CL-FLASH:CLASS0", extras.getString(1));
        JSONArray anomalies = o.getJSONArray(Ipc.EventKey.ANOMALIES);
        assertEquals(2, anomalies.length());
        assertEquals(AnomalyCode.ADDR_TOA.wire(), anomalies.getString(0), "contract order");
        assertEquals(AnomalyCode.IE_DUP.wire(), anomalies.getString(1));
    }

    @Test
    void consumerFactsGoIntoTheFateDetail() throws Exception {
        // The sealed event carries the consumer facts, not only the entries a test puts in by hand.
        Event e = new Event();
        e.consumerPackage = "com.example.consumer";
        e.por = "9000";
        e.oemReason = "oemFilter";
        e.expired = true;
        JSONObject d = new JSONObject(e.toJson()).getJSONObject("fateDetail");
        assertEquals("com.example.consumer", d.getString("package"));
        assertEquals("9000", d.getString("por"));
        assertEquals("oemFilter", d.getString("oemReason"));
        assertTrue(d.getBoolean("expired"));

        e.fateDetail("package", "com.example.stored");
        assertEquals("com.example.stored",
                new JSONObject(e.toJson()).getJSONObject("fateDetail").getString("package"), "a stored entry wins");
        assertFalse(new JSONObject(new Event().toJson()).has("fateDetail"));
    }

    @Test
    void outboundEventsGetTheSubtypeOfTheirClass() throws Exception {
        Event e = sample();
        e.direction = Direction.OUT;
        e.finalClass = ClassId.MO_STK;
        assertEquals(Subtype.MoStk.SEND_SMS, new JSONObject(e.toJson()).getString(Ipc.EventKey.SUBTYPE));
    }
}
