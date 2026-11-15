package dev.nightjar.payload.firewall;

import dev.nightjar.contract.ClassId;

/**
 * What an enforcement point must do with one message: skip the original (and with which result) or let it run, plus the
 * verdict for the event and the trace.
 *
 * <p>Immutable. The {@code skipResult} is always a value the framework accepts for that hook: {@code 1} for HK-14 and
 * HK-15, "void" for HK-17/HK-23. {@code -1} is never produced for a skip.
 */
public final class HookDecision {

    private static final HookDecision PASS = new HookDecision(false, 0, VerdictRecord.allow(), null);

    private final boolean skip;
    private final int skipResult;
    private final VerdictRecord verdict;
    private final ClassId classId;

    private HookDecision(boolean skip, int skipResult, VerdictRecord verdict, ClassId classId) {
        this.skip = skip;
        this.skipResult = skipResult;
        this.verdict = verdict;
        this.classId = classId;
    }

    /** Let the original run. */
    public static HookDecision pass(VerdictRecord verdict, ClassId classId) {
        return new HookDecision(false, 0, verdict == null ? VerdictRecord.allow() : verdict, classId);
    }

    /** Default pass with no verdict information (fail open). */
    public static HookDecision pass() {
        return PASS;
    }

    /** Skip the original and return {@code result}. */
    public static HookDecision skip(int result, VerdictRecord verdict, ClassId classId) {
        if (result == -1) {
            // Defence in depth: a −1 skip would broadcast the message we just decided to hold.
            throw new IllegalArgumentException("skip result must never be -1");
        }
        return new HookDecision(true, result, verdict == null ? VerdictRecord.allow() : verdict, classId);
    }

    /** Skip a void method (HK-17, HK-23). */
    public static HookDecision skipVoid(VerdictRecord verdict, ClassId classId) {
        return new HookDecision(true, 0, verdict == null ? VerdictRecord.allow() : verdict, classId);
    }

    public boolean skip() {
        return skip;
    }

    /** Value to pass to {@code Param.setResult}; ignored for a void skip. */
    public int skipResult() {
        return skipResult;
    }

    public VerdictRecord verdict() {
        return verdict;
    }

    /** Predicted class at this point, {@code null} when unknown. */
    public ClassId classId() {
        return classId;
    }

    @Override
    public String toString() {
        return "HookDecision{skip=" + skip + " result=" + skipResult + " " + verdict.traceDetail() + "}";
    }
}
