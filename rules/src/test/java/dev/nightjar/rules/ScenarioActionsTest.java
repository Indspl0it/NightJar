package dev.nightjar.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Verdict;
import dev.nightjar.contract.Wire;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The {@code expect.action} of every scenario in {@code testdata/scenarios.json} is what the
 * Recommended preset gives for the scenario's class and subtype. The action comes from the engine with a Recommended
 * snapshot, no user rules and nominal guards, so it is the action the user sees on the phone with Recommended set.
 *
 * <p>The file path comes from the system property {@code nj.scenarios}; without it the test looks in the usual places
 * relative to the working directory. A missing file fails the test.
 */
class ScenarioActionsTest {
    private static final String PROPERTY = "nj.scenarios";
    private static final String[] CANDIDATES = {
        "../testdata/scenarios.json", "testdata/scenarios.json",
    };

    @Test
    void CL_05_everyScenarioActionIsTheRecommendedAction() throws IOException {
        JSONObject doc = new JSONObject(new String(Files.readAllBytes(scenarioFile()), StandardCharsets.UTF_8));
        JSONArray scenarios = doc.getJSONArray("scenarios");
        RuleEngine engine = RuleEngine.compile(Presets.apply(Preset.RECOMMENDED, RulesConfig.DEFAULT));
        MatchInput in = new MatchInput();
        Decision d = new Decision();
        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < scenarios.length(); i++) {
            JSONObject s = scenarios.getJSONObject(i);
            JSONObject expect = s.getJSONObject("expect");
            String id = s.getString("id");
            ClassId c = Wire.parse(ClassId.class, expect.getString("class"), null);
            assertNotNull(c, id + ": unknown class " + expect.getString("class"));
            String subtype = expect.isNull("subtype") ? null : expect.getString("subtype");
            Verdict want = verdict(id, expect.getString("action"));

            engine.evaluate(in.reset().classId(c).subtype(subtype), Guards.NOMINAL, d);
            if (d.action() != want) {
                mismatches.add(
                        id + ": " + c.wire() + "/" + subtype + " expect " + want + ", Recommended " + d.action());
            }
            assertEquals(d.action(), Presets.recommendedAction(c, subtype), id + ": engine and Presets disagree");
        }
        assertTrue(scenarios.length() >= 150, "scenarios: " + scenarios.length());
        assertEquals(List.of(), mismatches);
    }

    /** The words of the {@code action} field. "Notify" is Allow with a notification. */
    private static Verdict verdict(String id, String word) {
        switch (word) {
            case "Allow":
                return Verdict.ALLOW;
            case "Notify":
                return Verdict.NOTIFY;
            case "Hold":
                return Verdict.QUARANTINE;
            case "Block":
                return Verdict.BLOCK;
            default:
                fail(id + ": unknown action " + word);
                return null; // unreachable: fail() throws
        }
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
