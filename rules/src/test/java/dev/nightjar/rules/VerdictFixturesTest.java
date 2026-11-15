package dev.nightjar.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Direction;
import dev.nightjar.contract.Verdict;
import dev.nightjar.contract.Wire;
import dev.nightjar.contract.WireEnum;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The cross-implementation verdict fixtures.
 *
 * <p>Every fixture is a {@code rules.json} snapshot (parsed through {@link RulesJson}, exactly as the app and the payload
 * parse the real document), a {@link MatchInput}, optional guards, and the verdict that {@link EnforcementMatrix} and
 * the five clamps must produce. The same files are on the payload's test classpath (copied by
 * {@code verdictFixtures}), so the two implementations are compared on identical data instead of on separately
 * written expectations.
 *
 * <p>Coverage is asserted, not assumed: every {@link ClassId} must appear with all four actions on both the class-action
 * and the rule path, and every {@link ClampReason} except {@code ENGINE_ERROR} must be produced at least once.
 */
class VerdictFixturesTest {
    @Test
    void everyFixtureProducesTheExpectedVerdict() throws IOException {
        List<Path> files = fixtures();
        assertTrue(files.size() >= 30, "fixture files: " + files.size());

        int cases = 0;
        Set<ClassId> classes = EnumSet.noneOf(ClassId.class);
        Set<ClampReason> clamps = EnumSet.noneOf(ClampReason.class);
        Set<String> pairs = new HashSet<>();

        for (Path file : files) {
            JSONObject doc = new JSONObject(Files.readString(file));
            var array = doc.optJSONArray("cases");
            assertNotNull(array, file.getFileName() + ": no cases array");
            for (int i = 0; i < array.length(); i++) {
                JSONObject c = array.getJSONObject(i);
                String where = file.getFileName() + "[" + i + "] " + c.optString("name");
                Decision d = evaluate(c, where);
                JSONObject expected = c.getJSONObject("expected");

                Verdict action = wire(Verdict.class, expected.getString("action"));
                assertEquals(action, d.action(), where + ": action");
                assertEquals(
                        optionalVerdict(expected, "clampedFrom"), d.clampedFrom(), where + ": clampedFrom");
                assertEquals(optionalClamp(expected), d.clampReason(), where + ": clampReason");
                assertEquals(expected.optBoolean("hasRule", false), d.hasRule(), where + ": hasRule");
                assertEquals(
                        Decision.Source.valueOf(expected.getString("source")), d.source(), where + ": source");
                Object expectedRuleId = expected.opt("ruleId");
                if (d.hasRule()) {
                    assertTrue(expectedRuleId instanceof Number, where + ": ruleId missing");
                    assertEquals(((Number) expectedRuleId).longValue(), d.ruleId(), where + ": ruleId");
                } else {
                    assertFalse(expectedRuleId instanceof Number, where + ": unexpected ruleId");
                }

                cases++;
                if (c.getJSONObject("input").opt("classId") instanceof String cid) {
                    ClassId inputClass = wire(ClassId.class, cid);
                    classes.add(inputClass);
                    Verdict requested = requested(c.getJSONObject("snapshot"), inputClass);
                    if (requested != null) pairs.add(inputClass.name() + "|" + requested + "|" + d.source());
                }
                if (d.clampReason() != null) clamps.add(d.clampReason());
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
        for (ClassId c : ClassId.values()) {
            for (Verdict v : Verdict.values()) {
                assertTrue(
                        pairs.contains(c.name() + "|" + v + "|RULE")
                                || pairs.contains(c.name() + "|" + v + "|CLASS_ACTION")
                                || pairs.contains(c.name() + "|" + v + "|DEFAULT"),
                        "missing " + c + " x " + v);
            }
        }
        assertTrue(cases >= 300, "cases: " + cases);
    }

    /** The action the fixture asks for: its class action, else its first rule's action. */
    private static Verdict requested(JSONObject snapshot, ClassId cls) {
        JSONObject actions = snapshot.optJSONObject("classActions");
        if (actions != null && actions.opt(cls.wire()) instanceof String s) return wire(Verdict.class, s);
        var rules = snapshot.optJSONArray("rules");
        if (rules != null && rules.length() > 0) {
            return wire(Verdict.class, rules.getJSONObject(0).getString("action"));
        }
        return null;
    }

    private static Decision evaluate(JSONObject c, String where) {
        RulesConfig config;
        try {
            config = RulesJson.parse(c.getJSONObject("snapshot").toString());
        } catch (RulesFormatException e) {
            throw new AssertionError(where + ": snapshot does not parse", e);
        }
        MatchInput in = input(c.getJSONObject("input"));
        Decision d = new Decision();
        RuleEngine.compile(config).evaluate(in, guards(c.optJSONObject("guards")), d);
        return d;
    }

    private static MatchInput input(JSONObject i) {
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

    /**
     * Fixtures name class, verdict, direction and origin by their wire name, exactly like {@code rules.json} does.
     * {@code clampReason} and {@code source} are engine results with no wire form: fixtures use the Java enum name for
     * them ({@code BLOCKS_ALL_NORMAL}, not the event wire name {@code G05_ALL_NORMAL}).
     */
    private static <E extends Enum<E> & WireEnum> E wire(Class<E> type, String name) {
        E value = Wire.parse(type, name, null);
        assertNotNull(value, "unknown wire name " + name);
        return value;
    }

    private static ClampReason optionalClamp(JSONObject o) {
        return o.opt("clampReason") instanceof String s ? ClampReason.valueOf(s) : null;
    }

    /** Every {@code verdicts/*.json} on the test classpath (copied from :contract by the {@code verdictFixtures} task). */
    private static List<Path> fixtures() throws IOException {
        URL url = VerdictFixturesTest.class.getClassLoader().getResource("verdicts");
        if (url == null) {
            throw new AssertionError("verdicts/ is not on the test classpath; run :rules:verdictFixtures");
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
            return files;
        }
    }
}
