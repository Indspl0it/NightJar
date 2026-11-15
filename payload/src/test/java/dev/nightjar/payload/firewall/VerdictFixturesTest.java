package dev.nightjar.payload.firewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Direction;
import dev.nightjar.contract.Verdict;
import dev.nightjar.contract.Wire;
import dev.nightjar.contract.WireEnum;
import dev.nightjar.rules.ClampReason;
import dev.nightjar.rules.Decision;
import dev.nightjar.rules.Guards;
import dev.nightjar.rules.MatchInput;
import dev.nightjar.rules.RuleEngine;
import dev.nightjar.rules.RulesConfig;
import dev.nightjar.rules.RulesFormatException;
import dev.nightjar.rules.RulesJson;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The payload half of the cross-implementation fixture test.
 *
 * <p>The fixtures are not copied into this module by hand: {@code :payload:verdictFixtures} copies
 * {@code contract/src/test/resources/verdicts} onto the test classpath, so this test and
 * {@code :rules:VerdictFixturesTest} read the same bytes. What is asserted here is the payload's own output object
 * ({@link VerdictRecord}, the one {@code Enforcer} publishes to the event stream), not just the engine: the action, the
 * deciding rule id, the clamp and its wire name must all match the fixture.
 */
class VerdictFixturesTest {
    @Test
    void payloadVerdictsMatchTheSharedFixtures() throws Exception {
        List<Path> files = fixtures();
        assertTrue(files.size() >= 30, "fixture files: " + files.size());

        int cases = 0;
        Set<ClassId> classes = EnumSet.noneOf(ClassId.class);
        Set<ClampReason> clamps = EnumSet.noneOf(ClampReason.class);
        for (Path file : files) {
            JSONObject doc = new JSONObject(Files.readString(file));
            var array = doc.optJSONArray("cases");
            assertNotNull(array, file.getFileName() + ": no cases array");
            for (int i = 0; i < array.length(); i++) {
                JSONObject c = array.getJSONObject(i);
                String where = file.getFileName() + "[" + i + "] " + c.optString("name");

                RulesConfig config;
                try {
                    config = RulesJson.parse(c.getJSONObject("snapshot").toString());
                } catch (RulesFormatException e) {
                    throw new AssertionError(where + ": snapshot does not parse", e);
                }
                MatchInput in = input(c.getJSONObject("input"));
                Decision d = new Decision();
                RuleEngine.compile(config).evaluate(in, guards(c.optJSONObject("guards")), d);
                VerdictRecord record = VerdictRecord.of(d);

                JSONObject expected = c.getJSONObject("expected");
                Verdict action = wire(Verdict.class, expected.getString("action"));
                assertEquals(action, record.action(), where + ": action");
                assertEquals(
                        optionalVerdict(expected, "clampedFrom"), record.clampedFrom(), where + ": clampedFrom");
                ClampReason clamp = optionalClamp(expected);
                assertEquals(clamp, record.clampReason(), where + ": clampReason");
                assertEquals(clamp == null ? null : clamp.wire(), record.reasonWire(), where + ": reasonWire");
                if (expected.optBoolean("hasRule", false)) {
                    assertEquals(expected.getLong("ruleId"), record.ruleId(), where + ": ruleId");
                } else {
                    assertEquals(VerdictRecord.NO_RULE, record.ruleId(), where + ": ruleId");
                }
                assertEquals(
                        action == Verdict.BLOCK || action == Verdict.QUARANTINE,
                        record.blocked(),
                        where + ": blocked()");

                cases++;
                if (c.getJSONObject("input").opt("classId") instanceof String cid) {
                    classes.add(wire(ClassId.class, cid));
                }
                if (clamp != null) clamps.add(clamp);
            }
        }

        assertEquals(ClassId.values().length, classes.size(), "every class must be covered");
        assertEquals(
                EnumSet.of(
                        ClampReason.ACTION_NOT_ALLOWED,
                        ClampReason.BLOCKS_ALL_NORMAL,
                        ClampReason.EMERGENCY_CB,
                        ClampReason.SUPL_EMERGENCY,
                        ClampReason.SAFE_MODE,
                        ClampReason.FIREWALL_OFF),
                clamps,
                "every clamp must be exercised");
        assertTrue(cases >= 300, "cases: " + cases);
    }

    private static MatchInput input(JSONObject i) throws org.json.JSONException {
        MatchInput in = new MatchInput();
        if (i.opt("classId") instanceof String s) in.classId(wire(ClassId.class, s));
        if (i.opt("direction") instanceof String s) in.direction(wire(Direction.class, s));
        if (i.opt("originator") instanceof String s) in.originator(s);
        if (i.opt("smsc") instanceof String s) in.smsc(s);
        if (i.opt("contentType") instanceof String s) in.contentType(s);
        if (i.opt("subtype") instanceof String s) in.subtype(s);
        in.destPort(i.optInt("destPort", -1));
        in.slot(i.optInt("slot", -1));
        in.roaming(i.optBoolean("roaming", false));
        in.cbEmergency(i.optBoolean("cbEmergency", false));
        if (i.opt("text") instanceof String s) {
            in.textProvider(() -> s);
        }
        return in;
    }

    private static Guards guards(JSONObject g) {
        if (g == null) return Guards.NOMINAL;
        return new Guards(
                g.optBoolean("safeMode", false),
                g.optBoolean("firewallAvailable", true),
                g.optBoolean("firewallEnabled", true),
                g.optBoolean("emergencyCallActive", false),
                g.optLong("emergencyCallEndedAtMs", -1),
                g.optLong("nowMs", 0));
    }

    private static Verdict optionalVerdict(JSONObject o, String key) {
        return o.opt(key) instanceof String s ? wire(Verdict.class, s) : null;
    }

    private static ClampReason optionalClamp(JSONObject o) {
        return o.opt("clampReason") instanceof String s ? ClampReason.valueOf(s) : null;
    }

    private static <E extends Enum<E> & WireEnum> E wire(Class<E> type, String name) {
        E value = Wire.parse(type, name, null);
        assertNotNull(value, "unknown wire name " + name);
        return value;
    }

    /** The fixtures copied from :contract by the {@code verdictFixtures} task. */
    private static List<Path> fixtures() throws IOException {
        URL url = VerdictFixturesTest.class.getClassLoader().getResource("verdicts");
        if (url == null) {
            throw new AssertionError("verdicts/ is not on the test classpath; run :payload:verdictFixtures");
        }
        Path dir;
        try {
            dir = Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError("unreadable fixture directory " + url, e);
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = new ArrayList<>();
            stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
            assertTrue(!files.isEmpty(), "no fixtures");
            return files;
        }
    }
}
