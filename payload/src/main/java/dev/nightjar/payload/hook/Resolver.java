package dev.nightjar.payload.hook;

import dev.nightjar.contract.HookState;
import java.lang.reflect.Member;

/**
 * Resolves one {@link HookSpec} to a live {@link Member}.
 *
 * <p>Order: name + signature with the phone app class loader ({@code lpparam.classLoader}), anonymous-class
 * matching, overload matching by arity, and the OnePlus {@code mImpl} loader supplied by the caller. A method declared
 * on a superclass is found by the superclass scan in {@link StructuralMatchers}. A spec whose method name is
 * {@code <init>} resolves to one declared constructor (HK-24: {@code InboundSmsHandler} has one constructor on both
 * reference builds).
 *
 * <p>Thread: main thread during {@code handleLoadPackage}; the worker for the IMPL_LOADER specs.
 */
public final class Resolver {
    /** Outcome of one resolution. */
    public record Result(HookState state, Member target, String error) {
        public static Result live(Member m, String description) {
            return new Result(HookState.LIVE, m, description);
        }

        public static Result notFound(String error) {
            return new Result(HookState.NOT_FOUND, null, error);
        }

        public static Result failed(String error) {
            return new Result(HookState.FAILED, null, error);
        }

        public static Result notApplicable(String reason) {
            return new Result(HookState.NOT_APPLICABLE, null, reason);
        }

        public boolean isLive() {
            return state == HookState.LIVE;
        }
    }

    /** Method name that resolves to constructors. */
    public static final String INIT = "<init>";

    private Resolver() {}

    /** Resolves against {@code loader}; a null loader means "not applicable" for IMPL_LOADER specs. */
    public static Result resolve(ClassLoader loader, HookSpec spec) {
        if (spec == null) return Result.failed("spec == null");
        if (spec.resolver() == HookSpec.ResolverKind.IMPL_LOADER && loader == null) {
            return Result.notApplicable("no mImpl class loader");
        }
        try {
            ClassLoader cl = loader != null ? loader : ClassLoader.getSystemClassLoader();
            Class<?> owner;
            if (spec.resolver() == HookSpec.ResolverKind.ANONYMOUS) {
                owner = StructuralMatchers.findAnonymous(cl, spec.className(), spec.superName(),
                        spec.methodName(), spec.arity());
                if (owner == null) {
                    return Result.notFound("anonymous class of " + spec.className()
                            + " extending " + spec.superName() + " not found");
                }
            } else {
                owner = Class.forName(spec.className(), false, cl);
            }
            Member m = find(owner, spec);
            if (m == null) {
                return Result.notFound(spec.methodName() + " not found on " + owner.getName());
            }
            return Result.live(m, spec.targetName());
        } catch (ClassNotFoundException e) {
            return Result.notFound("class " + spec.className() + " not found");
        } catch (Throwable t) {
            return Result.failed(t.getClass().getSimpleName());
        }
    }

    /** Resolves a target that is known to exist (tests, own classes). */
    public static Result resolve(Class<?> owner, HookSpec spec) {
        try {
            Member m = find(owner, spec);
            return m == null
                    ? Result.notFound(spec.methodName() + " not found on " + owner.getName())
                    : Result.live(m, spec.targetName());
        } catch (Throwable t) {
            return Result.failed(t.getClass().getSimpleName());
        }
    }

    private static Member find(Class<?> owner, HookSpec spec) {
        if (INIT.equals(spec.methodName())) {
            return StructuralMatchers.findConstructor(owner, spec.paramTypes(), spec.arity());
        }
        return StructuralMatchers.findMethod(owner, spec.methodName(), spec.paramTypes(),
                spec.arity());
    }
}
