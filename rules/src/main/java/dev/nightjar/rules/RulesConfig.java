package dev.nightjar.rules;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Schemas;
import dev.nightjar.contract.Verdict;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The parsed {@code rules.json} document (schema 1). Immutable. A class absent from {@code classActions}
 * has the action {@link #absentClassAction}: ALLOW, except CL-UNREADABLE-SMS.
 *
 * @param suplGraceMin {@code guards.suplGraceMin} as stored; use {@link #effectiveSuplGraceMin()} for the clamped value
 */
public record RulesConfig(
        int schema,
        long revision,
        Preset preset,
        Map<ClassId, Verdict> classActions,
        List<Rule> rules,
        List<String> knownExact,
        List<String> knownPrefix,
        int suplGraceMin) {

    /** Floor and settings range for the SUPL emergency grace. */
    public static final int SUPL_GRACE_MIN_FLOOR = 5;

    public static final int SUPL_GRACE_MIN_MAX = 60;

    /**
     * The empty document: Monitor, nothing blocked. The payload uses it before its first snapshot, so a missing snapshot
     * allows everything (fail open). A new install does not keep it: the app applies {@link Presets#DEFAULT_PRESET}
     * when onboarding ends.
     */
    public static final RulesConfig DEFAULT = new RulesConfig(
            Schemas.RULES, 0, Preset.MONITOR, Map.of(), List.of(), List.of(), List.of(), SUPL_GRACE_MIN_FLOOR);

    public RulesConfig {
        Objects.requireNonNull(preset, "preset");
        EnumMap<ClassId, Verdict> m = new EnumMap<>(ClassId.class);
        if (classActions != null) m.putAll(classActions);
        classActions = Collections.unmodifiableMap(m);
        rules = rules == null ? List.of() : List.copyOf(rules);
        knownExact = knownExact == null ? List.of() : List.copyOf(knownExact);
        knownPrefix = knownPrefix == null ? List.of() : List.copyOf(knownPrefix);
    }

    /** {@code suplGraceMin} clamped to 5..60 minutes (flo). */
    public int effectiveSuplGraceMin() {
        return Math.max(SUPL_GRACE_MIN_FLOOR, Math.min(SUPL_GRACE_MIN_MAX, suplGraceMin));
    }

    /**
     * The action of a class that {@code classActions} does not name. It is ALLOW, except for
     * CL-UNREADABLE-SMS outside Monitor: QUARANTINE. A snapshot from before CL-UNREADABLE-SMS has no entry for it, and
     * the default for it is Hold. Monitor holds nothing. To allow CL-UNREADABLE-SMS, the snapshot names ALLOW.
     */
    public static Verdict absentClassAction(ClassId c, Preset preset) {
        if (c == ClassId.UNREADABLE_SMS && preset != Preset.MONITOR) return Verdict.QUARANTINE;
        return Verdict.ALLOW;
    }

    /** The configured class action, {@link #absentClassAction} when absent. */
    public Verdict classAction(ClassId c) {
        Verdict v = classActions.get(c);
        return v == null ? absentClassAction(c, preset) : v;
    }

    /**
     * Sets one class action and relabels the preset {@code CUSTOM} (AC2). The entry is removed when the action
     * is the absent action under CUSTOM, so ALLOW of CL-UNREADABLE-SMS stays in the map.
     */
    public RulesConfig withClassAction(ClassId c, Verdict v) {
        EnumMap<ClassId, Verdict> m = new EnumMap<>(ClassId.class);
        m.putAll(classActions);
        if (v == absentClassAction(c, Preset.CUSTOM)) m.remove(c);
        else m.put(c, v);
        return new RulesConfig(schema, revision, Preset.CUSTOM, m, rules, knownExact, knownPrefix, suplGraceMin);
    }

    public RulesConfig withRules(List<Rule> r) {
        return new RulesConfig(schema, revision, preset, classActions, r, knownExact, knownPrefix, suplGraceMin);
    }

    public RulesConfig withRevision(long r) {
        return new RulesConfig(schema, r, preset, classActions, rules, knownExact, knownPrefix, suplGraceMin);
    }

    /** Changes only the preset label ({@link Presets#apply} replaces the preset's data too). */
    public RulesConfig withPreset(Preset p) {
        return new RulesConfig(schema, revision, p, classActions, rules, knownExact, knownPrefix, suplGraceMin);
    }

    public RulesConfig withKnownOriginators(List<String> exact, List<String> prefix) {
        return new RulesConfig(schema, revision, preset, classActions, rules, exact, prefix, suplGraceMin);
    }

    public RulesConfig withSuplGraceMin(int min) {
        return new RulesConfig(schema, revision, preset, classActions, rules, knownExact, knownPrefix, min);
    }

    /** User rules only (origin USER), in list order. */
    public List<Rule> userRules() {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules) if (r.origin() == RuleOrigin.USER) out.add(r);
        return out;
    }
}
