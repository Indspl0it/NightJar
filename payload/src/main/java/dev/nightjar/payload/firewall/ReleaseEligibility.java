package dev.nightjar.payload.firewall;

import dev.nightjar.contract.ClassId;
import dev.nightjar.payload.capture.ClassPredictor;
import dev.nightjar.payload.capture.PduFacts;
import dev.nightjar.payload.capture.ProvisionalClassifier;
import dev.nightjar.rules.EnforcementMatrix;

/**
 * "Releasable" column and which quarantined classes may be re-injected.
 *
 * <p>The class table is {@link EnforcementMatrix#releasable} in {@code:rules}, shared with the app. It
 * excludes CL-WAP-UNREADABLE and CL-MALFORMED-SMS: a re-injection would give the framework parser the push or PDU that
 * it can trip over (RULES-X8). CL-UNREADABLE-SMS is releasable.
 * This class adds the format check and the class of the released PDUs.
 *
 * <p>The class is taken from the released PDUs ({@link #classify}), not from the event ring: the ring drops events
 * that the app has acknowledged, and a quarantined message is released long after that.
 *
 * <p>Thread: any (pure).
 */
public final class ReleaseEligibility {

    private ReleaseEligibility() {}

    /** True when {@code release} may be offered for this class: the shared {@link EnforcementMatrix}. */
    public static boolean releasable(ClassId c) {
        return EnforcementMatrix.releasable(c);
    }

    /** True when the format can be re-injected: only 3GPP TPDUs. */
    public static boolean releasableFormat(String format) {
        return "3gpp".equals(format);
    }

    /**
     * The class of a message from its PDUs, as the head filter predicts it (part 1 carries the class and the WAP header).
     * Each part may be a radio PDU (SMSC prefix) or a bare TPDU. {@code null} when part 1 cannot be parsed, which makes
     * the message not releasable (fail closed for a release: nothing is re-injected). The one exception is
     * CL-UNREADABLE-SMS: its parse is invalid, but AOSP reads it as an SMS, so it is released like any held
     * message. Known damage (CL-MALFORMED-SMS) stays {@code null}.
     */
    public static ClassId classify(byte[][] pdus) {
        if (pdus == null || pdus.length == 0 || pdus[0] == null || pdus[0].length == 0) return null;
        try {
            PduFacts facts = SmsFilterCore.facts(pdus[0]);
            ProvisionalClassifier.Result r = ClassPredictor.predictResult(facts, false, false, null);
            ClassId c = r == null ? null : r.classId();
            if (!facts.valid() && c != ClassId.UNREADABLE_SMS) return null;
            return c;
        } catch (Throwable t) {
            return null;
        }
    }
}
