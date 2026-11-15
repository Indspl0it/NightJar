package dev.nightjar.pduparse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.nightjar.contract.AnomalyCode;
import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Subtype;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The SIM OTA subtypes. Every fixture of {@code anomalies.json} with a {@code simOta} list gives
 * exactly those codes, every subtype is the first code of two fixtures or more, and a cut or changed command header
 * gives {@code AN-OTA-HDR} and never an exception.
 */
class SimOtaSubtypesTest {
    private static final long SEED = 0x53494D4F54414C31L;
    private static final int MUTATIONS = 50_000;

    /** The codes of one fixture input: the payload's view for a TPDU, the header alone for {@code sms_pp}. */
    static List<String> codes(String kind, byte[] b) {
        return switch (kind) {
            case "tpdu" -> {
                TpduFacts f = TpduParser.parseRadio(b);
                yield SimOtaSubtypes.isSimOta(f) ? SimOtaSubtypes.codes(f) : List.of();
            }
            case "sms_pp" -> SimOtaSubtypes.codes(OtaParser.parseCommand(b));
            default -> throw new IllegalArgumentException("no SIM OTA codes for kind " + kind);
        };
    }

    static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        return out;
    }

    /** The fixtures with a {@code simOta} list. */
    static List<JSONObject> simOtaFixtures() throws IOException {
        JSONArray all = AnomalyFixturesTest.fixtures();
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject f = all.getJSONObject(i);
            if (f.has("simOta")) out.add(f);
        }
        return out;
    }

    @Test
    void everyFixtureGivesExactlyItsCodes() throws IOException {
        List<JSONObject> fixtures = simOtaFixtures();
        List<String> failures = new ArrayList<>();
        for (JSONObject f : fixtures) {
            List<String> want = strings(f.getJSONArray("simOta"));
            List<String> got = codes(f.getString("kind"), AnomalyFixturesTest.hex(f.getString("hex")));
            if (!want.equals(got)) failures.add(f.getString("name") + ": want " + want + " got " + got);
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertTrue(fixtures.size() >= 20, "only " + fixtures.size() + " SIM OTA fixtures");
    }

    @Test
    void everySubtypeIsTheFirstCodeOfTwoFixturesOrMore() throws IOException {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String code : Subtype.known(ClassId.SIM_DD)) counts.put(code, 0);
        for (JSONObject f : simOtaFixtures()) {
            JSONArray codes = f.getJSONArray("simOta");
            if (codes.length() > 0) counts.merge(codes.getString(0), 1, Integer::sum);
        }
        assertEquals(6, counts.size(), "plan §8.5 has six subtypes: " + counts.keySet());
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            assertTrue(e.getValue() >= 2, e.getKey() + " is the subtype of " + e.getValue() + " fixtures");
        }
    }

    @Test
    void codesFollowTheOrderOfOd2AndHaveNoDuplicates() throws IOException {
        List<String> order = Subtype.known(ClassId.SIM_DD);
        for (JSONObject f : simOtaFixtures()) {
            List<String> codes = codes(f.getString("kind"), AnomalyFixturesTest.hex(f.getString("hex")));
            List<String> extras = codes.isEmpty() ? List.of() : codes.subList(1, codes.size());
            int last = -1;
            for (String c : extras) {
                int at = order.indexOf(c);
                assertTrue(at > last, f.getString("name") + ": extras out of order " + codes);
                last = at;
            }
            assertEquals(codes.size(), codes.stream().distinct().count(), f.getString("name") + ": " + codes);
        }
    }

    @Test
    void tarTable() {
        assertEquals(Subtype.SimOta.SAT, SimOtaSubtypes.tarSubtype(0x505348));
        assertEquals(Subtype.SimOta.SAT, SimOtaSubtypes.tarSubtype(0x534054));
        assertEquals(Subtype.SimOta.WIB, SimOtaSubtypes.tarSubtype(0x000001));
        assertEquals(Subtype.SimOta.WIB, SimOtaSubtypes.tarSubtype(0x000009));
        assertNull(SimOtaSubtypes.tarSubtype(0x00000A));
        assertEquals(Subtype.SimOta.RAM, SimOtaSubtypes.tarSubtype(0x000000));
        assertEquals(Subtype.SimOta.RAM, SimOtaSubtypes.tarSubtype(0xB20100));
        assertEquals(Subtype.SimOta.RFM, SimOtaSubtypes.tarSubtype(0xB00000));
        assertEquals(Subtype.SimOta.RFM, SimOtaSubtypes.tarSubtype(0xB0FFFF));
        assertNull(SimOtaSubtypes.tarSubtype(0xB10000));
        assertNull(SimOtaSubtypes.tarSubtype(0xBFFF00));
        assertNull(SimOtaSubtypes.tarSubtype(-1));
    }

    @Test
    void securityParameterIndicator() {
        // ETSI TS 102 225 SPI1 bits 2-1 integrity, bit 3 ciphering.
        assertTrue(command(0x0000).noSecurity());
        assertTrue(command(0x0001).noSecurity(), "PoR request only: the Simjacker SPI");
        assertTrue(command(0x0100).noSecurity(), "a redundancy check is not a security check");
        assertFalse(command(0x0200).noSecurity(), "cryptographic checksum");
        assertFalse(command(0x0300).noSecurity(), "digital signature");
        assertFalse(command(0x0400).noSecurity(), "ciphering");
        assertTrue(command(0x1621).cryptographicIntegrity());
        assertTrue(command(0x1621).ciphered());
    }

    @Test
    void nullAndEmptyInputGiveOther() {
        assertEquals(List.of(Subtype.SimOta.OTHER), SimOtaSubtypes.codes((TpduFacts) null));
        assertEquals(List.of(Subtype.SimOta.OTHER), SimOtaSubtypes.codes((OtaFacts) null));
        assertEquals(List.of(Subtype.SimOta.OTHER), SimOtaSubtypes.codes(OtaParser.parseCommand(new byte[0])));
        assertFalse(SimOtaSubtypes.isSimOta(null));
        assertFalse(SimOtaSubtypes.isSimOta(TpduParser.parse(new byte[] {0x04})));
    }

    /**
     * Every cut of every whole command packet, inside its TPDU with a matching UDL: the header anomaly, the subtype
     * SIM-OTA-OTHER, and no exception from the unguarded methods.
     */
    @Test
    void everyTruncatedHeaderGivesOtaHdr() throws IOException {
        int packets = 0;
        for (JSONObject fx : simOtaFixtures()) {
            if (!fx.getString("kind").equals("tpdu")) continue;
            TpduFacts f = TpduParser.parseRadio(AnomalyFixturesTest.hex(fx.getString("hex")));
            OtaFacts ota = f.ota();
            if (ota == null || ota.response() || ota.anomalyMask() != Anomalies.NONE) continue;
            packets++;
            byte[] ud = f.userData();
            int body = f.bodyOffset();
            for (int k = 0; k < ud.length - body; k++) {
                String label = fx.getString("name") + " cut to " + k + " packet octets";
                try {
                    TpduFacts cut = TpduParser.parseInternal(withUserData(f, Arrays.copyOf(ud, body + k)), null);
                    assertTrue(Anomalies.has(cut.anomalyMask(), AnomalyCode.OTA_HDR), label);
                    assertTrue(SimOtaSubtypes.isSimOta(cut), label);
                    OtaFacts header = OtaParser.parseUnguarded(ud, body, k, false, false);
                    assertTrue(Anomalies.has(header.anomalyMask(), AnomalyCode.OTA_HDR), label);
                    // The shape checks need the whole packet: the full packet's shape never matches a cut one.
                    int data = body + 3 + ota.headerLength();
                    int cutEnd = body + k;
                    assertFalse(OtaParser.satPushShape(ud, data, cutEnd) || OtaParser.wibCommandShape(ud, data, cutEnd),
                            label + ": shape of a cut packet");
                    List<String> codes = SimOtaSubtypes.codesUnguarded(cut.ota());
                    assertEquals(Subtype.SimOta.OTHER, codes.get(0), label);
                } catch (RuntimeException e) {
                    fail(label + " threw " + e, e);
                }
            }
        }
        assertTrue(packets >= 15, "only " + packets + " whole command packets");
    }

    /** Random changes to the header octets: the codes stay known SIM OTA codes and nothing throws. */
    @Test
    void mutatedHeadersNeverThrow() throws IOException {
        List<byte[]> packets = new ArrayList<>();
        for (JSONObject fx : simOtaFixtures()) {
            if (!fx.getString("kind").equals("tpdu")) continue;
            TpduFacts f = TpduParser.parseRadio(AnomalyFixturesTest.hex(fx.getString("hex")));
            if (f.ota() != null) packets.add(f.body());
        }
        assertTrue(packets.size() >= 15, "only " + packets.size() + " packets");
        List<String> known = Subtype.known(ClassId.SIM_DD);
        Random random = new Random(SEED);
        for (int n = 0; n < MUTATIONS; n++) {
            byte[] p = packets.get(random.nextInt(packets.size())).clone();
            int changes = 1 + random.nextInt(3);
            for (int c = 0; c < changes; c++) {
                // The header is the first 3 + CHL octets; aim most changes there.
                int limit = Math.min(p.length, random.nextInt(4) == 0 ? p.length : 24);
                if (limit > 0) p[random.nextInt(limit)] = (byte) random.nextInt(256);
            }
            boolean response = random.nextInt(8) == 0;
            boolean multipart = random.nextInt(8) == 0;
            try {
                OtaFacts header = OtaParser.parseUnguarded(p, 0, p.length, response, multipart);
                assertNotNull(header);
                List<String> codes = SimOtaSubtypes.codesUnguarded(header);
                assertFalse(codes.isEmpty(), "mutation #" + n);
                assertTrue(known.containsAll(codes), "mutation #" + n + ": " + codes);
            } catch (RuntimeException e) {
                fail("mutation #" + n + " threw " + e, e);
            }
        }
    }

    /** A command packet with only the SPI set; the rest of the header is zero. */
    private static OtaFacts command(int spi) {
        byte[] p = new byte[16];
        p[1] = 14;
        p[2] = 13;
        p[3] = (byte) (spi >>> 8);
        p[4] = (byte) spi;
        return OtaParser.parseCommand(p);
    }

    /** The bare TPDU of {@code f} with other user data and a matching octet UDL (8-bit data). */
    private static byte[] withUserData(TpduFacts f, byte[] ud) {
        byte[] t = f.tpdu();
        int udlAt = t.length - f.userData().length - 1;
        byte[] out = Arrays.copyOf(t, udlAt + 1 + ud.length);
        out[udlAt] = (byte) ud.length;
        System.arraycopy(ud, 0, out, udlAt + 1, ud.length);
        return out;
    }
}
