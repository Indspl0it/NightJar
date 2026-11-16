package dev.nightjar.payload.boot;

import dev.nightjar.contract.Ipc;

import android.content.Context;
import dev.nightjar.payload.capture.PackageResolver;
import dev.nightjar.payload.capture.InjectionTokens;
import dev.nightjar.payload.env.AndroidClock;
import dev.nightjar.payload.env.AndroidEnv;
import dev.nightjar.payload.env.Contexts;
import dev.nightjar.payload.env.Env;
import dev.nightjar.payload.env.HandlerWorker;
import dev.nightjar.payload.hook.HookBackend;
import dev.nightjar.payload.log.LogcatSink;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Entry point called by the app's {@code XposedEntry} when Vector/LSPosed load {@code com.android.phone}
 * .
 *
 * <p>{@link #start} is wrapped in a catch-all: any failure leaves no hook installed (the runtime unhooks on a
 * capture-tier failure) and returns a negative code.
 *
 * <p><b>What runs here and what does not.</b> {@code handleLoadPackage} runs before the phone application exists, so
 * there is no {@code Context} yet and no {@code PhoneFactory}. This method installs hooks only (reflection, no I/O,
 * no binder). Everything that needs a context or a {@code Phone} (IPC, the pending-unlock spool, release and self-test
 * injection, the head-filter sweep) is posted to the payload worker by {@link PayloadRuntime#scheduleWiring()}, which
 * waits for the context to appear.
 *
 * <p>Configuration: the profiles come from the module's resources; rules and settings arrive over the IPC handshake.
 * The wiring step {@code configCopies} applies the copies of the last session from device-protected storage before
 * that ({@code ConfigCopies}). Without a copy the default rules (every class ALLOW) apply, so the payload only monitors.
 * Before the first unlock after boot the head filter filters no message.
 *
 * <p>Thread: the main thread of the phone process, in {@code handleLoadPackage}; nothing here blocks on I/O.
 */
public final class Entry {
    private Entry() {}

    /** The runtime of the current process, or null before {@link #start} and after a failure. */
    private static volatile PayloadRuntime runtime;

    public static PayloadRuntime runtime() {
        return runtime;
    }

    /**
     * Called once by {@code XposedEntry}.
     *
     * @param backend the Xposed hook bridge
     * @param loader the phone application's class loader ({@code lpparam.classLoader}); it sees the boot class path and
     *     the phone app classes. Null means the system class loader
     * @param rules {@code rules.json} bytes, or null
     * @param settings {@code settings.json} bytes, or null
     * @param profiles {@code profiles.json} bytes, or null
     * @param info JSON {@code {payloadVersion, buildId, moduleVersionCode, bootId}}, or null
     * @return 0 when the session reached RUNNING, −1 on a caught failure, −2 when the build is
     *     UNSUPPORTED (capture tier failed; nothing is hooked)
     */
    public static int start(HookBackend backend, ClassLoader loader, byte[] rules, byte[] settings, byte[] profiles,
            byte[] info) {
        // The thread is created before install, so every failure path has to stop it.
        HandlerWorker worker = null;
        try {
            String infoJson = info == null ? "{}" : new String(info, java.nio.charset.StandardCharsets.UTF_8);
            String payloadVersion = version(infoJson);
            // The context does not exist yet (ActivityThread.attach runs later); AndroidEnv resolves
            // it on first use, and scheduleWiring() waits for it before any service is created.
            Env env = new AndroidEnv(null, payloadVersion);
            String sessionId = UUID.randomUUID().toString();
            worker = new HandlerWorker();
            PayloadRuntime rt = new PayloadRuntime(
                    AndroidClock.INSTANCE,
                    worker,
                    new LogcatSink(),
                    env,
                    backend == null ? HookBackend.NONE : backend,
                    InjectionTokens.NONE,
                    packageResolver(),
                    sessionId,
                    infoJson);
            rt.setClassLoader(loader);
            boolean ok = rt.install(rules, settings, profiles);
            runtime = rt;
            if (!ok) {
                // Capture tier failed or install threw: keep the object for the health report but
                // leave nothing hooked (PayloadRuntime already unhooked).
                rt.log().e("boot", "unsupported build", "session", sessionId);
                worker.quit();
                return -2;
            }
            rt.scheduleWiring();
            rt.log().i("boot", "payload installed", "session", sessionId, "version", payloadVersion);
            return 0;
        } catch (Throwable t) {
            try {
                PayloadRuntime rt = runtime;
                if (rt != null) rt.log().e("boot", "start failed",
                        "err", t.getClass().getSimpleName());
            } catch (Throwable ignored) {
                // Logging must never mask the original failure.
            }
            runtime = null;
            if (worker != null) worker.quit();
            return -1;
        }
    }

    private static String version(String infoJson) {
        try {
            String v = new JSONObject(infoJson).optString(Ipc.Key.PAYLOAD_VERSION, null);
            if (v != null && !v.isEmpty()) return v;
        } catch (Throwable ignored) {
            // Fall through to the placeholder.
        }
        return "unknown";
    }

    /** Uid → packages, resolved against the phone process's context when one exists. */
    private static PackageResolver packageResolver() {
        return uid -> {
            Context context = Contexts.application();
            if (context == null) return new String[0];
            try {
                String[] pkgs = context.getPackageManager().getPackagesForUid(uid);
                return pkgs == null ? new String[0] : pkgs;
            } catch (Throwable t) {
                return new String[0];
            }
        };
    }
}
