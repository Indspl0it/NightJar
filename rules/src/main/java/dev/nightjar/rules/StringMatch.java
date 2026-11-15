package dev.nightjar.rules;

import dev.nightjar.contract.WireEnum;
import java.util.Objects;

/**
 * A string condition {@code {op, value}}. Originator supports all ops; SMSC and content type support
 * {@code IS} and {@code PREFIX} only (other ops make the rule unsupported, see {@link Rule#unsupported()}).
 */
public record StringMatch(Op op, String value) {
    /** Comparison operator. */
    public enum Op implements WireEnum {
        IS("IS"),
        IS_NOT("IS_NOT"),
        PREFIX("PREFIX"),
        REGEX("REGEX");

        private final String wire;

        Op(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }
    }

    public StringMatch {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(value, "value");
    }

    public static StringMatch is(String v) {
        return new StringMatch(Op.IS, v);
    }

    public static StringMatch isNot(String v) {
        return new StringMatch(Op.IS_NOT, v);
    }

    public static StringMatch prefix(String v) {
        return new StringMatch(Op.PREFIX, v);
    }

    public static StringMatch regex(String v) {
        return new StringMatch(Op.REGEX, v);
    }
}
