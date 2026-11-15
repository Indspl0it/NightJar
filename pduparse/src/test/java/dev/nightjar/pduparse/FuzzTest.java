package dev.nightjar.pduparse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The parsers are total. Each public entry point has a catch-all, so "no exception escaped" would prove
 * nothing; these tests call the unguarded methods behind the catch-all and fail on the first exception of any kind.
 * Inputs: 100 000 random arrays (fixed seed) and 100 000 mutations of the anomaly fixtures, plus every truncation of
 * every fixture. The range test gives every public entry point that takes an offset or a length the edge values
 * (negative, past the end, {@code Integer.MAX_VALUE}) whose sums overflow an int.
 */
class FuzzTest {
    private static final long SEED = 0x4E494748544A4152L;
    private static final int RANDOM_INPUTS = 100_000;
    private static final int MUTATIONS = 100_000;
    private static final int RANGE_INPUTS = 20_000;
    private static final int[] EDGES = {
        Integer.MIN_VALUE, -1, 0, 1, 2, 3, 7, 140, Integer.MAX_VALUE - 1, Integer.MAX_VALUE
    };

    @Test
    void randomInputsNeverThrow() {
        Random random = new Random(SEED);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (int n = 0; n < RANDOM_INPUTS; n++) {
                byte[] b = new byte[random.nextInt(200)];
                random.nextBytes(b);
                // Bias half of the inputs to a plausible first octet so the deep paths run too.
                if (b.length > 0 && (n & 1) == 0) b[0] = (byte) (random.nextBoolean() ? 0x44 : 0x06);
                runAll(b, "random #" + n);
            }
        });
    }

    @Test
    void mutatedFixturesNeverThrow() throws IOException {
        List<byte[]> seeds = seeds();
        assertTrue(seeds.size() >= 40, "only " + seeds.size() + " seeds");
        Random random = new Random(SEED + 1);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (int n = 0; n < MUTATIONS; n++) {
                byte[] base = seeds.get(random.nextInt(seeds.size()));
                runAll(mutate(base, random), "mutation #" + n);
            }
        });
    }

    @Test
    void everyTruncationNeverThrows() throws IOException {
        for (byte[] seed : seeds()) {
            for (int length = 0; length <= seed.length; length++) {
                byte[] cut = new byte[length];
                System.arraycopy(seed, 0, cut, 0, length);
                runAll(cut, "truncation " + length);
            }
        }
    }

    @Test
    void nullAndBadRangesAreTotal() {
        assertNotNull(TpduParser.parse(null));
        assertNotNull(TpduParser.parseRadio(null));
        assertNotNull(UdhParser.parse(null));
        assertNotNull(UdhParser.parse(new byte[4], 9, 2));
        assertNotNull(WspParser.parse(null));
        assertNotNull(WspParser.parse(new byte[4], 9, 2));
        assertNotNull(WbxmlScanner.scan(null));
        assertNotNull(WbxmlScanner.scan(new byte[4], 3, 99));
        assertNotNull(OtaParser.parseCommand(null));
        assertNotNull(OtaParser.parse(new byte[4], 9, 2, false, false));
        assertNotNull(SimOtaSubtypes.codes((TpduFacts) null));
        assertNotNull(SimOtaSubtypes.codes((OtaFacts) null));
        assertNull(WbxmlScanner.scanPushBody(null, null, 0));
    }

    @Test
    void edgeRangesNeverThrow() throws IOException {
        List<byte[]> seeds = seeds();
        Random random = new Random(SEED + 2);
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (int n = 0; n < RANGE_INPUTS; n++) {
                byte[] b = seeds.get(random.nextInt(seeds.size()));
                int off = pick(random, b.length);
                int len = pick(random, b.length);
                runRange(b, off, len, "range #" + n + " off " + off + " len " + len);
            }
            for (byte[] b : seeds) {
                for (int off : EDGES) {
                    for (int len : EDGES) runRange(b, off, len, "edge off " + off + " len " + len);
                }
            }
        });
    }

    /** Runs every unguarded entry point on {@code b}; any exception fails the test with the input. */
    private static void runAll(byte[] b, String label) {
        try {
            assertNotNull(TpduParser.parseInternal(b, null));
            assertNotNull(TpduParser.parseRadioUnguarded(b));
            assertNotNull(UdhParser.parseUnguarded(b, 0, b.length));
            assertNotNull(WspParser.parseUnguarded(b));
            assertNotNull(WbxmlScanner.scanUnguarded(b, 0, b.length, 0));
            assertNotNull(WbxmlScanner.scanUnguarded(b, 0, b.length, WbxmlScanner.PUBLIC_ID_SI));
            assertNotNull(OtaParser.parseUnguarded(b, 0, b.length, false, false));
            assertNotNull(OtaParser.parseUnguarded(b, 0, b.length, true, true));
            assertNotNull(SimOtaSubtypes.codesUnguarded(OtaParser.parseUnguarded(b, 0, b.length, false, false)));
            TpduFacts radio = TpduParser.parseRadioUnguarded(b);
            SimOtaSubtypes.isSimOta(radio);
            assertNotNull(SimOtaSubtypes.codesUnguarded(radio.ota()));
            new ConcatTracker(4, 1000).check(radio, 0);
        } catch (RuntimeException e) {
            fail(label + " threw " + e + " on " + hex(b), e);
        }
    }

    /**
     * Runs every entry point that takes an offset or a length. The public WSP and push body methods range-check
     * before their catch-all, so an exception there shows as an error that names the exception class.
     */
    private static void runRange(byte[] b, int off, int len, String label) {
        try {
            assertNotNull(UdhParser.parseUnguarded(b, off, len));
            assertNotNull(OtaParser.parseUnguarded(b, off, len, false, true));
            assertNotNull(OtaParser.parseUnguarded(b, off, len, true, false));
            assertNotNull(WbxmlScanner.scanUnguarded(b, off, len, 0));
            assertNotNull(TpduParser.decodeAddress(b, off, len, 0x91));
            assertNotNull(TpduParser.decodeAddress(b, off, len, 0xD0));
            TpduParser.decodeScts(b, off);
            TpduParser.addressOctets(len, 0x91);
            WspFacts push = WspParser.parse(b, off, len);
            assertNotNull(push);
            String error = push.error();
            assertFalse(error != null && error.endsWith("Exception"), label + " WSP threw " + error);
            WbxmlFacts body = WbxmlScanner.scanPushBody(WspParser.parse(b), b, off);
            if (body != null) assertNotNull(body.anomalies());
        } catch (RuntimeException e) {
            fail(label + " threw " + e + " on " + hex(b), e);
        }
    }

    /** An offset or a length: an edge value, or a value in or near the array. */
    private static int pick(Random random, int length) {
        if (random.nextInt(3) == 0) return EDGES[random.nextInt(EDGES.length)];
        return random.nextInt(length + 8) - 4;
    }

    private static List<byte[]> seeds() throws IOException {
        JSONArray all = AnomalyFixturesTest.fixtures();
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject f = all.getJSONObject(i);
            if (f.has("hex")) out.add(AnomalyFixturesTest.hex(f.getString("hex")));
            if (f.has("sequence")) {
                JSONArray parts = f.getJSONArray("sequence");
                for (int k = 0; k < parts.length(); k++) out.add(AnomalyFixturesTest.hex(parts.getString(k)));
            }
        }
        return out;
    }

    private static byte[] mutate(byte[] base, Random random) {
        if (base.length == 0) return base;
        byte[] out = base.clone();
        switch (random.nextInt(5)) {
            case 0 -> out[random.nextInt(out.length)] = (byte) random.nextInt(256);
            case 1 -> {
                byte[] cut = new byte[random.nextInt(out.length + 1)];
                System.arraycopy(out, 0, cut, 0, cut.length);
                return cut;
            }
            case 2 -> {
                int count = 1 + random.nextInt(4);
                for (int k = 0; k < count; k++) out[random.nextInt(out.length)] = (byte) random.nextInt(256);
            }
            case 3 -> {
                // Length octets are the interesting targets: push one to an extreme.
                out[random.nextInt(out.length)] = (byte) (random.nextBoolean() ? 0xFF : 0x00);
            }
            default -> {
                int index = random.nextInt(out.length);
                out[index] = (byte) (out[index] ^ (1 << random.nextInt(8)));
            }
        }
        return out;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF));
        return sb.toString();
    }
}
