package dev.nightjar.payload.firewall;

import dev.nightjar.contract.Verdict;
import dev.nightjar.rules.ClampReason;
import dev.nightjar.rules.Decision;
import dev.nightjar.rules.RuleOrigin;

/**
 * One enforcement result as the event and the trace need it: final action, the deciding
 * rule, and the clamp that overrode it.
 *
 * <p>Two kinds of reason exist. {@link ClampReason} values come from the rule engine's five clamps. The payload's own
 * reasons ({@link #CANARY}) are not engine clamps, so they travel as a plain wire string — the contract documents that
 * the payload adds {@code CANARY} itself (Appendix F).
 *
 * <p>Immutable; created by {@link Enforcer} from a reused {@link Decision} so the hot path does not keep the mutable
 * object.
 */
public final class VerdictRecord {

    /** {@code ruleId} when no rule decided the action. */
    public static final long NO_RULE = -1L;

    /** Payload-added reason for a self-test interception (Appendix F). */
    public static final String CANARY = "CANARY";

    /** Payload-added reason for a push the framework would crash on (structurally broken WSP header). */
    public static final String MALFORMED = "MALFORMED";

    private static final VerdictRecord ALLOW = new VerdictRecord(Verdict.ALLOW, NO_RULE, null, null, null, null);

    private final Verdict action;
    private final long ruleId;
    private final RuleOrigin ruleOrigin;
    private final Verdict clampedFrom;
    private final ClampReason clampReason;
    private final String reason;

    public VerdictRecord(Verdict action, long ruleId, RuleOrigin ruleOrigin, Verdict clampedFrom,
            ClampReason clampReason) {
        this(action, ruleId, ruleOrigin, clampedFrom, clampReason, null);
    }

    private VerdictRecord(Verdict action, long ruleId, RuleOrigin ruleOrigin, Verdict clampedFrom,
            ClampReason clampReason, String reason) {
        this.action = action == null ? Verdict.ALLOW : action;
        this.ruleId = ruleId;
        this.ruleOrigin = ruleOrigin;
        this.clampedFrom = clampedFrom;
        this.clampReason = clampReason;
        this.reason = reason;
    }

    /** The unconditional ALLOW used when no snapshot exists or a hook fails open. */
    public static VerdictRecord allow() {
        return ALLOW;
    }

    /** A self-test canary interception: BLOCK with {@code clampReason=CANARY} (Appendix F). */
    public static VerdictRecord canaryBlock() {
        return new VerdictRecord(Verdict.BLOCK, NO_RULE, null, null, null, CANARY);
    }

    /** A structurally broken PDU, blocked before the framework parses it. */
    public static VerdictRecord malformedBlock() {
        return new VerdictRecord(Verdict.BLOCK, NO_RULE, null, null, null, MALFORMED);
    }

    /** A release: rules are skipped and the message is delivered. */
    public static VerdictRecord releaseAllow() {
        return new VerdictRecord(Verdict.ALLOW, NO_RULE, null, null, null, null);
    }

    static VerdictRecord of(Decision d) {
        if (d == null) return ALLOW;
        return new VerdictRecord(d.action(), d.hasRule() ? d.ruleId() : NO_RULE, d.ruleOrigin(), d.clampedFrom(),
                d.clampReason());
    }

    public Verdict action() {
        return action;
    }

    /** Id of the deciding rule, or {@link #NO_RULE}. */
    public long ruleId() {
        return ruleId;
    }

    public RuleOrigin ruleOrigin() {
        return ruleOrigin;
    }

    /** Action before clamping, {@code null} when not clamped. */
    public Verdict clampedFrom() {
        return clampedFrom;
    }

    public ClampReason clampReason() {
        return clampReason;
    }

    /** The wire reason: the engine clamp's wire name, the payload reason, or {@code null}. */
    public String reasonWire() {
        if (reason != null) return reason;
        return clampReason == null ? null : clampReason.wire();
    }

    public boolean clamped() {
        return clampReason != null || reason != null;
    }

    /** True when the message must not be delivered (BLOCK or QUARANTINE). */
    public boolean blocked() {
        return action == Verdict.BLOCK || action == Verdict.QUARANTINE;
    }

    /**
     * Detail for {@code fw.headFilter}: {@code verdict=<v> rule=<id> clamp=<reason>}. Never contains text
     * or a number.
     */
    public String traceDetail() {
        StringBuilder b = new StringBuilder(48);
        b.append("verdict=").append(action.wire());
        b.append(" rule=").append(ruleId == NO_RULE ? "none" : Long.toString(ruleId));
        String r = reasonWire();
        b.append(" clamp=").append(r == null ? "none" : r);
        return b.toString();
    }

    @Override
    public String toString() {
        return traceDetail();
    }
}
