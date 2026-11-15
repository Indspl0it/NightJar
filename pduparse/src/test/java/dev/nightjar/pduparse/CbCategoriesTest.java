package dev.nightjar.pduparse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.nightjar.contract.CbChannels;
import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Subtype;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * Cell broadcast message identifier → class and subtype (3GPP TS 23.041). One test per
 * subtype, the range edges, and the {@code cb} family of {@code testdata/scenarios.json}.
 */
class CbCategoriesTest {

    private static final String PROPERTY = "nj.scenarios";
    private static final String[] CANDIDATES = {
        "../testdata/scenarios.json", "testdata/scenarios.json",
    };

    private static void assertCb(String subtype, int... ids) {
        for (int id : ids) {
            assertEquals(ClassId.CB, CbCategories.classOf(id), "class of " + id);
            assertEquals(subtype, CbCategories.subtypeOf(id), "subtype of " + id);
        }
    }

    // ------------------------------------------------------------------ CMAS (4370-4399)

    @Test
    void presidential() {
        assertCb(Subtype.Cb.PRESIDENTIAL, 4370, 4383);
    }

    @Test
    void extreme() {
        assertCb(Subtype.Cb.EXTREME, 4371, 4372, 4384, 4385);
    }

    @Test
    void severe() {
        assertCb(Subtype.Cb.SEVERE, 4373, 4374, 4375, 4376, 4377, 4378, 4386, 4391);
    }

    @Test
    void amber() {
        assertCb(Subtype.Cb.AMBER, 4379, 4392);
    }

    @Test
    void testIsTheMonthlyTestAndTheStateOrLocalTest() {
        assertCb(Subtype.Cb.TEST, 4380, 4393, 4398, 4399);
    }

    @Test
    void exercise() {
        assertCb(Subtype.Cb.EXERCISE, 4381, 4394);
    }

    @Test
    void operatorDefined() {
        assertCb(Subtype.Cb.OPERATOR, 4382, 4395);
    }

    @Test
    void publicSafetyIsNotPresidential() {
        // The old app table mapped 4396 to 4383 and then to Presidential.
        assertCb(Subtype.Cb.PUBLIC_SAFETY, 4396, 4397);
    }

    // ------------------------------------------------------------------ ETWS (4352-4359)

    @Test
    void etwsEarthquake() {
        assertCb(Subtype.Cb.ETWS_EQ, 4352);
    }

    @Test
    void etwsTsunami() {
        assertCb(Subtype.Cb.ETWS_TSUNAMI, 4353);
    }

    @Test
    void etwsEarthquakeAndTsunami() {
        assertCb(Subtype.Cb.ETWS_BOTH, 4354);
    }

    @Test
    void etwsTest() {
        assertCb(Subtype.Cb.ETWS_TEST, 4355);
    }

    @Test
    void etwsOtherAndFutureExtension() {
        assertCb(Subtype.Cb.ETWS_OTHER, 4356, 4357, 4358, 4359);
    }

    // ------------------------------------------------------------------ other PWS (4360-4369, 4400-6399)

    @Test
    void pwsIdentifiersWithoutANameAreTheGenericEmergencyWarning() {
        // Framework SmsCbConstants: 0x1100-0x18FF is the PWS range; ids without a name are still warnings.
        assertCb(Subtype.Cb.ETWS_OTHER, 4360, 4365, 4369, 4400, 4401, 5000, 6398, 6399);
    }

    // ------------------------------------------------------------------ channels

    @Test
    void areaInformationIsChannel50() {
        assertCb(Subtype.Cb.AREA_INFO, CbCategories.AREA_INFO_CHANNEL);
    }

    @Test
    void anyOtherChannel() {
        assertCb(Subtype.Cb.CHANNEL, 0, 1, 49, 51, 221, 919, 999, 1000, 4095, 6400, 0xA000, 0xFFFF);
    }

    @Test
    void unknownIdentifierIsAChannelNeverTheSim() {
        assertCb(Subtype.Cb.CHANNEL, -1, Integer.MIN_VALUE, 0x10000, Integer.MAX_VALUE);
    }

    // ------------------------------------------------------------------ CL-CB-SIM

    @Test
    void simDataDownloadRangeGivesCbSimWithoutSubtype() {
        for (int id : new int[] {0x1000, 0x1001, 0x1080, 0x10FF}) {
            assertEquals(ClassId.CB_SIM, CbCategories.classOf(id), "class of " + id);
            assertNull(CbCategories.subtypeOf(id), "subtype of " + id);
            assertTrue(CbCategories.isSimDownload(id));
            assertFalse(CbCategories.isEmergency(id));
        }
        assertFalse(CbCategories.isSimDownload(0x0FFF));
        assertFalse(CbCategories.isSimDownload(0x1100));
    }

    // ------------------------------------------------------------------ whole table

    @Test
    void everyEmergencyIdentifierIsClassCbWithAnEmergencySubtype() {
        Set<String> nonEmergency = Set.of(Subtype.Cb.AREA_INFO, Subtype.Cb.CHANNEL);
        for (int id = 0; id <= 0xFFFF; id++) {
            if (!CbChannels.isEmergency(id)) continue;
            assertEquals(ClassId.CB, CbCategories.classOf(id), "class of " + id);
            assertTrue(CbCategories.isEmergency(id));
            String sub = CbCategories.subtypeOf(id);
            assertNotNull(sub, "subtype of " + id);
            assertFalse(nonEmergency.contains(sub), "emergency " + id + " named as " + sub);
        }
    }

    @Test
    void everyCodeIsAKnownContractSubtypeAndEveryKnownCodeIsReached() {
        Set<String> reached = new HashSet<>();
        for (int id = -1; id <= 0xFFFF; id++) {
            String sub = CbCategories.subtypeOf(id);
            if (sub == null) continue;
            assertTrue(Subtype.known(ClassId.CB).contains(sub), id + " -> " + sub);
            reached.add(sub);
        }
        assertEquals(new HashSet<>(Subtype.known(ClassId.CB)), reached);
    }

    // ------------------------------------------------------------------ scenarios.json

    @Test
    void cbScenariosMatch() throws IOException {
        JSONObject doc = new JSONObject(new String(Files.readAllBytes(scenarioFile()), StandardCharsets.UTF_8));
        JSONArray scenarios = doc.getJSONArray("scenarios");
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (int i = 0; i < scenarios.length(); i++) {
            JSONObject s = scenarios.getJSONObject(i);
            if (!"cb-pdu".equals(s.optString("method"))) continue;
            byte[] page = hex(s.getString("pdu"));
            // GSM CB page header: serial(2) message identifier(2) DCS(1) page(1), TS 23.041 §9.4.1.2.
            int id = ((page[2] & 0xFF) << 8) | (page[3] & 0xFF);
            JSONObject expect = s.getJSONObject("expect");
            String wantClass = expect.getString("class");
            String wantSub = expect.isNull("subtype") ? null : expect.getString("subtype");
            String gotClass = CbCategories.classOf(id).wire();
            String gotSub = CbCategories.subtypeOf(id);
            if (!wantClass.equals(gotClass) || !java.util.Objects.equals(wantSub, gotSub)) {
                failures.add(s.getString("id") + ": id " + id + " gave " + gotClass + "/" + gotSub + ", expected "
                        + wantClass + "/" + wantSub);
            }
            checked++;
        }
        assertTrue(checked >= 13, "cb scenarios checked: " + checked);
        assertTrue(failures.isEmpty(), String.join("\n", failures));
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
