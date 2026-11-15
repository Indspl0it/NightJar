package dev.nightjar.payload.env;

import dev.nightjar.payload.hook.HookBackend;
import dev.nightjar.payload.hook.HookKind;
import dev.nightjar.payload.hook.Hooker;
import java.lang.reflect.Member;

/** {@link HookBackend} that accepts every install and counts calls (tests). */
public final class HookBackendSpy implements HookBackend {
    public int installed;
    public int unhooked;
    public int deoptimized;
    public boolean refuse;
    private long next = 1;

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public long install(Member target, HookKind kind, Hooker hooker) {
        if (refuse) return 0L;
        installed++;
        return next++;
    }

    @Override
    public boolean uninstall(long handle) {
        unhooked++;
        return true;
    }

    @Override
    public boolean forceDeoptimize(Member target) {
        deoptimized++;
        return true;
    }
}
