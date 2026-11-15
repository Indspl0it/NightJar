package dev.nightjar.rules;

import dev.nightjar.contract.CbChannels;
import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Direction;

/**
 * Facts about one message, filled by the caller before {@link RuleEngine#evaluate}. Mutable and reusable: call {@link
 * #reset()} and the setters per message. Setters are allocation-free after the internal buffers have grown once.
 *
 * <p>Thread: not thread-safe. Use one instance per thread (the payload keeps one for the main looper).
 */
public final class MatchInput {
    ClassId classId;
    Direction direction = Direction.IN;
    String originator;
    char[] origNorm = new char[32];
    int origNormLen = -1;
    String smsc;
    char[] smscNorm = new char[32];
    int smscNormLen = -1;
    int destPort = -1;
    String contentType;
    String subtype;
    int slot = -1;
    boolean roaming;
    boolean cbEmergency;
    TextProvider textProvider;
    private CharSequence text;
    private boolean textResolved;

    /** Clears every field (class unknown, direction IN, no originator, no subtype, port/slot −1, no text). */
    public MatchInput reset() {
        classId = null;
        direction = Direction.IN;
        originator = null;
        origNormLen = -1;
        smsc = null;
        smscNormLen = -1;
        destPort = -1;
        contentType = null;
        subtype = null;
        slot = -1;
        roaming = false;
        cbEmergency = false;
        textProvider = null;
        text = null;
        textResolved = false;
        return this;
    }

    /** Predicted class at the enforcement point. {@code null} = unknown: only class-less rules can match. */
    public MatchInput classId(ClassId c) {
        classId = c;
        return this;
    }

    public MatchInput direction(Direction d) {
        direction = d == null ? Direction.IN : d;
        return this;
    }

    /** Originator as received; normalised here. {@code null} = absent. */
    public MatchInput originator(String raw) {
        originator = raw;
        if (raw == null) {
            origNormLen = -1;
        } else {
            if (origNorm.length < raw.length()) origNorm = new char[raw.length()];
            origNormLen = Numbers.normalizeInto(raw, origNorm);
        }
        return this;
    }

    /** SMSC address as received. {@code null} = absent. */
    public MatchInput smsc(String raw) {
        smsc = raw;
        if (raw == null) {
            smscNormLen = -1;
        } else {
            if (smscNorm.length < raw.length()) smscNorm = new char[raw.length()];
            smscNormLen = Numbers.normalizeInto(raw, smscNorm);
        }
        return this;
    }

    /** Destination port, −1 when none. */
    public MatchInput destPort(int port) {
        destPort = port;
        return this;
    }

    /** WAP content type, {@code null} when none. */
    public MatchInput contentType(String ct) {
        contentType = ct;
        return this;
    }

    /**
     * Subtype code of the predicted class ({@code Subtype}), {@code null} when unknown or when the class has
     * none. Only rules with a {@code subtypes} condition read it.
     */
    public MatchInput subtype(String s) {
        subtype = s;
        return this;
    }

    /** SIM slot index, −1 when unknown. */
    public MatchInput slot(int s) {
        slot = s;
        return this;
    }

    public MatchInput roaming(boolean r) {
        roaming = r;
        return this;
    }

    /** Emergency cell broadcast flag. Set by the caller or via {@link #cbMessageIdentifier(int)}. */
    public MatchInput cbEmergency(boolean e) {
        cbEmergency = e;
        return this;
    }

    /** Sets {@link #cbEmergency(boolean)} from the CB message identifier using the shared {@link CbChannels} table. */
    public MatchInput cbMessageIdentifier(int messageIdentifier) {
        cbEmergency = CbChannels.isEmergency(messageIdentifier);
        return this;
    }

    /** Lazy text source. {@code null} = no text. */
    public MatchInput textProvider(TextProvider p) {
        textProvider = p;
        text = null;
        textResolved = false;
        return this;
    }

    /** Text, resolving the provider at most once. Exceptions from the provider count as "no text" (fail open). */
    CharSequence text() {
        if (!textResolved) {
            textResolved = true;
            try {
                text = textProvider == null ? null : textProvider.text();
            } catch (RuntimeException e) {
                text = null;
            }
        }
        return text;
    }

    public ClassId classId() {
        return classId;
    }

    public String originator() {
        return originator;
    }
}
