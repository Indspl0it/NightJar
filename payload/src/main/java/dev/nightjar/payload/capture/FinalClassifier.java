package dev.nightjar.payload.capture;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Direction;
import dev.nightjar.contract.Subtype;
import dev.nightjar.pduparse.CbCategories;
import java.util.List;

/**
 * Final class once the consumer is known. The consumer rules (VVM, missed
 * call, carrier, RCS, app token, OEM) are rule 9 of the classification order in {@link ProvisionalClassifier}. A
 * consumer class wins over CL-NORMAL, a port match (rule 6) and a content match (rule 8); the PDU class then becomes
 * the first extra subtype. It never wins over rules 1 to 5 or 7 (anomaly, source, PID, DCS, UDH, WAP push): the
 * consumer class is then an extra subtype. A carrier voicemail SMS on a data port is CL-VVM, not an unknown port.
 *
 * <p>{@code CL-NORMAL} is the class of a message that reaches the default SMS app, so it survives
 * as the final class even when a Nightjar rule blocked or quarantined it — its PDUs are kept
 * and only the counter rule requires {@code FT-SHOWN}.
 *
 * <p>Thread: worker only (reads the event).
 */
public final class FinalClassifier {
    /** The package of the RCS client that consumes carrier-filter messages (CL-RCS-PROV). */
    public static final String RCS_PACKAGE = "com.google.android.ims";

    private FinalClassifier() {}

    public static ClassId classify(Event e) {
        if (e.cb != null) return CbCategories.classOf(e.cb.messageId());
        ClassId pdu = e.provisionalClass != null ? e.provisionalClass : e.predictedClass;
        ClassId consumer = consumerClass(e);
        if (consumer != null && consumerWins(e, pdu)) return consumer;
        return pdu != null ? pdu : ClassId.NORMAL;
    }

    /** True when a consumer class wins over the PDU class {@code pdu}. */
    private static boolean consumerWins(Event e, ClassId pdu) {
        if (pdu == null || pdu == ClassId.NORMAL) return true;
        ProvisionalClassifier.Result r = e.provisional;
        // Without the PDU result the rule that gave the class is not known: the PDU class stays.
        return r != null && r.classId() == pdu && r.consumerMayWin();
    }

    /** The class the consumer facts give (rule 9), or {@code null} when no consumer took the message. */
    static ClassId consumerClass(Event e) {
        if (e.vvmConsumed) return ClassId.VVM;
        if (e.missedCallConsumed) return ClassId.MISSED_CALL;
        if (e.carrierConsumed) {
            return RCS_PACKAGE.equals(e.consumerPackage) ? ClassId.RCS_PROV : ClassId.CARRIER;
        }
        if (e.appTokenConsumed) return ClassId.APP_TOKEN;
        if (e.oemConsumed || e.oemRedirected) return ClassId.OEM;
        return null;
    }

    /**
     * Sets {@link Event#finalClass}, {@link Event#subtype} and {@link Event#extraSubtypes}: the PDU rules' subtype and
     * extras when the final class is theirs, the package for CL-INJECTED, and a consumer class that did not win as an
     * extra.
     */
    public static void apply(Event e) {
        ClassId c = classify(e);
        e.finalClass = c;
        e.subtype = null;
        e.extraSubtypes.clear();
        ProvisionalClassifier.Result r = e.provisional;
        ClassId consumer = consumerClass(e);
        if (r != null && r.classId() == c) {
            e.subtype = r.subtype();
            e.extraSubtypes.addAll(r.extras());
        } else if (r != null && c == consumer && r.classId() != ClassId.NORMAL) {
            // The consumer won over a port or content match: that match and its extras follow as extras.
            e.extraSubtypes.addAll(ProvisionalClassifier.extrasFor(c, r));
        }
        if (c == ClassId.INJECTED) e.subtype = e.callingPackage;
        if (e.cb != null) e.subtype = CbCategories.subtypeOf(e.cb.messageId());
        if (consumer != null && consumer != c) addExtra(e.extraSubtypes, ProvisionalClassifier.extraCode(c, consumer, null));
    }

    /**
     * The subtype to serialize: {@link Event#subtype}, or for an outbound event the outbound subtype of its class
     * (the outbound ledger builds its events without {@link #apply}).
     */
    public static String subtype(Event e) {
        if (e.subtype != null || e.direction != Direction.OUT || e.finalClass == null) return e.subtype;
        return switch (e.finalClass) {
            case MO_STK -> Subtype.MoStk.SEND_SMS;
            case MO_VISIBLE, MO_UNPERSISTED, MO_RAW -> e.callingPackage;
            case MO_DATA -> e.destPort >= 0 ? String.valueOf(e.destPort) : null;
            default -> null;
        };
    }

    private static void addExtra(List<String> extras, String code) {
        if (code != null && !extras.contains(code)) extras.add(code);
    }
}
