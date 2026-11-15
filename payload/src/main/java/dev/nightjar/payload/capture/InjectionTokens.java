package dev.nightjar.payload.capture;

/**
 * One-time injection tokens. The release and self-test paths register
 * a TPDU key before injecting; the correlator consumes the token when the message comes back as
 * source 2. Unmatched source-2 messages are {@code CL-INJECTED}.
 *
 * <p>Implemented by {@code dev.nightjar.payload.inject.TokenRegistry}.
 */
public interface InjectionTokens {
    /** What a registered key belongs to. */
    enum Kind {
        RELEASE,
        CANARY,
        DEBUG,
        /** A local replay of a stored message (never over the air). */
        REPLAY
    }

    /**
     * A consumed token.
     *
     * @param kind release, canary, debug or replay
     * @param releaseOf original event id for a release or a replay
     * @param runId self-test run id for a canary
     * @param canaryClass expected class for a canary
     */
    record Match(Kind kind, String releaseOf, String runId, String canaryClass) {}

    /** Consumes the token for {@code tpduKey}; null when the key is not ours. */
    Match consume(String tpduKey);

    /** Registry with no tokens (tests, and builds without release/self-test). */
    InjectionTokens NONE = key -> null;
}
