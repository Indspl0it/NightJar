package dev.nightjar.payload.firewall;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Hashes;
import dev.nightjar.payload.capture.InjectionTokens;

/**
 * Recognises self-test canaries at enforcement points that cannot run the head filter (Appendix F).
 *
 * <p>Every canary carries an 8-byte random nonce in its user data, and the self-test registers its TPDU key in the token
 * registry before injecting. Recognition therefore keys on that per-run token, not on the originator: an ordinary
 * message can only match while a token exists, and a hostile message with the marker originator is never swallowed.
 *
 * <p>HK-23 receives nothing but the indicator value, so it cannot compute a key; there the gate answers the narrower
 * class-scoped question "is an MWI canary still in flight?", which limits the suppression to an actual MWI self-test.
 *
 * <p>Thread: read on the main looper and the CB looper; the source of the answer must be cheap and non-blocking.
 */
public final class CanaryGate {

    /** Answers "is a canary of this class still in flight?" for hooks that never see the message (HK-23). */
    @FunctionalInterface
    public interface Pending {
        boolean isPending(ClassId classId);

        /** Nothing pending (tests, and builds without a self-test). */
        Pending NONE = classId -> false;
    }

    private final InjectionLookup tokens;
    private final Pending pending;

    /**
     * @param tokens the head filter's token view, keyed on the TPDU hash of the canary nonce
     * @param pending class-scoped arming for HK-23
     */
    public CanaryGate(InjectionLookup tokens, Pending pending) {
        this.tokens = tokens == null ? InjectionLookup.NONE : tokens;
        this.pending = pending == null ? Pending.NONE : pending;
    }

    /**
     * True when {@code pdu} is a registered canary.
     *
     * @param pdu the TPDU, or the radio PDU with its SMSC prefix; both spellings are tried
     */
    public boolean matchesPdu(byte[] pdu) {
        if (pdu == null || pdu.length == 0) return false;
        try {
            String raw = Hashes.tpduKey(pdu);
            String stripped = Hashes.tpduKey(Hashes.stripSmsc(pdu));
            return tokens.peek(stripped, raw) == InjectionTokens.Kind.CANARY;
        } catch (Throwable t) {
            // Fail open: an unreadable PDU is ordinary traffic.
            return false;
        }
    }

    /** True while a canary of {@code classId} is still pending. */
    public boolean pending(ClassId classId) {
        try {
            return pending.isPending(classId);
        } catch (Throwable t) {
            return false;
        }
    }
}
