package dev.nightjar.payload.hook;

/** Where a hook runs relative to the original method ("Kind"). */
public enum HookKind {
    BEFORE,
    AFTER,
    /** Both phases: the before body can skip the original, the after body sees its result (HK-14). */
    AROUND
}
