package dev.nightjar.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Direction;
import dev.nightjar.contract.Schemas;
import dev.nightjar.contract.Verdict;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Reader/writer and the 64 KB guard. */
class RulesJsonTest {
    private static RulesConfig sample() {
        Conditions when = Conditions.builder()
                .classes(ClassId.WAP_CP, ClassId.WAP_DM)
                .originator(StringMatch.prefix("+9190"))
                .originatorKnown(Boolean.FALSE)
                .smsc(StringMatch.prefix("+9190"))
                .port(new PortRange(16000, 16999))
                .contentType(StringMatch.prefix("application/vnd.wap"))
                .slot(0)
                .roaming(true)
                .textContains("Example")
                .direction(Direction.IN)
                .build();
        return new RulesConfig(
                Schemas.RULES,
                42,
                Preset.CUSTOM,
                Map.of(ClassId.WAP_CP, Verdict.QUARANTINE, ClassId.SILENT, Verdict.ALLOW),
                List.of(
                        new Rule(7, 1, true, RuleOrigin.USER, "Unknown settings push", Verdict.QUARANTINE, when),
                        new Rule(8, 2, false, RuleOrigin.PRESET, "disabled", Verdict.BLOCK,
                                Conditions.builder().classes(ClassId.WAP_DM).build())),
                List.of("+919000000000"),
                List.of("+9190"),
                10);
    }

    @Test
    void FW_07_rulesJsonRoundTrip() throws Exception {
        RulesConfig c = sample();
        String json = RulesJson.serialize(c);
        RulesConfig back = RulesJson.parse(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(c, back);
        assertEquals(c.classAction(ClassId.WAP_CP), back.classAction(ClassId.WAP_CP));
        assertEquals(c.rules().get(0).when(), back.rules().get(0).when());
        assertEquals(10, back.effectiveSuplGraceMin());
    }

    @Test
    void FW_07_goldenRulesFixtureRoundTrips() throws Exception {
        byte[] fixture = getClass().getResourceAsStream("/rules/sample.json").readAllBytes();
        RulesConfig once = RulesJson.parse(fixture);
        String serialized = RulesJson.serialize(once);
        RulesConfig twice = RulesJson.parse(serialized);
        assertEquals(once, twice);
        assertEquals(2, twice.rules().size());
        assertEquals(10, twice.suplGraceMin());
        assertEquals(Verdict.QUARANTINE, twice.classAction(ClassId.WAP_CP));
    }

    @Test
    void IPC_02_tooLargeDocumentIsRejected() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < RulesJson.MAX_BYTES; i++) name.append('x');
        RulesConfig c = new RulesConfig(Schemas.RULES, 0, Preset.CUSTOM, Map.of(),
                List.of(new Rule(1, 0, true, RuleOrigin.USER, name.toString(), Verdict.ALLOW, Conditions.ANY)),
                List.of(), List.of(), 5);
        RulesFormatException e = assertThrows(RulesFormatException.class, () -> RulesJson.serializeChecked(c));
        assertEquals(RulesFormatException.Reason.TOO_LARGE, e.reason());
        assertThrows(RulesFormatException.class,
                () -> RulesJson.parse(RulesJson.serialize(c).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void FW_07_unknownFieldsAreIgnoredAndUnsupportedRulesAreDropped() throws Exception {
        String json = "{\"schema\":1,\"revision\":3,\"preset\":\"CUSTOM\",\"classActions\":{}," +
                "\"rules\":[{\"id\":1,\"action\":\"BLOCK\",\"when\":{\"originator\":{\"op\":\"NOPE\",\"value\":\"x\"}}}]," +
                "\"knownOriginators\":{\"exact\":[],\"prefix\":[]},\"guards\":{\"suplGraceMin\":9},\"future\":true}";
        RulesConfig c = RulesJson.parse(json);
        assertEquals(1, c.rules().size());
        assertFalse(c.rules().get(0).unsupported() == null);
        assertTrue(RulesJson.serialize(c).contains("\"rules\":[]"));
    }

    @Test
    void A_24_unknownWhenKeyMakesTheRuleUnsupported() throws Exception {
        String json = "{\"schema\":1,\"revision\":3,\"preset\":\"CUSTOM\",\"classActions\":{}," +
                "\"rules\":[{\"id\":1,\"action\":\"BLOCK\",\"when\":{\"classes\":[\"CL-WAP-CP\"]," +
                "\"newCondition\":\"x\"}}]," +
                "\"knownOriginators\":{\"exact\":[],\"prefix\":[]},\"guards\":{\"suplGraceMin\":9}}";
        RulesConfig c = RulesJson.parse(json);
        assertEquals(1, c.rules().size());
        assertEquals("newCondition", c.rules().get(0).unsupported());
        // Unsupported rules are dropped from the document the payload sees.
        assertTrue(RulesJson.serialize(c).contains("\"rules\":[]"));
    }

    @Test
    void A_22_emptyTextContainsIsUnsupported() throws Exception {
        String json = "{\"schema\":1,\"revision\":3,\"preset\":\"CUSTOM\",\"classActions\":{}," +
                "\"rules\":[{\"id\":1,\"action\":\"BLOCK\",\"when\":{\"textContains\":\"\"}}]," +
                "\"knownOriginators\":{\"exact\":[],\"prefix\":[]},\"guards\":{\"suplGraceMin\":9}}";
        RulesConfig c = RulesJson.parse(json);
        assertEquals("textContains", c.rules().get(0).unsupported());
    }

    @Test
    void CL_05_subtypesConditionRoundTrips() throws Exception {
        Conditions when = Conditions.builder().classes(ClassId.DATA_PORT).subtypes("UNKNOWN", "PORT0").build();
        RulesConfig c = new RulesConfig(Schemas.RULES, 1, Preset.CUSTOM, Map.of(),
                List.of(new Rule(3, 0, true, RuleOrigin.PRESET, "ports", Verdict.QUARANTINE, when)),
                List.of(), List.of(), 5);
        String json = RulesJson.serialize(c);
        assertTrue(json.contains("\"subtypes\":[\"UNKNOWN\",\"PORT0\"]"), json);
        RulesConfig back = RulesJson.parse(json);
        assertEquals(c, back);
        assertEquals(null, back.rules().get(0).unsupported());
        assertEquals(json, RulesJson.serialize(back));
    }

    @Test
    void CL_05_badSubtypesMakeTheRuleUnsupported() throws Exception {
        for (String value : List.of("\"SL\"", "[1]", "[\"\"]", "[\"SL\",null]")) {
            String json = "{\"schema\":1,\"revision\":3,\"preset\":\"CUSTOM\",\"classActions\":{}," +
                    "\"rules\":[{\"id\":1,\"action\":\"BLOCK\",\"when\":{\"classes\":[\"CL-WAP-SISL\"]," +
                    "\"subtypes\":" + value + "}}]," +
                    "\"knownOriginators\":{\"exact\":[],\"prefix\":[]},\"guards\":{\"suplGraceMin\":9}}";
            RulesConfig c = RulesJson.parse(json);
            assertEquals("subtypes", c.rules().get(0).unsupported(), value);
        }
    }

    /** A document with one rule; {@code rule} is the JSON object of that rule. */
    private static String doc(String rule) {
        return "{\"schema\":1,\"revision\":3,\"preset\":\"CUSTOM\",\"classActions\":{},\"rules\":[" + rule + "],"
                + "\"knownOriginators\":{\"exact\":[],\"prefix\":[]},\"guards\":{\"suplGraceMin\":9}}";
    }

    @Test
    void A_24_unknownRuleMatchOrPortKeyMakesTheRuleUnsupported() throws Exception {
        // A key this version does not read could narrow the rule in a newer version.
        assertEquals("expires", RulesJson.parse(doc("{\"id\":1,\"action\":\"BLOCK\",\"expires\":5,"
                + "\"when\":{\"classes\":[\"CL-WAP-CP\"]}}")).rules().get(0).unsupported());
        assertEquals("flags", RulesJson.parse(doc("{\"id\":1,\"action\":\"BLOCK\",\"when\":{\"classes\":"
                + "[\"CL-WAP-CP\"],\"originator\":{\"op\":\"IS\",\"value\":\"12345\",\"flags\":\"x\"}}}"))
                .rules().get(0).unsupported());
        assertEquals("step", RulesJson.parse(doc("{\"id\":1,\"action\":\"BLOCK\",\"when\":{\"classes\":"
                + "[\"CL-WAP-CP\"],\"port\":{\"from\":1,\"to\":9,\"step\":2}}}")).rules().get(0).unsupported());
    }

    @Test
    void FW_07_nonBooleanEnabledIsUnsupported() throws Exception {
        // "false" as a string must not switch the rule on.
        for (String value : List.of("\"false\"", "0", "1", "null")) {
            Rule rule = RulesJson.parse(doc("{\"id\":1,\"enabled\":" + value + ",\"action\":\"BLOCK\","
                    + "\"when\":{\"classes\":[\"CL-WAP-CP\"]}}")).rules().get(0);
            assertEquals("enabled", rule.unsupported(), value);
            assertFalse(rule.enabled(), value);
        }
        assertTrue(RulesJson.parse(doc("{\"id\":1,\"action\":\"BLOCK\",\"when\":{\"classes\":[\"CL-WAP-CP\"]}}"))
                .rules().get(0).enabled());
    }

    @Test
    void FW_07_badPortIsUnsupported() throws Exception {
        // A port outside 0..65535 would match messages without a port.
        for (String port : List.of("{\"from\":-1}", "{\"from\":70000}", "{\"from\":5,\"to\":4}", "{\"from\":1.5}",
                "{\"from\":\"5\"}", "{\"from\":5,\"to\":70000}", "{\"to\":5}")) {
            Rule rule = RulesJson.parse(doc("{\"id\":1,\"action\":\"BLOCK\",\"when\":{\"classes\":[\"CL-WAP-CP\"],"
                    + "\"port\":" + port + "}}")).rules().get(0);
            assertEquals("port", rule.unsupported(), port);
        }
        Rule exact = RulesJson.parse(doc("{\"id\":1,\"action\":\"BLOCK\",\"when\":{\"classes\":[\"CL-WAP-CP\"],"
                + "\"port\":{\"from\":2948}}}")).rules().get(0);
        assertEquals(null, exact.unsupported());
        assertEquals(PortRange.exact(2948), exact.when().port());
    }

    @Test
    void FW_07_slotThatIsNotAWholeIntIsUnsupported() throws Exception {
        // Cast to int, these would be slot 0 and make the rule match messages of the first SIM.
        for (String slot : List.of("0.5", "4294967296", "\"0\"", "1e300")) {
            Rule rule = RulesJson.parse(doc("{\"id\":1,\"action\":\"BLOCK\",\"when\":{\"classes\":[\"CL-WAP-CP\"],"
                    + "\"slot\":" + slot + "}}")).rules().get(0);
            assertEquals("slot", rule.unsupported(), slot);
        }
        Rule second = RulesJson.parse(doc("{\"id\":1,\"action\":\"BLOCK\",\"when\":{\"classes\":[\"CL-WAP-CP\"],"
                + "\"slot\":1}}")).rules().get(0);
        assertEquals(null, second.unsupported());
        assertEquals(Integer.valueOf(1), second.when().slot());
    }

    @Test
    void FW_07_emptyOrNormalisedEmptyValueIsUnsupported() throws Exception {
        // "00" and "+" normalise to empty, a prefix of every number.
        for (String m : List.of("\"originator\":{\"op\":\"PREFIX\",\"value\":\"00\"}",
                "\"originator\":{\"op\":\"IS_NOT\",\"value\":\"+\"}", "\"originator\":{\"op\":\"IS\",\"value\":\"\"}",
                "\"smsc\":{\"op\":\"PREFIX\",\"value\":\" + \"}", "\"contentType\":{\"op\":\"PREFIX\",\"value\":\"\"}",
                "\"originator\":{\"op\":\"REGEX\",\"value\":\"\"}")) {
            Rule rule = RulesJson.parse(doc("{\"id\":1,\"action\":\"NOTIFY\",\"when\":{\"classes\":[\"CL-WAP-CP\"],"
                    + m + "}}")).rules().get(0);
            assertTrue(rule.unsupported() != null, m);
        }
        // A regex is not normalised: "00" is a valid pattern.
        Rule regex = RulesJson.parse(doc("{\"id\":1,\"action\":\"NOTIFY\",\"when\":{\"classes\":[\"CL-WAP-CP\"],"
                + "\"originator\":{\"op\":\"REGEX\",\"value\":\"^00\"}}}")).rules().get(0);
        assertEquals(null, regex.unsupported());
    }

    @Test
    void IPC_02_deeplyNestedDocumentIsMalformed() {
        // org.json parses recursively; a deep document must give MALFORMED, not a StackOverflowError.
        StringBuilder b = new StringBuilder("{\"schema\":1,\"x\":");
        int depth = 30_000;
        for (int i = 0; i < depth; i++) b.append('[');
        for (int i = 0; i < depth; i++) b.append(']');
        b.append('}');
        assertTrue(RulesJson.utf8Length(b) <= RulesJson.MAX_BYTES);
        RulesFormatException e = assertThrows(RulesFormatException.class, () -> RulesJson.parse(b.toString()));
        assertEquals(RulesFormatException.Reason.MALFORMED, e.reason());
    }

    @Test
    void FW_07_contractGoldenRulesParsesAndIsValid() throws Exception {
        // The same file the :contract tests check (copied by the verdictFixtures task).
        byte[] golden = getClass().getResourceAsStream("/golden/rules.json").readAllBytes();
        RulesConfig c = RulesJson.parse(golden);
        assertEquals(2, c.rules().size());
        for (Rule rule : c.rules()) assertEquals(null, rule.unsupported(), rule.name());
        Rule preset = c.rules().get(1);
        assertEquals(RuleOrigin.PRESET, preset.origin());
        assertEquals(java.util.Set.of("SL"), preset.when().subtypes());
        assertEquals(java.util.Set.of(ClassId.WAP_SISL), preset.when().classes());
        assertEquals(List.of(), RuleValidator.validate(c));
        assertEquals(c, RulesJson.parse(RulesJson.serialize(c)));
    }

    @Test
    void FW_07_schemaMismatchIsRejected() {
        RulesFormatException e = assertThrows(RulesFormatException.class, () -> RulesJson.parse("{\"schema\":2}"));
        assertEquals(RulesFormatException.Reason.SCHEMA_UNSUPPORTED, e.reason());
    }

    @Test
    void FW_07_suplGraceIsClampedToFiveMinutes() {
        assertEquals(5, RulesConfig.DEFAULT.withSuplGraceMin(0).effectiveSuplGraceMin());
        assertEquals(60, RulesConfig.DEFAULT.withSuplGraceMin(999).effectiveSuplGraceMin());
    }
}
