package dev.nightjar.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Subtype;
import dev.nightjar.contract.Verdict;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Every class has an {@link EnforcementMatrix} entry, and the Recommended preset gives the default action of each
 * class. The expected table here is a separate copy, so a change
 * in {@link Presets} alone fails the test. The Strict and Quarantine columns are pinned in {@link PresetTableTest}.
 */
class EnforcementCoverageTest {
    private static final Verdict A = Verdict.ALLOW;
    private static final Verdict N = Verdict.NOTIFY;
    private static final Verdict Q = Verdict.QUARANTINE;
    private static final Verdict B = Verdict.BLOCK;

    /**
     * Recommended class action per class (the "Recommended (default)" column of the preset table; Allow = ALLOW,
     * Hold = QUARANTINE, Block = BLOCK, Notify = NOTIFY). CL-MALFORMED-SMS is BLOCK: it cannot be released.
     */
    private static final Map<ClassId, Verdict> PLAN_8_4 = new EnumMap<>(ClassId.class);

    static {
        for (ClassId c : ClassId.values()) PLAN_8_4.put(c, A);
        for (ClassId c : List.of(ClassId.FLASH, ClassId.REPLACE, ClassId.WAP_SISL, ClassId.WAP_OTHER,
                ClassId.INJECTED, ClassId.RETURN_CALL, ClassId.ME_DEPERSO, ClassId.TELEMATIC, ClassId.BINARY,
                ClassId.COMPRESSED, ClassId.SMART, ClassId.WAP_CO, ClassId.WAP_DS, ClassId.WAP_VCARD,
                ClassId.WAP_IMPS, ClassId.UNREADABLE_SMS)) {
            PLAN_8_4.put(c, Q);
        }
        for (ClassId c : List.of(ClassId.WAP_CP, ClassId.WAP_DM, ClassId.WAP_SUPL, ClassId.LOC_SUPL, ClassId.SIM_DD,
                ClassId.ME_DOWNLOAD, ClassId.EMPTY, ClassId.WAP_UNREADABLE, ClassId.MALFORMED_SMS, ClassId.CB_SIM)) {
            PLAN_8_4.put(c, B);
        }
        for (ClassId c : List.of(ClassId.SILENT, ClassId.MULTIPART_INCOMPLETE, ClassId.MO_STK)) PLAN_8_4.put(c, N);
    }

    /** Subtypes whose Recommended action differs from the class row above (included). */
    private static final List<Object[]> PLAN_8_4_SUBTYPES = List.of(
            new Object[] {ClassId.FLASH, Subtype.Flash.CLASS0_EMPTY, Q},
            new Object[] {ClassId.MWI, Subtype.Mwi.DISCARD_SILENT, B},
            new Object[] {ClassId.DATA_PORT, Subtype.DataPort.UNKNOWN, N},
            new Object[] {ClassId.DATA_PORT, Subtype.DataPort.INTERNET_ACCESS_CONFIG, N},
            new Object[] {ClassId.DATA_PORT, Subtype.DataPort.SYNCML_OTA_CONFIG, N},
            new Object[] {ClassId.DATA_PORT, Subtype.DataPort.WAP_OTA_CONFIG, N},
            new Object[] {ClassId.DATA_PORT, Subtype.DataPort.PORT0, Q},
            new Object[] {ClassId.WAP_SISL, Subtype.WapSisl.SL, B});

    private static Verdict evaluate(RulesConfig config, ClassId c, String subtype) {
        Decision d = new Decision();
        RuleEngine.compile(config).evaluate(new MatchInput().classId(c).subtype(subtype), Guards.NOMINAL, d);
        assertEquals(null, d.clampReason(), c + "/" + subtype + ": clamped");
        return d.action();
    }

    @Test
    void CL_05_everyClassHasAMatrixEntryThatAllowsAllow() {
        for (ClassId c : ClassId.values()) {
            Set<Verdict> actions = EnforcementMatrix.allowedClassActions(c);
            assertTrue(actions.contains(A), c + ": ALLOW must always be allowed");
            assertTrue(PLAN_8_4.containsKey(c), c + ": no §8.4 row in this test");
        }
    }

    @Test
    void CL_05_newClassesHaveTheirMatrixEntry() {
        Set<Verdict> all = EnumSet.allOf(Verdict.class);
        for (ClassId c : List.of(ClassId.RETURN_CALL, ClassId.ME_DOWNLOAD, ClassId.ME_DEPERSO, ClassId.CLASS3,
                ClassId.TELEMATIC, ClassId.EMPTY, ClassId.BINARY, ClassId.COMPRESSED, ClassId.SMART,
                ClassId.LOC_SUPL, ClassId.WAP_CO, ClassId.WAP_DS, ClassId.WAP_EMN, ClassId.WAP_DRM, ClassId.WAP_VCARD,
                ClassId.WAP_IMPS)) {
            assertEquals(all, EnforcementMatrix.allowedClassActions(c), c.wire());
        }
        assertEquals(EnumSet.of(A, N), EnforcementMatrix.allowedClassActions(ClassId.MULTIPART_INCOMPLETE));
        assertEquals(EnumSet.of(A, N, B), EnforcementMatrix.allowedClassActions(ClassId.CB_SIM));
        // RULES-X8: a PDU or push that can crash the phone service is not released again, so no Hold (changed).
        for (ClassId c : List.of(ClassId.MALFORMED_SMS, ClassId.WAP_UNREADABLE)) {
            assertEquals(EnumSet.of(A, N, B), EnforcementMatrix.allowedClassActions(c), c.wire());
            assertEquals(EnumSet.of(A, N, B), EnforcementMatrix.allowedRuleActions(c, true),
                    c.wire());
        }
        // AOSP reads a PDU with the wrong message type as an SMS-DELIVER, so a release works.
        assertEquals(all, EnforcementMatrix.allowedClassActions(ClassId.UNREADABLE_SMS));
        assertEquals(all, EnforcementMatrix.allowedRuleActions(ClassId.UNREADABLE_SMS, true));
        assertTrue(EnforcementMatrix.releasable(ClassId.UNREADABLE_SMS));
        assertEquals(B, Presets.classActions(Preset.STRICT).get(ClassId.UNREADABLE_SMS));
        assertNull(Presets.classActions(Preset.MONITOR).get(ClassId.UNREADABLE_SMS));
    }

    @Test
    void CROSS_19_noHoldWithoutARelease() {
        // A Hold that nothing can release acts as a silent Block. Both rule kinds.
        for (ClassId c : ClassId.values()) {
            boolean hold = EnforcementMatrix.allowedClassActions(c).contains(Q)
                    || EnforcementMatrix.allowedRuleActions(c, false).contains(Q)
                    || EnforcementMatrix.allowedRuleActions(c, true).contains(Q);
            if (hold) assertTrue(EnforcementMatrix.releasable(c), c.wire() + " can be held but not released");
        }
        // No enforcement point sees the consumer classes or CL-OEM, so no Hold or Block, not even in a rule
        // with a sender, port or text condition.
        for (ClassId c : List.of(ClassId.VVM, ClassId.MISSED_CALL, ClassId.APP_TOKEN, ClassId.CARRIER,
                ClassId.RCS_PROV, ClassId.OEM)) {
            assertEquals(EnumSet.of(A, N), EnforcementMatrix.allowedClassActions(c), c.wire());
            assertEquals(EnumSet.of(A, N), EnforcementMatrix.allowedRuleActions(c, false), c.wire());
            assertEquals(EnumSet.of(A, N), EnforcementMatrix.allowedRuleActions(c, true), c.wire());
        }
        assertEquals(EnumSet.of(A, N, Q, B), EnforcementMatrix.allowedRuleActions(ClassId.NORMAL, true));
        assertEquals(EnumSet.of(A, N), EnforcementMatrix.allowedRuleActions(ClassId.NORMAL, false));
        for (ClassId c : List.of(ClassId.MALFORMED_SMS, ClassId.WAP_UNREADABLE, ClassId.SIM_DD,
                ClassId.CB, ClassId.CB_SIM, ClassId.MULTIPART_INCOMPLETE, ClassId.MO_VISIBLE)) {
            assertFalse(EnforcementMatrix.releasable(c), c.wire());
        }
        // Every class that Recommended or Strict holds can be released.
        for (ClassId c : ClassId.values()) {
            if (Presets.classActions(Preset.STRICT).get(c) == Q) assertTrue(EnforcementMatrix.releasable(c), c.wire());
        }
        assertFalse(EnforcementMatrix.releasable(null));
    }

    @Test
    void CL_05_guardsAreKept() {
        // Guards that must not widen: no hold for SILENT, SIM-DD, CB; no class block for SILENT,
        // outbound or the fixed-ALLOW classes. MWI can be held.
        assertEquals(EnumSet.of(A, N), EnforcementMatrix.allowedClassActions(ClassId.SILENT));
        assertEquals(EnumSet.of(A, N, B), EnforcementMatrix.allowedClassActions(ClassId.SIM_DD));
        assertEquals(EnumSet.of(A, N, Q, B),
                EnforcementMatrix.allowedClassActions(ClassId.MWI));
        assertTrue(EnforcementMatrix.releasable(ClassId.MWI));
        assertEquals(EnumSet.of(A, N, B), EnforcementMatrix.allowedClassActions(ClassId.CB));
        assertEquals(EnumSet.of(A, N), EnforcementMatrix.allowedClassActions(ClassId.MO_STK));
        for (ClassId c : List.of(ClassId.NORMAL, ClassId.SIM_STORED, ClassId.STATUS)) {
            assertEquals(EnumSet.of(A), EnforcementMatrix.allowedClassActions(c), c.wire());
        }
    }

    @Test
    void CL_05_recommendedIsInTheAllowedSetAndEqualsPlan84() {
        for (ClassId c : ClassId.values()) {
            Verdict rec = Presets.recommendedClassAction(c);
            assertTrue(EnforcementMatrix.allowedClassActions(c).contains(rec),
                    c + ": Recommended " + rec + " is not allowed");
            assertEquals(PLAN_8_4.get(c), rec, c + ": Recommended class action");
            assertEquals(PLAN_8_4.get(c), Presets.recommendedAction(c, null), c + ": no subtype");
        }
        for (Rule r : List.copyOf(concat(Presets.subtypeRules(Preset.RECOMMENDED),
                Presets.subtypeRules(Preset.STRICT)))) {
            assertEquals(RuleOrigin.PRESET, r.origin());
            assertNotNull(r.when().subtypes(), r.name());
            assertFalse(r.when().hasSenderPortOrText(), r.name());
            for (ClassId c : r.when().classes()) {
                assertTrue(EnforcementMatrix.allowedRuleActions(c, false).contains(r.action()),
                        r.name() + ": " + r.action() + " not allowed for " + c);
            }
        }
        for (Object[] row : PLAN_8_4_SUBTYPES) {
            assertEquals(row[2], Presets.recommendedAction((ClassId) row[0], (String) row[1]), row[0] + "/" + row[1]);
        }
    }

    @Test
    void CL_05_recommendedSnapshotGivesPlan84ThroughTheEngine() {
        RulesConfig rec = Presets.apply(Preset.RECOMMENDED, RulesConfig.DEFAULT);
        assertEquals(List.of(), RuleValidator.validate(rec));
        for (ClassId c : ClassId.values()) {
            assertEquals(PLAN_8_4.get(c), rec.classAction(c), c + ": snapshot class action");
            assertEquals(PLAN_8_4.get(c), evaluate(rec, c, null), c + ": engine, no subtype");
            for (String st : Subtype.known(c)) {
                assertEquals(Presets.recommendedAction(c, st), evaluate(rec, c, st), c + "/" + st + ": engine");
            }
        }
        for (Object[] row : PLAN_8_4_SUBTYPES) {
            assertEquals(row[2], evaluate(rec, (ClassId) row[0], (String) row[1]), row[0] + "/" + row[1]);
        }
    }

    @Test
    void CL_05_otherPresetsCoverEveryClass() {
        RulesConfig monitor = Presets.apply(Preset.MONITOR, RulesConfig.DEFAULT);
        RulesConfig strict = Presets.apply(Preset.STRICT, RulesConfig.DEFAULT);
        assertEquals(List.of(), RuleValidator.validate(strict));
        for (ClassId c : ClassId.values()) {
            assertEquals(A, evaluate(monitor, c, null), c + ": Monitor records and allows");
            Verdict s = strict.classAction(c);
            assertTrue(EnforcementMatrix.allowedClassActions(c).contains(s), c + ": Strict " + s);
            assertTrue(strength(s) >= strength(Presets.recommendedClassAction(c)),
                    c + ": Strict is weaker than Recommended");
        }
        RulesConfig rec = Presets.apply(Preset.RECOMMENDED, RulesConfig.DEFAULT);
        for (ClassId c : ClassId.values()) {
            for (String st : Subtype.known(c)) {
                assertTrue(strength(evaluate(strict, c, st)) >= strength(evaluate(rec, c, st)),
                        c + "/" + st + ": Strict is weaker than Recommended");
            }
        }
        assertEquals(B, evaluate(strict, ClassId.WAP_SISL, Subtype.WapSisl.SL));
        assertEquals(B, evaluate(strict, ClassId.WAP_SISL, Subtype.WapSisl.SI));
        // Strict holds only UNKNOWN (the carrier voicemail SMS) and blocks the other data messages.
        Rule hold = strict.rules().stream().filter(r -> r.id() == Presets.P_PORT_HOLD_ID).findFirst().orElseThrow();
        assertEquals(Q, hold.action());
        assertEquals(java.util.Set.of(Subtype.DataPort.UNKNOWN), hold.when().subtypes());
        assertTrue(strict.rules().stream().noneMatch(r -> r.id() == Presets.P_PORT_NOTIFY_ID));
        assertTrue(strict.rules().stream().noneMatch(r -> r.id() == Presets.P_STR_1_ID), "P-STR-1 is retired");
    }

    @Test
    void UX2_FW_10_strictHoldsTheCarrierVoicemailPortAndBlocksTheRest() {
        // The head filter gives CL-DATA-PORT/UNKNOWN for a carrier VVM SMS. From any sender it must
        // be held (it can be released), not blocked.
        RulesConfig strict =
                Presets.apply(Preset.STRICT, RulesConfig.DEFAULT).withKnownOriginators(List.of("+15550100"), List.of());
        RuleEngine engine = RuleEngine.compile(strict);
        Decision d = new Decision();
        MatchInput in = new MatchInput();
        for (String sender : List.of("+15550100", "+15550199")) {
            engine.evaluate(in.reset().classId(ClassId.DATA_PORT).subtype(Subtype.DataPort.UNKNOWN).originator(sender),
                    Guards.NOMINAL, d);
            assertEquals(Q, d.action(), "UNKNOWN from " + (sender.endsWith("100") ? "known" : "unknown"));
            assertEquals(Presets.P_PORT_HOLD_ID, d.ruleId());
        }
        // Every other data message is blocked by the class action, also from a known sender.
        for (String st : List.of(Subtype.DataPort.PORT0, Subtype.DataPort.SERVICE_CARD,
                Subtype.DataPort.INTERNET_ACCESS_CONFIG, Subtype.DataPort.SYNCML_OTA_CONFIG,
                Subtype.DataPort.WAP_OTA_CONFIG)) {
            for (String sender : List.of("+15550100", "+15550199")) {
                engine.evaluate(in.reset().classId(ClassId.DATA_PORT).subtype(st).originator(sender), Guards.NOMINAL,
                        d);
                assertEquals(B, d.action(), st);
                assertFalse(d.hasRule(), st);
            }
        }
    }

    @Test
    void UX2_FW_02_anAbsentUnreadableEntryHoldsOutsideMonitor() throws RulesFormatException {
        // A snapshot from before CL-UNREADABLE-SMS has no entry for it: Hold, the default. Monitor holds
        // nothing.
        for (Preset p : List.of(Preset.CUSTOM, Preset.RECOMMENDED, Preset.STRICT)) {
            RulesConfig old = new RulesConfig(dev.nightjar.contract.Schemas.RULES, 1, p, Map.of(ClassId.FLASH, B),
                    List.of(), List.of(), List.of(), 5);
            assertEquals(Q, old.classAction(ClassId.UNREADABLE_SMS), p.toString());
            assertEquals(Q, evaluate(old, ClassId.UNREADABLE_SMS, null), p.toString());
            assertEquals(A, evaluate(old, ClassId.NORMAL, null), p + ": other absent classes stay ALLOW");
        }
        RulesConfig monitor = Presets.apply(Preset.MONITOR, RulesConfig.DEFAULT);
        assertEquals(A, monitor.classAction(ClassId.UNREADABLE_SMS));
        assertEquals(A, evaluate(monitor, ClassId.UNREADABLE_SMS, null));

        // ALLOW stays in the map, so the user can allow it. Hold is the absent action under Custom.
        RulesConfig allowed = RulesConfig.DEFAULT.withClassAction(ClassId.UNREADABLE_SMS, A);
        assertEquals(A, allowed.classActions().get(ClassId.UNREADABLE_SMS));
        assertEquals(A, evaluate(allowed, ClassId.UNREADABLE_SMS, null));
        RulesConfig held = allowed.withClassAction(ClassId.UNREADABLE_SMS, Q);
        assertFalse(held.classActions().containsKey(ClassId.UNREADABLE_SMS));
        assertEquals(Q, evaluate(held, ClassId.UNREADABLE_SMS, null));
        assertEquals(B, evaluate(held.withClassAction(ClassId.UNREADABLE_SMS, B), ClassId.UNREADABLE_SMS, null));
        // Other classes keep the old rule: ALLOW is removed from the map.
        assertFalse(allowed.withClassAction(ClassId.FLASH, A).classActions().containsKey(ClassId.FLASH));

        // The ALLOW entry survives the JSON round trip that carries the snapshot to the payload.
        RulesConfig back = RulesJson.parse(RulesJson.serialize(allowed));
        assertEquals(A, back.classAction(ClassId.UNREADABLE_SMS));
    }

    private static List<Rule> concat(List<Rule> a, List<Rule> b) {
        List<Rule> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static int strength(Verdict v) {
        return v.ordinal(); // ALLOW < NOTIFY < QUARANTINE < BLOCK (EnforcementMatrix checks this order)
    }
}
