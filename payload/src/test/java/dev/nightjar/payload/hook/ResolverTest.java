package dev.nightjar.payload.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.HookState;
import dev.nightjar.contract.Tier;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Resolver, structural matchers and the tier manager. */
class ResolverTest {

    /** Fixture: a class hierarchy with an overloaded method. */
    public static class Base {
        public int handle(String s) {
            return 1;
        }

        public int handle(String s, int n) {
            return 2;
        }
    }

    /** Fixture: an "anonymous" inner class, resolved as {@code Outer$1}. */
    public static class Outer {
        public static class Inner extends Base {
            public int innerOnly() {
                return 3;
            }
        }
    }

    /** Fixture: implements an interface, for the hierarchy check. */
    public interface Listener {
        void onEvent(int x);
    }

    public static class Impl implements Listener {
        @Override
        public void onEvent(int x) {
            // fixture
        }
    }

    @Test
    void resolvesByNameAndArity() {
        HookSpec spec = HookSpecs.builder("HK-06", Tier.CAPTURE_REQUIRED, HookKind.AFTER,
                Base.class.getName(), "handle").arity(2).build();
        Resolver.Result r = Resolver.resolve(Base.class, spec);
        assertTrue(r.isLive(), r.error());
        assertTrue(r.target() instanceof Method, String.valueOf(r.target()));
        assertEquals(2, ((Method) r.target()).getParameterCount());
    }

    @Test
    void resolvesInheritedMethod() {
        HookSpec spec = HookSpecs.builder("HK-X", Tier.MEANING, HookKind.AFTER,
                Outer.Inner.class.getName(), "handle").arity(1).build();
        Resolver.Result r = Resolver.resolve(Outer.Inner.class, spec);
        assertTrue(r.isLive(), r.error());
    }

    @Test
    void missingClassIsNotFoundNotFailed() {
        HookSpec spec = HookSpecs.builder("HK-X", Tier.MEANING, HookKind.AFTER,
                "com.example.DoesNotExist", "m").arity(1).build();
        assertEquals(HookState.NOT_FOUND, Resolver.resolve((ClassLoader) null, spec).state());
    }

    /** An anonymous subclass of {@link Base}, compiled as {@code ResolverTest$1}. */
    static final Base ANONYMOUS = new Base() {};

    @Test
    void anonymousMatcherFindsBySuperclass() {
        Class<?> c = StructuralMatchers.findAnonymous(ResolverTest.class.getClassLoader(),
                ResolverTest.class.getName(), Base.class.getName());
        assertNotNull(c, "the anonymous matcher must find a subclass of Base");
        assertTrue(StructuralMatchers.hierarchyContains(c, Base.class.getName()));
        assertEquals(ANONYMOUS.getClass(), c, "it returns the anonymous class, not the outer one");
    }

    @Test
    void anonymousMatcherHonoursInterface() {
        assertTrue(StructuralMatchers.hierarchyContains(Impl.class, Listener.class.getName()));
        assertFalse(StructuralMatchers.hierarchyContains(Impl.class, Runnable.class.getName()));
    }

    @Test
    void implLoaderSpecIsNotApplicableWithoutLoader() {
        HookSpec spec = HookSpecs.builder("HK-30", Tier.MEANING, HookKind.AFTER,
                "com.oplus.internal.telephony.OplusInboundSmsHandlerImpl", "oemCheck")
                .resolver(HookSpec.ResolverKind.IMPL_LOADER).arity(5).build();
        assertEquals(HookState.NOT_APPLICABLE, Resolver.resolve((ClassLoader) null, spec).state());
    }

    @Test
    void typeMatchingHandlesArraysGenericsAndSimpleNames() {
        assertTrue(StructuralMatchers.typeMatches("byte[]", "byte[]"));
        assertTrue(StructuralMatchers.typeMatches("[B", "byte[]"));
        assertTrue(StructuralMatchers.typeMatches("java.util.ArrayList", "ArrayList<Byte>"));
        assertTrue(StructuralMatchers.typeMatches("java.lang.String", "String"));
        assertFalse(StructuralMatchers.typeMatches("java.lang.String", "int"));
        assertTrue(StructuralMatchers.typeMatches("java.lang.Object", "*"));
    }

    /** Each {@code []} removes exactly one array dimension. */
    @Test
    void arrayDimensionsMustMatch() {
        assertTrue(StructuralMatchers.typeMatches("[[B", "byte[][]"));
        assertFalse(StructuralMatchers.typeMatches("[[B", "byte[]"));
        assertFalse(StructuralMatchers.typeMatches("[B", "byte[][]"));
        assertFalse(StructuralMatchers.typeMatches("[[B", "byte"));
        assertTrue(StructuralMatchers.typeMatches("[Ljava.lang.String;", "String[]"));
        assertTrue(StructuralMatchers.typeMatches("[[Ljava.lang.String;", "java.lang.String[][]"));
        assertFalse(StructuralMatchers.typeMatches("[Ljava.lang.String;", "String[][]"));
        assertEquals("[B", StructuralMatchers.descriptor("[[B"));
        assertEquals("byte", StructuralMatchers.descriptor("[B"));
    }

    @Test
    void catalogCoversEveryAppendixBHookThePayloadOwns() {
        List<String> ids = HookCatalog.all().stream().map(HookSpec::id).toList();
        for (String required : List.of("HK-01", "HK-02", "HK-03", "HK-03b", "HK-04", "HK-05",
                "HK-06", "HK-07", "HK-09", "HK-10", "HK-11", "HK-12", "HK-13a", "HK-13b",
                "HK-13c", "HK-14", "HK-15", "HK-15b", "HK-16", "HK-17", "HK-21", "HK-23",
                "HK-26", "HK-30", "HK-31", "HK-32")) {
            assertTrue(ids.contains(required), "missing " + required);
        }
        assertEquals(Tier.CAPTURE_REQUIRED, HookCatalog.all().stream()
                .filter(s -> s.id().equals("HK-06")).findFirst().orElseThrow().tier());
        assertTrue(HookCatalog.all().stream()
                .filter(s -> s.id().equals("HK-14")).findFirst().orElseThrow().neverMinusOne());
        assertTrue(HookCatalog.all().stream()
                .filter(s -> s.id().equals("HK-15")).findFirst().orElseThrow().neverMinusOne());
    }

    @Test
    void captureTierFailsWhenHk06IsMissing() {
        HookRegistry registry = new HookRegistry();
        for (HookSpec s : HookCatalog.all()) registry.register(s);
        registry.resolved("HK-01", "a", 1L);
        registry.resolved("HK-04", "b", 2L);
        registry.unresolved("HK-06", HookState.FAILED, "nope");
        TierManager.Health h = TierManager.compute(registry);
        assertFalse(h.captureLive());
        assertTrue(h.unsupported());
    }

    @Test
    void captureTierAcceptsHidlRadioAlternative() {
        HookRegistry registry = new HookRegistry();
        for (HookSpec s : HookCatalog.all()) registry.register(s);
        registry.resolved("HK-01b", "a", 1L);
        registry.resolved("HK-04", "b", 2L);
        registry.resolved("HK-06", "c", 3L);
        assertTrue(TierManager.compute(registry).captureLive());
    }

    @Test
    void firewallTierNeedsAllCoreHooks() {
        HookRegistry registry = new HookRegistry();
        for (HookSpec s : HookCatalog.all()) registry.register(s);
        registry.register(HookSpecs.builder("HK-24", Tier.FIREWALL_CORE, HookKind.AFTER,
                "a.B", "<init>").anyArity().build());
        registry.resolved("HK-24", "a", 1L);
        registry.resolved("HK-14", "b", 2L);
        assertFalse(TierManager.compute(registry).firewallAvailable());
        registry.resolved("HK-15", "c", 3L);
        assertTrue(TierManager.compute(registry).firewallAvailable());
    }

    @Test
    void notApplicableHooksDoNotCountAgainstTheirTier() {
        HookRegistry registry = new HookRegistry();
        for (HookSpec s : HookCatalog.all()) registry.register(s);
        registry.resolved("HK-06", "a", 1L);
        registry.resolved("HK-04", "b", 2L);
        registry.resolved("HK-01", "c", 3L);
        registry.unresolved("HK-01b", HookState.NOT_APPLICABLE, "AIDL radio");
        TierManager.Health h = TierManager.compute(registry);
        assertEquals(3, h.capture().live());
        assertEquals(3, h.capture().total());
    }

    @Test
    void degradeUnhooksEverythingOnCaptureFailure() {
        final int[] unhooked = {0};
        HookRegistry registry = new HookRegistry();
        registry.setBridge(new HookBackend() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public long install(Member target, HookKind kind, Hooker hooker) {
                return 1L;
            }

            @Override
            public boolean uninstall(long handle) {
                unhooked[0]++;
                return true;
            }

            @Override
            public boolean forceDeoptimize(Member target) {
                return true;
            }
        });
        for (HookSpec s : HookCatalog.all()) registry.register(s);
        registry.resolved("HK-01", "a", 1L);
        registry.resolved("HK-04", "b", 2L);
        registry.unresolved("HK-06", HookState.FAILED, "no");
        TierManager.Health h = TierManager.degrade(registry);
        assertTrue(h.unsupported());
        assertEquals(2, unhooked[0]);
    }

    @Test
    void healthJsonHasEveryHookWithItsState() {
        HookRegistry registry = new HookRegistry();
        registry.register(HookSpecs.builder("HK-06", Tier.CAPTURE_REQUIRED, HookKind.AFTER,
                "a.B", "m").arity(1).build());
        registry.resolved("HK-06", "a.B#m", 1L);
        registry.recordLatency("HK-06", 1_000L);
        String json = registry.hookHealthJson("fp", "1.0.0", "KNOWN");
        assertTrue(json.contains("\"HK-06\""));
        assertTrue(json.contains("\"state\":\"LIVE\""));
        assertTrue(json.contains("\"tier\":\"capture-required\""));
        assertTrue(json.contains("\"p50Ns\":1000"));
        assertNull(registry.get("HK-99"));
    }
}
