package dev.nightjar.payload.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.nightjar.contract.Direction;
import dev.nightjar.contract.Fate;
import dev.nightjar.contract.Ipc;
import dev.nightjar.contract.Wire;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * RULES-X2: the payload serializer writes the keys of the contract golden events ({@code contract/src/test/resources/
 * golden/event_*.json}, the files the app tests parse). Each golden event goes into an {@link Event} through
 * {@link EventDto#restoreFrom} and comes back through {@link EventDto#toJson}; the key sets must be equal at every
 * level, and the values too, except the trace offsets (the serializer makes them relative to the first step) and the
 * order of the anomaly codes.
 */
class GoldenEventKeysTest {
    private static final String PROPERTY = "nj.golden";
    private static final String[] CANDIDATES = {
        "../contract/src/test/resources/golden",
        "contract/src/test/resources/golden",
        "nightjar/contract/src/test/resources/golden",
    };
    /** Any time after the golden trace offsets, so no restored step has a negative time. */
    private static final long NOW_NS = 10_000_000_000L;

    @Test
    void everyGoldenEventKeepsItsKeysThroughThePayloadSerializer() throws Exception {
        List<Path> files = goldenEvents();
        assertTrue(files.size() >= 7, "golden events: " + files);
        for (Path f : files) {
            JSONObject golden = new JSONObject(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
            JSONObject out = new JSONObject(fromGolden(golden).toJson());
            compareObjects(f.getFileName() + ": ", golden, out);
        }
    }

    @Test
    void theGoldenEventsCoverTheClassifierKeys() throws Exception {
        Set<String> seen = new HashSet<>();
        for (Path f : goldenEvents()) {
            seen.addAll(keys(new JSONObject(new String(Files.readAllBytes(f), StandardCharsets.UTF_8))));
        }
        for (String k : List.of(Ipc.EventKey.SUBTYPE, Ipc.EventKey.EXTRA_SUBTYPES, Ipc.EventKey.ANOMALIES,
                Ipc.EventKey.SIM_OTA_HEADER)) {
            assertTrue(seen.contains(k), "no golden event has " + k);
        }
    }

    /** The fields that the correlator sets itself are copied here; {@link EventDto#restoreFrom} copies the rest. */
    private static Event fromGolden(JSONObject golden) throws Exception {
        Event e = new Event();
        e.eventId = golden.getString("eventId");
        e.revision = golden.getInt("revision");
        e.sessionId = golden.optString(Ipc.Key.SESSION_ID, null);
        e.direction = Wire.parse(Direction.class, golden.optString("direction", null), Direction.IN);
        if (golden.has("ackResult")) e.ackResult = golden.getInt("ackResult");
        e.fate = Wire.parse(Fate.class, golden.optString("fate", null), null);
        JSONObject env = golden.getJSONObject("env");
        e.env.roaming = env.getBoolean("roaming");
        e.env.emergencyCallActive = env.getBoolean("emergencyCallActive");
        e.env.userUnlocked = env.getBoolean(Ipc.Key.USER_UNLOCKED);
        e.env.bootCount = env.getInt(Ipc.Key.BOOT_COUNT);
        e.payloadVersion = golden.optString(Ipc.Key.PAYLOAD_VERSION, null);
        e.buildFingerprint = golden.optString(Ipc.Key.BUILD_FINGERPRINT, null);
        EventDto.restoreFrom(e, golden.toString(), NOW_NS, true, true);
        if (golden.has(Ipc.EventKey.SIM_OTA_HEADER)) {
            // The header comes from the parsed PDU, never from JSON: use the SIM OTA fixture with the same header.
            e.facts = PduFacts.parseRadio(hex(SimOtaClassifierTest.SAT_PUSH_NOSEC));
        }
        return e;
    }

    private static void compareObjects(String at, JSONObject golden, JSONObject out) throws Exception {
        Set<String> want = keys(golden);
        Set<String> got = keys(out);
        assertEquals(want, got, at + "keys");
        for (String k : want) compareValues(at + k, k, golden.get(k), out.get(k));
    }

    private static void compareValues(String at, String key, Object golden, Object out) throws Exception {
        if (key.equals(Ipc.EventKey.ANOMALIES)) {
            assertEquals(strings((JSONArray) golden), strings((JSONArray) out), at);
        } else if (golden instanceof JSONObject g) {
            assertTrue(out instanceof JSONObject, at + " is not an object");
            compareObjects(at + ".", g, (JSONObject) out);
        } else if (golden instanceof JSONArray g) {
            assertTrue(out instanceof JSONArray, at + " is not an array");
            JSONArray o = (JSONArray) out;
            assertEquals(g.length(), o.length(), at + " length");
            for (int i = 0; i < g.length(); i++) compareValues(at + "[" + i + "]", key, g.get(i), o.get(i));
        } else if (key.equals("tNs") && at.contains("trace[")) {
            assertTrue(out instanceof Number, at);
        } else if (golden instanceof Number g) {
            assertTrue(out instanceof Number, at + " is not a number");
            assertEquals(g.longValue(), ((Number) out).longValue(), at);
        } else {
            assertEquals(golden, out, at);
        }
    }

    private static Set<String> keys(JSONObject o) {
        Set<String> out = new TreeSet<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    private static Set<String> strings(JSONArray a) throws Exception {
        Set<String> out = new TreeSet<>();
        for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }

    private static List<Path> goldenEvents() throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(goldenDir(), "event_*.json")) {
            for (Path p : dir) out.add(p);
        }
        Collections.sort(out);
        return out;
    }

    private static Path goldenDir() {
        String configured = System.getProperty(PROPERTY);
        if (configured != null && !configured.isEmpty()) {
            Path p = Paths.get(configured);
            assertTrue(Files.isDirectory(p), PROPERTY + " names no directory: " + p.toAbsolutePath());
            return p;
        }
        for (String c : CANDIDATES) {
            Path p = Paths.get(c);
            if (Files.isDirectory(p)) return p;
        }
        fail("golden directory not found from " + Paths.get("").toAbsolutePath() + "; set -D" + PROPERTY);
        return null; // unreachable: fail() throws
    }

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
