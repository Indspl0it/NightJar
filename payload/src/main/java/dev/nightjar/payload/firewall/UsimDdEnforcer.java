package dev.nightjar.payload.firewall;

import dev.nightjar.contract.ClassId;
import dev.nightjar.payload.capture.PduFacts;

/**
 * HK-15, {@code UsimDataDownloadHandler#handleUsimDataDownload}.
 *
 * <p>A block returns {@code 1}: the handler then sends a plain RP-ACK with no envelope to the SIM, so the network
 * exchange ends without a PoR and without any external effect (AC2). Never {@code -1}.
 *
 * <p>Canaries take the same path: they are recognised by the token their nonce PDU was registered under, so the dummy
 * command packet never reaches the SIM. Interception is not a verdict, so it works with the firewall off or
 * unavailable.
 *
 * <p>Thread: main looper.
 */
public final class UsimDdEnforcer {

    /** The only skip result this hook may produce. */
    public static final int SKIP_RESULT = 1;

    private final Enforcer enforcer;
    private final CanaryGate canaries;

    public UsimDdEnforcer(Enforcer enforcer, CanaryGate canaries) {
        this.enforcer = enforcer;
        this.canaries = canaries;
    }

    /**
     * @param originator originating address as the framework reports it, or {@code null} when only the PDU is available
     * @param facts parsed TPDU, or {@code null} when the message could not be parsed
     * @param pdu the message as the framework holds it (radio PDU or bare TPDU), for the canary token; may be {@code null}
     * @param slot SIM slot, −1 when unknown
     * @param roaming roaming state at arrival
     */
    public HookDecision decide(String originator, PduFacts facts, byte[] pdu, int slot, boolean roaming) {
        byte[] message = pdu != null ? pdu : (facts == null ? null : facts.tpdu());
        if (canaries != null && canaries.matchesPdu(message)) {
            return HookDecision.skip(SKIP_RESULT, VerdictRecord.canaryBlock(), ClassId.SIM_DD);
        }
        VerdictRecord record = enforcer.simDataDownload(originator, facts, slot, roaming);
        if (record.blocked()) {
            return HookDecision.skip(SKIP_RESULT, record, ClassId.SIM_DD);
        }
        return HookDecision.pass(record, ClassId.SIM_DD);
    }
}
