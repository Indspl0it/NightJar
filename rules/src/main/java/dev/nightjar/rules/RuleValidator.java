package dev.nightjar.rules;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Subtype;
import dev.nightjar.contract.Verdict;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a {@code rules.json} snapshot for the app's rule editor (-03). The
 * validator never throws; it returns every problem it can name. An invalid snapshot is not saved.
 *
 * <p>Thread: any (app background).
 */
public final class RuleValidator {
    private RuleValidator() {}

    /** Typed error codes shown by the editor. */
    public enum Code {
        /** The regex is not supported by {@link SafeRegex} or is malformed. */
        REGEX_INVALID,
        /** The regex is longer than {@link SafeRegex#MAX_PATTERN}. */
        REGEX_TOO_LONG,
        /**
         * A condition value is empty, or an originator/SMSC value is empty after normalisation ({@code +}, {@code
         * 00}).
         */
        EMPTY_VALUE,
        /** The action is not allowed for the rule's class. */
        ACTION_NOT_ALLOWED_FOR_CLASS,
        /** A rule that would block/quarantine every CL-NORMAL message without a sender/port/text condition. */
        BLOCKS_ALL_NORMAL,
        /** The serialized document exceeds {@link RulesJson#MAX_BYTES}. */
        TOO_LARGE,
        /**
         * A condition value that can never match as intended: a port range outside 0..65535 or with {@code from > to},
         * subtypes without classes (the same code exists in several classes), or a subtype code that none of the
         * rule's classes has.
         */
        INVALID_VALUE,
        /**
         * Two rules share an id, a user rule has an id in the preset range, or a preset rule has an id below it.
         * A shared id makes the deciding rule of a verdict ambiguous.
         */
        INVALID_ID
    }

    /** One problem. {@code ruleId} is 0 for document-level errors (size, class actions, known originators). */
    public record Error(Code code, long ruleId, String detail) {
        @Override
        public String toString() {
            return code + "(" + ruleId + "): " + detail;
        }
    }

    public static List<Error> validate(RulesConfig config) {
        List<Error> errors = new ArrayList<>();
        if (config == null) {
            errors.add(new Error(Code.EMPTY_VALUE, 0, "no document"));
            return errors;
        }

        int size = RulesJson.utf8Length(RulesJson.serialize(config));
        if (size > RulesJson.MAX_BYTES) errors.add(new Error(Code.TOO_LARGE, 0, size + " bytes"));

        for (Map.Entry<ClassId, Verdict> e : config.classActions().entrySet()) {
            Verdict v = e.getValue();
            if (!EnforcementMatrix.allows(EnforcementMatrix.classActionMask(e.getKey()), v)) {
                errors.add(new Error(Code.ACTION_NOT_ALLOWED_FOR_CLASS, 0,
                        e.getKey().wire() + " class action " + v.wire()));
            }
        }

        for (String s : config.knownExact()) checkKnown(s, errors);
        for (String s : config.knownPrefix()) checkKnown(s, errors);

        Set<Long> ids = new HashSet<>();
        for (Rule rule : config.rules()) {
            checkId(rule, ids, errors);
            if (rule.unsupported() != null) continue;
            Conditions w = rule.when();
            boolean senderPortOrText = w.hasSenderPortOrText();

            checkMatch(w.originator(), true, rule.id(), errors);
            checkMatch(w.smsc(), true, rule.id(), errors);
            checkMatch(w.contentType(), false, rule.id(), errors);
            if (w.textContains() != null && w.textContains().isEmpty()) {
                errors.add(new Error(Code.EMPTY_VALUE, rule.id(), "textContains"));
            }
            if (w.classes() != null && w.classes().isEmpty()) {
                errors.add(new Error(Code.EMPTY_VALUE, rule.id(), "classes"));
            }
            if (w.port() != null && !w.port().isValid()) {
                errors.add(new Error(Code.INVALID_VALUE, rule.id(), "port " + w.port().from() + "-" + w.port().to()));
            }
            checkSubtypes(w, rule.id(), errors);

            Verdict action = rule.action();
            if ((action == Verdict.BLOCK || action == Verdict.QUARANTINE) && w.canMatchNormal() && !senderPortOrText) {
                errors.add(new Error(Code.BLOCKS_ALL_NORMAL, rule.id(), rule.name()));
            }
            // Every action but ALLOW: a NOTIFY rule for a class that allows only ALLOW is clamped too.
            if (action != Verdict.ALLOW && w.classes() != null) {
                for (ClassId c : w.classes()) {
                    if (!EnforcementMatrix.allows(
                            EnforcementMatrix.ruleActionMask(c, senderPortOrText), action)) {
                        errors.add(new Error(Code.ACTION_NOT_ALLOWED_FOR_CLASS, rule.id(),
                                action.wire() + " for " + c.wire()));
                        break;
                    }
                }
            }
        }
        return errors;
    }

    private static void checkId(Rule rule, Set<Long> ids, List<Error> errors) {
        if (!ids.add(rule.id())) {
            errors.add(new Error(Code.INVALID_ID, rule.id(), "duplicate id"));
        }
        boolean presetRange = rule.id() >= Presets.PRESET_ID_BASE;
        if (rule.origin() == RuleOrigin.USER && presetRange) {
            errors.add(new Error(Code.INVALID_ID, rule.id(), "user rule in the preset id range"));
        } else if (rule.origin() == RuleOrigin.PRESET && !presetRange) {
            errors.add(new Error(Code.INVALID_ID, rule.id(), "preset rule below the preset id range"));
        }
    }

    private static void checkSubtypes(Conditions w, long ruleId, List<Error> errors) {
        Set<String> subtypes = w.subtypes();
        if (subtypes == null) return;
        if (subtypes.isEmpty()) {
            errors.add(new Error(Code.EMPTY_VALUE, ruleId, "subtypes"));
            return;
        }
        if (w.classes() == null) {
            errors.add(new Error(Code.INVALID_VALUE, ruleId, "subtypes need classes"));
            return;
        }
        Set<String> known = new HashSet<>();
        for (ClassId c : w.classes()) {
            if (Subtype.isFreeForm(c)) return; // a package name, port or content type can be any text
            known.addAll(Subtype.known(c));
        }
        for (String st : subtypes) {
            if (!known.contains(st)) errors.add(new Error(Code.INVALID_VALUE, ruleId, "subtype " + st));
        }
    }

    private static void checkKnown(String s, List<Error> errors) {
        if (s == null || Numbers.normalize(s).isEmpty()) {
            errors.add(new Error(Code.EMPTY_VALUE, 0, "known originator"));
        }
    }

    /** {@code number}: originator or SMSC, where the value must not normalise to empty. */
    private static void checkMatch(StringMatch m, boolean number, long ruleId, List<Error> errors) {
        if (m == null) return;
        if (m.value() == null || m.value().isEmpty()) {
            errors.add(new Error(Code.EMPTY_VALUE, ruleId, m.op().wire()));
            return;
        }
        if (m.op() == StringMatch.Op.REGEX) {
            try {
                SafeRegex.compile(m.value());
            } catch (SafeRegex.SyntaxException e) {
                errors.add(new Error(e.tooLong ? Code.REGEX_TOO_LONG : Code.REGEX_INVALID, ruleId, e.getMessage()));
            }
        } else if (number && Numbers.normalize(m.value()).isEmpty()) {
            errors.add(new Error(Code.EMPTY_VALUE, ruleId, m.op().wire() + " " + m.value()));
        }
    }
}
