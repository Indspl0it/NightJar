package dev.nightjar.rules;

import dev.nightjar.contract.Verdict;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Result of one evaluation: final action, the rule that decided it, and the clamp if any (event {@code verdict}).
 * Mutable and reusable so the hot path does not allocate.
 *
 * <p>Thread: not thread-safe; one instance per caller thread.
 */
public final class Decision {
    /** Where the pre-clamp action came from. */
    public enum Source {
        /** First matching enabled rule. */
        RULE,
        /** The class action. */
        CLASS_ACTION,
        /** Implicit last rule: ALLOW (class absent from {@code classActions} or class unknown). */
        DEFAULT,
        /** No snapshot loaded: ALLOW (fail open). */
        NO_SNAPSHOT,
        /** The engine threw: ALLOW (fail open). */
        ERROR
    }

    Verdict action = Verdict.ALLOW;
    Source source = Source.DEFAULT;
    boolean hasRule;
    long ruleId;
    RuleOrigin ruleOrigin;
    Verdict clampedFrom;
    ClampReason clampReason;
    int regexAborts;
    boolean textEvaluated;

    /** Resets to ALLOW / DEFAULT with no rule and no clamp. */
    public Decision reset() {
        action = Verdict.ALLOW;
        source = Source.DEFAULT;
        hasRule = false;
        ruleId = 0;
        ruleOrigin = null;
        clampedFrom = null;
        clampReason = null;
        regexAborts = 0;
        textEvaluated = false;
        return this;
    }

    /** Final action after clamps. */
    public Verdict action() {
        return action;
    }

    public Source source() {
        return source;
    }

    /** True when a rule decided the pre-clamp action; {@link #ruleId()} is then valid. */
    public boolean hasRule() {
        return hasRule;
    }

    public long ruleId() {
        return ruleId;
    }

    /** Origin of the deciding rule, {@code null} when {@link #hasRule()} is false. */
    public RuleOrigin ruleOrigin() {
        return ruleOrigin;
    }

    /** The action before clamping, or {@code null} when not clamped. */
    public Verdict clampedFrom() {
        return clampedFrom;
    }

    public ClampReason clampReason() {
        return clampReason;
    }

    public boolean isClamped() {
        return clampReason != null;
    }

    /**
     * Number of regex conditions that hit the step budget during this evaluation (counted as no match).
     * The engine does not log it. A caller that wants a W line for a budget abort must read this value.
     */
    public int regexAborts() {
        return regexAborts;
    }

    /** True when the text provider was consulted during this evaluation. */
    public boolean textEvaluated() {
        return textEvaluated;
    }

    /** Copies {@code o} into this instance. */
    public Decision set(Decision o) {
        action = o.action;
        source = o.source;
        hasRule = o.hasRule;
        ruleId = o.ruleId;
        ruleOrigin = o.ruleOrigin;
        clampedFrom = o.clampedFrom;
        clampReason = o.clampReason;
        regexAborts = o.regexAborts;
        textEvaluated = o.textEvaluated;
        return this;
    }

    /** Event DTO {@code verdict} object: {@code {action, ruleId?, clampedFrom?, clampReason?}}. Worker thread only. */
    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("action", action.wire());
            if (hasRule) o.put("ruleId", ruleId);
            if (clampedFrom != null) o.put("clampedFrom", clampedFrom.wire());
            if (clampReason != null) o.put("clampReason", clampReason.wire());
            return o;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return "Decision{" + action + ", " + source + (hasRule ? ", rule=" + ruleId : "")
                + (clampReason != null ? ", clampedFrom=" + clampedFrom + ", reason=" + clampReason : "") + "}";
    }
}
