package dev.nightjar.payload.hook;

import dev.nightjar.contract.Tier;

/**
 * One hook catalog entry: what to hook, how to find it, and how it degrades.
 *
 * @param id hook id, {@code "HK-01"} … {@code "HK-32"}
 * @param tier degradation tier
 * @param kind before or after
 * @param className target class, or the enclosing class for an anonymous target
 * @param methodName method name
 * @param paramTypes parameter type names; empty = any signature
 * @param resolver how to find the target
 * @param superName for anonymous targets: the superclass or interface the anonymous class extends
 * @param arity expected parameter count, −1 = any
 * @param neverMinusOne true when the skip result may never be −1
 * @param mainLooper true when the hook body runs on the phone main looper (target)
 */
public record HookSpec(
        String id,
        Tier tier,
        HookKind kind,
        String className,
        String methodName,
        java.util.List<String> paramTypes,
        ResolverKind resolver,
        String superName,
        int arity,
        boolean neverMinusOne,
        boolean mainLooper) {

    /** How the resolver finds the target. */
    public enum ResolverKind {
        /** Load the class by name and find the method by name and signature. */
        NAME,
        /** Scan {@code Outer$1…$30} for the class with the given superclass/interface (HK-04/05/17). */
        ANONYMOUS,
        /** Name + arity + parameter type names, for signatures that drift (HK-19). */
        OVERLOAD,
        /** Resolved lazily through another object's class loader (OnePlus, HK-30…32). */
        IMPL_LOADER
    }

    public HookSpec {
        paramTypes = paramTypes == null ? java.util.List.of() : java.util.List.copyOf(paramTypes);
        if (resolver == null) resolver = ResolverKind.NAME;
        if (kind == null) kind = HookKind.BEFORE;
        if (tier == null) tier = Tier.CAPTURE_OPTIONAL;
        if (methodName == null) methodName = "";
        if (className == null) className = "";
    }

    /** Short target description for health JSON ({@code InboundSmsHandler#dispatchMessage}). */
    public String targetName() {
        String simple = className;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) simple = simple.substring(dot + 1);
        return simple + "#" + methodName;
    }

}
