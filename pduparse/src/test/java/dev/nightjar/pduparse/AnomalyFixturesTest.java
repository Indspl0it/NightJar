package dev.nightjar.pduparse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.AnomalyCode;
import dev.nightjar.contract.Wire;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Every fixture of {@code decoder/src/test/resources/corpus/anomalies.json} gives exactly its expected codes,
 * and every code of the contract has two fixtures or more. The build copies the file into this test runtime. A
 * {@code clean-} fixture expects no code and the parsers give none for it.
 */
class AnomalyFixturesTest {
    static JSONArray fixtures() throws IOException {
        try (InputStream in = AnomalyFixturesTest.class.getResourceAsStream("/anomalies.json")) {
            assertNotNull(in, "anomalies.json is missing; the anomalyFixtures task copies it from the decoder corpus");
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getJSONArray("fixtures");
        }
    }

    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /** Anomaly mask of one fixture input; the kinds are the decoder's kind names. */
    static int anomalies(String kind, byte[] b) {
        return switch (kind) {
            case "tpdu" -> TpduParser.parseRadio(b).anomalyMask();
            case "udh" -> UdhParser.parse(b).anomalyMask();
            case "wsp" -> {
                WspFacts push = WspParser.parse(b);
                WbxmlFacts body = WbxmlScanner.scanPushBody(push, b, 0);
                yield push.anomalyMask() | (body == null ? Anomalies.NONE : body.anomalyMask());
            }
            case "wbxml" -> WbxmlScanner.scan(b).anomalyMask();
            case "sms_pp" -> OtaParser.parseCommand(b).anomalyMask();
            default -> throw new IllegalArgumentException("unknown fixture kind " + kind);
        };
    }

    /** Parts of a sequence fixture go through one tracker, 5 seconds apart; the result is for the last part. */
    static int sequence(JSONArray parts) {
        ConcatTracker tracker = new ConcatTracker();
        long now = 1_000_000L;
        int mask = Anomalies.NONE;
        for (int i = 0; i < parts.length(); i++) {
            mask = tracker.check(TpduParser.parseRadio(hex(parts.getString(i))), now);
            now += 5_000L;
        }
        return mask;
    }

    static int expected(JSONArray codes) {
        int mask = Anomalies.NONE;
        for (int i = 0; i < codes.length(); i++) {
            AnomalyCode c = Wire.parse(AnomalyCode.class, codes.getString(i), null);
            assertNotNull(c, "unknown code " + codes.getString(i));
            mask = Anomalies.with(mask, c);
        }
        return mask;
    }

    @Test
    void everyFixtureGivesExactlyItsCodes() throws IOException {
        JSONArray all = fixtures();
        List<String> failures = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject f = all.getJSONObject(i);
            String kind = f.getString("kind");
            int got = kind.equals("tpdu_sequence")
                    ? sequence(f.getJSONArray("sequence"))
                    : anomalies(kind, hex(f.getString("hex")));
            int want = expected(f.getJSONArray("expect"));
            if (got != want) {
                failures.add(f.getString("name") + ": want [" + Anomalies.names(want) + "] got ["
                        + Anomalies.names(got) + "]");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertTrue(all.length() >= 60, "only " + all.length() + " fixtures");
    }

    @Test
    void everyCodeHasTwoFixturesOrMore() throws IOException {
        JSONArray all = fixtures();
        Map<AnomalyCode, Integer> counts = new EnumMap<>(AnomalyCode.class);
        for (AnomalyCode c : AnomalyCode.values()) counts.put(c, 0);
        for (int i = 0; i < all.length(); i++) {
            for (AnomalyCode c : Anomalies.list(expected(all.getJSONObject(i).getJSONArray("expect")))) {
                counts.merge(c, 1, Integer::sum);
            }
        }
        for (Map.Entry<AnomalyCode, Integer> e : counts.entrySet()) {
            assertTrue(e.getValue() >= 2, e.getKey().wire() + " has " + e.getValue() + " fixtures");
        }
    }

    @Test
    void cleanFixturesHaveNoAnomaly() throws IOException {
        JSONArray all = fixtures();
        int clean = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject f = all.getJSONObject(i);
            if (!f.getString("name").startsWith("clean-")) continue;
            clean++;
            assertEquals(0, f.getJSONArray("expect").length(), f.getString("name"));
            String kind = f.getString("kind");
            int got = kind.equals("tpdu_sequence")
                    ? sequence(f.getJSONArray("sequence"))
                    : anomalies(kind, hex(f.getString("hex")));
            assertEquals(Anomalies.NONE, got, f.getString("name") + " gave [" + Anomalies.names(got) + "]");
        }
        assertTrue(clean >= 10, "only " + clean + " clean fixtures");
    }
}
