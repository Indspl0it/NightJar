package dev.nightjar.app.core.root

import dev.nightjar.app.core.log.ALog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The only seam that may touch a root shell. Production uses [LibsuShellPort]; tests inject a fake (AC).
 *
 * Implementations must never print command output into a log; they only return it.
 */
interface ShellPort {
    /** Runs one command list in a root shell. Returns exit code and merged output. */
    suspend fun exec(vararg command: String): ShellResult

    /** True when a root shell is available (used by [LibsuRootOps.detectRootSolution] and onboarding). */
    suspend fun isRootAvailable(): Boolean

    /**
     * Why the last attempt did not produce a root shell, in words a user can act on ("su could not start: ..."), or
     * null when it did. The checks screen shows this instead of a bare "root was denied".
     */
    val lastError: String? get() = null
}

/**
 * [RootOps] over [ShellPort].
 *
 * Guarantees that the interface alone cannot express:
 *  - the shell is created lazily, on the first operation, never at launch;
 *  - every operation logs its name and exit code to L2, never its output;
 *  - only the typed [RootOps] operations exist — no method forwards an arbitrary command from a caller.
 */
class LibsuRootOps(
    private val shell: ShellPort,
    private val log: ALog,
) : RootOps {
    private val mutex = Mutex()

    override suspend fun detectRootSolution(): RootSolution =
        run("detectRootSolution") {
            // Root is "we hold UID 0", not "a manager binary was on PATH". `magisk -V` is the usual probe, but the
            // binary lives at /debug_ramdisk/magisk on current Magisk and a manager update can move it; asking the
            // shell for its uid is the only check that cannot be fooled by a name. The manager name is cosmetic and
            // only decides which install command the module manager uses.
            val uid = shell.exec("id -u")
            val rooted = uid.ok && uid.stdout.trim() == "0"
            if (!rooted) {
                val reason =
                    shell.lastError
                        ?: "id -u returned ${if (uid.ok) "'${uid.stdout.trim()}'" else "exit ${uid.exitCode}"}"
                return@run RootSolution(RootManager.UNKNOWN, null, rooted, reason)
            }
            for (binary in MAGISK_BINARIES) {
                versionOf("$binary -V")?.let { version ->
                    return@run RootSolution(RootManager.MAGISK, version, rooted = true)
                }
            }
            versionOf("ksud -V")?.let { return@run RootSolution(RootManager.KERNEL_SU, it, rooted = true) }
            versionOf("apd -V")?.let { return@run RootSolution(RootManager.APATCH, it, rooted = true) }
            RootSolution(
                RootManager.UNKNOWN,
                null,
                rooted = true,
                diagnostic = "root shell works, but no Magisk/KernelSU/APatch CLI answered",
            )
        }

    override suspend fun phonePid(): Int? =
        try {
            // Polled every few seconds by the phone-process watchdog, so it bypasses the per-operation log line.
            val r = mutex.withLock { shell.exec("pidof com.android.phone") }
            if (r.ok) {
                r.stdout
                    .trim()
                    .split(" ")
                    .firstOrNull()
                    ?.toIntOrNull()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

    override suspend fun detectXposed(): Boolean =
        run("detectXposed") {
            // Vector and LSPosed keep /data/adb/lspd, ship as a Zygisk module (zygisk_lsposed / zygisk_vector) and install a
            // manager app. Any one of the three is enough; none requires a particular Zygisk implementation.
            val probe =
                shell.exec(
                    "test -d /data/adb/lspd && echo y; " +
                        "ls ${RootOps.MODULES_ROOT} 2>/dev/null | grep -qiE 'lsposed|vector' && echo y; " +
                        "pm list packages 2>/dev/null | grep -qE 'org.lsposed.manager|org.matrix.vector' && echo y; true",
                )
            probe.stdout.lineSequence().any { it.trim() == "y" }
        }

    /** Version token of a `-V` probe, or null when the binary is absent, refused or prints nothing. */
    private suspend fun versionOf(command: String): String? {
        val result = shell.exec(command)
        if (!result.ok) return null
        return firstVersionToken(result.stdout)
    }

    override suspend fun readCompanionLog(): String? =
        run("readCompanionLog") {
            // Vector/LSPosed keep what a module logs through XposedBridge.log in their own log directory. Only the
            // Nightjar lines are shown; the files rotate, so the newest two are read.
            val r =
                shell.exec(
                    "for f in $(ls -t /data/adb/lspd/log/modules_*.log /data/adb/lspd/log/*.log 2>/dev/null | head -n 3); do " +
                        "grep -i nightjar \"${'$'}f\"; done | tail -n 400",
                )
            if (r.ok) r.stdout.ifEmpty { "No Nightjar lines in the Vector/LSPosed log yet." } else null
        }

    override suspend fun restartPhoneProcess(): ShellResult =
        run("restartPhoneProcess") {
            shell.exec("killall com.android.phone || pkill -f com.android.phone")
        }

    override suspend fun reboot(): ShellResult = run("reboot") { shell.exec("svc power reboot") }

    // ------------------------------------------------------------------ internals

    private fun firstVersionToken(text: String): String? =
        text
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.split(' ')
            ?.lastOrNull { it.any(Char::isDigit) }

    /** Runs one operation, serialising shell use and logging the name and exit code only. */
    private suspend fun <T> run(
        name: String,
        vararg detail: String,
        block: suspend () -> T,
    ): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val result =
                    try {
                        block()
                    } catch (e: RootUnavailableException) {
                        log.w(TAG, "root op failed", e)
                        throw e
                    }
                val code = (result as? ShellResult)?.exitCode ?: 0
                log.i(TAG, "root op", listOf("op" to name, "exit" to code) + detail.mapIndexed { i, d -> "arg$i" to d })
                result
            }
        }

    private companion object {
        const val TAG = "root"

        /** PATH first, then the absolute location current Magisk uses (its daemon directory is not on PATH). */
        val MAGISK_BINARIES = listOf("magisk", "/debug_ramdisk/magisk")
    }
}
