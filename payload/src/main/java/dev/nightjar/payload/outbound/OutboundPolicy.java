package dev.nightjar.payload.outbound;

import dev.nightjar.contract.ClassId;

/**
 * The outbound enforcement seam. The payload's HK-19 before-hook asks this
 * policy once per {@code SmsController#send*} call, before the original runs.
 *
 * <p>The default is {@link #ALLOW}: with no gate installed nothing changes and the send proceeds
 * exactly as the framework wanted. A gate that returns a blocking decision makes the hook
 * skip the original; the caller's {@code PendingIntent} is failed by the gate itself, because only
 * it knows the result code to use.
 *
 * <p>Thread: the binder thread of the send. Implementations must not allocate per call beyond their
 * own decision object and must never throw — the hook treats a throw as ALLOW.
 */
public interface OutboundPolicy {

    /**
     * Each field goes into its own {@code fateDetail} key of the blocked outgoing event.
     *
     * @param block true when the send must not happen
     * @param ruleId the rule that blocked it, −1 when the gate has no rule id
     * @param reason short reason code for {@code fateDetail.reason} and the log ({@code } for the per-app list)
     * @param pkg the blocked package, or null
     * @param classId the class of the blocked send, or null
     * @param sentIntentFailed true when the caller's sent intent was failed, false when it could not be, null when the
     *     gate did not try
     */
    record Decision(boolean block, long ruleId, String reason, String pkg, ClassId classId,
            Boolean sentIntentFailed) {
        /** The send proceeds. */
        public static final Decision ALLOW = new Decision(false, -1L, null, null, null, null);

        public static Decision block(long ruleId, String reason) {
            return new Decision(true, ruleId, reason, null, null, null);
        }

        /** A block without a rule id. */
        public static Decision block(String reason) {
            return new Decision(true, -1L, reason, null, null, null);
        }

        /** A block of the per-app list: no rule id, the package, the class and the intent result. */
        public static Decision blockPackage(String reason, String pkg, ClassId classId, boolean sentIntentFailed) {
            return new Decision(true, -1L, reason, pkg, classId, Boolean.valueOf(sentIntentFailed));
        }
    }

    /** The decision for one send. */
    Decision decide(OutboundRequest request);

    /** No gate installed: everything is allowed. */
    OutboundPolicy ALLOW = request -> Decision.ALLOW;
}
