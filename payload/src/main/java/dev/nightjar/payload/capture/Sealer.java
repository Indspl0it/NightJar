package dev.nightjar.payload.capture;

import dev.nightjar.contract.AckResult;
import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Fate;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.payload.firewall.VerdictRecord;
import java.util.function.BooleanSupplier;

/**
 * Fate decision for one event (03).
 *
 * <p>Terminal fates come from, in order: an explicit seal (a hook that knows the fate), the
 * firewall verdict, an Android/OEM consumer, the radio-specific branch, a status report, the
 * ACK result, and finally the time-based fates — {@code FT-PENDING-UNLOCK} 5 s after the last
 * stage while the user is locked, and 30 s after the last stage {@code FT-ACCEPTED} (Android accepted the
 * message and no consumer reported it) or {@code FT-NOT-DISPATCHED} (Android did not dispatch it). the sealer
 * never gives {@code FT-UNKNOWN}.
 *
 * <p>Thread: worker only. The caller adds the timeout trace step ({@link #noteTimeout(Event)}).
 */
public final class Sealer {
    /** 30 s after the last stage. */
    public static final long TIMEOUT_NS = 30_000_000_000L;
    /** 5 s while locked before sealing FT-PENDING-UNLOCK. */
    public static final long PENDING_UNLOCK_DELAY_NS = 5_000_000_000L;

    private final BooleanSupplier userUnlocked;

    public Sealer(BooleanSupplier userUnlocked) {
        this.userUnlocked = userUnlocked == null ? () -> true : userUnlocked;
    }

    /**
     * The fate the event must be sealed with, or null while more stages may arrive.
     * {@code e.fate} already set means an explicit seal and is returned unchanged.
     */
    public Fate decide(Event e, long nowNs) {
        if (e.sealed) return null;
        if (e.fate != null) return e.fate;

        // A canary interception is flagged by the dispatch observation that follows it; sealing first would emit the
        // event without its run id. Wait a moment for that observation.
        if (VerdictRecord.CANARY.equals(e.clampReason) && awaitingDispatch(e, nowNs)) return null;

        // An explicit consumer decision outranks everything else.
        if (e.blockedByAndroid) return Fate.BLOCKLIST;
        if (e.oemConsumed) return Fate.OEM_DROPPED;
        if (e.oemRedirected) return Fate.OEM_REDIRECTED;
        if (e.verdict == dev.nightjar.contract.Verdict.BLOCK) return Fate.BLOCKED;
        if (e.verdict == dev.nightjar.contract.Verdict.QUARANTINE) return Fate.QUARANTINED;
        if (e.mwiSuppressed) {
            // HK-23 runs before HK-06's dispatch observation, which is what flags a canary: wait for it so the event
            // is sealed once, with its self-test flags, instead of twice.
            if (awaitingDispatch(e, nowNs)) return null;
            // Appendix F: the MWI canary ends as FT-SYSTEM; suppression for a real indicator is a block.
            return e.flags.selfTest ? Fate.SYSTEM : Fate.BLOCKED;
        }
        if (e.released) return Fate.RELEASED;
        if (e.cb != null) return Fate.SYSTEM;

        // Status reports are sealed immediately.
        if (e.facts != null && e.facts.statusReport()) return Fate.SYSTEM;
        if (e.statusReport) return Fate.SYSTEM;
        if (e.ackResult != null && e.ackResult == AckResult.OUT_OF_MEMORY) return Fate.NACK_STORAGE;
        // Android refused the message at dispatch; it deletes the PDU and no consumer will report it.
        if (e.ackResult != null && AckResult.rejected(e.ackResult)) return Fate.REJECTED;

        if (e.branch != null) {
            switch (e.branch) {
                case "type0":
                    // The radio-specific stage comes before the dispatch observation; sealing now would emit the
                    // event without its canary flags and let the dispatch open a second, bogus event.
                    return awaitingDispatch(e, nowNs) ? null : Fate.SYSTEM;
                case "storageFull":
                    return Fate.NACK_STORAGE;
                case "mwi":
                    if (e.facts != null && e.facts.mwiDontStore()) {
                        return awaitingDispatch(e, nowNs) ? null : Fate.SYSTEM;
                    }
                    return null;
                case "usimdd":
                    return e.por != null ? Fate.SIM : null;
                default:
                    break;
            }
        }
        if (e.vvmConsumed) return Fate.VVM;
        if (e.missedCallConsumed) return Fate.MISSED_CALL;
        if (e.carrierConsumed) return Fate.CARRIER;
        if (e.appTokenConsumed) return Fate.APP;
        if (e.wapHandled) {
            if (e.wapResult == -1) return Fate.APP;
            if (e.wapResult == 1) return wapConsumed(e);
            // Any other result (2, RESULT_SMS_GENERIC_ERROR, when the WSP header does not decode) makes
            // processMessagePart delete the push from the raw table. The network ACK went out at dispatch, so
            // Android acknowledged the push and did not keep it.
            return Fate.SYSTEM;
        }

        long last = e.lastTNs >= 0 ? e.lastTNs : e.createdAtNs;
        long age = nowNs - last;

        // Before the first unlock Android leaves port-addressed and WAP messages in the raw
        // table; seal them as pending so the replay can update the same event.
        if (!userUnlocked.getAsBoolean() && age >= PENDING_UNLOCK_DELAY_NS && leavesForUnlock(e)) {
            return Fate.PENDING_UNLOCK;
        }
        // A self-test canary that reached dispatch and then met no filter was not intercepted: it is on its way to the
        // messaging app. Say so now instead of waiting out the timeout, so the self-test reports a definite result.
        if (e.flags.selfTest && !e.verdictSeen && e.source >= 0 && age >= CANARY_PASSED_NS) return Fate.SHOWN;
        if (age >= TIMEOUT_NS) return timeoutFate(e);
        return null;
    }

    /**
     * The fate 30 s after the last stage. An accepted result, or the unlock replay of a message that waited
     * in the raw table, means Android took the message and no consumer reported it. No dispatch result means Android did
     * not dispatch the message, for example a PDU that the framework cannot read. Any other result is a refusal (the
     * known refusals are sealed at once).
     */
    static Fate timeoutFate(Event e) {
        if (e.ackResult == null) return e.flags.replay ? Fate.ACCEPTED : Fate.NOT_DISPATCHED;
        return AckResult.accepted(e.ackResult) ? Fate.ACCEPTED : Fate.REJECTED;
    }

    /** True for a fate that {@link #decide} gives only at the timeout (the caller adds the timeout trace step). */
    public static boolean isTimeoutFate(Fate f) {
        return f == Fate.ACCEPTED || f == Fate.NOT_DISPATCHED;
    }

    /**
     * WAP dispatch result 1 ({@code Intents.RESULT_SMS_HANDLED}). Without a {@code by} marker it is Android's own
     * result: AOSP {@code WapPushOverSms.dispatchWapPdu} returns it when a {@code WapPushManager} app consumed the push,
     * so the fate is {@code FT-APP}. {@code by=nightjar} (the WAP enforcer consumed it) and {@code by=crash} (the
     * safety net consumed a push that made the original method throw) are blocks: the verdict that the hook sends with
     * them normally decides first.
     */
    private static Fate wapConsumed(Event e) {
        if (e.wapBy == null) return Fate.APP;
        return Fate.BLOCKED;
    }

    /** How long a dispatched canary may wait for its interception before it counts as delivered. */
    static final long CANARY_PASSED_NS = 1_500_000_000L;

    /** The dispatch observation normally follows the radio-specific branch within milliseconds. */
    static final long DISPATCH_GRACE_NS = 3_000_000_000L;

    private static boolean awaitingDispatch(Event e, long nowNs) {
        if (e.source >= 0) return false;
        long last = e.lastTNs >= 0 ? e.lastTNs : e.createdAtNs;
        return nowNs - last < DISPATCH_GRACE_NS;
    }

    /** True when the timeout stage must be recorded (seal.timeout trace, C.9). */
    public boolean isTimeout(Event e, long nowNs) {
        if (e.fate != null || e.sealed) return false;
        if (!userUnlocked.getAsBoolean() && leavesForUnlock(e)) return false;
        long last = e.lastTNs >= 0 ? e.lastTNs : e.createdAtNs;
        return nowNs - last >= TIMEOUT_NS;
    }

    /** Port-addressed, WAP or still-undelivered SMS on the normal delivery path. */
    private static boolean leavesForUnlock(Event e) {
        if (e.destPort >= 0 || e.contentType != null) return true;
        return e.ackResult == null && normalDeliveryPath(e.provisionalClass);
    }

    /**
     * True for a class that Android delivers like a normal SMS: it waits in the raw table until the first unlock. Up to
     * These were all CL-NORMAL. Not CL-MALFORMED-SMS: a PDU that the framework cannot read never reaches the
     * raw table, so it is FT-NOT-DISPATCHED after the timeout. CL-UNREADABLE-SMS is on this path: AOSP reads a PDU
     * with TP-MTI 3 as an SMS-DELIVER.
     */
    public static boolean normalDeliveryPath(ClassId c) {
        if (c == null) return false;
        return switch (c) {
            case NORMAL, RETURN_CALL, ME_DOWNLOAD, ME_DEPERSO, CLASS3, TELEMATIC, EMPTY, BINARY, COMPRESSED,
                    UNREADABLE_SMS -> true;
            default -> false;
        };
    }

    /** Adds the seal-timeout trace step before the caller seals with a {@link #isTimeoutFate(Fate) timeout fate}. */
    public void noteTimeout(Event e) {
        e.addTrace(TraceStage.SEAL_TIMEOUT, e.lastTNs >= 0 ? e.lastTNs : e.createdAtNs, null);
    }
}
