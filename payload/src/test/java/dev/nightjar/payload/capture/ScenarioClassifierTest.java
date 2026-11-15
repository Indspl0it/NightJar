package dev.nightjar.payload.capture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.nightjar.contract.AnomalyCode;
import dev.nightjar.contract.Ipc;
import dev.nightjar.contract.Transport;
import dev.nightjar.payload.store.OutboundEvent;
import dev.nightjar.payload.testing.FakeClock;
import dev.nightjar.payload.testing.FakeEnv;
import dev.nightjar.payload.testing.FakeSpool;
import dev.nightjar.payload.testing.FakeTokens;
import dev.nightjar.pduparse.CbCategories;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Every inbound scenario of {@code nightjar/testdata/scenarios.json} that the PDU classifier sees gets the
 * expected class, subtype, anomaly codes and extra subtypes. A scenario without {@code extraSubtypes} expects none. A
 * cell broadcast page ({@code method: cb-pdu}) goes through {@link Correlator} as the CB hook reports it, and its class
 * and subtype come from {@code pduparse.CbCategories}.
 *
 * <p>Skipped scenario kinds, with the reason:
 *
 * <ul>
 *   <li>{@code method: inject-provider, stk-send, app-send, consumer-observed}: no received PDU; the class comes from
 *       the source, the outbound ledger or the consumer facts ({@code FinalClassifier}, {@code CorrelatorTest});
 *   <li>{@code sequence}: a pattern over several messages, or AN-CONCAT-DUP, which needs the stateful tracker
 *       ({@code CorrelatorTest});
 *   <li>{@code waitHours}: the class comes from the 24 h seal of an incomplete group, not from the PDU
 *       ({@code CorrelatorTest.aGroupStillIncompleteAfter24HoursIsMultipartIncomplete}).
 * </ul>
 *
 * <p>The file path comes from the system property {@code nj.scenarios}; without it the test looks in the usual places
 * relative to the working directory. A missing file fails the test: the table must not pass by finding nothing.
 */
class ScenarioClassifierTest {

    private static final String PROPERTY = "nj.scenarios";
    private static final String[] CANDIDATES = {
        "../testdata/scenarios.json", "testdata/scenarios.json",
    };

    @Test
    void everyInboundScenarioMatches() throws Exception {
        JSONObject doc = new JSONObject(new String(Files.readAllBytes(scenarioFile()), StandardCharsets.UTF_8));
        JSONArray scenarios = doc.getJSONArray("scenarios");
        Map<String, String> skipped = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (int i = 0; i < scenarios.length(); i++) {
            JSONObject s = scenarios.getJSONObject(i);
            String id = s.getString("id");
            String why = skipReason(s);
            if (why != null) {
                skipped.put(id, why);
                continue;
            }
            JSONObject expect = s.getJSONObject("expect");
            if ("cb-pdu".equals(s.optString("method", ""))) {
                checked++;
                String failure = checkCb(id, s.getString("pdu"), expect);
                if (failure != null) failures.add(failure);
                continue;
            }
            for (String hex : pdus(s)) {
                checked++;
                String failure = check(id, hex, expect);
                if (failure != null) failures.add(failure);
            }
        }
        assertTrue(checked > 0, "no scenario PDU was checked");
        if (!failures.isEmpty()) {
            fail(failures.size() + " of " + checked + " scenario PDUs do not match:\n" + String.join("\n", failures));
        }
        // Keep the skip list visible in the test report.
        System.out.println("CL-04 scenarios: checked PDUs=" + checked + " skipped=" + skipped.size());
        skipped.forEach((id, why) -> System.out.println("  skip " + id + ": " + why));
    }

    @Test
    void theSkipRulesLeaveTheInboundFamilies() throws Exception {
        // A guard against a skip rule that grows by accident: most scenarios carry a PDU the classifier sees.
        JSONObject doc = new JSONObject(new String(Files.readAllBytes(scenarioFile()), StandardCharsets.UTF_8));
        JSONArray scenarios = doc.getJSONArray("scenarios");
        int skipped = 0;
        for (int i = 0; i < scenarios.length(); i++) {
            if (skipReason(scenarios.getJSONObject(i)) != null) skipped++;
        }
        assertTrue(skipped * 3 < scenarios.length(), skipped + " of " + scenarios.length() + " skipped");
    }

    /** Null when the PDU classifier sees the scenario, else why it does not. */
    static String skipReason(JSONObject s) throws Exception {
        String method = s.optString("method", "");
        switch (method) {
            case "inject-provider":
            case "stk-send":
            case "app-send":
            case "consumer-observed":
                return "no received PDU (method " + method + ")";
            default:
                break;
        }
        if (s.has("sequence")) return "sequence (pattern or AN-CONCAT-DUP: CorrelatorTest)";
        if (s.optInt("waitHours", 0) > 0) return "class decided by the 24 h group seal, not by the PDU (CorrelatorTest)";
        if (pdus(s).isEmpty()) return "no pdu";
        return null;
    }

    private static List<String> pdus(JSONObject s) throws Exception {
        List<String> out = new ArrayList<>();
        if (s.has("pdu")) out.add(s.getString("pdu"));
        JSONArray parts = s.optJSONArray("parts");
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) out.add(parts.getJSONObject(i).getString("pdu"));
        }
        return out;
    }

    private static String check(String id, String hex, JSONObject expect) throws Exception {
        PduFacts facts = Correlator.parseFacts(hex(hex), Transport.CS);
        ProvisionalClassifier.Result r = ProvisionalClassifier.result(facts, false, false, null);
        String wantClass = expect.getString("class");
        String wantSubtype = expect.isNull("subtype") ? null : expect.getString("subtype");
        List<String> wantAnomalies = strings(expect.optJSONArray("anomalies"));
        Collections.sort(wantAnomalies);
        List<String> gotAnomalies = new ArrayList<>();
        for (AnomalyCode c : facts.anomalies()) gotAnomalies.add(c.wire());
        Collections.sort(gotAnomalies);
        boolean ok = wantClass.equals(r.classId().wire())
                && Objects.equals(wantSubtype, r.subtype())
                && wantAnomalies.equals(gotAnomalies);
        // Rule order counts: the list is compared as it is, not sorted.
        List<String> wantExtras = strings(expect.optJSONArray("extraSubtypes"));
        ok &= wantExtras.equals(r.extras());
        if (ok) return null;
        return "  " + id + ": want " + wantClass + " " + wantSubtype + " " + wantAnomalies
                + " extras " + wantExtras
                + "; got " + r.classId().wire() + " " + r.subtype() + " " + gotAnomalies + " extras " + r.extras();
    }

    /**
     * A GSM cell broadcast page (3GPP TS 23.041): serial number in octets 1-2, message identifier in octets
     * 3-4, page parameter in octet 6. The CB hook reports these fields; the correlator gives the class and subtype.
     */
    private static String checkCb(String id, String hex, JSONObject expect) throws Exception {
        byte[] page = hex(hex);
        int serial = ((page[0] & 0xFF) << 8) | (page[1] & 0xFF);
        int messageId = ((page[2] & 0xFF) << 8) | (page[3] & 0xFF);
        int pageNo = Math.max(1, (page[5] & 0xF0) >> 4);
        int pages = Math.max(1, page[5] & 0x0F);
        FakeClock clock = new FakeClock(1_000_000_000L);
        FakeEnv env = new FakeEnv();
        Correlator correlator = new Correlator(clock, new Sealer(env::userUnlocked), new Counters(), env,
                new FakeSpool(), new FakeTokens(), uid -> new String[0], "session-1", "1.0.0", 7);
        correlator.accept(Observation.cb(page, serial, messageId, pageNo, pages, CbCategories.isEmergency(messageId),
                0, 1, 0, null, clock.elapsedNs(), clock.wallMs()));
        List<OutboundEvent> out = correlator.drainOutbox();
        if (out.size() != 1) return "  " + id + ": want 1 sealed event, got " + out.size();
        JSONObject e = new JSONObject(out.get(0).json());
        String wantClass = expect.getString("class");
        String wantSubtype = expect.isNull("subtype") ? null : expect.getString("subtype");
        String gotSubtype = e.isNull(Ipc.EventKey.SUBTYPE) ? null : e.optString(Ipc.EventKey.SUBTYPE, null);
        List<String> wantExtras = strings(expect.optJSONArray("extraSubtypes"));
        List<String> gotExtras = strings(e.optJSONArray(Ipc.EventKey.EXTRA_SUBTYPES));
        boolean ok = wantClass.equals(e.optString("finalClass")) && Objects.equals(wantSubtype, gotSubtype)
                && wantExtras.equals(gotExtras);
        if (ok) return null;
        return "  " + id + ": want " + wantClass + " " + wantSubtype + " extras " + wantExtras + "; got "
                + e.optString("finalClass") + " " + gotSubtype + " extras " + gotExtras;
    }

    private static List<String> strings(JSONArray a) throws Exception {
        List<String> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static Path scenarioFile() {
        String configured = System.getProperty(PROPERTY);
        if (configured != null && !configured.isEmpty()) {
            Path p = Paths.get(configured);
            assertTrue(Files.isRegularFile(p), PROPERTY + " names no file: " + p.toAbsolutePath());
            return p;
        }
        for (String c : CANDIDATES) {
            Path p = Paths.get(c);
            if (Files.isRegularFile(p)) return p;
        }
        fail("scenarios.json not found from " + Paths.get("").toAbsolutePath() + "; set -D" + PROPERTY);
        return null; // unreachable: fail() throws
    }
}
