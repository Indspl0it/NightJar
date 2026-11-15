package dev.nightjar.rules;

/** {@code rules.json} could not be read. Callers keep their previous snapshot or fall back to all-ALLOW (fail open). */
public final class RulesFormatException extends Exception {
    private static final long serialVersionUID = 1L;

    /** Why the document was rejected. */
    public enum Reason {
        /** More than {@link RulesJson#MAX_BYTES} bytes of UTF-8 (limit). */
        TOO_LARGE,
        /** Not a JSON object. */
        MALFORMED,
        /** {@code schema} missing or not {@link dev.nightjar.contract.Schemas#RULES}. */
        SCHEMA_UNSUPPORTED
    }

    private final Reason reason;

    public RulesFormatException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
