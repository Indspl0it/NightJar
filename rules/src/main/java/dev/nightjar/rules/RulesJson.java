package dev.nightjar.rules;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Direction;
import dev.nightjar.contract.Ipc;
import dev.nightjar.contract.Schemas;
import dev.nightjar.contract.Verdict;
import dev.nightjar.contract.Wire;
import dev.nightjar.contract.WireEnum;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * {@code org.json} reader and writer for {@code rules.json}. Unknown top-level fields are
 * ignored. Unknown class names and unknown class-action values are dropped. A rule is kept but marked {@link
 * Rule#unsupported()}, so that it never matches (fail open), when it has an unknown key (in the rule, in {@code when},
 * in a match or port object), an unknown operator, action or direction, a field of the wrong type, an empty value, an
 * originator or SMSC value that normalises to empty, or a port range outside 0..65535. Ignoring any of these would make
 * the rule match more than the document says.
 *
 * <p>Thread: any (payload worker, app background). Never call on the phone main looper.
 */
public final class RulesJson {
    private RulesJson() {}

    /** Serialized snapshot limit: 64 KB of UTF-8. */
    public static final int MAX_BYTES = Ipc.MAX_RULES_BYTES;

    /** Key of a rule's condition object. The app builds one-rule documents with it. */
    public static final String KEY_WHEN = "when";

    // ---------------------------------------------------------------- read

    public static RulesConfig parse(byte[] utf8) throws RulesFormatException {
        if (utf8 == null) throw new RulesFormatException(RulesFormatException.Reason.MALFORMED, "null");
        if (utf8.length > MAX_BYTES) {
            throw new RulesFormatException(RulesFormatException.Reason.TOO_LARGE, utf8.length + " bytes");
        }
        return parseChecked(new String(utf8, StandardCharsets.UTF_8));
    }

    public static RulesConfig parse(String json) throws RulesFormatException {
        if (json == null) throw new RulesFormatException(RulesFormatException.Reason.MALFORMED, "null");
        int size = utf8Length(json);
        if (size > MAX_BYTES) throw new RulesFormatException(RulesFormatException.Reason.TOO_LARGE, size + " bytes");
        return parseChecked(json);
    }

    private static RulesConfig parseChecked(String json) throws RulesFormatException {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (RuntimeException | StackOverflowError e) {
            // org.json parses recursively: a deeply nested document overflows the stack instead of failing cleanly.
            throw new RulesFormatException(RulesFormatException.Reason.MALFORMED, "not a JSON object");
        }
        Object schema = o.opt("schema");
        if (!(schema instanceof Number) || ((Number) schema).intValue() != Schemas.RULES) {
            throw new RulesFormatException(RulesFormatException.Reason.SCHEMA_UNSUPPORTED, "schema=" + schema);
        }
        long revision = o.optLong("revision", 0);
        Preset preset = Wire.parse(Preset.class, o.optString("preset", null), Preset.CUSTOM);

        Map<ClassId, Verdict> actions = new java.util.EnumMap<>(ClassId.class);
        JSONObject ca = o.optJSONObject("classActions");
        if (ca != null) {
            // keys(), not keySet(): keySet() is @hide in the Android framework copy of org.json.
            for (java.util.Iterator<String> it = ca.keys(); it.hasNext(); ) {
                String k = it.next();
                ClassId c = Wire.parse(ClassId.class, k, null);
                Object v = ca.opt(k);
                Verdict verdict = v instanceof String ? Wire.parse(Verdict.class, (String) v, null) : null;
                if (c != null && verdict != null) actions.put(c, verdict);
            }
        }

        List<Rule> rules = new ArrayList<>();
        JSONArray ra = o.optJSONArray(Ipc.Key.RULES);
        if (ra != null) {
            for (int i = 0; i < ra.length(); i++) {
                JSONObject r = ra.optJSONObject(i);
                if (r != null) rules.add(readRule(r));
            }
        }

        List<String> exact = List.of();
        List<String> prefix = List.of();
        JSONObject ko = o.optJSONObject("knownOriginators");
        if (ko != null) {
            exact = strings(ko.optJSONArray("exact"));
            prefix = strings(ko.optJSONArray("prefix"));
        }
        int grace = RulesConfig.SUPL_GRACE_MIN_FLOOR;
        JSONObject g = o.optJSONObject("guards");
        if (g != null) grace = g.optInt("suplGraceMin", RulesConfig.SUPL_GRACE_MIN_FLOOR);

        return new RulesConfig(Schemas.RULES, revision, preset, actions, rules, exact, prefix, grace);
    }

    private static List<String> strings(JSONArray a) {
        if (a == null) return List.of();
        List<String> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            Object v = a.opt(i);
            if (v instanceof String) out.add((String) v);
        }
        return out;
    }

    /** Collects the first reason a rule is unsupported. */
    private static final class Problems {
        String first;

        void add(String p) {
            if (first == null) first = p;
        }
    }

    /** Every key {@link #readRule} reads; anything else makes the rule unsupported. */
    private static final java.util.Set<String> KNOWN_RULE_KEYS =
            java.util.Set.of("id", "priority", "enabled", "origin", "name", "action", KEY_WHEN);

    private static final java.util.Set<String> KNOWN_MATCH_KEYS = java.util.Set.of("op", "value");

    private static final java.util.Set<String> KNOWN_PORT_KEYS = java.util.Set.of("from", "to");

    /** Every key {@link #readWhen} evaluates; anything else makes the rule unsupported. */
    private static final java.util.Set<String> KNOWN_WHEN_KEYS = java.util.Set.of(
            "classes", "originator", "originatorKnown", "smsc", "port", "contentType", "slot", "roaming",
            "textContains", "direction", "subtypes");

    private static Rule readRule(JSONObject r) {
        Problems p = new Problems();
        checkKeys(r, KNOWN_RULE_KEYS, p);
        long id = r.optLong("id", 0);
        int priority = r.optInt("priority", 0);
        Object en = r.opt("enabled");
        boolean enabled = en == null || Boolean.TRUE.equals(en);
        // "enabled": "false" or 0 must not switch a rule on.
        if (en != null && !(en instanceof Boolean)) p.add("enabled");
        RuleOrigin origin = Wire.parse(RuleOrigin.class, r.optString("origin", null), RuleOrigin.USER);
        String name = r.optString("name", "");
        Verdict action = Wire.parse(Verdict.class, r.optString("action", null), null);
        if (action == null) {
            p.add("action");
            action = Verdict.ALLOW;
        }
        Conditions when = Conditions.ANY;
        Object w = r.opt(KEY_WHEN);
        if (w instanceof JSONObject) when = readWhen((JSONObject) w, p);
        else if (w != null) p.add(KEY_WHEN);
        return new Rule(id, priority, enabled, origin, name, action, when, p.first);
    }

    private static Conditions readWhen(JSONObject w, Problems p) {
        Conditions.Builder b = Conditions.builder();
        // A key this engine version does not evaluate would silently widen the rule (BLOCK matching more than the
        // document says), so the whole rule becomes unsupported instead (fail open).
        checkKeys(w, KNOWN_WHEN_KEYS, p);
        if (w.has("classes")) {
            JSONArray a = w.optJSONArray("classes");
            if (a == null) {
                p.add("classes");
            } else {
                EnumSet<ClassId> set = EnumSet.noneOf(ClassId.class);
                for (int i = 0; i < a.length(); i++) {
                    Object v = a.opt(i);
                    ClassId c = v instanceof String ? Wire.parse(ClassId.class, (String) v, null) : null;
                    if (c != null) set.add(c);
                }
                b.classes(set);
            }
        }
        b.originator(readMatch(w, "originator", true, p));
        b.smsc(readMatch(w, "smsc", false, p));
        b.contentType(readMatch(w, "contentType", false, p));
        b.originatorKnown(readBool(w, "originatorKnown", p));
        b.roaming(readBool(w, "roaming", p));
        if (w.has("port")) {
            Object v = w.opt("port");
            if (v instanceof JSONObject) {
                JSONObject po = (JSONObject) v;
                checkKeys(po, KNOWN_PORT_KEYS, p);
                Integer from = portNumber(po.opt("from"));
                Integer to = po.has("to") ? portNumber(po.opt("to")) : from;
                if (from != null && to != null && from <= to) {
                    b.port(new PortRange(from, to));
                } else {
                    p.add("port");
                }
            } else {
                p.add("port");
            }
        }
        if (w.has("slot")) {
            Object v = w.opt("slot");
            if (v instanceof Number) b.slot(((Number) v).intValue());
            else p.add("slot");
        }
        if (w.has("textContains")) {
            Object v = w.opt("textContains");
            if (v instanceof String) {
                b.textContains((String) v);
                // "" is a substring of every text, so the editor (RuleValidator) and an imported file must agree: it
                // does not narrow, and a rule whose only condition it is cannot block CL-NORMAL.
                if (((String) v).isEmpty()) p.add("textContains");
            } else {
                p.add("textContains");
            }
        }
        if (w.has("direction")) {
            Object v = w.opt("direction");
            Direction d = v instanceof String ? Wire.parse(Direction.class, (String) v, null) : null;
            if (d != null) b.direction(d);
            else p.add("direction");
        }
        if (w.has("subtypes")) {
            // A value that is not a string makes the rule unsupported: dropping it would widen the rule.
            JSONArray a = w.optJSONArray("subtypes");
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            if (a == null) {
                p.add("subtypes");
            } else {
                for (int i = 0; i < a.length(); i++) {
                    Object v = a.opt(i);
                    if (v instanceof String && !((String) v).isEmpty()) set.add((String) v);
                    else p.add("subtypes");
                }
                b.subtypes(set);
            }
        }
        return b.build();
    }

    private static void checkKeys(JSONObject o, java.util.Set<String> known, Problems p) {
        // keys(), not keySet(): keySet() is @hide in the Android framework copy of org.json.
        for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
            String key = it.next();
            if (!known.contains(key)) p.add(key);
        }
    }

    /** A whole number in 0..65535, else {@code null}. */
    private static Integer portNumber(Object v) {
        if (!(v instanceof Number)) return null;
        double d = ((Number) v).doubleValue();
        if (d != Math.rint(d) || d < 0 || d > 65535) return null;
        return (int) d;
    }

    private static Boolean readBool(JSONObject w, String key, Problems p) {
        if (!w.has(key)) return null;
        Object v = w.opt(key);
        if (v instanceof Boolean) return (Boolean) v;
        p.add(key);
        return null;
    }

    private static StringMatch readMatch(JSONObject w, String key, boolean allOps, Problems p) {
        if (!w.has(key)) return null;
        Object v = w.opt(key);
        if (!(v instanceof JSONObject)) {
            p.add(key);
            return null;
        }
        JSONObject m = (JSONObject) v;
        checkKeys(m, KNOWN_MATCH_KEYS, p);
        Object opv = m.opt("op");
        Object val = m.opt("value");
        StringMatch.Op op = opv instanceof String ? Wire.parse(StringMatch.Op.class, (String) opv, null) : null;
        if (op == null || !(val instanceof String) || ((String) val).isEmpty()) {
            p.add(key);
            return null;
        }
        if (!allOps && op != StringMatch.Op.IS && op != StringMatch.Op.PREFIX) {
            p.add(key);
            return null;
        }
        // An originator or SMSC value such as "+" or "00" normalises to empty and would match every number.
        if (!"contentType".equals(key) && op != StringMatch.Op.REGEX && Numbers.normalize((String) val).isEmpty()) {
            p.add(key);
            return null;
        }
        return new StringMatch(op, (String) val);
    }

    // ---------------------------------------------------------------- write

    /** Serializes {@code c}. Unsupported rules are omitted. Does not check the size; see {@link #serializeChecked}. */
    public static String serialize(RulesConfig c) {
        return toJson(c).toString();
    }

    /**
     * Serializes {@code c} and refuses documents over {@link #MAX_BYTES} (the app shows an error and
     * does not save).
     */
    public static String serializeChecked(RulesConfig c) throws RulesFormatException {
        String s = serialize(c);
        int size = utf8Length(s);
        if (size > MAX_BYTES) throw new RulesFormatException(RulesFormatException.Reason.TOO_LARGE, size + " bytes");
        return s;
    }

    public static JSONObject toJson(RulesConfig c) {
        try {
            JSONObject o = new JSONObject();
            o.put("schema", c.schema());
            o.put("revision", c.revision());
            o.put("preset", c.preset().wire());
            JSONObject ca = new JSONObject();
            for (Map.Entry<ClassId, Verdict> e : c.classActions().entrySet()) {
                ca.put(e.getKey().wire(), e.getValue().wire());
            }
            o.put("classActions", ca);
            JSONArray ra = new JSONArray();
            for (Rule r : c.rules()) {
                if (r.unsupported() == null) ra.put(ruleJson(r));
            }
            o.put(Ipc.Key.RULES, ra);
            JSONObject ko = new JSONObject();
            ko.put("exact", new JSONArray(c.knownExact()));
            ko.put("prefix", new JSONArray(c.knownPrefix()));
            o.put("knownOriginators", ko);
            JSONObject g = new JSONObject();
            g.put("suplGraceMin", c.suplGraceMin());
            o.put("guards", g);
            return o;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static JSONObject ruleJson(Rule r) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", r.id());
        o.put("priority", r.priority());
        o.put("enabled", r.enabled());
        o.put("origin", r.origin().wire());
        o.put("name", r.name());
        o.put("action", r.action().wire());
        Conditions w = r.when();
        JSONObject j = new JSONObject();
        if (w.classes() != null) {
            JSONArray a = new JSONArray();
            for (ClassId c : w.classes()) a.put(c.wire());
            j.put("classes", a);
        }
        putMatch(j, "originator", w.originator());
        if (w.originatorKnown() != null) j.put("originatorKnown", w.originatorKnown().booleanValue());
        putMatch(j, "smsc", w.smsc());
        if (w.port() != null) {
            JSONObject po = new JSONObject();
            po.put("from", w.port().from());
            po.put("to", w.port().to());
            j.put("port", po);
        }
        putMatch(j, "contentType", w.contentType());
        if (w.slot() != null) j.put("slot", w.slot().intValue());
        if (w.roaming() != null) j.put("roaming", w.roaming().booleanValue());
        if (w.textContains() != null) j.put("textContains", w.textContains());
        if (w.direction() != null) j.put("direction", wire(w.direction()));
        if (w.subtypes() != null) {
            JSONArray a = new JSONArray();
            for (String st : w.subtypes()) a.put(st);
            j.put("subtypes", a);
        }
        o.put(KEY_WHEN, j);
        return o;
    }

    private static String wire(WireEnum e) {
        return e.wire();
    }

    private static void putMatch(JSONObject j, String key, StringMatch m) throws JSONException {
        if (m == null) return;
        JSONObject o = new JSONObject();
        o.put("op", m.op().wire());
        o.put("value", m.value());
        j.put(key, o);
    }

    /** UTF-8 byte length without encoding. */
    public static int utf8Length(CharSequence s) {
        int n = 0;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) n += 1;
            else if (c < 0x800) n += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))) {
                n += 4;
                i++;
            } else n += 3;
        }
        return n;
    }
}
