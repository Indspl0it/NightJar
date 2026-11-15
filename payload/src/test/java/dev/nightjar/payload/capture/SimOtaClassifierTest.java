package dev.nightjar.payload.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.AnomalyCode;
import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Ipc;
import dev.nightjar.contract.Subtype;
import dev.nightjar.pduparse.Anomalies;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The PDU classifier names a SIM OTA message by its TAR and SPI ({@code SimOtaSubtypes}, in rule order), and the
 * event carries only the header fields. The PDUs are the SIM OTA fixtures of {@code anomalies.json} (synthetic: dummy
 * counters, checksums and data, no keys).
 */
class SimOtaClassifierTest {
    /** TAR 'PSH' (S@T low priority push), SPI 0x0001, S@T push shape: the Simjacker shape. */
    static final String SAT_PUSH_NOSEC =
            "059151551000440891515510247FF6621150214365221A02700000150D0001000050534800000000010042050000000000";
    /** The same with CPL one more than the data: AN-OTA-HDR. */
    static final String SAT_TAR_BAD_CPL =
            "059151551000440891515510247FF6621150214365221A02700000160D0001000050534800000000010042050000000000";
    /** Class 2, PID 0, IE 0x70, TAR 'PSH', CC and ciphering. */
    static final String SAT_CLASS2_PID0 = "0591515510004408915155102400F66211502143652223027000001E15162115155053480000"
            + "0000010000000000000000000000000000000000";
    /** RFM TAR, redundancy check only: no security. */
    static final String NOSEC_RFM_RC =
            "059151551000440891515510247FF6621150214365221B02700000161101000000B000000000000001000000000000000000";
    /** RFM TAR with CC and ciphering. */
    static final String RFM_CIPHERED = "059151551000440891515510247FF66211502143652223027000001E1516211515B000100000"
            + "0000010000000000000000000000000000000000";
    /** PID 0x7F without a command packet identifier. */
    static final String NO_CPI = "059151551000040891515510247FF6621150214365220400112233";
    /** IE 0x70 in a message that does not go to the SIM (PID 0, 8-bit, no class) and a wrong CPL. */
    static final String IEI70_NOT_FOR_SIM =
            "059151551000440891515510240004621150214365221702700000130D0000000050534800000000010000000000";

    private static ProvisionalClassifier.Result result(String hex) {
        return ProvisionalClassifier.result(PduFacts.parseRadio(hex(hex)), false, false, null);
    }

    private static void assertResult(String hex, ClassId c, String subtype, List<String> extras) {
        ProvisionalClassifier.Result r = result(hex);
        assertEquals(c, r.classId());
        assertEquals(subtype, r.subtype());
        assertEquals(extras, r.extras());
    }

    @Test
    void browserTarWinsAndNoSecurityIsTheExtra() {
        assertResult(SAT_PUSH_NOSEC, ClassId.SIM_DD, Subtype.SimOta.SAT, List.of(Subtype.SimOta.NOSEC));
    }

    @Test
    void classTwoWithCommandPacketIsSimOta() {
        assertResult(SAT_CLASS2_PID0, ClassId.SIM_DD, Subtype.SimOta.SAT, List.of());
    }

    @Test
    void noSecurityWinsOverRfm() {
        assertResult(NOSEC_RFM_RC, ClassId.SIM_DD, Subtype.SimOta.NOSEC, List.of(Subtype.SimOta.RFM));
        assertResult(RFM_CIPHERED, ClassId.SIM_DD, Subtype.SimOta.RFM, List.of());
    }

    @Test
    void headerAnomalyGivesOtherAndKeepsTheReadCodesAsExtras() {
        // AN-OTA-HDR forces SIM-OTA-OTHER; the TAR and SPI that could be read stay visible as extras.
        assertResult(SAT_TAR_BAD_CPL, ClassId.SIM_DD, Subtype.SimOta.OTHER,
                List.of(Subtype.SimOta.SAT, Subtype.SimOta.NOSEC));
        assertTrue(Anomalies.has(PduFacts.parseRadio(hex(SAT_TAR_BAD_CPL)).facts().anomalyMask(), AnomalyCode.OTA_HDR));
    }

    @Test
    void simDataDownloadWithoutCommandPacketIsOther() {
        assertResult(NO_CPI, ClassId.SIM_DD, Subtype.SimOta.OTHER, List.of());
    }

    @Test
    void commandPacketIdentifierOutsideTheSimPathIsNotSimOta() {
        ProvisionalClassifier.Result r = result(IEI70_NOT_FOR_SIM);
        assertNotEquals(ClassId.SIM_DD, r.classId());
        assertFalse(r.extras().contains(ClassId.SIM_DD.wire() + ":" + Subtype.SimOta.OTHER));
        assertFalse(Anomalies.has(PduFacts.parseRadio(hex(IEI70_NOT_FOR_SIM)).facts().anomalyMask(),
                AnomalyCode.OTA_HDR));
    }

    @Test
    void cutPacketsNeverThrowAndGiveOther() {
        byte[] full = hex(SAT_PUSH_NOSEC);
        // Radio PDU: SMSC (6) + first octet, OA (6), PID, DCS, SCTS (7), UDL; then UDH (3) and the packet.
        int udlAt = 6 + 1 + 6 + 2 + 7;
        int packetAt = udlAt + 1 + 3;
        for (int k = 0; k < full.length - packetAt; k++) {
            byte[] cut = java.util.Arrays.copyOf(full, packetAt + k);
            cut[udlAt] = (byte) (3 + k);
            ProvisionalClassifier.Result r = ProvisionalClassifier.result(PduFacts.parseRadio(cut), false, false, null);
            assertEquals(ClassId.SIM_DD, r.classId(), "cut to " + k);
            assertEquals(Subtype.SimOta.OTHER, r.subtype(), "cut to " + k);
        }
    }

    @Test
    void eventCarriesOnlyTheHeaderFields() throws Exception {
        Event e = new Event();
        e.facts = PduFacts.parseRadio(hex(SAT_PUSH_NOSEC));
        JSONObject h = new JSONObject(e.toJson()).getJSONObject(Ipc.EventKey.SIM_OTA_HEADER);
        assertEquals(0x15, h.getInt(Ipc.SimOtaKey.CPL));
        assertEquals(13, h.getInt(Ipc.SimOtaKey.CHL));
        assertEquals(0x0001, h.getInt(Ipc.SimOtaKey.SPI));
        assertEquals(0, h.getInt(Ipc.SimOtaKey.KIC));
        assertEquals(0, h.getInt(Ipc.SimOtaKey.KID));
        assertEquals(0x505348, h.getInt(Ipc.SimOtaKey.TAR));
        assertEquals(1L, h.getLong(Ipc.SimOtaKey.CNTR));
        assertEquals(7, h.length(), "header fields only: " + h.names());
    }

    @Test
    void eventWithoutCommandPacketHasNoHeader() throws Exception {
        Event e = new Event();
        e.facts = PduFacts.parseRadio(hex(NO_CPI));
        assertFalse(new JSONObject(e.toJson()).has(Ipc.EventKey.SIM_OTA_HEADER));
        assertFalse(new JSONObject(new Event().toJson()).has(Ipc.EventKey.SIM_OTA_HEADER));
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }
}
