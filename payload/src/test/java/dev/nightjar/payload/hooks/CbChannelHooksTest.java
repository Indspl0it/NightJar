package dev.nightjar.payload.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import android.telephony.CellBroadcastIdRange;
import dev.nightjar.contract.HookState;
import dev.nightjar.payload.hook.HookBackend;
import dev.nightjar.payload.hook.HookRegistry;
import dev.nightjar.payload.hook.HookSpec;
import dev.nightjar.payload.hook.Param;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** HK-18 widens the CB table to 0…65535 and keeps activation on. */
class CbChannelHooksTest {

    private static HookSpec spec(String id) {
        for (HookSpec s : CbChannelHooks.specs()) {
            if (s.id().equals(id)) return s;
        }
        throw new AssertionError("no spec " + id);
    }

    private static Param param(String id, Object... args) {
        return Param.of(spec(id), args, void.class);
    }

    /** The production path: the range is built through the reflective framework lookup. */
    private static CbChannelHooks hooks(boolean allChannels) {
        AtomicBoolean flag = new AtomicBoolean(allChannels);
        return new CbChannelHooks(flag::get, null);
    }

    @Test
    void theWidenedListIsOneFrameworkRangeCoveringEveryChannel() {
        CbChannelHooks hooks = hooks(true);
        List<Object> original = List.of("before-1", "before-2");
        Object[] args = {new Object(), original, "request"};

        Param p = param(CbChannelHooks.ID_GSM_CONFIG, args);
        hooks.before(p);

        assertFalse(p.skipped(), "the original still runs; only its argument changed");
        assertTrue(args[1] instanceof List<?>);
        List<?> widened = (List<?>) args[1];
        assertEquals(1, widened.size());

        // The framework casts every element to CellBroadcastIdRange and throws otherwise.
        CellBroadcastIdRange range = assertInstanceOf(CellBroadcastIdRange.class, widened.get(0));
        assertEquals(0, range.getStartId());
        assertEquals(0xFFFF, range.getEndId());
        assertEquals(CellBroadcastIdRange.RAN_TYPE_GSM, range.getRanType());
        assertTrue(range.isEnabled());
        assertEquals(2, original.size(), "the list the framework passed in is not mutated");
    }

    @Test
    void theRangeCoversTheFullThreeGppSpace() {
        AtomicInteger from = new AtomicInteger(-1);
        AtomicInteger to = new AtomicInteger(-1);
        CbChannelHooks hooks = new CbChannelHooks(
                () -> true,
                null,
                (lo, hi) -> {
                    from.set(lo);
                    to.set(hi);
                    return new CellBroadcastIdRange(lo, hi, CellBroadcastIdRange.RAN_TYPE_GSM, true);
                });
        Object[] args = {new Object(), List.of(), "request"};

        hooks.before(param(CbChannelHooks.ID_GSM_CONFIG, args));

        assertEquals(0, from.get());
        assertEquals(65535, to.get());
        assertEquals(new CellBroadcastIdRange(0, 65535, CellBroadcastIdRange.RAN_TYPE_GSM, true),
                ((List<?>) args[1]).get(0));
    }

    @Test
    void activationIsForcedOnForTheGsmRanType() {
        CbChannelHooks hooks = hooks(true);
        Object[] args = {new Object(), CellBroadcastIdRange.RAN_TYPE_GSM, Boolean.FALSE, "request"};

        hooks.before(param(CbChannelHooks.ID_ACTIVATION, args));

        assertEquals(Boolean.TRUE, args[2]);
    }

    /** The CDMA RAN type (2) is not forced on; the target has no CDMA. */
    @Test
    void activationIsNotForcedForTheCdmaRanType() {
        CbChannelHooks hooks = hooks(true);
        Object[] args = {new Object(), 2, Boolean.FALSE, "request"};

        hooks.before(param(CbChannelHooks.ID_ACTIVATION, args));

        assertEquals(Boolean.FALSE, args[2]);
    }

    /** CONTRIBUTING: a hook body that throws leaves the original call and its arguments unchanged. */
    @Test
    void aThrowingBodyLeavesTheOriginalArguments() throws Throwable {
        HookRegistry registry = new HookRegistry();
        HookSpec spec = spec(CbChannelHooks.ID_GSM_CONFIG);
        registry.register(spec);
        CbChannelHooks hooks = new CbChannelHooks(() -> {
            throw new IllegalStateException("boom");
        }, null);
        dev.nightjar.payload.hook.Hooker hooker = new dev.nightjar.payload.hook.Hooker(spec, hooks::before,
                registry, dev.nightjar.payload.hook.FaultInjector.NONE, null);
        List<Object> original = List.of("before");
        Object[] seen = new Object[1];
        hooker.setOriginal((receiver, params) -> {
            seen[0] = params[0];
            return null;
        });
        hooker.call(new Object[] {new Object(), original, "request"});
        assertSame(original, seen[0]);
    }

    @Test
    void theFeatureIsInertWhileTheSettingIsOff() {
        CbChannelHooks hooks = hooks(false);
        List<Object> original = List.of("before");
        Object[] configArgs = {new Object(), original, "request"};
        Object[] activationArgs = {new Object(), CellBroadcastIdRange.RAN_TYPE_GSM, Boolean.FALSE, "request"};

        hooks.before(param(CbChannelHooks.ID_GSM_CONFIG, configArgs));
        hooks.before(param(CbChannelHooks.ID_ACTIVATION, activationArgs));

        assertSame(original, configArgs[1], "cb.allChannels off must not touch the CB table");
        assertEquals(Boolean.FALSE, activationArgs[2]);
    }

    @Test
    void aFactoryThatCannotBuildARangeLeavesTheOriginalList() {
        CbChannelHooks hooks = new CbChannelHooks(() -> true, null, (from, to) -> null);
        List<Object> original = List.of("before");
        Object[] args = {new Object(), original, "request"};

        hooks.before(param(CbChannelHooks.ID_GSM_CONFIG, args));

        assertEquals(List.of("before"), args[1], "fail open: the framework list is passed through unchanged");
    }

    @Test
    void aNonListArgumentIsIgnored() {
        CbChannelHooks hooks = hooks(true);
        Object[] args = {new Object(), "not a list", "request"};

        hooks.before(param(CbChannelHooks.ID_GSM_CONFIG, args));

        assertEquals("not a list", args[1]);
    }

    @Test
    void aShortArgumentArrayIsIgnored() {
        CbChannelHooks hooks = hooks(true);
        Object[] activation = {new Object(), 0};

        hooks.before(param(CbChannelHooks.ID_ACTIVATION, activation));

        assertEquals(2, activation.length);
    }

    @Test
    void installRegistersBothHooksAndStaysFailOpenWithoutANativeBackend() {
        HookRegistry registry = new HookRegistry();
        CbChannelHooks hooks = hooks(true);

        hooks.install(registry, HookBackend.NONE, null);

        assertEquals(2, registry.all().size());
        assertEquals(HookState.NOT_FOUND, registry.state(CbChannelHooks.ID_GSM_CONFIG));
        assertEquals(HookState.NOT_FOUND, registry.state(CbChannelHooks.ID_ACTIVATION));
        assertFalse(registry.live(CbChannelHooks.ID_ACTIVATION));
    }

    @Test
    void theSpecsMatchAppendixB() {
        List<HookSpec> specs = new ArrayList<>(CbChannelHooks.specs());
        assertEquals(2, specs.size());
        for (HookSpec s : specs) {
            assertEquals("com.android.internal.telephony.CellBroadcastConfigTracker", s.className());
            assertTrue(s.methodName().equals("setGsmConfig") || s.methodName().equals("setActivation"));
        }
        assertEquals(2, spec(CbChannelHooks.ID_GSM_CONFIG).arity());
        assertEquals(3, spec(CbChannelHooks.ID_ACTIVATION).arity());
    }
}
