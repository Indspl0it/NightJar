package dev.nightjar.payload.hook;

import dev.nightjar.contract.Tier;
import java.util.List;

/** Test-only {@link HookSpec} builder (the payload dex carries no builder). */
public final class HookSpecs {
    private String id;
    private Tier tier = Tier.CAPTURE_OPTIONAL;
    private HookKind kind = HookKind.BEFORE;
    private String cls = "a.B";
    private String method = "m";
    private List<String> params = List.of();
    private HookSpec.ResolverKind resolver = HookSpec.ResolverKind.NAME;
    private String superName;
    private int arity = -1;
    private boolean neverMinusOne;
    private boolean mainLooper;

    public static HookSpecs builder(String id, Tier tier, HookKind kind, String cls, String method) {
        HookSpecs b = new HookSpecs();
        b.id = id;
        b.tier = tier;
        b.kind = kind;
        b.cls = cls;
        b.method = method;
        return b;
    }

    public HookSpecs params(String... names) {
        params = List.of(names);
        arity = names.length;
        return this;
    }

    public HookSpecs anyArity() {
        params = List.of();
        arity = -1;
        return this;
    }

    public HookSpecs arity(int n) {
        arity = n;
        return this;
    }

    public HookSpecs resolver(HookSpec.ResolverKind r) {
        resolver = r;
        return this;
    }

    public HookSpecs superName(String name) {
        superName = name;
        return this;
    }

    public HookSpecs neverMinusOne() {
        neverMinusOne = true;
        return this;
    }

    public HookSpecs mainLooper() {
        mainLooper = true;
        return this;
    }

    public HookSpec build() {
        return new HookSpec(id, tier, kind, cls, method, params, resolver, superName, arity,
                neverMinusOne, mainLooper);
    }
}
