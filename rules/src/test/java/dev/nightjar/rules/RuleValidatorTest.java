package dev.nightjar.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Schemas;
import dev.nightjar.contract.Verdict;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Typed validation errors for the rule editor. */
class RuleValidatorTest {
    private static RulesConfig config(List<Rule> rules) {
        return new RulesConfig(Schemas.RULES, 0, Preset.CUSTOM, Map.of(), rules, List.of(), List.of(), 5);
    }

    private static Rule rule(long id, Verdict action, Conditions when) {
        return new Rule(id, 0, true, RuleOrigin.USER, "r" + id, action, when);
    }

    private static Set<RuleValidator.Code> codes(List<RuleValidator.Error> errors) {
        return errors.stream().map(RuleValidator.Error::code).collect(Collectors.toSet());
    }

    @Test
    void FW_07_invalidRegexIsReported() {
        List<RuleValidator.Error> errors = RuleValidator.validate(config(List.of(
                rule(1, Verdict.BLOCK, Conditions.builder().classes(ClassId.WAP_CP)
                        .originator(StringMatch.regex("(")).build()))));
        assertTrue(codes(errors).contains(RuleValidator.Code.REGEX_INVALID));
    }

    @Test
    void FW_07_longRegexIsReported() {
        List<RuleValidator.Error> errors = RuleValidator.validate(config(List.of(
                rule(1, Verdict.BLOCK, Conditions.builder().classes(ClassId.WAP_CP)
                        .originator(StringMatch.regex("a".repeat(201))).build()))));
        assertTrue(codes(errors).contains(RuleValidator.Code.REGEX_TOO_LONG));
    }

    @Test
    void FW_07_emptyConditionValueIsReported() {
        List<RuleValidator.Error> errors = RuleValidator.validate(config(List.of(
                rule(1, Verdict.NOTIFY, Conditions.builder().classes(ClassId.WAP_CP)
                        .originator(StringMatch.is("")).build()))));
        assertTrue(codes(errors).contains(RuleValidator.Code.EMPTY_VALUE));
    }

    @Test
    void FW_06_actionNotAllowedForClassIsReported() {
        List<RuleValidator.Error> errors = RuleValidator.validate(config(List.of(
                rule(1, Verdict.BLOCK, Conditions.builder().classes(ClassId.SILENT).build()))));
        assertTrue(codes(errors).contains(RuleValidator.Code.ACTION_NOT_ALLOWED_FOR_CLASS));
    }

    @Test
    void G_05_blockAllNormalIsReported() {
        List<RuleValidator.Error> errors = RuleValidator.validate(config(List.of(
                rule(1, Verdict.QUARANTINE, Conditions.builder().classes(ClassId.NORMAL).build()))));
        assertTrue(codes(errors).contains(RuleValidator.Code.BLOCKS_ALL_NORMAL));
    }

    @Test
    void IPC_02_oversizeDocumentIsReported() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < RulesJson.MAX_BYTES; i++) name.append('x');
        List<RuleValidator.Error> errors = RuleValidator.validate(config(List.of(
                new Rule(1, 0, true, RuleOrigin.USER, name.toString(), Verdict.ALLOW, Conditions.ANY))));
        assertTrue(codes(errors).contains(RuleValidator.Code.TOO_LARGE));
    }

    @Test
    void FW_07_validDocumentHasNoErrors() {
        RulesConfig ok = config(List.of(
                rule(1, Verdict.QUARANTINE, Conditions.builder().classes(ClassId.WAP_CP)
                        .originator(StringMatch.prefix("+9190")).build())));
        assertEquals(List.of(), RuleValidator.validate(ok));
    }

    @Test
    void FW_07_numberThatNormalisesToEmptyIsReported() {
        // "00" and "+" are a prefix of every number after normalisation.
        for (StringMatch m : List.of(StringMatch.prefix("00"), StringMatch.is("+"), StringMatch.isNot(" "))) {
            List<RuleValidator.Error> errors = RuleValidator.validate(config(List.of(
                    rule(1, Verdict.NOTIFY, Conditions.builder().classes(ClassId.WAP_CP).originator(m).build()))));
            assertEquals(Set.of(RuleValidator.Code.EMPTY_VALUE), codes(errors), m.toString());
        }
        List<RuleValidator.Error> smsc = RuleValidator.validate(config(List.of(
                rule(1, Verdict.NOTIFY, Conditions.builder().classes(ClassId.WAP_CP)
                        .smsc(StringMatch.prefix("00")).build()))));
        assertEquals(Set.of(RuleValidator.Code.EMPTY_VALUE), codes(smsc));
        // A known originator of "+" would mark every sender as known.
        RulesConfig known = config(List.of()).withKnownOriginators(List.of("+"), List.of("00"));
        assertEquals(2, RuleValidator.validate(known).size());
        assertEquals(Set.of(RuleValidator.Code.EMPTY_VALUE), codes(RuleValidator.validate(known)));
    }

    @Test
    void FW_07_invalidPortIsReported() {
        for (PortRange port : List.of(new PortRange(-1, -1), new PortRange(70000, 70000), new PortRange(5, 4))) {
            List<RuleValidator.Error> errors = RuleValidator.validate(config(List.of(
                    rule(1, Verdict.NOTIFY, Conditions.builder().classes(ClassId.WAP_CP).port(port).build()))));
            assertEquals(Set.of(RuleValidator.Code.INVALID_VALUE), codes(errors), port.toString());
        }
    }

    @Test
    void CL_05_subtypesNeedClassesAndKnownCodes() {
        // The same code exists in several classes, so subtypes without classes are refused.
        List<RuleValidator.Error> noClass = RuleValidator.validate(config(List.of(
                rule(1, Verdict.NOTIFY, Conditions.builder().subtypes("NOTIFY").build()))));
        assertEquals(Set.of(RuleValidator.Code.INVALID_VALUE), codes(noClass));
        // A typo gives a rule that never matches.
        List<RuleValidator.Error> typo = RuleValidator.validate(config(List.of(
                rule(1, Verdict.BLOCK, Conditions.builder().classes(ClassId.WAP_SISL).subtypes("Sl").build()))));
        assertEquals(Set.of(RuleValidator.Code.INVALID_VALUE), codes(typo));
        assertEquals("subtype Sl", typo.get(0).detail());
        // A code of another listed class is accepted.
        assertEquals(List.of(), RuleValidator.validate(config(List.of(rule(1, Verdict.NOTIFY,
                Conditions.builder().classes(ClassId.WAP_SISL, ClassId.WAP_MMS).subtypes("NOTIFY").build())))));
        // A free-form class (content type, package name) takes any text.
        assertEquals(List.of(), RuleValidator.validate(config(List.of(rule(1, Verdict.QUARANTINE,
                Conditions.builder().classes(ClassId.WAP_OTHER).subtypes("application/vnd.example").build())))));
    }

    @Test
    void FW_06_everyActionButAllowIsCheckedAgainstTheClass() {
        // A NOTIFY rule for a class that allows only ALLOW is clamped too, so the editor reports it.
        List<RuleValidator.Error> errors = RuleValidator.validate(config(List.of(
                rule(1, Verdict.NOTIFY, Conditions.builder().classes(ClassId.SIM_STORED).build()))));
        assertEquals(Set.of(RuleValidator.Code.ACTION_NOT_ALLOWED_FOR_CLASS), codes(errors));
        assertEquals(List.of(), RuleValidator.validate(config(List.of(
                rule(1, Verdict.ALLOW, Conditions.builder().classes(ClassId.SIM_STORED).build())))));
    }

    @Test
    void G_05_classLessAndIsNotRulesAreReported() {
        // A rule without classes can match CL-NORMAL.
        List<RuleValidator.Error> any = RuleValidator.validate(config(List.of(
                rule(1, Verdict.BLOCK, Conditions.builder().slot(0).build()))));
        assertTrue(codes(any).contains(RuleValidator.Code.BLOCKS_ALL_NORMAL));
        // IS_NOT one number does not narrow.
        List<RuleValidator.Error> isNot = RuleValidator.validate(config(List.of(
                rule(1, Verdict.BLOCK, Conditions.builder().classes(ClassId.NORMAL)
                        .originator(StringMatch.isNot("+15550100")).build()))));
        assertTrue(codes(isNot).contains(RuleValidator.Code.BLOCKS_ALL_NORMAL));
        // Outbound or content-type rules cannot match CL-NORMAL.
        assertEquals(List.of(), RuleValidator.validate(config(List.of(rule(1, Verdict.BLOCK,
                Conditions.builder().contentType(StringMatch.prefix("application/vnd.wap")).build())))));
    }

    @Test
    void A_21_ruleIdsAreUniqueAndInTheirRange() {
        // A shared id makes the deciding rule ambiguous.
        List<RuleValidator.Error> dup = RuleValidator.validate(config(List.of(
                rule(1, Verdict.NOTIFY, Conditions.builder().classes(ClassId.WAP_CP).build()),
                rule(1, Verdict.NOTIFY, Conditions.builder().classes(ClassId.WAP_DM).build()))));
        assertEquals(Set.of(RuleValidator.Code.INVALID_ID), codes(dup));
        List<RuleValidator.Error> userHigh = RuleValidator.validate(config(List.of(
                rule(Presets.PRESET_ID_BASE + 50, Verdict.NOTIFY,
                        Conditions.builder().classes(ClassId.WAP_CP).build()))));
        assertEquals(Set.of(RuleValidator.Code.INVALID_ID), codes(userHigh));
        List<RuleValidator.Error> presetLow = RuleValidator.validate(config(List.of(new Rule(
                5, 0, true, RuleOrigin.PRESET, "p", Verdict.NOTIFY,
                Conditions.builder().classes(ClassId.WAP_CP).build()))));
        assertEquals(Set.of(RuleValidator.Code.INVALID_ID), codes(presetLow));
        // A new draft has id 0 until the app gives it one: that is not an error.
        assertEquals(List.of(), RuleValidator.validate(config(List.of(
                rule(0, Verdict.NOTIFY, Conditions.builder().classes(ClassId.WAP_CP).build())))));
    }

    @Test
    void FW_07_noDocumentIsReported() {
        List<RuleValidator.Error> errors = RuleValidator.validate(null);
        assertEquals(1, errors.size());
        assertEquals(RuleValidator.Code.EMPTY_VALUE, errors.get(0).code());
        assertEquals("no document", errors.get(0).detail());
    }

    @Test
    void FW_08_strictPresetIsValid() {
        assertEquals(List.of(), RuleValidator.validate(Presets.apply(Preset.STRICT, RulesConfig.DEFAULT)));
        assertEquals(List.of(), RuleValidator.validate(Presets.apply(Preset.RECOMMENDED, RulesConfig.DEFAULT)));
    }
}
