package dev.nightjar.payload.firewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Schemas;
import dev.nightjar.contract.Verdict;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.payload.hooks.CbHooks;
import dev.nightjar.payload.hooks.WapHooks;
import dev.nightjar.rules.Conditions;
import dev.nightjar.rules.Preset;
import dev.nightjar.rules.Rule;
import dev.nightjar.rules.RuleEngine;
import dev.nightjar.rules.RuleOrigin;
import dev.nightjar.rules.RulesConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The ports resolve slot and roaming from the subscription. */
class FirewallPortsTest {

    /** WSP Push with content type 0x36 (connectivity-wbxml → CL-WAP-CP). */
    private static final byte[] PUSH = {0x01, 0x06, 0x01, (byte) 0xB6};

    static final class FixedClock implements Clock {
        @Override
        public long elapsedNs() {
            return 1_000_000L;
        }

        @Override
        public long wallMs() {
            return 1_762_330_000_000L;
        }
    }

    private static Enforcer enforcer(Rule rule) {
        RuleEngine engine = RuleEngine.compile(
                new RulesConfig(Schemas.RULES, 1, Preset.CUSTOM, Map.of(), List.of(rule), List.of(), List.of(), 5));
        FirewallState state = new FirewallState(new FixedClock());
        state.setAvailable(true);
        state.setEnabled(true);
        return new Enforcer(() -> engine, state, TextSource.NONE, null);
    }

    private static Rule onSubscription(int slot, boolean roaming) {
        return new Rule(4, 0, true, RuleOrigin.USER, "subscription", Verdict.BLOCK,
                Conditions.builder().classes(ClassId.WAP_CP).slot(slot).roaming(roaming).build());
    }

    @Test
    void P16_theWapPortUsesTheSubscriptionOfTheMessage() {
        WapEnforcer wap = new WapEnforcer(enforcer(onSubscription(1, true)), InjectionLookup.NONE);
        WapHooks.Port port = FirewallPorts.wap(wap, subId -> subId == 7 ? 1 : -1, subId -> subId == 7);

        assertEquals(1, port.beforeDispatch(PUSH, "application/vnd.wap.connectivity-wbxml", null, 7));
        assertEquals(WapHooks.CONTINUE,
                port.beforeDispatch(PUSH, "application/vnd.wap.connectivity-wbxml", null, 3),
                "another subscription must not inherit slot 1 / roaming");
    }

    @Test
    void P16_unknownSuppliersYieldUnknownFacts() {
        WapEnforcer wap = new WapEnforcer(enforcer(onSubscription(1, true)), InjectionLookup.NONE);
        WapHooks.Port port = FirewallPorts.wap(wap, null, null);

        assertEquals(WapHooks.CONTINUE,
                port.beforeDispatch(PUSH, "application/vnd.wap.connectivity-wbxml", null, 7));
    }

    @Test
    void P16_theCbPortForwardsTheSubscription() {
        CbEnforcer cb = new CbEnforcer(enforcer(new Rule(5, 0, true, RuleOrigin.USER, "cb-slot", Verdict.BLOCK,
                Conditions.builder().classes(ClassId.CB).slot(1).build())));
        CbHooks.Port port = FirewallPorts.cb(cb, subId -> subId == 7 ? 1 : -1, subId -> false);

        assertTrue(port.block(1000, false, 7));
        assertFalse(port.block(1000, false, 3));
        assertFalse(port.block(1000, true, 7), "G-01: an emergency channel is never blocked");
    }
}
