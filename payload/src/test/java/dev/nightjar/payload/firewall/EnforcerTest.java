package dev.nightjar.payload.firewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Schemas;
import dev.nightjar.contract.Verdict;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.rules.ClampReason;
import dev.nightjar.rules.Conditions;
import dev.nightjar.rules.Preset;
import dev.nightjar.rules.Rule;
import dev.nightjar.rules.RuleEngine;
import dev.nightjar.rules.RuleOrigin;
import dev.nightjar.rules.RulesConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The enforcement path, the guard-rail clamps and the fail-open rules of the enforcement matrix.
 */
class EnforcerTest {

    static final class FixedClock implements Clock {
        long ms = 1_000L;

        @Override
        public long elapsedNs() {
            return ms * 1_000_000L;
        }

        @Override
        public long wallMs() {
            return 1_762_330_000_000L + ms;
        }
    }

    private static RulesConfig config(Map<ClassId, Verdict> actions, List<Rule> rules) {
        return new RulesConfig(Schemas.RULES, 1, Preset.CUSTOM, actions, rules, List.of(), List.of(), 5);
    }

    private static Enforcer enforcer(RulesConfig config, FirewallState state) {
        RuleEngine engine = RuleEngine.compile(config);
        return new Enforcer(() -> engine, state, TextSource.NONE, null);
    }

    private static FirewallState nominal(FixedClock clock) {
        FirewallState state = new FirewallState(clock);
        state.setAvailable(true);
        state.setEnabled(true);
        return state;
    }

    @Test
    void FW_06_classActionDecidesWithoutARule() {
        FixedClock clock = new FixedClock();
        Enforcer e = enforcer(config(Map.of(ClassId.WAP_CP, Verdict.QUARANTINE), List.of()), nominal(clock));
        VerdictRecord r = e.headFilter(ClassId.WAP_CP, null, 0, false, null);
        assertEquals(Verdict.QUARANTINE, r.action());
        assertTrue(r.blocked());
        assertEquals(Verdict.QUARANTINE, r.action());
        assertFalse(r.clamped());
        assertEquals("verdict=QUARANTINE rule=none clamp=none", r.traceDetail());
    }

    @Test
    void FW_10_noSnapshotMeansAllow() {
        FixedClock clock = new FixedClock();
        Enforcer e = new Enforcer(() -> null, nominal(clock), TextSource.NONE, null);
        VerdictRecord r = e.headFilter(ClassId.WAP_CP, null, 0, false, null);
        assertEquals(Verdict.ALLOW, r.action());
        assertFalse(r.blocked());
    }

    @Test
    void FW_12_safeModeClampsBlockButKeepsNotify() {
        FixedClock clock = new FixedClock();
        FirewallState state = nominal(clock);
        state.setSafeMode(true);
        Enforcer e = enforcer(config(Map.of(ClassId.WAP_CP, Verdict.BLOCK, ClassId.WAP_SISL, Verdict.NOTIFY),
                List.of()), state);

        VerdictRecord blocked = e.headFilter(ClassId.WAP_CP, null, 0, false, null);
        assertEquals(Verdict.ALLOW, blocked.action());
        assertEquals(ClampReason.SAFE_MODE, blocked.clampReason());
        assertEquals(Verdict.BLOCK, blocked.clampedFrom());

        VerdictRecord notify = e.headFilter(ClassId.WAP_SISL, null, 0, false, null);
        assertEquals(Verdict.NOTIFY, notify.action());
        assertFalse(notify.clamped());
    }

    @Test
    void HOOK_08_firewallOffClampsBlockAndQuarantine() {
        FixedClock clock = new FixedClock();
        FirewallState state = new FirewallState(clock);
        state.setAvailable(true);
        state.setEnabled(false);
        Enforcer e = enforcer(config(Map.of(ClassId.FLASH, Verdict.BLOCK), List.of()), state);
        VerdictRecord r = e.headFilter(ClassId.FLASH, null, 0, false, null);
        assertEquals(Verdict.ALLOW, r.action());
        assertEquals(ClampReason.FIREWALL_OFF, r.clampReason());
    }

    @Test
    void HOOK_08_unavailableFirewallClampsToo() {
        FixedClock clock = new FixedClock();
        FirewallState state = new FirewallState(clock);
        state.setAvailable(false);
        state.setEnabled(true);
        Enforcer e = enforcer(config(Map.of(ClassId.FLASH, Verdict.QUARANTINE), List.of()), state);
        assertEquals(Verdict.ALLOW, e.headFilter(ClassId.FLASH, null, 0, false, null).action());
    }

    @Test
    void G01_emergencyBroadcastIsNeverHeldBack() {
        FixedClock clock = new FixedClock();
        Enforcer e = enforcer(config(Map.of(ClassId.CB, Verdict.BLOCK), List.of()), nominal(clock));
        VerdictRecord r = e.cellBroadcast(4370, 0, false);
        assertEquals(Verdict.ALLOW, r.action());
        assertEquals(ClampReason.EMERGENCY_CB, r.clampReason());
    }

    @Test
    void G01_nonEmergencyBroadcastCanBeBlocked() {
        FixedClock clock = new FixedClock();
        Enforcer e = enforcer(config(Map.of(ClassId.CB, Verdict.BLOCK), List.of()), nominal(clock));
        assertEquals(Verdict.BLOCK, e.cellBroadcast(1000, 0, false).action());
    }

    @Test
    void G04_suplIsClampedDuringAnEmergencyCallAndItsGrace() {
        FixedClock clock = new FixedClock();
        FirewallState state = nominal(clock);
        Enforcer e = enforcer(config(Map.of(ClassId.WAP_SUPL, Verdict.BLOCK), List.of()), state);

        state.setEmergencyCallActive(true);
        VerdictRecord during = e.wap(ClassId.WAP_SUPL, "application/vnd.omaloc-supl-init", null, 0, false);
        assertEquals(Verdict.ALLOW, during.action());
        assertEquals(ClampReason.SUPL_EMERGENCY, during.clampReason());

        state.setEmergencyCallActive(false); // stamps the end time
        clock.ms += 60_000L; // 1 min into the 5 min floor
        assertEquals(Verdict.ALLOW,
                e.wap(ClassId.WAP_SUPL, "application/vnd.omaloc-supl-init", null, 0, false).action());

        clock.ms += 5L * 60_000L; // past the grace
        assertEquals(Verdict.BLOCK,
                e.wap(ClassId.WAP_SUPL, "application/vnd.omaloc-supl-init", null, 0, false).action());
    }

    @Test
    void CL_05_headFilterPassesTheSubtypeToTheRules() {
        FixedClock clock = new FixedClock();
        Enforcer e = enforcer(dev.nightjar.rules.Presets.apply(Preset.RECOMMENDED, RulesConfig.DEFAULT),
                nominal(clock));
        // Synthetic data messages (scenarios.json data-port-0, data-port-16001, data-port-9200).
        String head = "059151551000440891515510240004621150214365220B060504";
        dev.nightjar.payload.capture.PduFacts port0 = radio(head + "0000000001020304");
        dev.nightjar.payload.capture.PduFacts unknown = radio(head + "3E81000001020304");
        dev.nightjar.payload.capture.PduFacts named = radio(head + "23F0000001020304");

        // The caller (SmsFilterCore) classifies once and passes the class and subtype; the enforcer does not classify.
        dev.nightjar.payload.capture.ProvisionalClassifier.Result r =
                dev.nightjar.payload.capture.ClassPredictor.predictResult(port0, false, false, null);
        assertEquals(ClassId.DATA_PORT, r.classId());
        assertEquals("PORT0", r.subtype());
        assertEquals(Verdict.QUARANTINE, e.headFilter(r.classId(), r.subtype(), port0, 0, false, null).action());
        assertEquals(Verdict.NOTIFY, e.headFilter(ClassId.DATA_PORT, "UNKNOWN", unknown, 0, false, null).action());
        assertEquals(Verdict.ALLOW,
                e.headFilter(ClassId.DATA_PORT, "WAP-CONNECTIONLESS-SESSION", named, 0, false, null).action());
        // Fail open: a null subtype gives the class action, also when the PDU would match a subtype rule.
        assertEquals(Verdict.ALLOW, e.headFilter(ClassId.DATA_PORT, null, port0, 0, false, null).action());
        assertEquals(Verdict.ALLOW, e.headFilter(ClassId.DATA_PORT, port0, 0, false, null).action());
        assertEquals(Verdict.ALLOW, e.headFilter(ClassId.DATA_PORT, null, 0, false, null).action());
    }

    @Test
    void CL_05_wapPassesTheContentTypeSubtypeToTheRules() {
        FixedClock clock = new FixedClock();
        Enforcer e = enforcer(dev.nightjar.rules.Presets.apply(Preset.RECOMMENDED, RulesConfig.DEFAULT),
                nominal(clock));
        assertEquals("SL", Enforcer.wapSubtype(ClassId.WAP_SISL, "application/vnd.wap.slc"));
        assertEquals(Verdict.BLOCK, e.wap(ClassId.WAP_SISL, "application/vnd.wap.slc", null, 0, false).action());
        assertEquals(Verdict.BLOCK, e.wap(ClassId.WAP_SISL, "text/vnd.wap.sl", null, 0, false).action());
        // SI needs the body for its subtype; without it the SISL class action (QUARANTINE) applies.
        assertNull(Enforcer.wapSubtype(ClassId.WAP_SISL, "application/vnd.wap.sic"));
        assertEquals(Verdict.QUARANTINE,
                e.wap(ClassId.WAP_SISL, "application/vnd.wap.sic", null, 0, false).action());
        assertNull(Enforcer.wapSubtype(null, "application/vnd.wap.slc"));
    }

    private static dev.nightjar.payload.capture.PduFacts radio(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return dev.nightjar.payload.capture.PduFacts.parseRadio(b);
    }

    @Test
    void P18_theWapOriginatorReachesTheRules() {
        FixedClock clock = new FixedClock();
        Rule rule = new Rule(9, 0, true, RuleOrigin.USER, "wap-sender", Verdict.BLOCK,
                Conditions.builder().classes(ClassId.WAP_CP)
                        .originator(dev.nightjar.rules.StringMatch.prefix("+9190")).build());
        Enforcer e = enforcer(config(Map.of(), List.of(rule)), nominal(clock));

        assertEquals(Verdict.BLOCK, e.wap(ClassId.WAP_CP, "application/vnd.wap.connectivity-wbxml",
                "+919000000000", 0, false).action());
        assertEquals(Verdict.ALLOW, e.wap(ClassId.WAP_CP, "application/vnd.wap.connectivity-wbxml", null, 0,
                false).action(), "no address: the originator condition cannot match");
    }

    @Test
    void FW_06_quarantineIsClampedForNonReleasableClasses() {
        FixedClock clock = new FixedClock();
        Enforcer e = enforcer(config(Map.of(ClassId.SIM_DD, Verdict.QUARANTINE), List.of()), nominal(clock));
        VerdictRecord r = e.simDataDownload(null, null, 0, false);
        assertEquals(Verdict.ALLOW, r.action());
        assertEquals(ClampReason.ACTION_NOT_ALLOWED, r.clampReason());
    }

    @Test
    void G05_blockingAllNormalIsClampedToAllow() {
        FixedClock clock = new FixedClock();
        Rule blockAll = new Rule(1, 0, true, RuleOrigin.USER, "all", Verdict.BLOCK,
                Conditions.builder().classes(ClassId.NORMAL).build());
        Enforcer e = enforcer(config(Map.of(), List.of(blockAll)), nominal(clock));
        VerdictRecord r = e.headFilter(ClassId.NORMAL, null, 0, false, null);
        assertEquals(Verdict.ALLOW, r.action());
        assertEquals(ClampReason.BLOCKS_ALL_NORMAL, r.clampReason());
    }

    @Test
    void FW_07_ruleWithSenderConditionBlocksNormal() {
        FixedClock clock = new FixedClock();
        Rule rule = new Rule(7, 0, true, RuleOrigin.USER, "sender", Verdict.BLOCK,
                Conditions.builder().classes(ClassId.NORMAL).originator(
                        dev.nightjar.rules.StringMatch.prefix("+9190")).build());
        Enforcer e = enforcer(config(Map.of(), List.of(rule)), nominal(clock));
        VerdictRecord r = e.headFilter(ClassId.NORMAL, radioFacts(), 0, false, null);
        assertEquals(Verdict.BLOCK, r.action());
        assertEquals(7, r.ruleId());
        assertFalse(r.clamped());
    }

    @Test
    void FW_16_verdictJsonCarriesTheRuleAndTheClamp() {
        FixedClock clock = new FixedClock();
        FirewallState state = nominal(clock);
        state.setSafeMode(true);
        Enforcer e = enforcer(config(Map.of(ClassId.DATA_PORT, Verdict.BLOCK), List.of()), state);
        VerdictRecord r = e.headFilter(ClassId.DATA_PORT, null, 0, false, null);
        assertEquals("{\"action\":\"ALLOW\",\"clampedFrom\":\"BLOCK\",\"clampReason\":\"SAFE_MODE\"}", AppendixD.verdictJson(r));
    }

    @Test
    void H4_aThrowingRuleSourceYieldsAllow() {
        FixedClock clock = new FixedClock();
        Enforcer e = new Enforcer(() -> {
            throw new IllegalStateException("boom");
        }, nominal(clock), TextSource.NONE, null);
        assertEquals(Verdict.ALLOW, e.headFilter(ClassId.FLASH, null, 0, false, null).action());
    }

    @Test
    void FW_07_textRuleUsesTheLazyProvider() {
        FixedClock clock = new FixedClock();
        Rule rule = new Rule(3, 0, true, RuleOrigin.USER, "text", Verdict.BLOCK,
                Conditions.builder().classes(ClassId.FLASH).textContains("marker").build());
        RuleEngine engine = RuleEngine.compile(config(Map.of(), List.of(rule)));
        int[] decodes = {0};
        Enforcer e = new Enforcer(() -> engine, nominal(clock), tpdu -> {
            decodes[0]++;
            return "UNDERTEXT-MARKER-TEXT";
        }, null);
        assertEquals(Verdict.BLOCK, e.headFilter(ClassId.FLASH, null, 0, false, new byte[] {1, 2}).action());
        assertEquals(1, decodes[0]);
        // No text rule matched: the provider is never consulted.
        Enforcer plain = new Enforcer(() -> engine, nominal(clock), tpdu -> {
            throw new IllegalStateException("must not be called");
        }, null);
        assertEquals(Verdict.ALLOW, plain.headFilter(ClassId.DATA_PORT, null, 0, false, null).action());
    }

    @Test
    void E07_T08_stateChangeNotifiesTheListener() {
        FixedClock clock = new FixedClock();
        FirewallState state = new FirewallState(clock);
        int[] changes = {0};
        state.setListener(() -> changes[0]++);
        state.setEnabled(true);
        state.setSafeMode(true);
        state.setEnabled(false);
        assertEquals(3, changes[0]);
        state.setEnabled(false); // unchanged: no notification
        assertEquals(3, changes[0]);
    }

    @Test
    void E07_T08_emergencyEndStampsTheGraceClock() {
        FixedClock clock = new FixedClock();
        FirewallState state = new FirewallState(clock);
        assertEquals(-1L, state.emergencyCallEndedAtMs());
        state.setEmergencyCallActive(true);
        clock.ms = 5_000L;
        state.setEmergencyCallActive(false);
        assertEquals(5_000L, state.emergencyCallEndedAtMs());
    }

    @Test
    void QUA_07_releaseEligibilityFollowsAppendixD() {
        assertTrue(ReleaseEligibility.releasable(ClassId.WAP_CP));
        assertTrue(ReleaseEligibility.releasable(ClassId.DATA_PORT));
        assertTrue(ReleaseEligibility.releasable(ClassId.NORMAL));
        assertTrue(ReleaseEligibility.releasable(ClassId.FLASH));
        assertFalse(ReleaseEligibility.releasable(ClassId.SIM_DD));
        assertFalse(ReleaseEligibility.releasable(ClassId.CB));
        assertFalse(ReleaseEligibility.releasable(ClassId.SILENT));
        assertTrue(ReleaseEligibility.releasable(ClassId.MWI), "MWI can be released");
        assertFalse(ReleaseEligibility.releasable(null));
        assertFalse(ReleaseEligibility.releasable(ClassId.MALFORMED_SMS), "RULES-X8: a crash PDU is never re-injected");
        assertFalse(ReleaseEligibility.releasable(ClassId.WAP_UNREADABLE));
        assertTrue(ReleaseEligibility.releasableFormat("3gpp"));
        assertFalse(ReleaseEligibility.releasableFormat("cb"));
    }

    @Test
    void CL_05_everyClassHasOneEnforcementPointThatFitsTheMatrix() {
        for (ClassId c : ClassId.values()) {
            if (c.wire().startsWith("CL-MO-")) continue; // outbound: HK-19/HK-20, not a verdict point
            int points = (EnforcementPoints.atHeadFilter(c) ? 1 : 0) + (AppendixD.atWap(c) ? 1 : 0)
                    + (AppendixD.atUsimDataDownload(c) ? 1 : 0)
                    + (AppendixD.atCellBroadcast(c) ? 1 : 0);
            // The consumer classes and CL-OEM have no enforcement point of their own.
            boolean none = AppendixD.hasNoEnforcementPoint(c) || dev.nightjar.rules.EnforcementMatrix.noOwnEnforcementPoint(c);
            assertEquals(none ? 0 : 1, points, c.wire() + ": enforcement points");
            java.util.Set<Verdict> actions = dev.nightjar.rules.EnforcementMatrix.allowedClassActions(c);
            if (none) {
                assertFalse(actions.contains(Verdict.QUARANTINE) || actions.contains(Verdict.BLOCK),
                        c.wire() + ": hold or block without an enforcement point");
            }
            if (ReleaseEligibility.releasable(c)) {
                assertTrue(dev.nightjar.rules.EnforcementMatrix.allowedRuleActions(c, true)
                        .contains(Verdict.QUARANTINE), c.wire() + ": releasable but cannot be held");
            }
        }
        for (ClassId c : List.of(ClassId.EMPTY, ClassId.BINARY, ClassId.MALFORMED_SMS, ClassId.UNREADABLE_SMS,
                ClassId.ME_DEPERSO, ClassId.LOC_SUPL, ClassId.SMART)) {
            assertTrue(EnforcementPoints.atHeadFilter(c), c.wire());
            // RULES-X8: a crash PDU is enforced at the head filter but never re-injected (EnforcementMatrix.releasable).
            assertEquals(c != ClassId.MALFORMED_SMS, ReleaseEligibility.releasable(c), c.wire());
        }
        for (ClassId c : List.of(ClassId.WAP_CO, ClassId.WAP_DS, ClassId.WAP_EMN, ClassId.WAP_DRM,
                ClassId.WAP_VCARD, ClassId.WAP_IMPS, ClassId.WAP_UNREADABLE)) {
            assertTrue(AppendixD.atWap(c), c.wire());
        }
        assertFalse(ReleaseEligibility.releasable(ClassId.WAP_UNREADABLE));
        assertTrue(AppendixD.hasNoEnforcementPoint(ClassId.MULTIPART_INCOMPLETE));
    }

    @Test
    void AppendixD_enforcementPointsDoNotOverlap() {
        assertTrue(EnforcementPoints.atHeadFilter(ClassId.DATA_PORT));
        assertTrue(EnforcementPoints.atHeadFilter(ClassId.FLASH));
        assertTrue(EnforcementPoints.atHeadFilter(ClassId.INJECTED));
        assertTrue(EnforcementPoints.atHeadFilter(ClassId.NORMAL));
        assertFalse(EnforcementPoints.atHeadFilter(ClassId.WAP_CP));
        assertTrue(AppendixD.atWap(ClassId.WAP_CP));
        assertFalse(AppendixD.atWap(ClassId.FLASH));
        assertTrue(AppendixD.atUsimDataDownload(ClassId.SIM_DD));
        assertTrue(AppendixD.atCellBroadcast(ClassId.CB));
        assertTrue(AppendixD.hasNoEnforcementPoint(ClassId.SILENT));
        assertTrue(AppendixD.hasNoEnforcementPoint(ClassId.STATUS));
        assertTrue(AppendixD.hasNoEnforcementPoint(ClassId.SIM_STORED));
    }

    @Test
    void E07_T03_skipResultIsNeverMinusOne() {
        assertThrows(IllegalArgumentException.class, () -> HookDecision.skip(-1, VerdictRecord.allow(), ClassId.FLASH));
        HookDecision skip = HookDecision.skip(1, VerdictRecord.allow(), ClassId.WAP_CP);
        assertTrue(skip.skip());
        assertEquals(1, skip.skipResult());
        assertEquals(WapPush.RESULT_CONSUMED, AppendixD.wapSkipResult(
                new VerdictRecord(Verdict.BLOCK, -1, null, null, null)));
        assertEquals(WapPush.RESULT_BROADCAST, AppendixD.wapSkipResult(VerdictRecord.allow()));
    }

    @Test
    void AppendixF_canaryBlockIsItsOwnReason() {
        VerdictRecord canary = VerdictRecord.canaryBlock();
        assertEquals(Verdict.BLOCK, canary.action());
        assertEquals(VerdictRecord.CANARY, canary.reasonWire());
        assertTrue(canary.traceDetail().contains("clamp=CANARY"));
        assertEquals("{\"action\":\"BLOCK\",\"clampReason\":\"CANARY\"}", AppendixD.verdictJson(canary));
        assertNull(canary.clampReason());
    }

    @Test
    void AppendixD_matrixRejectsBlockForSilentAndStatus() {
        FixedClock clock = new FixedClock();
        Enforcer e = enforcer(config(Map.of(ClassId.SILENT, Verdict.BLOCK), List.of()), nominal(clock));
        VerdictRecord r = e.headFilter(ClassId.SILENT, null, 0, false, null);
        assertEquals(Verdict.ALLOW, r.action());
        assertEquals(ClampReason.ACTION_NOT_ALLOWED, r.clampReason());
    }

    /** A minimal SMS-DELIVER radio PDU from {@code +919000000000}. */
    /** Facts of a synthetic radio PDU (SMSC prefix, then SMS-DELIVER) from +919000000000. */
    private static dev.nightjar.payload.capture.PduFacts radioFacts() {
        byte[] radio = new byte[] {
            0x07, (byte) 0x91, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xF0, // SMSC +00000000000
            0x04, // SMS-DELIVER
            0x0C, (byte) 0x91, 0x19, 0x09, 0x00, 0x00, 0x00, 0x00, // OA +919000000000
            0x00, // PID
            0x00, // DCS
            0x42, 0x10, 0x10, 0x00, 0x00, 0x00, 0x00, // SCTS
            0x00, // UDL
        };
        return dev.nightjar.payload.capture.PduFacts.parseRadio(radio);
    }
}
