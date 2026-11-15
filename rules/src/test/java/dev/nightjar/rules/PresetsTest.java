package dev.nightjar.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Schemas;
import dev.nightjar.contract.Verdict;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The preset definitions as data. */
class PresetsTest {
    private static final Rule USER_RULE = new Rule(
            99, 5, true, RuleOrigin.USER, "mine", Verdict.NOTIFY,
            Conditions.builder().classes(ClassId.WAP_CP).build());

    @Test
    void FW_08_presetsMatchAppendixE() {
        RulesConfig monitor = Presets.apply(Preset.MONITOR, RulesConfig.DEFAULT);
        assertEquals(Preset.MONITOR, monitor.preset());
        assertTrue(monitor.classActions().isEmpty());
        assertTrue(monitor.rules().isEmpty());
        assertFalse(Presets.notifyNotable(Preset.MONITOR));

        // Recommended = the default column (EnforcementCoverageTest and PresetTableTest check every
        // class).
        RulesConfig recommended = Presets.apply(Preset.RECOMMENDED, RulesConfig.DEFAULT);
        assertEquals(Preset.RECOMMENDED, recommended.preset());
        assertEquals(Verdict.BLOCK, recommended.classAction(ClassId.WAP_CP));
        // RULES-X8: a malformed SMS cannot be released, so Recommended blocks it (asked for Hold).
        assertEquals(Verdict.BLOCK, recommended.classAction(ClassId.MALFORMED_SMS));
        // A message with the wrong message type can be released, so Recommended holds it.
        assertEquals(Verdict.QUARANTINE, recommended.classAction(ClassId.UNREADABLE_SMS));
        assertEquals(Verdict.ALLOW, recommended.classAction(ClassId.WAP_MMS));
        assertEquals(Verdict.QUARANTINE, recommended.classAction(ClassId.FLASH));
        assertFalse(recommended.classActions().containsValue(Verdict.ALLOW));
        assertEquals(Presets.subtypeRules(Preset.RECOMMENDED), recommended.rules());
        assertEquals(
                List.of(Presets.P_FLASH_EMPTY_ID, Presets.P_MWI_SILENT_ID, Presets.P_PORT_HOLD_ID,
                        Presets.P_PORT_NOTIFY_ID, Presets.P_SISL_SL_ID),
                recommended.rules().stream().map(Rule::id).toList());
        for (Rule rec : recommended.rules()) {
            assertEquals(RuleOrigin.PRESET, rec.origin());
            assertTrue(rec.id() != Presets.P_REC_1_ID, "P-REC-1 is retired");
        }
        assertFalse(Presets.notifyNotable(Preset.RECOMMENDED));

        RulesConfig strict = Presets.apply(Preset.STRICT, RulesConfig.DEFAULT);
        assertEquals(Preset.STRICT, strict.preset());
        // Strict allows only regular SMS (emergency alerts).
        assertEquals(Verdict.BLOCK, strict.classAction(ClassId.WAP_DM));
        assertEquals(Verdict.BLOCK, strict.classAction(ClassId.WAP_SISL));
        assertEquals(Verdict.QUARANTINE, strict.classAction(ClassId.WAP_MMS));
        assertEquals(Verdict.QUARANTINE, strict.classAction(ClassId.MWI));
        assertEquals(Verdict.BLOCK, strict.classAction(ClassId.DATA_PORT));
        assertEquals(Verdict.BLOCK, strict.classAction(ClassId.CB));
        assertEquals(Verdict.ALLOW, strict.classAction(ClassId.NORMAL));
        // Is retired: the class action blocks data messages, the subtype rule holds UNKNOWN.
        assertEquals(Presets.subtypeRules(Preset.STRICT), strict.rules());
        assertEquals(
                List.of(Presets.P_FLASH_EMPTY_ID, Presets.P_MWI_SILENT_ID, Presets.P_PORT_HOLD_ID,
                        Presets.P_SISL_SL_ID),
                strict.rules().stream().map(Rule::id).toList());
        assertTrue(Presets.notifyNotable(Preset.STRICT));
    }

    @Test
    void FW_08_presetKeepsUserRulesAndReplacesPresetRules() {
        RulesConfig base = new RulesConfig(
                Schemas.RULES, 7, Preset.RECOMMENDED, Map.of(ClassId.WAP_CP, Verdict.QUARANTINE),
                List.of(USER_RULE, Presets.presetRules(Preset.RECOMMENDED).get(0)),
                List.of("+9190"), List.of("+919"), 12);

        RulesConfig strict = Presets.apply(Preset.STRICT, base);
        assertEquals(1 + Presets.subtypeRules(Preset.STRICT).size(), strict.rules().size());
        assertEquals(USER_RULE, strict.rules().get(0));
        assertEquals(Presets.subtypeRules(Preset.STRICT), strict.rules().subList(1, strict.rules().size()));
        assertEquals(List.of("+9190"), strict.knownExact());
        assertEquals(List.of("+919"), strict.knownPrefix());
        assertEquals(12, strict.suplGraceMin());
        assertEquals(7, strict.revision());

        RulesConfig monitor = Presets.apply(Preset.MONITOR, strict);
        assertEquals(1, monitor.rules().size());
        assertEquals(USER_RULE, monitor.rules().get(0));
        assertTrue(monitor.classActions().isEmpty());
    }

    @Test
    void A_21_presetIdsLiveInTheReservedRange() {
        // User rules are allocated from 1 upwards, so the ranges never meet.
        assertTrue(Presets.PRESET_ID_BASE > 1_000_000L);
        for (Preset p : Preset.values()) {
            java.util.Set<Long> ids = new java.util.HashSet<>();
            for (Rule rule : Presets.presetRules(p)) {
                assertTrue(rule.id() > Presets.PRESET_ID_BASE, p + ": " + rule.id());
                assertTrue(rule.id() != Presets.P_REC_1_ID, p + ": P-REC-1 is retired");
                assertTrue(rule.id() != Presets.P_STR_1_ID, p + ": P-STR-1 is retired");
                assertTrue(ids.add(rule.id()), p + ": duplicate id " + rule.id());
                assertEquals(RuleOrigin.PRESET, rule.origin(), p + ": " + rule.id());
            }
            // Every preset is a valid snapshot: the editor must never refuse a preset.
            assertEquals(List.of(), RuleValidator.validate(Presets.apply(p, RulesConfig.DEFAULT)), p.toString());
        }
    }

    @Test
    void FW_08_customKeepsEverything() {
        RulesConfig base = new RulesConfig(
                Schemas.RULES, 1, Preset.STRICT, Map.of(ClassId.WAP_CP, Verdict.BLOCK),
                List.of(USER_RULE), List.of(), List.of(), 5);
        RulesConfig custom = Presets.apply(Preset.CUSTOM, base);
        assertEquals(Preset.CUSTOM, custom.preset());
        assertEquals(base.classActions(), custom.classActions());
        assertEquals(base.rules(), custom.rules());
    }
}
