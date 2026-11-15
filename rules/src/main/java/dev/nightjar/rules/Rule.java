package dev.nightjar.rules;

import dev.nightjar.contract.Verdict;
import java.util.Objects;

/**
 * One custom or preset rule. User rules are evaluated before preset rules; inside each origin a lower
 * {@code priority} is evaluated first, and ties keep file order ({@link RuleEngine}).
 *
 * @param unsupported {@code null} for a normal rule. Set by the reader when the rule uses something this engine
 *     version cannot evaluate faithfully (unknown key, op, action or direction, a value of the wrong type). Such a
 *     rule never matches (fail open) and the validator skips it. {@link RulesJson#serialize} does not write it back:
 *     no writer reads rules.json back (the app builds the document from its database, the payload only reads it), so
 *     nothing is lost.
 */
public record Rule(
        long id,
        int priority,
        boolean enabled,
        RuleOrigin origin,
        String name,
        Verdict action,
        Conditions when,
        String unsupported) {

    public Rule {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(action, "action");
        if (name == null) name = "";
        if (when == null) when = Conditions.ANY;
    }

    /** A supported rule. */
    public Rule(long id, int priority, boolean enabled, RuleOrigin origin, String name, Verdict action, Conditions when) {
        this(id, priority, enabled, origin, name, action, when, null);
    }

    public Rule withEnabled(boolean e) {
        return new Rule(id, priority, e, origin, name, action, when, unsupported);
    }

    public Rule withAction(Verdict a) {
        return new Rule(id, priority, enabled, origin, name, a, when, unsupported);
    }
}
