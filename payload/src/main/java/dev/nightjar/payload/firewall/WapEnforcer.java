package dev.nightjar.payload.firewall;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Hashes;
import dev.nightjar.payload.capture.InjectionTokens;
import dev.nightjar.payload.capture.WapContentType;
import dev.nightjar.payload.capture.WspMini;

/**
 * HK-14, {@code WapPushOverSms#dispatchWapPdu} (WAP rows).
 *
 * <p>The content type is parsed from the push PDU; a blocked push returns {@link WapPush#RESULT_CONSUMED} (1) so the
 * framework neither broadcasts it nor leaves the SMS state machine waiting. A skip result of {@code -1} would mean
 * "broadcast", which is exactly what a block must prevent.
 *
 * <p>WAP pushes never reach the head filter, so the release and self-test tokens are consulted here, before the rules:
 * a canary is consumed, and a released push runs the original so it is not quarantined a second time. The key
 * is computed from the push payload — the only bytes this hook receives — so the release and self-test paths register
 * the payload key of every port-2948 part.
 *
 * <p>The originating address is passed to the rules, so a rule with a sender condition can match a push.
 *
 * <p>A push whose WSP header cannot be parsed (CL-WAP-UNREADABLE) is consumed only while the firewall may block
 * at all (available, enabled, not in safe mode): the firewall-off and safe-mode clamps win. Otherwise it
 * runs the original; the HK-14 shield ({@code WapHooks.recover}) still catches a framework parser exception.
 *
 * <p>SUPL provisioning stays untouched during an emergency call and its grace period: the rule engine's SUPL grace
 * clamp already turns that verdict into ALLOW, so this class does not special-case it.
 *
 * <p>Thread: main looper.
 */
public final class WapEnforcer {

    private final Enforcer enforcer;
    private final InjectionLookup tokens;

    /** @param tokens the injection token view (release, canary, debug); {@link InjectionLookup#NONE} disables both */
    public WapEnforcer(Enforcer enforcer, InjectionLookup tokens) {
        this.enforcer = enforcer;
        this.tokens = tokens == null ? InjectionLookup.NONE : tokens;
    }

    /**
     * @param pdu the WAP push PDU (the SMS user data, not the TPDU)
     * @param originator originating address as the framework reports it, or {@code null} when unavailable
     * @param slot SIM slot, −1 when unknown
     * @param roaming roaming state at arrival
     */
    public HookDecision decide(byte[] pdu, String originator, int slot, boolean roaming) {
        try {
            InjectionTokens.Kind kind = peek(pdu);
            if (kind == InjectionTokens.Kind.CANARY) {
                // Interception is not a verdict, so it works with the firewall off or unavailable.
                return HookDecision.skip(WapPush.RESULT_CONSUMED, VerdictRecord.canaryBlock(),
                        WapContentType.classify(contentTypeOf(pdu)));
            }
            if (kind == InjectionTokens.Kind.RELEASE || kind == InjectionTokens.Kind.REPLAY) {
                // Our own release or replay is delivered whatever its content type. A DEBUG
                // injection runs the rules like a real push.
                return HookDecision.pass(VerdictRecord.releaseAllow(), null);
            }

            WspMini.Result parsed = WspMini.parse(pdu);
            if (!parsed.valid()) {
                // A WSP header that cannot be parsed is what the framework's own decoder trips over (the "SMS of
                // death" family): consume it so the phone process never sees it, but only while blocking is allowed.
                // With the firewall off, unavailable or in safe mode the push runs the original (fail open).
                ClassId unreadable = WapContentType.classify(null);
                if (enforcer.blockingAllowed()) {
                    return HookDecision.skip(WapPush.RESULT_CONSUMED, VerdictRecord.malformedBlock(), unreadable);
                }
                return HookDecision.pass(VerdictRecord.allow(), unreadable);
            }
            String contentType = parsed.contentType();
            ClassId predicted = WapContentType.classify(contentType);

            VerdictRecord record = enforcer.wap(predicted, contentType, originator, slot, roaming);
            if (record.blocked()) {
                return HookDecision.skip(WapPush.RESULT_CONSUMED, record, predicted);
            }
            return HookDecision.pass(record, predicted);
        } catch (Throwable t) {
            // A failure in the firewall never consumes a push.
            return HookDecision.pass();
        }
    }

    /** Content type of a push, or {@code null} when the PDU cannot be parsed. */
    public static String contentTypeOf(byte[] pdu) {
        WspMini.Result parsed = WspMini.parse(pdu);
        return parsed.valid() ? parsed.contentType() : null;
    }

    private InjectionTokens.Kind peek(byte[] pdu) {
        if (pdu == null || pdu.length == 0) return null;
        try {
            return tokens.peek(Hashes.tpduKey(pdu), null);
        } catch (Throwable t) {
            return null;
        }
    }
}
