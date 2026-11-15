package dev.nightjar.rules;

/**
 * Supplies the decoded message text on demand. The engine calls it at most once per {@link
 * MatchInput} and only when a rule with {@code textContains} has matched every other condition. Return {@code null}
 * when the message has no text (data SMS, WAP, decode failure); a text condition then does not match.
 */
@FunctionalInterface
public interface TextProvider {
    CharSequence text();
}
