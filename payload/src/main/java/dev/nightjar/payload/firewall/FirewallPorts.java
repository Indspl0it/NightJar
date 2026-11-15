package dev.nightjar.payload.firewall;

import dev.nightjar.payload.hooks.CbHooks;
import dev.nightjar.payload.hooks.WapHooks;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * Bindings between the capture-side hook bodies and the enforcement logic.
 *
 * <p>The hook bodies in {@code .hooks} own the framework plumbing (argument indices, {@code Param.setResult},
 * {@code Param.skip}); they expose a small {@code Port} that answers "must this message be held back?". These adapters
 * answer it from the {@link Enforcer} without knowing anything about the framework, so the decision logic stays testable
 * on the JVM.
 *
 * <p>Slot and roaming are resolved live from the subscription the message belongs to: a {@code when.slot} or
 * {@code when.roaming} rule must see the real values at the enforcement point, not a placeholder.
 *
 * <p>Thread: the hook's thread (main looper for HK-14, CB handler looper for HK-17).
 */
public final class FirewallPorts {

    /** Slot of a subscription this build does not know. */
    private static final IntUnaryOperator UNKNOWN_SLOT = subId -> -1;

    /** Roaming state this build does not know. */
    private static final IntPredicate UNKNOWN_ROAMING = subId -> false;

    private FirewallPorts() {}

    /**
     * HK-14 port: {@code 1} consumes the push, {@link WapHooks#CONTINUE} runs the original.
     *
     * @param slotOf slot of a subscription id, −1 when unknown
     * @param roamingOf roaming state of a subscription id
     */
    public static WapHooks.Port wap(WapEnforcer enforcer, IntUnaryOperator slotOf, IntPredicate roamingOf) {
        IntUnaryOperator slots = slotOf == null ? UNKNOWN_SLOT : slotOf;
        IntPredicate roaming = roamingOf == null ? UNKNOWN_ROAMING : roamingOf;
        return new WapHooks.Port() {
            // HK-14 runs on the main looper only, and the hook reads these right after beforeDispatch.
            private volatile VerdictRecord last;

            @Override
            public int beforeDispatch(byte[] pdu, String contentType, String originator, int subId) {
                HookDecision decision = enforcer.decide(pdu, originator, slots.applyAsInt(subId), roaming.test(subId));
                last = decision.skip() ? decision.verdict() : null;
                return decision.skip() ? decision.skipResult() : WapHooks.CONTINUE;
            }

            @Override
            public dev.nightjar.contract.Verdict lastVerdict() {
                VerdictRecord r = last;
                return r == null ? null : r.action();
            }

            @Override
            public long lastRuleId() {
                VerdictRecord r = last;
                return r == null ? -1L : r.ruleId();
            }

            @Override
            public String lastClamp() {
                VerdictRecord r = last;
                return r == null ? null : r.reasonWire();
            }
        };
    }

    /**
     * HK-17 port: {@code true} means the broadcast is skipped. Emergency channels are always false.
     *
     * @param slotOf slot of a subscription id, −1 when unknown
     * @param roamingOf roaming state of a subscription id
     */
    public static CbHooks.Port cb(CbEnforcer enforcer, IntUnaryOperator slotOf, IntPredicate roamingOf) {
        IntUnaryOperator slots = slotOf == null ? UNKNOWN_SLOT : slotOf;
        IntPredicate roaming = roamingOf == null ? UNKNOWN_ROAMING : roamingOf;
        return (messageId, emergency, subId) -> {
            if (emergency) return false;
            return enforcer.decide(messageId, slots.applyAsInt(subId), roaming.test(subId)).skip();
        };
    }
}
