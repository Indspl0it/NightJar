package dev.nightjar.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Golden fixtures parse with org.json and use valid wire names. */
class GoldenFixturesTest {

    static JSONObject load(String name) throws IOException {
        return new JSONObject(text(name));
    }

    static String text(String name) throws IOException {
        try (InputStream in = GoldenFixturesTest.class.getResourceAsStream("/golden/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void IPC_03_wapQuarantinedFixtureUsesWireNames() throws IOException {
        JSONObject e = load("event_wap_quarantined.json");
        assertEquals(1, e.getInt("revision"));
        assertEquals(ClassId.WAP_CP, Wire.parse(ClassId.class, e.getString("finalClass"), null));
        assertEquals(Fate.QUARANTINED, Wire.parse(Fate.class, e.getString("fate"), Fate.UNKNOWN));
        assertEquals(Verdict.QUARANTINE, Wire.parse(Verdict.class, e.getJSONObject("verdict").getString("action"), Verdict.ALLOW));
        assertEquals(Transport.CS, Wire.parse(Transport.class, e.getString("transport"), null));
        JSONArray trace = e.getJSONArray("trace");
        for (int i = 0; i < trace.length(); i++) {
            String stage = trace.getJSONObject(i).getString("stage");
            assertNotNull(Wire.parse(TraceStage.class, stage, null), stage);
        }
        assertEquals(e.toString(), new JSONObject(e.toString()).toString());
    }

    @Test
    void UX_B02_rejectedFixtureKeepsTheDispatchResult() throws IOException {
        JSONObject e = load("event_rejected_duplicate.json");
        assertEquals(Fate.REJECTED, Wire.parse(Fate.class, e.getString("fate"), Fate.UNKNOWN));
        assertEquals(AckResult.DUPLICATED, e.getInt("ackResult"));
        assertTrue(AckResult.rejected(e.getInt("ackResult")));
        assertEquals(e.toString(), new JSONObject(e.toString()).toString());
    }

    @Test
    void CAP_05_normalSmsFixtureCarriesNoBody() throws IOException {
        JSONObject e = load("event_normal_counters_only.json");
        assertEquals("CL-NORMAL", e.getString("finalClass"));
        assertFalse(e.has("pdus"));
        assertFalse(e.has("originator"));
    }

    @Test
    void CL_01_malformedEventCarriesSubtypeAndAnomalies() throws IOException {
        JSONObject e = load("event_malformed_anomalies.json");
        ClassId cls = Wire.parse(ClassId.class, e.getString("finalClass"), null);
        assertEquals(ClassId.MALFORMED_SMS, cls);
        JSONArray anomalies = e.getJSONArray(Ipc.EventKey.ANOMALIES);
        assertTrue(anomalies.length() > 0);
        for (int i = 0; i < anomalies.length(); i++) {
            assertNotNull(Wire.parse(AnomalyCode.class, anomalies.getString(i), null), anomalies.getString(i));
        }
        // The first hard anomaly gives the class and is the subtype.
        AnomalyCode first = Wire.parse(AnomalyCode.class, anomalies.getString(0), null);
        assertEquals(cls, first.forcedClass());
        assertEquals(first.forcedSubtype(), e.getString(Ipc.EventKey.SUBTYPE));
        assertFalse(e.has(Ipc.EventKey.EXTRA_SUBTYPES));
        assertEquals(e.toString(), new JSONObject(e.toString()).toString());
    }

    @Test
    void UX2_FW_02_unreadableEventIsHeldWithTheWrongMessageType() throws IOException {
        JSONObject e = load("event_unreadable_held.json");
        ClassId cls = Wire.parse(ClassId.class, e.getString("finalClass"), null);
        assertEquals(ClassId.UNREADABLE_SMS, cls);
        assertEquals("CL-UNREADABLE-SMS", cls.wire());
        JSONArray anomalies = e.getJSONArray(Ipc.EventKey.ANOMALIES);
        assertEquals(1, anomalies.length());
        AnomalyCode mti = Wire.parse(AnomalyCode.class, anomalies.getString(0), null);
        assertEquals(AnomalyCode.MTI, mti);
        assertEquals(cls, mti.forcedClass());
        assertEquals(mti.forcedSubtype(), e.getString(Ipc.EventKey.SUBTYPE));
        assertEquals(Fate.QUARANTINED, Wire.parse(Fate.class, e.getString("fate"), Fate.UNKNOWN));
        assertEquals(Verdict.QUARANTINE,
                Wire.parse(Verdict.class, e.getJSONObject("verdict").getString("action"), Verdict.ALLOW));
        assertEquals(e.toString(), new JSONObject(e.toString()).toString());
    }

    @Test
    void CL_01_simOtaEventCarriesExtraSubtypes() throws IOException {
        JSONObject e = load("event_sim_ota_subtypes.json");
        ClassId cls = Wire.parse(ClassId.class, e.getString("finalClass"), null);
        assertEquals(ClassId.SIM_DD, cls);
        assertEquals(Subtype.SimOta.RFM, e.getString(Ipc.EventKey.SUBTYPE));
        JSONArray extra = e.getJSONArray(Ipc.EventKey.EXTRA_SUBTYPES);
        assertEquals(1, extra.length());
        assertEquals(Subtype.SimOta.NOSEC, extra.getString(0));
        assertTrue(Subtype.known(cls).contains(extra.getString(0)));
        assertFalse(e.has(Ipc.EventKey.ANOMALIES));
    }

    @Test
    void CL_08_simOtaEventCarriesOnlyTheHeaderFields() throws IOException {
        // Optional key inside version 2. Synthetic S@T push header (TAR 'PSH', SPI 0x0001, counter 1).
        JSONObject e = load("event_sim_ota_header.json");
        assertEquals(ClassId.SIM_DD, Wire.parse(ClassId.class, e.getString("finalClass"), null));
        assertEquals(Subtype.SimOta.SAT, e.getString(Ipc.EventKey.SUBTYPE));
        JSONObject h = e.getJSONObject(Ipc.EventKey.SIM_OTA_HEADER);
        java.util.Set<String> keys = java.util.Set.of(Ipc.SimOtaKey.CPL, Ipc.SimOtaKey.CHL, Ipc.SimOtaKey.SPI,
                Ipc.SimOtaKey.KIC, Ipc.SimOtaKey.KID, Ipc.SimOtaKey.TAR, Ipc.SimOtaKey.CNTR);
        assertEquals(keys, h.keySet());
        for (String k : keys) assertTrue(h.get(k) instanceof Number, k);
        assertEquals(0x505348, h.getInt(Ipc.SimOtaKey.TAR));
        assertEquals(0x0001, h.getInt(Ipc.SimOtaKey.SPI));
        assertEquals(1L, h.getLong(Ipc.SimOtaKey.CNTR));
        assertEquals(e.toString(), new JSONObject(e.toString()).toString());
    }

    @Test
    void CL_04_extraSubtypesUseTheOd13Forms() throws IOException {
        // A bare code for the event class, "<class wire>:<code>" for another class, "<class wire>" alone.
        JSONObject e = load("event_extra_cross_class.json");
        ClassId cls = Wire.parse(ClassId.class, e.getString("finalClass"), null);
        assertEquals(ClassId.SILENT, cls);
        assertTrue(Subtype.known(cls).contains(e.getString(Ipc.EventKey.SUBTYPE)));
        JSONArray extra = e.getJSONArray(Ipc.EventKey.EXTRA_SUBTYPES);
        assertEquals(2, extra.length());
        for (int i = 0; i < extra.length(); i++) {
            String entry = extra.getString(i);
            int sep = entry.indexOf(':');
            ClassId other = Wire.parse(ClassId.class, sep < 0 ? entry : entry.substring(0, sep), null);
            assertNotNull(other, entry);
            assertFalse(other == cls, entry + ": the event class takes a bare code");
            if (sep >= 0) assertTrue(Subtype.known(other).contains(entry.substring(sep + 1)), entry);
        }
        assertEquals(e.toString(), new JSONObject(e.toString()).toString());
    }

    @Test
    void CL_04_freeFormSubtypeCarriesTheContentType() throws IOException {
        JSONObject e = load("event_free_form_subtype.json");
        ClassId cls = Wire.parse(ClassId.class, e.getString("finalClass"), null);
        assertEquals(ClassId.WAP_OTHER, cls);
        assertTrue(Subtype.isFreeForm(cls));
        assertTrue(Subtype.known(cls).isEmpty());
        assertEquals(e.getString("contentType"), e.getString(Ipc.EventKey.SUBTYPE));
    }

    @Test
    void CL_01_olderEventsHaveNoNewFields() throws IOException {
        for (String name : new String[] {"event_wap_quarantined.json", "event_normal_counters_only.json"}) {
            JSONObject e = load(name);
            assertFalse(e.has(Ipc.EventKey.SUBTYPE), name);
            assertFalse(e.has(Ipc.EventKey.EXTRA_SUBTYPES), name);
            assertFalse(e.has(Ipc.EventKey.ANOMALIES), name);
            assertFalse(e.has(Ipc.EventKey.SIM_OTA_HEADER), name);
        }
    }

    @Test
    void IPC_04_configFixturesHaveSchemaVersions() throws IOException {
        assertEquals(Schemas.RULES, load("rules.json").getInt("schema"));
        assertEquals(Schemas.SETTINGS, load("settings.json").getInt("schema"));
        // The outgoing keys that the payload reads are the SettingsKeys names.
        JSONObject outgoing = load("settings.json").getJSONObject(SettingsKeys.OUTGOING);
        assertTrue(outgoing.has(SettingsKeys.OUTGOING_ENABLED));
        assertTrue(outgoing.has(SettingsKeys.OUTGOING_LOG_VISIBLE));
        assertEquals(0, outgoing.getJSONArray(SettingsKeys.OUTGOING_BLOCKED).length());
        assertEquals(3, outgoing.length());
        assertEquals(Schemas.PROFILES, load("profiles.json").getInt("schema"));
        JSONObject actions = load("rules.json").getJSONObject("classActions");
        for (String cls : actions.keySet()) {
            assertNotNull(Wire.parse(ClassId.class, cls, null), cls);
            assertNotNull(Wire.parse(Verdict.class, actions.getString(cls), null));
        }
        // A subtypes condition with fixed codes of its class.
        JSONArray rules = load("rules.json").getJSONArray("rules");
        boolean subtypes = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject when = rules.getJSONObject(i).getJSONObject("when");
            if (!when.has("subtypes")) continue;
            subtypes = true;
            ClassId cls = Wire.parse(ClassId.class, when.getJSONArray("classes").getString(0), null);
            JSONArray codes = when.getJSONArray("subtypes");
            for (int k = 0; k < codes.length(); k++) assertTrue(Subtype.known(cls).contains(codes.getString(k)));
        }
        assertTrue(subtypes, "rules.json needs a subtypes rule");
    }

    @Test
    void IPC_04_spoolJournalHasAHeaderAndRecords() throws IOException {
        String[] lines = text("pending_unlock_journal.jsonl").split("\n");
        JSONObject header = new JSONObject(lines[0]);
        assertEquals(Schemas.SPOOL, header.getInt("spool"));
        assertFalse(header.has("eventId"));
        for (int i = 1; i < lines.length; i++) {
            JSONObject record = new JSONObject(lines[i]);
            String op = record.getString("op");
            assertTrue(op.equals("put") || op.equals("remove"), op);
            assertFalse(record.getString("eventId").isEmpty());
            if (op.equals("put")) {
                assertNotNull(Wire.parse(ClassId.class, record.getString("predictedClass"), null));
                assertEquals(32, record.getString("key").length());
            }
        }
        // Version 1 (one object with an entries array) is read only to migrate it.
        assertEquals(1, load("pending_unlock_spool.json").getInt("spool"));
        assertTrue(Schemas.SPOOL > 1);
    }

    @Test
    void HOOK_01_hookHealthFixtureUsesWireNames() throws IOException {
        JSONObject hk = load("hook_health.json").getJSONObject("hooks").getJSONObject("HK-06");
        assertEquals(HookState.LIVE, Wire.parse(HookState.class, hk.getString("state"), null));
        assertEquals(Tier.CAPTURE_REQUIRED, Wire.parse(Tier.class, hk.getString("tier"), null));
    }
}
