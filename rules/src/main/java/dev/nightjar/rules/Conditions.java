package dev.nightjar.rules;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Direction;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The {@code when} block of a rule. Every field is optional ({@code null} = no condition);
 * present fields are ANDed.
 *
 * @param classes predicted class set; an empty set matches nothing
 * @param originator matched against the originator (IS/IS_NOT/PREFIX on the normalised number, REGEX on the raw value
 *     with {@code find()} semantics)
 * @param originatorKnown whether the originator is in {@code knownOriginators}; a missing originator counts as unknown
 * @param smsc IS/PREFIX on the normalised SMSC address
 * @param port destination port range (inclusive)
 * @param contentType IS/PREFIX, ASCII case-insensitive (WAP)
 * @param slot SIM slot index
 * @param roaming roaming state at arrival
 * @param textContains case-insensitive substring of the decoded text (: evaluated lazily)
 * @param direction IN or OUT
 * @param subtypes subtype codes of the predicted class ({@code Subtype}), exact match; an empty set matches
 *  nothing, and a message without a subtype never matches. Null and empty codes are dropped.
 */
public record Conditions(
        Set<ClassId> classes,
        StringMatch originator,
        Boolean originatorKnown,
        StringMatch smsc,
        PortRange port,
        StringMatch contentType,
        Integer slot,
        Boolean roaming,
        String textContains,
        Direction direction,
        Set<String> subtypes) {

    /** No conditions: matches every message. */
    public static final Conditions ANY = builder().build();

    public Conditions {
        if (classes != null) {
            classes = classes.isEmpty()
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(EnumSet.copyOf(classes));
        }
        if (subtypes != null) {
            // Insertion order is kept so that rules.json serializes the same way each time. A null or empty code can
            // match no subtype; dropping it only narrows the rule.
            LinkedHashSet<String> s = new LinkedHashSet<>();
            for (String st : subtypes) if (st != null && !st.isEmpty()) s.add(st);
            subtypes = Collections.unmodifiableSet(s);
        }
    }

    /**
     * True when the rule narrows by sender, port or text. Only such rules may block or quarantine
     * CL-NORMAL, and only such rules may block consumer classes (CL-VVM, CL-CARRIER, …) beyond their class actions.
     *
     * <p>A {@code subtypes} condition does not count: it narrows by what the message is, not by who sent it.
     *
     * <p>Not every present field narrows: {@code originatorKnown = false} holds for every sender outside the known
     * lists, an empty {@code textContains} is a substring of everything, and a port range covering all 65536 ports
     * excludes nothing. Also: {@code IS_NOT} holds for every sender but one, an {@code IS}/{@code PREFIX} value that
     * normalises to empty ({@code +}, {@code 00}) is a prefix of every number, a regex that matches the empty string
     * ({@code .*}) matches every sender, and a port range outside 0..65535 (for example {@code -1}, "no port") is no
     * port condition. Those alone must not unlock BLOCK/QUARANTINE for CL-NORMAL.
     *
     * <p>Not for the evaluation hot path: a regex is compiled on each call. The engine computes this once per rule.
     */
    public boolean hasSenderPortOrText() {
        return originatorNarrows(originator)
                || Boolean.TRUE.equals(originatorKnown)
                || (port != null && port.isValid() && (long) port.to() - port.from() + 1L < 65536L)
                || (textContains != null && !textContains.isEmpty());
    }

    private static boolean originatorNarrows(StringMatch m) {
        if (m == null) return false;
        switch (m.op()) {
            case IS:
            case PREFIX:
                return !Numbers.normalize(m.value()).isEmpty();
            case REGEX:
                try {
                    return SafeRegex.compile(m.value()).find("") != SafeRegex.MATCH;
                } catch (SafeRegex.SyntaxException e) {
                    return false; // the engine skips the rule
                }
            case IS_NOT:
            default:
                return false;
        }
    }

    /** True when the rule can match a CL-NORMAL message at all. */
    public boolean canMatchNormal() {
        return (classes == null || classes.contains(ClassId.NORMAL))
                && direction != Direction.OUT
                && contentType == null
                && subtypes == null; // CL-NORMAL has no subtype
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder. */
    public static final class Builder {
        private Set<ClassId> classes;
        private StringMatch originator;
        private Boolean originatorKnown;
        private StringMatch smsc;
        private PortRange port;
        private StringMatch contentType;
        private Integer slot;
        private Boolean roaming;
        private String textContains;
        private Direction direction;
        private Set<String> subtypes;

        public Builder classes(Set<ClassId> v) {
            classes = v;
            return this;
        }

        public Builder classes(ClassId first, ClassId... rest) {
            classes = EnumSet.of(first, rest);
            return this;
        }

        public Builder originator(StringMatch v) {
            originator = v;
            return this;
        }

        public Builder originatorKnown(Boolean v) {
            originatorKnown = v;
            return this;
        }

        public Builder smsc(StringMatch v) {
            smsc = v;
            return this;
        }

        public Builder port(PortRange v) {
            port = v;
            return this;
        }

        public Builder contentType(StringMatch v) {
            contentType = v;
            return this;
        }

        public Builder slot(Integer v) {
            slot = v;
            return this;
        }

        public Builder roaming(Boolean v) {
            roaming = v;
            return this;
        }

        public Builder textContains(String v) {
            textContains = v;
            return this;
        }

        public Builder direction(Direction v) {
            direction = v;
            return this;
        }

        public Builder subtypes(Set<String> v) {
            subtypes = v;
            return this;
        }

        public Builder subtypes(String first, String... rest) {
            LinkedHashSet<String> s = new LinkedHashSet<>();
            s.add(first);
            Collections.addAll(s, rest);
            subtypes = s;
            return this;
        }

        public Conditions build() {
            return new Conditions(
                    classes, originator, originatorKnown, smsc, port, contentType, slot, roaming, textContains,
                    direction, subtypes);
        }
    }
}
