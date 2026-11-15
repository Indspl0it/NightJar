package dev.nightjar.payload.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.HookState;
import org.junit.jupiter.api.Test;

/** The hook layer and its fail-open contract, through {@link Hooker#call} (the Xposed path). */
class HookerTest {

    /** Fake original: records invocations, can return a value or throw. */
    static final class Original implements Hooker.Original {
        int calls;
        Object value;
        Throwable error;

        @Override
        public Object invoke(Object receiver, Object[] params) throws Throwable {
            calls++;
            if (error != null) throw error;
            return value;
        }
    }

    /** A body that does nothing; tests override the phase they need. */
    static class Body implements HookCallback {
        @Override
        public void before(Param p) {
            // Nothing.
        }
    }

    private static HookSpec spec(String id, HookKind kind, boolean neverMinusOne) {
        HookSpecs b = HookSpecs.builder(id, dev.nightjar.contract.Tier.CAPTURE_OPTIONAL, kind, "x.Y", "m");
        if (neverMinusOne) b.neverMinusOne();
        return b.arity(1).build();
    }

    private static Hooker hooker(HookSpec s, HookRegistry registry, HookCallback callback,
            java.lang.reflect.Member target, Original original) {
        registry.register(s);
        Hooker h = new Hooker(s, callback, registry, FaultInjector.NONE, target);
        h.setOriginal(original);
        return h;
    }

    private static java.lang.reflect.Method intTarget() throws NoSuchMethodException {
        return HookerTest.class.getDeclaredMethod("intTarget", String.class);
    }

    @Test
    void beforeErrorCallsOriginalAndCounts() throws Throwable {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        original.value = "orig";
        Hooker h = hooker(spec("HK-T1", HookKind.BEFORE, false), registry, new Body() {
            @Override
            public void before(Param p) {
                throw new IllegalStateException("boom");
            }
        }, null, original);
        assertEquals("orig", h.call(new Object[] {null, "a"}));
        assertEquals(1, original.calls);
        assertEquals(1, registry.errorCount("HK-T1"));
    }

    @Test
    void beforeSkipReturnsValueAndSkipsOriginal() throws Throwable {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        Hooker h = hooker(spec("HK-T2", HookKind.BEFORE, false), registry, new Body() {
            @Override
            public void before(Param p) {
                p.setResult(1);
            }
        }, null, original);
        assertEquals(1, h.call(new Object[] {null}));
        assertEquals(0, original.calls);
        assertEquals(0, registry.errorCount("HK-T2"));
    }

    @Test
    void wrongTypeIsRejectedCountedAndTheOriginalRuns() throws Throwable {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        original.value = 5;
        Hooker h = hooker(spec("HK-T3", HookKind.BEFORE, false), registry, new Body() {
            @Override
            public void before(Param p) {
                p.setResult("not an int");
            }
        }, intTarget(), original);
        // intTarget is static: the arguments carry no receiver.
        assertEquals(5, h.call(new Object[] {"x"}));
        assertEquals(1, original.calls);
        assertEquals(1, registry.errorCount("HK-T3"));
    }

    @Test
    void minusOneIsRejectedForNeverMinusOneHooks() throws Throwable {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        original.value = 2;
        Hooker h = hooker(spec("HK-14", HookKind.BEFORE, true), registry, new Body() {
            @Override
            public void before(Param p) {
                p.setResult(-1);
            }
        }, null, original);
        assertEquals(2, h.call(new Object[] {null}));
        assertEquals(1, original.calls);
        assertEquals(1, registry.errorCount("HK-14"));
    }

    @Test
    void minusOneIsAllowedForOtherHooks() throws Throwable {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        Hooker h = hooker(spec("HK-99", HookKind.BEFORE, false), registry, new Body() {
            @Override
            public void before(Param p) {
                p.setResult(-1);
            }
        }, null, original);
        assertEquals(-1, h.call(new Object[] {null}));
        assertEquals(0, original.calls);
    }

    @Test
    void afterErrorKeepsOriginalResult() throws Throwable {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        original.value = "orig";
        Hooker h = hooker(spec("HK-T4", HookKind.AFTER, false), registry, new Body() {
            @Override
            public void after(Param p) {
                throw new IllegalStateException("after boom");
            }
        }, null, original);
        assertEquals("orig", h.call(new Object[] {null}));
        assertEquals(1, registry.errorCount("HK-T4"));
    }

    @Test
    void afterSeesTheThrowableAndTheThrowableStands() {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        RuntimeException error = new RuntimeException("original");
        original.error = error;
        final Throwable[] seen = new Throwable[1];
        Hooker h = hooker(spec("HK-T5", HookKind.AFTER, false), registry, new Body() {
            @Override
            public void after(Param p) {
                seen[0] = p.throwable();
            }
        }, null, original);
        assertSame(error, assertThrows(Throwable.class, () -> h.call(new Object[] {null})));
        assertSame(error, seen[0]);
    }

    @Test
    void errorLimitDisablesHook() {
        HookRegistry registry = new HookRegistry();
        HookSpec s = spec("HK-T6", HookKind.AFTER, false);
        registry.register(s);
        registry.resolved("HK-T6", "x.Y#m", 0L);
        assertEquals(HookState.LIVE, registry.state("HK-T6"));
        for (int i = 0; i < HookRegistry.MAX_ERRORS; i++) {
            registry.onError("HK-T6", new RuntimeException("e"));
        }
        assertEquals(HookState.LIVE, registry.state("HK-T6"), "exactly MAX_ERRORS is still tolerated");
        registry.onError("HK-T6", new RuntimeException("e"));
        assertEquals(HookState.DISABLED, registry.state("HK-T6"));
    }

    @Test
    void aDisabledHookRunsOnlyTheOriginal() throws Throwable {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        original.value = "orig";
        final int[] bodies = new int[1];
        Hooker h = hooker(spec("HK-T15", HookKind.AROUND, false), registry, new Body() {
            @Override
            public void before(Param p) {
                bodies[0]++;
                p.setResult("skip");
            }

            @Override
            public void after(Param p) {
                bodies[0]++;
            }
        }, null, original);
        registry.disable("HK-T15", new IllegalStateException("x"));
        assertEquals("orig", h.call(new Object[] {null}));
        assertEquals(0, bodies[0]);
        assertEquals(1, original.calls);
    }

    @Test
    void faultInjectionThrowsBeforeAndAfter() throws Throwable {
        HookRegistry registry = new HookRegistry();
        HookSpec s = spec("HK-06", HookKind.AROUND, false);
        registry.register(s);
        FaultInjector fi = (id, phase) -> {
            throw new IllegalStateException("injected " + phase);
        };
        Original original = new Original();
        original.value = "orig";
        Hooker h = new Hooker(s, new Body(), registry, fi, null);
        h.setOriginal(original);
        assertEquals("orig", h.call(new Object[] {null}));
        assertEquals(2, registry.errorCount("HK-06"));
    }

    /** An AROUND hook runs the before body, the original and the after body with the original result. */
    @Test
    void aroundRunsBothPhases() throws Throwable {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        original.value = 2;
        final Object[] seen = new Object[2];
        Hooker h = hooker(spec("HK-T16", HookKind.AROUND, false), registry, new Body() {
            @Override
            public void before(Param p) {
                seen[0] = "before";
            }

            @Override
            public void after(Param p) {
                seen[1] = p.result();
            }
        }, null, original);
        assertEquals(2, h.call(new Object[] {null}));
        assertEquals("before", seen[0]);
        assertEquals(2, seen[1]);
    }

    @Test
    void paramHidesTheReceiverSlot() {
        Param p = new Param(spec("HK-T7", HookKind.BEFORE, false), new Object[] {null, 1}, int.class);
        assertEquals(1, p.argCount());
        assertEquals(1, p.arg(0));
        assertNull(p.arg(5));
        Param st = Param.of(spec("HK-T7", HookKind.BEFORE, false), new Object[] {7}, int.class, true);
        assertNull(st.thisObject());
        assertEquals(7, st.arg(0));
        assertEquals(1, st.argCount());
    }

    /** A real void target; skip() returns null and the original does not run. */
    @Test
    void voidHookSkipsWithNull() throws Throwable {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        java.lang.reflect.Method target = HookerTest.class.getDeclaredMethod("voidTarget", int.class);
        Hooker h = hooker(spec("HK-23", HookKind.BEFORE, false), registry, new Body() {
            @Override
            public void before(Param p) {
                p.skip();
            }
        }, target, original);
        assertNull(h.call(new Object[] {1}));
        assertEquals(0, original.calls);
        assertEquals(0, registry.errorCount("HK-23"));
    }

    @Test
    void typeCompatibilityIsBoxingAware() {
        assertTrue(Param.isCompatible(int.class, 1));
        assertFalse(Param.isCompatible(int.class, 1L));
        assertTrue(Param.isCompatible(void.class, null));
        assertFalse(Param.isCompatible(void.class, 1));
        assertThrows(IllegalArgumentException.class, () -> {
            Param p = new Param(spec("HK-T8", HookKind.BEFORE, false), new Object[] {null}, long.class);
            p.setResult(1);
        });
    }

    @Test
    void fullCallSequencesOriginalAndAfter() throws Throwable {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        original.value = 42;
        final Object[] seenAfter = new Object[1];
        Hooker h = hooker(spec("HK-T9", HookKind.AFTER, false), registry, new Body() {
            @Override
            public void after(Param p) {
                seenAfter[0] = p.result();
            }
        }, null, original);
        assertEquals(42, h.call(new Object[] {new Object()}));
        assertEquals(1, original.calls);
        assertEquals(42, seenAfter[0]);
    }

    @Test
    void callRethrowsOriginalThrowableUnchanged() {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        RuntimeException thrownByOriginal = new IllegalStateException("from original");
        original.error = thrownByOriginal;
        Hooker h = hooker(spec("HK-T10", HookKind.AFTER, false), registry, new Body() {
            @Override
            public void after(Param p) {
                throw new RuntimeException("after boom");
            }
        }, null, original);
        Throwable thrown = assertThrows(Throwable.class, () -> h.call(new Object[] {null}));
        assertSame(thrownByOriginal, thrown, "the original throwable must pass through unchanged");
        assertNotEquals(0, registry.errorCount("HK-T10"));
    }

    @Test
    void anInvocationTargetExceptionIsUnwrapped() {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        IllegalStateException cause = new IllegalStateException("inner");
        original.error = new java.lang.reflect.InvocationTargetException(cause);
        Hooker h = hooker(spec("HK-T17", HookKind.BEFORE, false), registry, new Body(), null, original);
        assertSame(cause, assertThrows(Throwable.class, () -> h.call(new Object[] {null})));
        assertEquals(0, registry.errorCount("HK-T17"));
    }

    @Test
    void theShieldTurnsAnOriginalThrowableIntoAResultAndCountsIt() throws Throwable {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        original.error = new ArrayIndexOutOfBoundsException(3);
        Hooker h = hooker(spec("HK-T18", HookKind.BEFORE, false), registry, new Body(), null, original);
        h.setShield((args, t) -> t instanceof IndexOutOfBoundsException ? 1 : null);
        assertEquals(1, h.call(new Object[] {null}));
        assertEquals(1, registry.get("HK-T18").recoveredCount.get());
        assertEquals(0, registry.errorCount("HK-T18"));
    }

    @Test
    void skipDoesNotRunOriginal() throws Throwable {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        Hooker h = hooker(spec("HK-T11", HookKind.BEFORE, false), registry, new Body() {
            @Override
            public void before(Param p) {
                p.setResult(7);
            }
        }, null, original);
        assertEquals(7, h.call(new Object[] {null}));
        assertEquals(0, original.calls);
    }

    /** A refused reflective call is our failure: defaulted, never thrown, and the hook is disabled at once. */
    @Test
    void aRefusedOriginalCallReturnsTheDefaultAndDisablesTheHook() throws Throwable {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        original.error = new IllegalArgumentException("argument type mismatch");
        Hooker h = hooker(spec("HK-T13", HookKind.BEFORE, false), registry, new Body(), intTarget(),
                original);
        registry.resolved("HK-T13", "x.Y#m", 1L);
        assertEquals(0, h.call(new Object[] {"x"}));
        assertEquals(HookState.DISABLED, registry.state("HK-T13"));
        assertEquals(1, registry.disabledWithHandle().size());
    }

    /** A skip on a primitive-returning method hands back the boxed default, never null. */
    @Test
    void aSkipOnAPrimitiveReturnYieldsTheDefault() throws Throwable {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        original.value = 9;
        Hooker h = hooker(spec("HK-T14", HookKind.BEFORE, false), registry, new Body() {
            @Override
            public void before(Param p) {
                p.skip();
            }
        }, intTarget(), original);
        assertEquals(0, h.call(new Object[] {"x"}));
        assertEquals(0, original.calls);
    }

    /** setResult(null) on an int method is rejected: counted, and the original runs. */
    @Test
    void aNullResultOnAPrimitiveReturnIsRejected() throws Throwable {
        HookRegistry registry = new HookRegistry();
        Original original = new Original();
        original.value = 9;
        Hooker h = hooker(spec("HK-T19", HookKind.BEFORE, false), registry, new Body() {
            @Override
            public void before(Param p) {
                p.setResult(null);
            }
        }, intTarget(), original);
        assertEquals(9, h.call(new Object[] {"x"}));
        assertEquals(1, registry.errorCount("HK-T19"));
    }

    /** A target whose return type is {@code int}. */
    static int intTarget(String s) {
        return 1;
    }

    /** A target that returns nothing. */
    static void voidTarget(int x) {
        // Target only.
    }
}
