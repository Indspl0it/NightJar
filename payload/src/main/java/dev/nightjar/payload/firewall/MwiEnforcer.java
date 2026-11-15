package dev.nightjar.payload.firewall;

import dev.nightjar.contract.ClassId;
import dev.nightjar.payload.capture.PduFacts;
import java.util.function.BooleanSupplier;

/**
 * HK-23, {@code GsmInboundSmsHandler#updateMessageWaitingIndicator(int)}.
 *
 * <p>The indicator is updated inside {@code dispatchMessageRadioSpecific}, before any filter, so the only way to block
 * an MWI is to suppress this call. That covers both "set" and "clear" MWI; the "store" variant is additionally dropped
 * at HK-08. A hold suppresses the call too. A release re-injects the TPDU, and
 * this hook then lets the call run while a release of an MWI is live, so the framework applies the indicator again.
 *
 * <p>Canaries are suppressed here too: the MWI self-test canary must never change the real voicemail icon.
 * The hook receives only the indicator value, so it cannot compute the canary's token key; it therefore suppresses only
 * while an MWI canary token is in flight, which keeps ordinary voicemail indicators working during other canaries.
 *
 * <p>Thread: main looper.
 */
public final class MwiEnforcer {

    private final Enforcer enforcer;
    private final CanaryGate canaries;
    private final BooleanSupplier releasePending;

    public MwiEnforcer(Enforcer enforcer, CanaryGate canaries) {
        this(enforcer, canaries, null);
    }

    /**
     * @param releasePending true while a release of an MWI is live (the token registry's class-scoped answer), or
     *     {@code null} when this build cannot release
     */
    public MwiEnforcer(Enforcer enforcer, CanaryGate canaries, BooleanSupplier releasePending) {
        this.enforcer = enforcer;
        this.canaries = canaries;
        this.releasePending = releasePending;
    }

    /**
     * @param originator originating address as the framework reports it, or {@code null} when unavailable
     * @param facts parsed TPDU, or {@code null} when only framework objects are available
     * @param slot SIM slot, −1 when unknown
     * @param roaming roaming state at arrival
     */
    public HookDecision decide(String originator, PduFacts facts, int slot, boolean roaming) {
        if (canaries != null && canaries.pending(ClassId.MWI)) {
            return HookDecision.skipVoid(VerdictRecord.canaryBlock(), ClassId.MWI);
        }
        if (releasing()) {
            // Our own release is delivered without rules, as at HK-08 and HK-14.
            return HookDecision.pass(VerdictRecord.releaseAllow(), ClassId.MWI);
        }
        VerdictRecord record = enforcer.evaluate(ClassId.MWI, facts, null, -1, slot, roaming, -1,
                facts == null ? null : facts.tpdu(), originator);
        if (record.blocked()) {
            return HookDecision.skipVoid(record, ClassId.MWI);
        }
        return HookDecision.pass(record, ClassId.MWI);
    }

    private boolean releasing() {
        try {
            return releasePending != null && releasePending.getAsBoolean();
        } catch (Throwable t) {
            // The rules run as usual.
            return false;
        }
    }
}
