package dev.nightjar.payload.firewall;

/**
 * Supplies decoded message text for rules with a {@code textContains} condition.
 *
 * <p>The rule engine asks at most once per evaluation and only after every other condition matched, so decoding happens
 * on the main looper only for the rare message a text rule actually examines.
 *
 * <p>Thread: the hook thread that evaluates the verdict. Implementations must not throw; return {@code null} on failure.
 */
@FunctionalInterface
public interface TextSource {

    /** Decoded body, or {@code null} when the message has none or cannot be decoded. */
    CharSequence decode(byte[] tpdu);

    /** No text: text conditions never match. */
    TextSource NONE = tpdu -> null;
}
