package dev.nightjar.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Direction;
import dev.nightjar.contract.Schemas;
import dev.nightjar.contract.Verdict;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Rule order, matching and the five clamps. */
class RuleEngineTest {
    private static RulesConfig config(Map<ClassId, Verdict> actions, List<Rule> rules) {
        return new RulesConfig(Schemas.RULES, 0, Preset.CUSTOM, actions, rules, List.of(), List.of(), 5);
    }

    private static Rule rule(long id, int priority, Verdict action, Conditions when) {
        return new Rule(id, priority, true, RuleOrigin.USER, "r" + id, action, when);
    }

    private static MatchInput input(ClassId c) {
        return new MatchInput().classId(c);
    }

    @Test
    void FW_01_firstMatchingRuleWins() {
        RulesConfig c = config(Map.of(), List.of(
                rule(1, 1, Verdict.NOTIFY, Conditions.builder().classes(ClassId.WAP_CP).build()),
                rule(2, 0, Verdict.BLOCK, Conditions.builder().classes(ClassId.WAP_CP).build())));
        Decision d = new Decision();
        RuleEngine.compile(c).evaluate(input(ClassId.WAP_CP), Guards.NOMINAL, d);
        assertEquals(Verdict.BLOCK, d.action());
        assertEquals(2, d.ruleId());
        assertEquals(Decision.Source.RULE, d.source());
    }

    @Test
    void FW_06_classActionAppliesWhenNoRuleMatches() {
        RulesConfig c = config(Map.of(ClassId.WAP_CP, Verdict.QUARANTINE), List.of());
        Decision d = new Decision();
        RuleEngine.compile(c).evaluate(input(ClassId.WAP_CP), Guards.NOMINAL, d);
        assertEquals(Verdict.QUARANTINE, d.action());
        assertEquals(Decision.Source.CLASS_ACTION, d.source());
        assertFalse(d.isClamped());
    }

    @Test
    void FW_06_defaultAllowWhenClassActionAbsent() {
        Decision d = new Decision();
        RuleEngine.compile(RulesConfig.DEFAULT).evaluate(input(ClassId.WAP_CP), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(Decision.Source.DEFAULT, d.source());
    }

    @Test
    void FW_06_actionNotAllowedForClassIsClamped() {
        RulesConfig c = config(Map.of(ClassId.SILENT, Verdict.BLOCK), List.of());
        Decision d = new Decision();
        RuleEngine.compile(c).evaluate(input(ClassId.SILENT), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.ACTION_NOT_ALLOWED, d.clampReason());
        assertEquals(Verdict.BLOCK, d.clampedFrom());
    }

    @Test
    void G_01_emergencyCellBroadcastNeverHeldOrAlerted() {
        RulesConfig c = config(Map.of(ClassId.CB, Verdict.BLOCK), List.of());
        MatchInput in = input(ClassId.CB).cbEmergency(true);
        Decision d = new Decision();
        RuleEngine.compile(c).evaluate(in, Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.EMERGENCY_CB, d.clampReason());
    }

    @Test
    void G_04_suplClampedDuringEmergencyAndGrace() {
        RulesConfig c = config(Map.of(ClassId.WAP_SUPL, Verdict.QUARANTINE), List.of());
        RuleEngine e = RuleEngine.compile(c);
        Decision d = new Decision();

        e.evaluate(input(ClassId.WAP_SUPL), Guards.NOMINAL.withEmergency(true, -1, 0), d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.SUPL_EMERGENCY, d.clampReason());

        // 1 s after the call ended, still inside the 5-minute grace.
        e.evaluate(input(ClassId.WAP_SUPL), Guards.NOMINAL.withEmergency(false, 1_000, 2_000), d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.SUPL_EMERGENCY, d.clampReason());

        // 6 minutes after the call ended: the rule applies again.
        e.evaluate(input(ClassId.WAP_SUPL), Guards.NOMINAL.withEmergency(false, 0, 6 * 60_000), d);
        assertEquals(Verdict.QUARANTINE, d.action());
        assertFalse(d.isClamped());
    }

    @Test
    void FW_12_safeModeClampsBlockAndQuarantineButKeepsNotify() {
        RuleEngine block = RuleEngine.compile(config(Map.of(ClassId.WAP_CP, Verdict.BLOCK), List.of()));
        Decision d = new Decision();
        block.evaluate(input(ClassId.WAP_CP), Guards.NOMINAL.withSafeMode(true), d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.SAFE_MODE, d.clampReason());

        RuleEngine notify = RuleEngine.compile(config(Map.of(ClassId.WAP_CP, Verdict.NOTIFY), List.of()));
        notify.evaluate(input(ClassId.WAP_CP), Guards.NOMINAL.withSafeMode(true), d);
        assertEquals(Verdict.NOTIFY, d.action());
        assertFalse(d.isClamped());
    }

    @Test
    void FW_12_firewallOffClampsBlockButKeepsNotify() {
        RuleEngine block = RuleEngine.compile(config(Map.of(ClassId.WAP_CP, Verdict.BLOCK), List.of()));
        Decision d = new Decision();
        block.evaluate(input(ClassId.WAP_CP), Guards.NOMINAL.withFirewall(false, true), d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.FIREWALL_OFF, d.clampReason());

        block.evaluate(input(ClassId.WAP_CP), Guards.NOMINAL.withFirewall(true, false), d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.FIREWALL_OFF, d.clampReason());

        RuleEngine notify = RuleEngine.compile(config(Map.of(ClassId.WAP_CP, Verdict.NOTIFY), List.of()));
        notify.evaluate(input(ClassId.WAP_CP), Guards.NOMINAL.withFirewall(false, false), d);
        assertEquals(Verdict.NOTIFY, d.action());
        assertFalse(d.isClamped());
    }

    @Test
    void G_05_blockingAllNormalWithoutConditionIsClamped() {
        RulesConfig c = config(Map.of(), List.of(rule(7, 0, Verdict.BLOCK,
                Conditions.builder().classes(ClassId.NORMAL).build())));
        Decision d = new Decision();
        RuleEngine.compile(c).evaluate(input(ClassId.NORMAL), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.BLOCKS_ALL_NORMAL, d.clampReason());
    }

    @Test
    void G_05_conditionsThatNarrowNothingAreSkippedOrClamped() {
        Decision d = new Decision();
        // "00", "+" and " " normalise to empty: a PREFIX of every number. The rule is skipped (fail open).
        for (String value : List.of("00", "+", " ")) {
            RuleEngine e = RuleEngine.compile(config(Map.of(), List.of(rule(30, 0, Verdict.BLOCK,
                    Conditions.builder().classes(ClassId.NORMAL).originator(StringMatch.prefix(value)).build()))));
            assertEquals(1, e.skippedRules(), value);
            e.evaluate(input(ClassId.NORMAL).originator("+919000000000"), Guards.NOMINAL, d);
            assertEquals(Verdict.ALLOW, d.action(), value);
            assertFalse(d.hasRule(), value);
        }
        // IS_NOT one number holds for almost every sender: it does not unlock BLOCK of CL-NORMAL.
        RuleEngine isNot = RuleEngine.compile(config(Map.of(), List.of(rule(31, 0, Verdict.BLOCK,
                Conditions.builder().classes(ClassId.NORMAL).originator(StringMatch.isNot("+919000000000")).build()))));
        isNot.evaluate(input(ClassId.NORMAL).originator("+918800000000"), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.BLOCKS_ALL_NORMAL, d.clampReason());
        // A port outside 0..65535 would match the messages without a port (destPort -1): skipped.
        RuleEngine badPort = RuleEngine.compile(config(Map.of(), List.of(rule(32, 0, Verdict.BLOCK,
                Conditions.builder().classes(ClassId.NORMAL).port(new PortRange(-1, -1)).build()))));
        assertEquals(1, badPort.skippedRules());
        badPort.evaluate(input(ClassId.NORMAL), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertFalse(d.hasRule());
        // A class-less rule can match CL-NORMAL too (canMatchNormal).
        assertTrue(Conditions.builder().build().canMatchNormal());
        RuleEngine any = RuleEngine.compile(config(Map.of(), List.of(rule(33, 0, Verdict.QUARANTINE,
                Conditions.builder().build()))));
        any.evaluate(input(ClassId.NORMAL), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.BLOCKS_ALL_NORMAL, d.clampReason());
    }

    @Test
    void FW_01_userRuleBeatsPresetRule() {
        // Every USER rule runs before every PRESET rule, whatever the priorities and the file order.
        Rule preset = new Rule(Presets.PRESET_ID_BASE + 100, 0, true, RuleOrigin.PRESET, "p", Verdict.BLOCK,
                Conditions.builder().classes(ClassId.WAP_CP).build());
        Rule user = rule(40, 50, Verdict.ALLOW, Conditions.builder().classes(ClassId.WAP_CP)
                .originator(StringMatch.is("+15550100")).build());
        RuleEngine e = RuleEngine.compile(config(Map.of(), List.of(preset, user)));
        Decision d = new Decision();
        e.evaluate(input(ClassId.WAP_CP).originator("+15550100"), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(40, d.ruleId());
        assertEquals(RuleOrigin.USER, d.ruleOrigin());
        e.evaluate(input(ClassId.WAP_CP).originator("+15550199"), Guards.NOMINAL, d);
        assertEquals(Verdict.BLOCK, d.action());
        assertEquals(RuleOrigin.PRESET, d.ruleOrigin());
    }

    @Test
    void G_01_emergencyFlagClampsWhateverClassWasPredicted() {
        // A wrong class prediction must not take the guard away.
        Decision d = new Decision();
        RuleEngine e = RuleEngine.compile(config(Map.of(ClassId.WAP_CP, Verdict.QUARANTINE,
                ClassId.CB_SIM, Verdict.NOTIFY), List.of()));
        e.evaluate(input(ClassId.WAP_CP).cbEmergency(true), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.EMERGENCY_CB, d.clampReason());
        assertEquals(Verdict.QUARANTINE, d.clampedFrom());
        e.evaluate(input(ClassId.CB_SIM).cbEmergency(true), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.EMERGENCY_CB, d.clampReason());
        e.evaluate(input(ClassId.CB_SIM), Guards.NOMINAL, d);
        assertEquals(Verdict.NOTIFY, d.action());
        assertFalse(d.isClamped());
    }

    @Test
    void A_22_originatorKnownFalseAloneDoesNotNarrow() {
        // "everyone not in the known list" is almost everyone, so it must not unlock BLOCK of CL-NORMAL.
        RulesConfig c = config(Map.of(), List.of(rule(20, 0, Verdict.BLOCK,
                Conditions.builder().classes(ClassId.NORMAL).originatorKnown(Boolean.FALSE).build())));
        Decision d = new Decision();
        RuleEngine.compile(c).evaluate(input(ClassId.NORMAL).originator("+919000000000"), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.BLOCKS_ALL_NORMAL, d.clampReason());
    }

    @Test
    void A_22_originatorKnownTrueNarrows() {
        RulesConfig c = new RulesConfig(Schemas.RULES, 0, Preset.CUSTOM, Map.of(),
                List.of(rule(21, 0, Verdict.BLOCK,
                        Conditions.builder().classes(ClassId.NORMAL).originatorKnown(Boolean.TRUE).build())),
                List.of("+919000000000"), List.of(), 5);
        Decision d = new Decision();
        RuleEngine.compile(c).evaluate(input(ClassId.NORMAL).originator("+919000000000"), Guards.NOMINAL, d);
        assertEquals(Verdict.BLOCK, d.action());
    }

    @Test
    void A_22_fullPortRangeDoesNotNarrowAndNarrowOneDoes() {
        RulesConfig full = config(Map.of(), List.of(rule(22, 0, Verdict.BLOCK,
                Conditions.builder().classes(ClassId.NORMAL).port(new PortRange(0, 65535)).build())));
        Decision d = new Decision();
        RuleEngine.compile(full).evaluate(input(ClassId.NORMAL).destPort(2948), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.BLOCKS_ALL_NORMAL, d.clampReason());

        RulesConfig narrow = config(Map.of(), List.of(rule(23, 0, Verdict.BLOCK,
                Conditions.builder().classes(ClassId.NORMAL).port(new PortRange(2948, 2948)).build())));
        RuleEngine.compile(narrow).evaluate(input(ClassId.NORMAL).destPort(2948), Guards.NOMINAL, d);
        assertEquals(Verdict.BLOCK, d.action());
    }

    @Test
    void A_22_emptyTextContainsDoesNotNarrow() {
        RulesConfig c = config(Map.of(), List.of(rule(24, 0, Verdict.BLOCK,
                Conditions.builder().classes(ClassId.NORMAL).textContains("").build())));
        Decision d = new Decision();
        RuleEngine.compile(c).evaluate(input(ClassId.NORMAL).textProvider(() -> "anything"), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.BLOCKS_ALL_NORMAL, d.clampReason());
    }

    @Test
    void FW_07_senderConditionAllowsBlockingNormal() {
        RulesConfig c = config(Map.of(), List.of(rule(8, 0, Verdict.BLOCK,
                Conditions.builder().classes(ClassId.NORMAL)
                        .originator(StringMatch.is("+91 90000 00000")).build())));
        Decision d = new Decision();
        RuleEngine.compile(c).evaluate(input(ClassId.NORMAL).originator("+919000000000"), Guards.NOMINAL, d);
        assertEquals(Verdict.BLOCK, d.action());

        RuleEngine.compile(c).evaluate(input(ClassId.NORMAL).originator("+919000000001"), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
    }

    @Test
    void FW_07_textIsResolvedOnlyWhenNeeded() {
        AtomicInteger calls = new AtomicInteger();
        RulesConfig c = config(Map.of(), List.of(rule(9, 0, Verdict.QUARANTINE,
                Conditions.builder().classes(ClassId.WAP_CP).textContains("Hello").build())));
        RuleEngine e = RuleEngine.compile(c);
        Decision d = new Decision();

        MatchInput other = input(ClassId.DATA_PORT).textProvider(() -> {
            calls.incrementAndGet();
            return "hello";
        });
        e.evaluate(other, Guards.NOMINAL, d);
        assertEquals(0, calls.get());
        assertFalse(d.textEvaluated());

        MatchInput match = input(ClassId.WAP_CP).textProvider(() -> {
            calls.incrementAndGet();
            return "say HELLO now";
        });
        e.evaluate(match, Guards.NOMINAL, d);
        assertEquals(1, calls.get());
        assertTrue(d.textEvaluated());
        assertEquals(Verdict.QUARANTINE, d.action());
    }

    @Test
    void DQ_04_numberNormalization() {
        assertEquals("919000000000", Numbers.normalize(" +91 90000 00000 "));
        assertEquals("919000000000", Numbers.normalize("0091-9000-000000".replace("-", "")));
        assertEquals("NJTEST", Numbers.normalize("NJTEST"));
        assertEquals("", Numbers.normalize(" + "));
        assertEquals("", Numbers.normalize("00"));
        // Separators go in a number-like value, and stay in an alphanumeric id.
        assertEquals("919000000210", Numbers.normalize("+91 (90000) 002.10"));
        assertEquals("919000000210", Numbers.normalize("+91 90000-00210"));
        assertEquals("919000000210", Numbers.normalize("+91\t90000 00210"));
        assertEquals("VM-AIRTEL", Numbers.normalize("VM-AIRTEL"));
    }

    @Test
    void FW_07_knownOriginatorExactAndPrefix() {
        RulesConfig c = new RulesConfig(Schemas.RULES, 0, Preset.CUSTOM, Map.of(), List.of(
                rule(10, 0, Verdict.QUARANTINE, Conditions.builder().classes(ClassId.WAP_CP)
                        .originatorKnown(Boolean.FALSE).build())),
                List.of("+919000000000"), List.of("+9190"), 5);
        Decision d = new Decision();
        RuleEngine e = RuleEngine.compile(c);

        e.evaluate(input(ClassId.WAP_CP).originator("+919000000000"), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());

        e.evaluate(input(ClassId.WAP_CP).originator("+919000000678"), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());

        e.evaluate(input(ClassId.WAP_CP).originator("+918800000000"), Guards.NOMINAL, d);
        assertEquals(Verdict.QUARANTINE, d.action());
    }

    @Test
    void FW_07_regexIsLinearAndInvalidRegexSkipsTheRule() {
        RulesConfig c = config(Map.of(), List.of(
                rule(11, 0, Verdict.BLOCK, Conditions.builder().classes(ClassId.WAP_CP)
                        .originator(StringMatch.regex("123|456")).build())));
        Decision d = new Decision();
        RuleEngine e = RuleEngine.compile(c);
        e.evaluate(input(ClassId.WAP_CP).originator("xx456yy"), Guards.NOMINAL, d);
        assertEquals(Verdict.BLOCK, d.action());

        RulesConfig bad = config(Map.of(), List.of(
                rule(12, 0, Verdict.BLOCK, Conditions.builder().classes(ClassId.WAP_CP)
                        .originator(StringMatch.regex("(")).build())));
        RuleEngine badEngine = RuleEngine.compile(bad);
        assertEquals(1, badEngine.skippedRules());
        badEngine.evaluate(input(ClassId.WAP_CP).originator("anything"), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
    }

    @Test
    void FW_07_portSlotRoamingAndDirection() {
        Conditions when = Conditions.builder().classes(ClassId.WAP_CP).port(new PortRange(2948, 2948))
                .slot(1).roaming(true).direction(Direction.IN).build();
        RuleEngine e = RuleEngine.compile(config(Map.of(), List.of(rule(13, 0, Verdict.QUARANTINE, when))));
        Decision d = new Decision();
        e.evaluate(input(ClassId.WAP_CP).destPort(2948).slot(1).roaming(true).direction(Direction.IN),
                Guards.NOMINAL, d);
        assertEquals(Verdict.QUARANTINE, d.action());

        e.evaluate(input(ClassId.WAP_CP).destPort(2949).slot(1).roaming(true).direction(Direction.IN),
                Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
    }

    @Test
    void FW_10_clampTableCoversEveryClassAndAction() {
        Decision d = new Decision();
        for (ClassId c : ClassId.values()) {
            for (Verdict v : Verdict.values()) {
                RulesConfig cfg = config(Map.of(c, v), List.of());
                RuleEngine.compile(cfg).evaluate(input(c), Guards.NOMINAL, d);
                boolean allowed = EnforcementMatrix.allows(
                        EnforcementMatrix.classActionMask(c), v);
                assertEquals(allowed ? v : Verdict.ALLOW, d.action(), c + " " + v);
                if (!allowed) {
                    assertEquals(ClampReason.ACTION_NOT_ALLOWED, d.clampReason(), c + " " + v);
                    assertEquals(v, d.clampedFrom(), c + " " + v);
                } else {
                    assertFalse(d.isClamped(), c + " " + v);
                }
            }
        }
    }

    @Test
    void FW_07_catastrophicRegexStaysInsideTheStepBudget() {
        RulesConfig c = config(Map.of(), List.of(rule(15, 0, Verdict.BLOCK,
                Conditions.builder().classes(ClassId.WAP_CP)
                        .originator(StringMatch.regex("(a+)+$")).build())));
        Decision d = new Decision();
        RuleEngine e = RuleEngine.compile(c);

        // A backtracking engine needs 2^500 steps here; the Pike VM finishes inside the budget, with no abort.
        e.evaluate(input(ClassId.WAP_CP).originator(run('a', 500) + "X"), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(0, d.regexAborts());
        assertEquals(SafeRegex.NO_MATCH, compile("(a+)+$").find(run('a', 500) + "X"));

        // An input long enough to need more than STEP_BUDGET steps aborts: no match, counted.
        String huge = run('a', SafeRegex.STEP_BUDGET) + "X";
        assertEquals(SafeRegex.ABORTED, compile("(a+)+$").find(huge));
        e.evaluate(input(ClassId.WAP_CP).originator(huge), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertFalse(d.hasRule());
        assertEquals(1, d.regexAborts());

        // The next evaluation starts again from zero.
        e.evaluate(input(ClassId.WAP_CP).originator("aaaa"), Guards.NOMINAL, d);
        assertEquals(Verdict.BLOCK, d.action());
        assertEquals(0, d.regexAborts());
    }

    private static String run(char c, int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append(c);
        return b.toString();
    }

    private static SafeRegex compile(String pattern) {
        try {
            return SafeRegex.compile(pattern);
        } catch (SafeRegex.SyntaxException e) {
            throw new AssertionError(pattern, e);
        }
    }

    @Test
    void CL_05_subtypesConditionMatchesExactlyAndNeedsASubtype() {
        RuleEngine e = RuleEngine.compile(config(Map.of(ClassId.WAP_SISL, Verdict.ALLOW), List.of(
                rule(21, 0, Verdict.BLOCK, Conditions.builder().classes(ClassId.WAP_SISL).subtypes("SL").build()))));
        Decision d = new Decision();

        e.evaluate(input(ClassId.WAP_SISL).subtype("SL"), Guards.NOMINAL, d);
        assertEquals(Verdict.BLOCK, d.action());
        assertEquals(21, d.ruleId());

        e.evaluate(input(ClassId.WAP_SISL).subtype("SI"), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(Decision.Source.CLASS_ACTION, d.source());

        e.evaluate(input(ClassId.WAP_SISL).subtype("sl"), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action(), "subtype codes match exactly");

        // Fail open: a payload that sends no subtype never matches a subtype rule.
        e.evaluate(input(ClassId.WAP_SISL), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertFalse(d.hasRule());

        // reset() clears the subtype.
        MatchInput in = input(ClassId.WAP_SISL).subtype("SL");
        in.reset().classId(ClassId.WAP_SISL);
        e.evaluate(in, Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
    }

    @Test
    void CL_05_subtypesConditionDoesNotUnlockNormal() {
        Conditions when = Conditions.builder().classes(ClassId.NORMAL).subtypes("X").build();
        assertFalse(when.hasSenderPortOrText());
        assertFalse(when.canMatchNormal());
        RuleEngine e = RuleEngine.compile(config(Map.of(), List.of(rule(22, 0, Verdict.BLOCK, when))));
        Decision d = new Decision();
        e.evaluate(input(ClassId.NORMAL).subtype("X"), Guards.NOMINAL, d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.BLOCKS_ALL_NORMAL, d.clampReason());
    }

    @Test
    void CL_05_locSuplIsClampedDuringAnEmergencyCall() {
        RuleEngine e = RuleEngine.compile(config(Map.of(ClassId.LOC_SUPL, Verdict.QUARANTINE), List.of()));
        Decision d = new Decision();
        e.evaluate(input(ClassId.LOC_SUPL), Guards.NOMINAL.withEmergency(true, -1, 0), d);
        assertEquals(Verdict.ALLOW, d.action());
        assertEquals(ClampReason.SUPL_EMERGENCY, d.clampReason());
        e.evaluate(input(ClassId.LOC_SUPL), Guards.NOMINAL, d);
        assertEquals(Verdict.QUARANTINE, d.action());
    }

    @Test
    void FW_10_evaluationAllocatesNothingAfterWarmup() {
        Assumptions.assumeTrue(
                ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean,
                "no allocation counter on this JVM");
        com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

        // Rules 16 and 17 do not match, so every evaluation walks the known-originator trie and the regex VM before
        // rule 14 decides.
        RuleEngine e = RuleEngine.compile(new RulesConfig(Schemas.RULES, 0, Preset.CUSTOM,
                Map.of(ClassId.WAP_CP, Verdict.QUARANTINE), List.of(
                        rule(16, 0, Verdict.BLOCK, Conditions.builder().classes(ClassId.WAP_CP)
                                .originatorKnown(Boolean.TRUE).build()),
                        rule(17, 1, Verdict.BLOCK, Conditions.builder().classes(ClassId.WAP_CP)
                                .originator(StringMatch.regex("^99[0-9]+$")).build()),
                        rule(14, 2, Verdict.NOTIFY, Conditions.builder().classes(ClassId.WAP_CP)
                                .originator(StringMatch.prefix("+9190")).build())),
                List.of("+919000000000"), List.of("+9180"), 5));
        MatchInput in = input(ClassId.WAP_CP).originator("+919000000678");
        Decision d = new Decision();
        for (int i = 0; i < 20_000; i++) e.evaluate(in, Guards.NOMINAL, d);
        assertEquals(14, d.ruleId());

        long before = mx.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < 20_000; i++) e.evaluate(in, Guards.NOMINAL, d);
        long allocated = mx.getCurrentThreadAllocatedBytes() - before;
        System.out.println("allocated=" + allocated + " bytes over 20000 evaluations");
        assertEquals(0L, allocated, "evaluation allocated bytes");
    }
}
