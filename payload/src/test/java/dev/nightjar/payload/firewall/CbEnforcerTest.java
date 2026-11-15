package dev.nightjar.payload.firewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Schemas;
import dev.nightjar.contract.Verdict;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.pduparse.CbCategories;
import dev.nightjar.rules.EnforcementMatrix;
import dev.nightjar.rules.Preset;
import dev.nightjar.rules.RuleEngine;
import dev.nightjar.rules.RulesConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** HK-17 enforcement per cell broadcast class. CL-CB-SIM has its own action; the emergency check stays first. */
class CbEnforcerTest {

    private static final class FixedClock implements Clock {
        @Override
        public long elapsedNs() {
            return 1_000_000L;
        }

        @Override
        public long wallMs() {
            return 1_762_330_000_000L;
        }
    }

    private static CbEnforcer enforcer(Map<ClassId, Verdict> actions) {
        RuleEngine engine = RuleEngine.compile(
                new RulesConfig(Schemas.RULES, 1, Preset.CUSTOM, actions, List.of(), List.of(), List.of(), 5));
        FirewallState state = new FirewallState(new FixedClock());
        state.setAvailable(true);
        state.setEnabled(true);
        return new CbEnforcer(new Enforcer(() -> engine, state, TextSource.NONE, null));
    }

    @Test
    void simDownloadIsDecidedAsCbSim() {
        CbEnforcer cb = enforcer(Map.of());
        assertEquals(ClassId.CB_SIM, cb.decide(0x1000, 0, false).classId());
        assertEquals(ClassId.CB_SIM, cb.decide(0x10FF, 0, false).classId());
        assertEquals(ClassId.CB, cb.decide(221, 0, false).classId());
        assertEquals(ClassId.CB, cb.decide(50, 0, false).classId());
    }

    @Test
    void cbActionDoesNotApplyToTheSimRange() {
        CbEnforcer cb = enforcer(Map.of(ClassId.CB, Verdict.BLOCK));
        assertTrue(cb.decide(221, 0, false).skip());
        assertFalse(cb.decide(0x1000, 0, false).skip(), "CL-CB-SIM has no action, so it is allowed");
    }

    @Test
    void cbSimActionFollowsTheEnforcementMatrix() {
        // Sets the CL-CB-SIM actions. The decision must follow the matrix, whatever it allows.
        int mask = EnforcementMatrix.classActionMask(ClassId.CB_SIM);
        CbEnforcer block = enforcer(Map.of(ClassId.CB_SIM, Verdict.BLOCK));
        assertEquals(EnforcementMatrix.allows(mask, Verdict.BLOCK), block.decide(0x1000, 0, false).skip());
        CbEnforcer hold = enforcer(Map.of(ClassId.CB_SIM, Verdict.QUARANTINE));
        assertEquals(EnforcementMatrix.allows(mask, Verdict.QUARANTINE), hold.decide(0x1000, 0, false).skip());
        assertFalse(block.decide(221, 0, false).skip(), "the CL-CB-SIM action does not apply to a channel");
    }

    @Test
    void emergencyIsNeverSkippedWhateverTheActions() {
        CbEnforcer cb = enforcer(Map.of(ClassId.CB, Verdict.BLOCK, ClassId.CB_SIM, Verdict.BLOCK));
        for (int id : new int[] {4352, 4355, 4356, 4359, 4370, 4382, 4396, 4398, 4399}) {
            HookDecision d = cb.decide(id, 0, false);
            assertFalse(d.skip(), "emergency " + id);
            assertEquals(ClassId.CB, d.classId());
        }
    }

    @Test
    void noSnapshotAllowsTheSimRange() {
        FirewallState state = new FirewallState(new FixedClock());
        state.setAvailable(true);
        state.setEnabled(true);
        CbEnforcer cb = new CbEnforcer(new Enforcer(() -> null, state, TextSource.NONE, null));
        assertFalse(cb.decide(0x1000, 0, false).skip());
        assertFalse(cb.decide(221, 0, false).skip());
    }

    @Test
    void cbSimIsEnforcedAtTheCellBroadcastHookAndIsNotReleasable() {
        assertTrue(AppendixD.atCellBroadcast(ClassId.CB_SIM));
        assertFalse(AppendixD.hasNoEnforcementPoint(ClassId.CB_SIM));
        assertFalse(ReleaseEligibility.releasable(ClassId.CB_SIM));
    }

    @Test
    void cbClassFollowsTheSharedTable() {
        assertEquals(ClassId.CB_SIM, CbCategories.classOf(0x1000));
        assertEquals(ClassId.CB, CbCategories.classOf(4370));
        assertEquals(ClassId.CB, CbCategories.classOf(221));
    }

    /**
     * The payload gives the engine only the CB message identifier (Enforcer.cellBroadcast); the engine
     * derives the emergency flag from it. This bypasses the CbEnforcer emergency check to prove the second guard.
     */
    @Test
    void theEngineDerivesTheEmergencyClampFromTheMessageIdentifier() {
        RuleEngine engine = RuleEngine.compile(new RulesConfig(Schemas.RULES, 1, Preset.CUSTOM,
                Map.of(ClassId.CB, Verdict.BLOCK), List.of(), List.of(), List.of(), 5));
        FirewallState state = new FirewallState(new FixedClock());
        state.setAvailable(true);
        state.setEnabled(true);
        Enforcer e = new Enforcer(() -> engine, state, TextSource.NONE, null);
        VerdictRecord emergency = e.cellBroadcast(4370, 0, false);
        assertEquals(Verdict.ALLOW, emergency.action());
        assertEquals(dev.nightjar.rules.ClampReason.EMERGENCY_CB, emergency.clampReason());
        assertEquals(Verdict.BLOCK, e.cellBroadcast(221, 0, false).action());
    }
}
