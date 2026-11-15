package dev.nightjar.payload.firewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.AnomalyCode;
import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Schemas;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.contract.Verdict;
import dev.nightjar.payload.capture.InjectionTokens;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.pduparse.Anomalies;
import dev.nightjar.rules.Preset;
import dev.nightjar.rules.RuleEngine;
import dev.nightjar.rules.RulesConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The head filter and the enforcement points. */
class SmsFilterCoreTest {

    static final class FixedClock implements Clock {
        long ns = 1_000_000L;

        @Override
        public long elapsedNs() {
            return ns;
        }

        @Override
        public long wallMs() {
            return 1_762_330_000_000L;
        }
    }

    /** Records what the filter asked the framework to do. */
    static final class RecordingHost implements SmsFilterHost {
        int drops;
        boolean failDrop;
        final List<VerdictRecord> verdicts = new ArrayList<>();
        final List<String> stages = new ArrayList<>();
        int errors;

        @Override
        public void dropFilteredSms() {
            drops++;
            if (failDrop) throw new IllegalStateException("dropFilteredSms failed");
        }

        @Override
        public void publishVerdict(VerdictRecord record, long tNs) {
            verdicts.add(record);
        }

        @Override
        public void publishStage(TraceStage stage, String detail, long tNs) {
            stages.add(stage.wire() + " " + detail);
        }

        @Override
        public void onError(Throwable t) {
            errors++;
        }
    }

    private static Enforcer enforcer(Map<ClassId, Verdict> actions) {
        RuleEngine engine = RuleEngine.compile(
                new RulesConfig(Schemas.RULES, 1, Preset.CUSTOM, actions, List.of(), List.of(), List.of(), 5));
        FirewallState state = new FirewallState(new FixedClock());
        state.setAvailable(true);
        state.setEnabled(true);
        return new Enforcer(() -> engine, state, TextSource.NONE, null);
    }

    private static SmsFilterCore core(Map<ClassId, Verdict> actions, InjectionLookup tokens) {
        return new SmsFilterCore(enforcer(actions), tokens, new FixedClock(), null);
    }

    private static SmsFilterCore.Input input(byte[] pdu, boolean unlocked) {
        return new SmsFilterCore.Input(new byte[][] {pdu}, -1, unlocked, 0, false);
    }

    /** SMS-DELIVER, DCS class 0 (flash). */
    private static byte[] flashPdu() {
        return new byte[] {
            0x07, (byte) 0x91, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xF0,
            0x04,
            0x0C, (byte) 0x91, 0x19, 0x09, 0x00, 0x00, 0x00, 0x00,
            0x00,
            0x10,
            0x62, 0x11, 0x50, 0x00, 0x00, 0x00, 0x00,
            0x03, 0x41, 0x42, 0x43, // TP-UDL 3 septets in 3 octets: no AN-UDL-LONG
        };
    }

    /** SMS-DELIVER, no UDH, DCS 0x00, PID 0x00 (normal). */
    private static byte[] normalPdu() {
        return new byte[] {
            0x07, (byte) 0x91, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xF0,
            0x04,
            0x0C, (byte) 0x91, 0x19, 0x09, 0x00, 0x00, 0x00, 0x00,
            0x00,
            0x00,
            0x62, 0x11, 0x50, 0x00, 0x00, 0x00, 0x00,
            0x03, 0x41, 0x42, 0x43, // TP-UDL 3 septets in 3 octets: no AN-UDL-LONG
        };
    }

    @Test
    void FW_03_blockDropsThroughHkR3AndReturnsTrue() {
        SmsFilterCore core = core(Map.of(ClassId.FLASH, Verdict.BLOCK), InjectionLookup.NONE);
        RecordingHost host = new RecordingHost();
        assertTrue(core.filter(input(flashPdu(), true), host));
        assertEquals(1, host.drops);
        assertEquals(1, host.verdicts.size());
        assertEquals(Verdict.BLOCK, host.verdicts.get(0).action());
    }

    @Test
    void FW_02_allowLeavesTheMessageAlone() {
        SmsFilterCore core = core(Map.of(), InjectionLookup.NONE);
        RecordingHost host = new RecordingHost();
        assertFalse(core.filter(input(normalPdu(), true), host));
        assertEquals(0, host.drops);
        assertEquals(1, host.verdicts.size());
        assertEquals(Verdict.ALLOW, host.verdicts.get(0).action());
    }

    @Test
    void FW_03_quarantineAlsoDrops() {
        SmsFilterCore core = core(Map.of(ClassId.FLASH, Verdict.QUARANTINE), InjectionLookup.NONE);
        RecordingHost host = new RecordingHost();
        assertTrue(core.filter(input(flashPdu(), true), host));
        assertEquals(1, host.drops);
        assertEquals(Verdict.QUARANTINE, host.verdicts.get(0).action());
    }

    @Test
    void CAP_10_lockedUserIsNeverFiltered() {
        SmsFilterCore core = core(Map.of(ClassId.FLASH, Verdict.BLOCK), InjectionLookup.NONE);
        RecordingHost host = new RecordingHost();
        assertFalse(core.filter(input(flashPdu(), false), host));
        assertEquals(0, host.drops);
        assertTrue(host.verdicts.isEmpty());
    }

    @Test
    void TST_02_canaryIsDroppedEvenWithTheFirewallOff() {
        RuleEngine none = RuleEngine.compile(RulesConfig.DEFAULT);
        FirewallState state = new FirewallState(new FixedClock());
        state.setAvailable(false);
        state.setEnabled(false);
        SmsFilterCore core = new SmsFilterCore(new Enforcer(() -> none, state, TextSource.NONE, null),
                (key, alt) -> InjectionTokens.Kind.CANARY, new FixedClock(), null);
        RecordingHost host = new RecordingHost();
        assertTrue(core.filter(input(flashPdu(), true), host));
        assertEquals(1, host.drops);
        assertEquals(VerdictRecord.CANARY, host.verdicts.get(0).reasonWire());
        assertEquals(Verdict.BLOCK, host.verdicts.get(0).action());
    }

    @Test
    void QUA_03_releaseSkipsTheRules() {
        SmsFilterCore core = core(Map.of(ClassId.FLASH, Verdict.BLOCK),
                (key, alt) -> InjectionTokens.Kind.RELEASE);
        RecordingHost host = new RecordingHost();
        assertFalse(core.filter(input(flashPdu(), true), host));
        assertEquals(0, host.drops);
        assertEquals(1, host.stages.size());
        assertTrue(host.stages.get(0).contains("verdict=ALLOW"));
    }

    @Test
    void CL_05_headFilterRulesOnTheSubtypeOfItsOneClassification() {
        RuleEngine engine = RuleEngine.compile(
                dev.nightjar.rules.Presets.apply(Preset.RECOMMENDED, RulesConfig.DEFAULT));
        FirewallState state = new FirewallState(new FixedClock());
        state.setAvailable(true);
        state.setEnabled(true);
        SmsFilterCore core = new SmsFilterCore(new Enforcer(() -> engine, state, TextSource.NONE, null),
                InjectionLookup.NONE, new FixedClock(), null);
        // Synthetic data messages (scenarios.json data-port-0, data-port-9200): the class action of CL-DATA-PORT is
        // ALLOW, the Recommended subtype rule holds PORT0.
        String head = "059151551000440891515510240004621150214365220B060504";

        RecordingHost port0 = new RecordingHost();
        assertTrue(core.filter(input(hex(head + "0000000001020304"), true), port0));
        assertEquals(1, port0.drops);
        assertEquals(Verdict.QUARANTINE, port0.verdicts.get(0).action());

        RecordingHost named = new RecordingHost();
        assertFalse(core.filter(input(hex(head + "23F0000001020304"), true), named));
        assertEquals(0, named.drops);
        assertEquals(Verdict.ALLOW, named.verdicts.get(0).action());
    }

    private static byte[] hex(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @Test
    void FW_04_wapPortIsLeftToHk14() {
        SmsFilterCore core = core(Map.of(ClassId.DATA_PORT, Verdict.BLOCK), InjectionLookup.NONE);
        RecordingHost host = new RecordingHost();
        assertFalse(core.filter(new SmsFilterCore.Input(new byte[][] {normalPdu()}, 2948, true, 0, false),
                host));
        assertTrue(host.verdicts.isEmpty());
    }

    @Test
    void FW_10_aDamagedPduIsDeliveredWhenMalformedSmsIsNotBlocked() {
        // {1, 2, 3} does not parse: the radio read leaves one TPDU octet (AN-PDU-SHORT, known damage). It is
        // CL-MALFORMED-SMS, and this snapshot has no action for that class: ALLOW, delivered.
        assertTrue(Anomalies.malformed(SmsFilterCore.facts(new byte[] {1, 2, 3}).facts().anomalyMask()));
        SmsFilterCore core = core(Map.of(ClassId.FLASH, Verdict.BLOCK), InjectionLookup.NONE);
        RecordingHost host = new RecordingHost();
        assertFalse(core.filter(input(new byte[] {1, 2, 3}, true), host));
        assertEquals(0, host.drops);
        assertEquals(1, host.verdicts.size());
        assertEquals(Verdict.ALLOW, host.verdicts.get(0).action());
    }

    // ---------------------------------------------- PDUs that do not parse at the head filter

    /**
     * Synthetic scenarios.json {@code anomaly-mti}: reserved TP-MTI 3. AOSP parsePdu reads it as an SMS-DELIVER. It is
     * CL-UNREADABLE-SMS.
     */
    private static final String ANOMALY_MTI =
            "059151551000070891515510240000621150214365220ED4F29C0E6A97E7F3F0B90C8A01";

    /** Synthetic scenarios.json {@code anomaly-addr-trunc}: the originator address runs past the PDU. Known damage. */
    private static final String ANOMALY_ADDR_TRUNC = "059151551000040C915155";

    /** Synthetic scenarios.json {@code anomaly-pdu-short}: the PDU ends after TP-DCS. Known damage. */
    private static final String ANOMALY_PDU_SHORT = "059151551000040891515510240000";

    private static SmsFilterCore recommended() {
        RuleEngine engine = RuleEngine.compile(
                dev.nightjar.rules.Presets.apply(Preset.RECOMMENDED, RulesConfig.DEFAULT));
        FirewallState state = new FirewallState(new FixedClock());
        state.setAvailable(true);
        state.setEnabled(true);
        return new SmsFilterCore(new Enforcer(() -> engine, state, TextSource.NONE, null), InjectionLookup.NONE,
                new FixedClock(), null);
    }

    /** The filter consumed the message after dropFilteredSms, with {@code verdict} (BLOCK or QUARANTINE). */
    private static void assertDropped(SmsFilterCore core, String pdu, Verdict verdict) {
        RecordingHost host = new RecordingHost();
        assertTrue(core.filter(input(hex(pdu), true), host), pdu);
        assertEquals(1, host.drops, pdu);
        assertEquals(1, host.verdicts.size(), pdu);
        assertEquals(verdict, host.verdicts.get(0).action(), pdu);
    }

    private static void assertDelivered(SmsFilterCore core, String pdu, Verdict verdict) {
        RecordingHost host = new RecordingHost();
        assertFalse(core.filter(input(hex(pdu), true), host), pdu);
        assertEquals(0, host.drops, pdu);
        assertEquals(1, host.verdicts.size(), pdu);
        assertEquals(verdict, host.verdicts.get(0).action(), pdu);
    }

    @Test
    void UX2_FW_01_truncatedAndShortPdusAreBlockedWhenMalformedSmsIsBlock() {
        SmsFilterCore custom = core(Map.of(ClassId.MALFORMED_SMS, Verdict.BLOCK), InjectionLookup.NONE);
        SmsFilterCore recommended = recommended();
        // AN-ADDR-TRUNC makes the parse invalid: an older head filter used CL-NORMAL and delivered it.
        // AN-PDU-SHORT here keeps the parse valid but malformed.
        assertFalse(SmsFilterCore.facts(hex(ANOMALY_ADDR_TRUNC)).valid());
        for (String pdu : new String[] {ANOMALY_ADDR_TRUNC, ANOMALY_PDU_SHORT}) {
            assertDropped(custom, pdu, Verdict.BLOCK);
            assertDropped(recommended, pdu, Verdict.BLOCK);
        }
    }

    @Test
    void UX2_FW_01_damagedPdusAreDeliveredWhenMalformedSmsIsAllowOrNotify() {
        for (String pdu : new String[] {ANOMALY_ADDR_TRUNC, ANOMALY_PDU_SHORT}) {
            assertDelivered(core(Map.of(ClassId.MALFORMED_SMS, Verdict.ALLOW), InjectionLookup.NONE), pdu,
                    Verdict.ALLOW);
            assertDelivered(core(Map.of(ClassId.MALFORMED_SMS, Verdict.NOTIFY), InjectionLookup.NONE), pdu,
                    Verdict.NOTIFY);
        }
    }

    @Test
    void UX2_FW_02_theWrongMessageTypeIsHeldUnderRecommended() {
        // An older head filter used CL-NORMAL for this invalid parse and delivered it.
        assertFalse(SmsFilterCore.facts(hex(ANOMALY_MTI)).valid());
        assertDropped(recommended(), ANOMALY_MTI, Verdict.QUARANTINE);
    }

    @Test
    void UX2_FW_02_theUnreadableSmsActionApplies() {
        assertDropped(core(Map.of(ClassId.UNREADABLE_SMS, Verdict.BLOCK), InjectionLookup.NONE), ANOMALY_MTI,
                Verdict.BLOCK);
        assertDropped(core(Map.of(ClassId.UNREADABLE_SMS, Verdict.QUARANTINE), InjectionLookup.NONE), ANOMALY_MTI,
                Verdict.QUARANTINE);
        assertDelivered(core(Map.of(ClassId.UNREADABLE_SMS, Verdict.ALLOW), InjectionLookup.NONE), ANOMALY_MTI,
                Verdict.ALLOW);
        assertDelivered(core(Map.of(ClassId.UNREADABLE_SMS, Verdict.NOTIFY), InjectionLookup.NONE), ANOMALY_MTI,
                Verdict.NOTIFY);
    }

    @Test
    void UX2_FW_02_aSnapshotWithoutAnUnreadableEntryHoldsIt() {
        // A snapshot from before CL-UNREADABLE-SMS: the default is Hold. The Malformed SMS action does not
        // apply to it.
        assertDropped(core(Map.of(ClassId.FLASH, Verdict.BLOCK), InjectionLookup.NONE), ANOMALY_MTI,
                Verdict.QUARANTINE);
        assertDropped(core(Map.of(ClassId.MALFORMED_SMS, Verdict.ALLOW), InjectionLookup.NONE), ANOMALY_MTI,
                Verdict.QUARANTINE);
    }

    @Test
    void UX2_FW_02_monitorHoldsNothing() {
        RuleEngine engine = RuleEngine.compile(RulesConfig.DEFAULT);
        FirewallState state = new FirewallState(new FixedClock());
        state.setAvailable(true);
        state.setEnabled(true);
        SmsFilterCore core = new SmsFilterCore(new Enforcer(() -> engine, state, TextSource.NONE, null),
                InjectionLookup.NONE, new FixedClock(), null);
        assertDelivered(core, ANOMALY_MTI, Verdict.ALLOW);
    }

    @Test
    void UX2_FW_02_theHeadFilterPicksTheClassOfThePdu() {
        // The facts that the head filter uses carry the anomaly that decides the class.
        int mti = SmsFilterCore.facts(hex(ANOMALY_MTI)).facts().anomalyMask();
        assertTrue(Anomalies.has(mti, AnomalyCode.MTI));
        assertFalse(Anomalies.malformed(mti));
        assertTrue(Anomalies.has(SmsFilterCore.facts(hex(ANOMALY_ADDR_TRUNC)).facts().anomalyMask(),
                AnomalyCode.ADDR_TRUNC));
        assertTrue(Anomalies.malformed(SmsFilterCore.facts(hex(ANOMALY_PDU_SHORT)).facts().anomalyMask()));
        assertEquals(ClassId.UNREADABLE_SMS, predicted(ANOMALY_MTI));
        assertEquals(ClassId.MALFORMED_SMS, predicted(ANOMALY_ADDR_TRUNC));
        assertEquals(ClassId.MALFORMED_SMS, predicted(ANOMALY_PDU_SHORT));
    }

    private static ClassId predicted(String pdu) {
        return dev.nightjar.payload.capture.ClassPredictor.predictResult(SmsFilterCore.facts(hex(pdu)), false, false,
                null).classId();
    }

    @Test
    void UX2_FW_02_aThrowingEnforcerDeliversAPduThatDoesNotParse() {
        SmsFilterCore core = new SmsFilterCore(new Enforcer(() -> {
            throw new IllegalStateException("boom");
        }, new FirewallState(new FixedClock()), TextSource.NONE, null), InjectionLookup.NONE, new FixedClock(), null);
        for (String pdu : new String[] {ANOMALY_MTI, ANOMALY_ADDR_TRUNC}) {
            RecordingHost host = new RecordingHost();
            assertFalse(core.filter(input(hex(pdu), true), host), pdu);
            assertEquals(0, host.drops, pdu);
        }
    }

    @Test
    void UX2_FW_02_aFailedDropDeliversWithoutABlockOrHoldVerdict() {
        for (Map<ClassId, Verdict> actions : List.of(Map.of(ClassId.MALFORMED_SMS, Verdict.BLOCK),
                Map.of(ClassId.UNREADABLE_SMS, Verdict.QUARANTINE))) {
            for (String pdu : new String[] {ANOMALY_MTI, ANOMALY_ADDR_TRUNC}) {
                RecordingHost host = new RecordingHost();
                host.failDrop = true;
                boolean consumed = core(actions, InjectionLookup.NONE).filter(input(hex(pdu), true), host);
                assertFalse(consumed, pdu);
                for (VerdictRecord v : host.verdicts) {
                    assertFalse(v.blocked(), pdu + ": a verdict that claims a drop was published");
                }
            }
        }
    }

    @Test
    void FW_10_aThrowingEnforcerDeliversTheMessage() {
        SmsFilterCore core = new SmsFilterCore(new Enforcer(() -> {
            throw new IllegalStateException("boom");
        }, new FirewallState(new FixedClock()), TextSource.NONE, null), InjectionLookup.NONE, new FixedClock(), null);
        RecordingHost host = new RecordingHost();
        assertFalse(core.filter(input(flashPdu(), true), host));
        assertEquals(0, host.drops);
    }

    @Test
    void FW_05_nullInputsAreSafe() {
        SmsFilterCore core = core(Map.of(), InjectionLookup.NONE);
        RecordingHost host = new RecordingHost();
        assertFalse(core.filter(null, host));
        assertFalse(core.filter(new SmsFilterCore.Input(null, -1, true, 0, false), host));
        assertFalse(core.filter(new SmsFilterCore.Input(new byte[][] {null}, -1, true, 0, false), host));
        assertFalse(core.filter(input(flashPdu(), true), null));
        assertEquals(0, host.drops);
    }

    @Test
    void FW_04_cbEnforcerNeverSkipsAnEmergencyChannel() {
        CbEnforcer cb = new CbEnforcer(enforcer(Map.of(ClassId.CB, Verdict.BLOCK)));
        assertFalse(cb.decide(4370, 0, false).skip());
        assertFalse(cb.decide(4352, 0, false).skip());
        // The whole PWS range 4352-6399 (0x1100-0x18FF) is emergency, not only the assigned ETWS/CMAS ids.
        assertFalse(cb.decide(4400, 0, false).skip(), "0x1130 CMAS geo-fencing trigger");
        assertFalse(cb.decide(6399, 0, false).skip());
        assertTrue(cb.decide(1000, 0, false).skip());
        assertTrue(cb.decide(6400, 0, false).skip());
        assertTrue(dev.nightjar.contract.CbChannels.isEmergency(4399));
        assertTrue(dev.nightjar.contract.CbChannels.isEmergency(4400));
        assertTrue(dev.nightjar.contract.CbChannels.isEmergency(6399));
        assertFalse(dev.nightjar.contract.CbChannels.isEmergency(4351));
        assertFalse(dev.nightjar.contract.CbChannels.isEmergency(6400));
    }

    /** WSP Push: transaction id 1, PDU type Push, one-octet header block, Content-Type 0x36 (connectivity-wbxml). */
    private static byte[] wapCpPush() {
        return new byte[] {0x01, 0x06, 0x01, (byte) 0xB6};
    }

    @Test
    void FW_04_wapEnforcerConsumesWithResultOne() {
        WapEnforcer wap = new WapEnforcer(enforcer(Map.of(ClassId.WAP_CP, Verdict.BLOCK)), InjectionLookup.NONE);
        HookDecision blocked = wap.decide(wapCpPush(), null, 0, false);
        assertTrue(blocked.skip());
        assertEquals(WapPush.RESULT_CONSUMED, blocked.skipResult());
        assertFalse(blocked.skipResult() == WapPush.RESULT_BROADCAST, "never skip with -1");
        assertEquals(ClassId.WAP_CP, blocked.classId());
    }

    @Test
    void FW_04_wapAllowRunsTheOriginal() {
        WapEnforcer wap = new WapEnforcer(enforcer(Map.of()), InjectionLookup.NONE);
        assertFalse(wap.decide(wapCpPush(), null, 0, false).skip());
    }

    @Test
    void P18_aReleasedWapPushIsNotRuledAgain() {
        WapEnforcer wap = new WapEnforcer(enforcer(Map.of(ClassId.WAP_CP, Verdict.QUARANTINE)),
                (key, alt) -> InjectionTokens.Kind.RELEASE);

        HookDecision decision = wap.decide(wapCpPush(), "+919000000000", 0, false);

        assertFalse(decision.skip(), "a release runs the original");
        assertEquals(Verdict.ALLOW, decision.verdict().action());
    }

    @Test
    void P18_aWapCanaryIsConsumed() {
        WapEnforcer wap = new WapEnforcer(enforcer(Map.of()), (key, alt) -> InjectionTokens.Kind.CANARY);

        HookDecision decision = wap.decide(wapCpPush(), null, 0, false);

        assertTrue(decision.skip());
        assertEquals(WapPush.RESULT_CONSUMED, decision.skipResult());
        assertEquals(VerdictRecord.CANARY, decision.verdict().reasonWire());
    }

    @Test
    void H4_aThrowingTokenLookupStillRunsTheOriginal() {
        WapEnforcer wap = new WapEnforcer(enforcer(Map.of()), (key, alt) -> {
            throw new IllegalStateException("boom");
        });
        assertFalse(wap.decide(wapCpPush(), null, 0, false).skip());
    }

    @Test
    void P36v_aFailedDropPublishesNoVerdict() {
        SmsFilterCore core = core(Map.of(ClassId.FLASH, Verdict.BLOCK), InjectionLookup.NONE);
        RecordingHost host = new RecordingHost();
        host.failDrop = true;

        assertFalse(core.filter(input(flashPdu(), true), host), "the message is delivered when the drop fails");

        assertEquals(1, host.drops);
        assertTrue(host.verdicts.isEmpty(), "a trace must not claim a drop that did not happen");
        assertEquals(1, host.errors);
    }

    @Test
    void FW_04_simDataDownloadBlocksWithResultOne() {
        UsimDdEnforcer usim = new UsimDdEnforcer(enforcer(Map.of(ClassId.SIM_DD, Verdict.BLOCK)), new CanaryGate(InjectionLookup.NONE, CanaryGate.Pending.NONE));
        HookDecision decision = usim.decide("+919000000000", null, null, 0, false);
        assertTrue(decision.skip());
        assertEquals(UsimDdEnforcer.SKIP_RESULT, decision.skipResult());
    }

    @Test
    void TST_02_simDataDownloadCanaryIsInterceptedByItsToken() {
        byte[] canary = flashPdu();
        CanaryGate gate = new CanaryGate((key, alt) -> InjectionTokens.Kind.CANARY, CanaryGate.Pending.NONE);
        UsimDdEnforcer usim = new UsimDdEnforcer(enforcer(Map.of()), gate);
        HookDecision decision = usim.decide(null, null, canary, 0, false);
        assertTrue(decision.skip());
        assertEquals(VerdictRecord.CANARY, decision.verdict().reasonWire());
    }

    @Test
    void P35_theCanaryMarkerOriginatorIsNotEnough() {
        // A hostile SIM data download from the marker address must not be swallowed outside a self-test.
        UsimDdEnforcer usim = new UsimDdEnforcer(enforcer(Map.of()), new CanaryGate(InjectionLookup.NONE, CanaryGate.Pending.NONE));
        assertFalse(usim.decide("NJSELFTEST", null, flashPdu(), 0, false).skip());
    }

    @Test
    void UX2_FW_06_mwiCanBeSuppressedAndHeld() {
        MwiEnforcer mwi = new MwiEnforcer(enforcer(Map.of(ClassId.MWI, Verdict.BLOCK)), new CanaryGate(InjectionLookup.NONE, CanaryGate.Pending.NONE));
        assertTrue(mwi.decide("+919000000000", null, 0, false).skip());
        MwiEnforcer quarantining = new MwiEnforcer(enforcer(Map.of(ClassId.MWI, Verdict.QUARANTINE)),
                new CanaryGate(InjectionLookup.NONE, CanaryGate.Pending.NONE));
        HookDecision held = quarantining.decide("+919000000000", null, 0, false);
        assertTrue(held.skip(), "a held MWI must not change the voicemail icon");
        assertEquals(Verdict.QUARANTINE, held.verdict().action());
    }

    @Test
    void UX2_FW_06_aReleasedMwiAppliesTheIndicator() {
        CanaryGate none = new CanaryGate(InjectionLookup.NONE, CanaryGate.Pending.NONE);
        MwiEnforcer releasing = new MwiEnforcer(enforcer(Map.of(ClassId.MWI, Verdict.QUARANTINE)), none, () -> true);
        HookDecision released = releasing.decide(null, null, 0, false);
        assertFalse(released.skip(), "a release is delivered without rules");
        assertEquals(Verdict.ALLOW, released.verdict().action());

        MwiEnforcer idle = new MwiEnforcer(enforcer(Map.of(ClassId.MWI, Verdict.QUARANTINE)), none, () -> false);
        assertTrue(idle.decide(null, null, 0, false).skip(), "no release in flight: the rules apply");

        MwiEnforcer broken = new MwiEnforcer(enforcer(Map.of(ClassId.MWI, Verdict.BLOCK)), none, () -> {
            throw new IllegalStateException("boom");
        });
        assertTrue(broken.decide(null, null, 0, false).skip(), "a failing release check leaves the rules in charge");

        MwiEnforcer canaryFirst = new MwiEnforcer(enforcer(Map.of()),
                new CanaryGate(InjectionLookup.NONE, cls -> cls == ClassId.MWI), () -> true);
        assertTrue(canaryFirst.decide(null, null, 0, false).skip(), "the canary never reaches the icon");
    }

    @Test
    void P35_mwiIsSuppressedOnlyWhileAnMwiCanaryIsPending() {
        Enforcer allow = enforcer(Map.of());
        MwiEnforcer armed = new MwiEnforcer(allow,
                new CanaryGate(InjectionLookup.NONE, cls -> cls == ClassId.MWI));
        assertTrue(armed.decide(null, null, 0, false).skip());

        MwiEnforcer otherCanary = new MwiEnforcer(allow,
                new CanaryGate(InjectionLookup.NONE, cls -> cls == ClassId.WAP_CP));
        assertFalse(otherCanary.decide(null, null, 0, false).skip(),
                "a WAP canary in flight must not suppress a real voicemail indicator");
    }

    @Test
    void E07_T01_filterChainIsIdempotentAndKeepsExistingFilters() {
        List<Object> existing = new ArrayList<>(List.of("vvm", "carrier"));
        List<Object> first = FilterChain.prepend(existing, new NjSmsFilterHolder());
        assertEquals(3, first.size());
        assertEquals("vvm", first.get(1));
        assertEquals("carrier", first.get(2));
        List<Object> second = FilterChain.prepend(first, new NjSmsFilterHolder());
        assertEquals(3, second.size());
        assertTrue(FilterChain.startsWith(second, NjSmsFilterHolder.class));
        assertEquals(1, FilterChain.prepend(null, new NjSmsFilterHolder()).size());
        assertTrue(FilterChain.prepend(List.of(), null).isEmpty());
    }

    /** Stand-in for {@link NjSmsFilter} so the chain test does not need the framework. */
    private static final class NjSmsFilterHolder {}

    @Test
    void E07_T09_traceDetailHasNoContentAndNoNumbers() {
        VerdictRecord record = new VerdictRecord(Verdict.QUARANTINE, 7, null, null, null);
        assertEquals("verdict=QUARANTINE rule=7 clamp=none", record.traceDetail());
        assertEquals("{\"action\":\"QUARANTINE\",\"ruleId\":7}", AppendixD.verdictJson(record));
    }
}
