package dev.nightjar.payload.firewall;

import dev.nightjar.payload.capture.InjectionTokens;

/**
 * Read-only view of the injection tokens, used by the head filter before the correlator consumes them.
 *
 * <p>The filter must not consume: the correlator needs the token to flag the event ({@code flags.releaseOf},
 * {@code flags.selfTest}). It only needs to know whether the incoming key is ours and which kind it is. The kind is the
 * same enum the correlator consumes, so the two sides can never disagree.
 *
 * <p>Thread: main looper.
 */
public interface InjectionLookup {

    /**
     * @return the token kind, or {@code null} when the key is ordinary traffic
     */
    InjectionTokens.Kind peek(String key, String altKey);

    /** No tokens. */
    InjectionLookup NONE = (key, altKey) -> null;
}
