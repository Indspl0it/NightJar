package dev.nightjar.app.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.nightjar.app.BuildConfig
import dev.nightjar.contract.HookState
import dev.nightjar.contract.Ipc
import dev.nightjar.payload.boot.Entry
import org.json.JSONObject
import java.io.File

/**
 * Xposed entry (`assets/xposed_init`). Vector/LSPosed call it in every process in the module's scope; only the main
 * process of `com.android.phone` is of interest. The payload then runs with its hooks installed through [XposedBackend].
 *
 * Configuration: the hook profiles come from the module's own resources. Rules and settings are not read from disk:
 * they arrive over the app's IPC handshake. Until the first handshake the payload uses the default rules (every class
 * ALLOW), so it only monitors. Before the first unlock after boot the head filter filters no message, and events wait
 * in the pending-unlock spool.
 *
 * Thread: the main thread of the phone process, in `handleLoadPackage`.
 */
class XposedEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PHONE_PACKAGE) return
        // Only the main process: a second process of the phone uid would start a second payload with its own IPC session.
        val proc = lpparam.processName ?: PHONE_PACKAGE
        if (proc != PHONE_PACKAGE) return
        if (android.os.Process.myUid() != PHONE_UID) return
        // The flag is per process (a restart is a fresh class). No second start: an unsupported build (rc -2) gives the
        // same result again, and after a partial start (rc -1) a second runtime could install the hooks a second time.
        if (!started.compareAndSet(false, true)) return
        startPayload(lpparam)
    }

    private fun startPayload(lpparam: XC_LoadPackage.LoadPackageParam) {
        val rc =
            try {
                val profiles =
                    XposedEntry::class.java.classLoader
                        ?.getResourceAsStream("nightjar-profiles.json")
                        ?.use { it.readBytes() }
                Entry.start(XposedBackend(), lpparam.classLoader, null, null, profiles, info().toByteArray(Charsets.UTF_8))
            } catch (t: Throwable) {
                // The class name only; an exception message can echo argument data.
                log("Nightjar: Entry.start failed: ${t.javaClass.simpleName}")
                -1
            }
        logSummary(rc)
    }

    private fun info(): String =
        JSONObject()
            .put(Ipc.Key.PAYLOAD_VERSION, BuildConfig.VERSION_NAME)
            .put("buildId", "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}")
            .put("bootId", bootId())
            .put("companionStatus", "xposed")
            .put("trustInstalledApp", "true")
            .put("moduleVersionCode", BuildConfig.VERSION_CODE)
            .put("injectedAtWall", System.currentTimeMillis())
            .put("pid", android.os.Process.myPid())
            .toString()

    /** One summary line, plus one line for each hook that is not LIVE (where the hooking stops). */
    private fun logSummary(rc: Int) {
        runCatching {
            val hooks =
                Entry
                    .runtime()
                    ?.registry()
                    ?.all()
                    .orEmpty()
            val live = hooks.count { it.state == HookState.LIVE }
            log("Nightjar: Entry.start rc=$rc (0 running, -1 failed, -2 unsupported) live=$live/${hooks.size}")
            hooks.filter { it.state != HookState.LIVE }.forEach {
                log("Nightjar: hook ${it.spec.id()} state=${it.state} err=${it.lastError}")
            }
        }
    }

    private fun log(line: String) {
        // Hook ids, states and exception class names only: never message text or a number.
        runCatching { XposedBridge.log(line) } // mask-ok: status line built in this file, no message data
    }

    private fun bootId(): String =
        try {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        } catch (_: Throwable) {
            ""
        }

    private companion object {
        const val PHONE_PACKAGE = "com.android.phone"
        const val PHONE_UID = 1001

        val started =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
    }
}
