package dev.nightjar.payload.hook;

import java.lang.reflect.Member;

/**
 * Hook backend. The app's {@code XposedBackend} implements it over the Xposed API
 * of Vector/LSPosed; the JVM test suite uses a fake that installs nothing.
 *
 * <p>The target is a {@link Member} so the head-filter installation can hook {@code
 * InboundSmsHandler} constructors (HK-24); every other hook is a {@code Method}.
 *
 * <p>Thread: {@link #install}/{@link #uninstall} on the installing thread; callbacks on the hooked
 * thread.
 */
public interface HookBackend {
    /** True when the hook framework (Vector/LSPosed) is present. */
    boolean available();

    /**
     * Installs one hook.
     *
     * @return a positive handle, or 0 when the platform refused
     */
    long install(Member target, HookKind kind, Hooker hooker);

    /** Removes the hook; true when it was removed. */
    boolean uninstall(long handle);

    /** Asks ART to deoptimize {@code target} so inlined call sites fall back to the hook; false when not supported. */
    boolean forceDeoptimize(Member target);

    /** Backend that refuses everything (tests). */
    HookBackend NONE = new HookBackend() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public long install(Member target, HookKind kind, Hooker hooker) {
            return 0L;
        }

        @Override
        public boolean uninstall(long handle) {
            return false;
        }

        @Override
        public boolean forceDeoptimize(Member target) {
            return false;
        }
    };
}
