package dev.nightjar.payload.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.android.internal.telephony.gsm.SmsMessage;
import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Schemas;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.contract.Verdict;
import dev.nightjar.payload.capture.InjectionTokens;
import dev.nightjar.payload.capture.Observation;
import dev.nightjar.payload.capture.Observations;
import dev.nightjar.payload.firewall.CanaryGate;
import dev.nightjar.payload.firewall.Enforcer;
import dev.nightjar.payload.firewall.FirewallState;
import dev.nightjar.payload.firewall.InjectionLookup;
import dev.nightjar.payload.firewall.MwiEnforcer;
import dev.nightjar.payload.firewall.TextSource;
import dev.nightjar.payload.firewall.UsimDdEnforcer;
import dev.nightjar.payload.hook.HookCatalog;
import dev.nightjar.payload.hook.Param;
import dev.nightjar.payload.store.EventQueue;
import dev.nightjar.payload.testing.FakeClock;
import dev.nightjar.payload.testing.FakeWorker;
import dev.nightjar.payload.testing.Pdu;
import dev.nightjar.rules.Preset;
import dev.nightjar.rules.RuleEngine;
import dev.nightjar.rules.RulesConfig;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** HK-15 and HK-23 bodies: the type the framework passes, and the fail-open behaviour. */
class FirewallHooksTest {

    private static Enforcer enforcer(Map<ClassId, Verdict> actions) {
        RuleEngine engine = RuleEngine.compile(
                new RulesConfig(Schemas.RULES, 1, Preset.CUSTOM, actions, List.of(), List.of(), List.of(), 5));
        FirewallState state = new FirewallState(new FakeClock(0));
        state.setAvailable(true);
        state.setEnabled(true);
        return new Enforcer(() -> engine, state, TextSource.NONE, null);
    }

    private static FirewallHooks hooks(Map<ClassId, Verdict> actions, InjectionLookup tokens,
            CanaryGate.Pending pending) {
        return hooks(actions, tokens, pending, new EventQueue<>(), () -> false);
    }

    private static FirewallHooks hooks(Map<ClassId, Verdict> actions, InjectionLookup tokens,
            CanaryGate.Pending pending, EventQueue<Observation> queue, BooleanSupplier releasePending) {
        FakeClock clock = new FakeClock(0);
        CanaryGate gate = new CanaryGate(tokens, pending);
        return new FirewallHooks(new Observations(queue, new FakeWorker(), () -> {}), clock, null,
                null, new UsimDdEnforcer(enforcer(actions), gate),
                new MwiEnforcer(enforcer(actions), gate, releasePending), subId -> -1, subId -> false);
    }

    /** The HK-23 stage that the last call offered, or null. */
    private static Observation mwiStage(EventQueue<Observation> queue) {
        Observation found = null;
        for (Observation o = queue.poll(); o != null; o = queue.poll()) {
            if (o.stage == TraceStage.MWI_INDICATOR) found = o;
        }
        return found;
    }

    private static Param mwiParam() {
        return Param.of(HookCatalog.of("HK-23"), new Object[] {new Object(), 1}, void.class);
    }

    private static Param usimParam(Object message) {
        return Param.of(HookCatalog.of("HK-15"), new Object[] {new Object(), new Object(), message, 5, 7}, int.class);
    }

    /** A USIM data download as the framework parses it: PID 0x7F, class 2. */
    private static byte[] usimDdPdu() {
        return Pdu.deliver().pid(0x7F).dcs(0xF6).build();
    }

    @Test
    void P11_theGsmMessageTypeIsWhatTheFrameworkPasses() {
        Param p = usimParam(new SmsMessage("+919000000000", usimDdPdu()));

        hooks(Map.of(ClassId.SIM_DD, Verdict.BLOCK), InjectionLookup.NONE, CanaryGate.Pending.NONE)
                .beforeUsimDataDownload(p);

        assertTrue(p.skipped(), "a blocking rule must reach the original, so the envelope is never sent");
        assertEquals(1, p.result());
    }

    @Test
    void P11_aCanaryIsInterceptedEvenWithoutAnAddress() {
        Param p = usimParam(new SmsMessage(null, usimDdPdu()));

        hooks(Map.of(), (key, alt) -> InjectionTokens.Kind.CANARY, CanaryGate.Pending.NONE)
                .beforeUsimDataDownload(p);

        assertTrue(p.skipped());
        assertEquals(1, p.result(), "never -1: the network is still acknowledged");
    }

    @Test
    void P11_ordinaryTrafficRunsTheOriginal() {
        Param p = usimParam(new SmsMessage("+919000000000", usimDdPdu()));

        hooks(Map.of(), InjectionLookup.NONE, CanaryGate.Pending.NONE).beforeUsimDataDownload(p);

        assertFalse(p.skipped());
    }

    @Test
    void P11_anUnexpectedArgumentRunsTheOriginal() {
        Param p = usimParam("not a message");

        hooks(Map.of(), InjectionLookup.NONE, CanaryGate.Pending.NONE).beforeUsimDataDownload(p);

        assertFalse(p.skipped());
    }

    @Test
    void H4_aThrowingMessageRunsTheOriginal() {
        SmsMessage broken = new SmsMessage("+919000000000", usimDdPdu()) {
            @Override
            public byte[] getPdu() {
                throw new IllegalStateException("boom");
            }
        };
        Param p = usimParam(broken);

        hooks(Map.of(ClassId.SIM_DD, Verdict.BLOCK), InjectionLookup.NONE, CanaryGate.Pending.NONE)
                .beforeUsimDataDownload(p);

        assertFalse(p.skipped(), "a failure in the hook never changes the telephony behaviour");
    }

    @Test
    void P35_mwiIsSuppressedOnlyDuringAnMwiCanary() {
        Param canary = Param.of(HookCatalog.of("HK-23"), new Object[] {new Object(), 1}, void.class);
        hooks(Map.of(ClassId.MWI, Verdict.BLOCK), InjectionLookup.NONE, cls -> cls == ClassId.MWI)
                .beforeMwiIndicator(canary);
        assertTrue(canary.skipped());

        Param real = Param.of(HookCatalog.of("HK-23"), new Object[] {new Object(), 1}, void.class);
        hooks(Map.of(), InjectionLookup.NONE, cls -> cls == ClassId.WAP_CP).beforeMwiIndicator(real);
        assertFalse(real.skipped(), "another canary must not hide a real voicemail indicator");
    }

    @Test
    void UX2_FW_06_aHeldMwiStageCarriesItsVerdict() {
        EventQueue<Observation> queue = new EventQueue<>();
        Param held = mwiParam();
        hooks(Map.of(ClassId.MWI, Verdict.QUARANTINE), InjectionLookup.NONE, CanaryGate.Pending.NONE, queue,
                () -> false).beforeMwiIndicator(held);
        assertTrue(held.skipped());
        Observation stage = mwiStage(queue);
        assertEquals(Verdict.QUARANTINE, stage.verdict, "a discard MWI gets its only verdict from HK-23");
        assertEquals("suppressed", stage.detail);
    }

    @Test
    void UX2_FW_06_aBlockOrACanaryKeepsThePlainStage() {
        EventQueue<Observation> queue = new EventQueue<>();
        hooks(Map.of(ClassId.MWI, Verdict.BLOCK), InjectionLookup.NONE, CanaryGate.Pending.NONE, queue, () -> false)
                .beforeMwiIndicator(mwiParam());
        assertNull(mwiStage(queue).verdict);

        hooks(Map.of(), InjectionLookup.NONE, cls -> cls == ClassId.MWI, queue, () -> false)
                .beforeMwiIndicator(mwiParam());
        assertNull(mwiStage(queue).verdict, "Appendix F: the MWI canary still seals as FT-SYSTEM");
    }

    @Test
    void UX2_FW_06_aReleasedMwiRunsTheOriginal() {
        EventQueue<Observation> queue = new EventQueue<>();
        Param released = mwiParam();
        hooks(Map.of(ClassId.MWI, Verdict.QUARANTINE), InjectionLookup.NONE, CanaryGate.Pending.NONE, queue,
                () -> true).beforeMwiIndicator(released);
        assertFalse(released.skipped(), "the release applies the indicator again");
        assertEquals("applied", mwiStage(queue).detail);
    }
}
