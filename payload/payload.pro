# R8 rules for payload.dex.
# payload.dex is a gate artifact only: no runtime path loads it. The APK carries the payload classes through
# implementation(project(":payload")) and keeps all of them (app/proguard-rules.pro). The dex measures what the phone
# process can reach from the Xposed entry: it proves the framework-only class rule and the 300 KiB size budget.
# The payload is shrunk and optimized, never obfuscated. Shrinking removes code the phone process cannot reach (the
# editor half of :rules, unused contract members) and the R8 optimizer removes dead branches, merges constants and
# inlines small accessors (306,716 B shrink-only, 221,144 B with optimization, hard limit 307,200 B).
# Obfuscation stays off so class and method names stay readable in logs and stack traces. The members below are the
# ones that app code (app/src/main/java/dev/nightjar/app/xposed) calls from outside the payload.
-dontobfuscate

# Called by XposedEntry when com.android.phone loads.
-keep class dev.nightjar.payload.boot.Entry {
    public static int start(dev.nightjar.payload.hook.HookBackend, java.lang.ClassLoader, byte[], byte[], byte[], byte[]);
    public static dev.nightjar.payload.boot.PayloadRuntime runtime();
}

# Implemented by XposedBackend (the hook bridge of Vector/LSPosed).
-keep interface dev.nightjar.payload.hook.HookBackend { *; }
-keep enum dev.nightjar.payload.hook.HookKind { *; }

# XposedBackend replaces each hooked method with Hooker.call, which runs the before/after phases, and gives the Hooker
# the original-method invoker.
-keepclassmembers class dev.nightjar.payload.hook.Hooker {
    public java.lang.Object call(java.lang.Object[]);
    public void setOriginal(dev.nightjar.payload.hook.Hooker$Original);
    public boolean staticTarget();
}
-keep interface dev.nightjar.payload.hook.Hooker$Original { *; }

# XposedEntry logs the hook states after the start.
-keepclassmembers class dev.nightjar.payload.hook.HookRegistry {
    public java.util.Collection all();
}

# Framework and org.json members are resolved reflectively in the phone process; android.jar is compile-only here.
-dontwarn com.android.internal.**
-dontwarn android.telephony.**
-dontwarn org.json.**
-ignorewarnings
