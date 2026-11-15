package dev.nightjar.payload.firewall;

import dev.nightjar.contract.CbChannels;
import dev.nightjar.contract.ClassId;
import dev.nightjar.pduparse.CbCategories;

/**
 * HK-17, {@code CellBroadcastServiceManager$N#handleMessage}.
 *
 * <p>Only non-emergency broadcasts can be skipped. Emergency channels (ETWS 4352–4359, CMAS 4370–4399 and the national
 * alert ranges in {@link CbChannels}) always run the original — checked here <em>before</em> the rule engine, so even a
 * hand-edited {@code rules.json} cannot suppress an alert, and the engine's emergency CB clamp stays as the second
 * line of defence.
 *
 * <p>A broadcast in the CB data download range of 3GPP TS 23.041 §9.4.1.2.2 is evaluated as {@code CL-CB-SIM}, so the
 * user's action for that class applies, not the action for {@code CL-CB} ({@link CbCategories}).
 *
 * <p>The skip is a void skip: {@code handleMessage} returns nothing, so the framework simply does not process the
 * broadcast. The fate is {@code FT-BLOCKED} with {@code fateDetail.channel}.
 *
 * <p>Thread: the cell-broadcast handler looper.
 */
public final class CbEnforcer {

    private final Enforcer enforcer;

    public CbEnforcer(Enforcer enforcer) {
        this.enforcer = enforcer;
    }

    /**
     * @param messageIdentifier CB message identifier from the PDU (TS 23.041)
     * @param slot SIM slot, −1 when unknown
     * @param roaming roaming state at arrival
     */
    public HookDecision decide(int messageIdentifier, int slot, boolean roaming) {
        if (CbChannels.isEmergency(messageIdentifier)) {
            // An emergency broadcast is never held back, whatever the rules say.
            return HookDecision.pass(VerdictRecord.allow(), ClassId.CB);
        }
        ClassId classId = CbCategories.classOf(messageIdentifier);
        VerdictRecord record = classId == ClassId.CB
                ? enforcer.cellBroadcast(messageIdentifier, slot, roaming)
                : enforcer.evaluate(classId, null, null, -1, slot, roaming, messageIdentifier, null, null);
        if (record.blocked()) {
            return HookDecision.skipVoid(record, classId);
        }
        return HookDecision.pass(record, classId);
    }
}
