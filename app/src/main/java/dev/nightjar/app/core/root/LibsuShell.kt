package dev.nightjar.app.core.root

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The only class in the app that may obtain a root shell.
 *
 * Two paths, tried in this order:
 *
 * 1. **libsu.** One interactive shell, kept alive between operations. It is the fast path (no process spawn per
 *    command).
 * 2. **`su -c`.** A one-shot root process per command. libsu silently falls back to a *non-root* `sh` when its `su`
 *    handshake does not confirm root (BuilderImpl.start(): on `NoShellException` or a non-root result it runs plain
 *    `sh`), and on some ROMs the interactive handshake fails while `su -c` works. Without this second path the app
 *    reports "root denied" on a device where root is granted and usable.
 *
 * Whatever happens, [lastError] says which path failed and how, so the checks screen can show the reason instead of
 * a bare "denied".
 */
class LibsuShell : ShellPort {
    @Volatile private var shell: Shell? = null

    /** Human-readable reason the last attempt produced no root shell; null once a root shell worked. */
    @Volatile override var lastError: String? = null
        private set

    override suspend fun isRootAvailable(): Boolean =
        withContext(Dispatchers.IO) {
            val result = exec("id -u")
            result.ok && result.stdout.trim() == "0"
        }

    override suspend fun exec(vararg command: String): ShellResult =
        withContext(Dispatchers.IO) {
            val sh = shellOrNull()
            if (sh != null && sh.isRoot) {
                lastError = null
                return@withContext runInShell(sh, command)
            }
            // libsu gave us a non-root shell (or none). `su -c` is the second opinion, and the reason libsu failed is
            // kept for the checks screen.
            runViaSu(command)
        }

    private fun runInShell(
        sh: Shell,
        command: Array<out String>,
    ): ShellResult {
        // libsu only stores output when the job is given lists to store it in; a bare `exec()` runs the command and
        // returns an empty `out`, which made every probe ("id -u", "cat module.prop") look like it printed nothing.
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val job = sh.newJob().to(out, err)
        command.forEach { job.add(it) }
        val result = job.exec()
        return ShellResult(result.code, (out + err).joinToString("\n").trimEnd())
    }

    /**
     * `su -c <script>`: one root process, output merged, bounded by [SU_TIMEOUT_SECONDS]. A missing or refusing `su`
     * surfaces as exit code [NO_SU_EXIT] so the caller can tell "no root" from "the command failed".
     */
    private fun runViaSu(command: Array<out String>): ShellResult {
        val script = command.joinToString("\n")
        val result = runBounded(ProcessBuilder("su", "-c", script), SU_TIMEOUT_SECONDS) { lastError = it }
        if (result.exitCode > 0) lastError = "su -c exit ${result.exitCode}: ${result.stdout.lineSequence().firstOrNull().orEmpty()}"
        return result
    }

    companion object {
        /** Long enough for a first-run Magisk prompt; libsu's 20 s default cut the user off mid-read. */
        private const val PROMPT_TIMEOUT_SECONDS = 60L

        /** A command inside a root shell should never take longer than this; the checks must not hang the screen. */
        private const val SU_TIMEOUT_SECONDS = 30L

        /** `su` missing or refused: distinct from a command that ran in a root shell and failed. */
        private const val NO_SU_EXIT = -2

        /** After the process is gone the pipe closes; this only bounds a reader that somehow does not notice. */
        private const val READER_JOIN_MS = 2_000L

        /**
         * Runs [builder] with merged output and kills it after [timeoutSeconds]. The output is drained on its own thread:
         * reading to EOF on the caller's thread would block until the process closes its output, so a hung `su` (an
         * unanswered prompt, a stuck daemon) could never reach the timeout. A process that cannot start or times out
         * reports [NO_SU_EXIT] and says why through [onProblem].
         */
        internal fun runBounded(
            builder: ProcessBuilder,
            timeoutSeconds: Long,
            onProblem: (String) -> Unit = {},
        ): ShellResult {
            val process =
                try {
                    builder.redirectErrorStream(true).start()
                } catch (e: Exception) {
                    val reason = "su could not start: ${e.javaClass.simpleName}: ${e.message}"
                    onProblem(reason)
                    return ShellResult(NO_SU_EXIT, reason)
                }
            val collected = StringBuilder()
            val reader =
                Thread {
                    runCatching {
                        process.inputStream.bufferedReader().forEachLine { line -> synchronized(collected) { collected.appendLine(line) } }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(READER_JOIN_MS)
            val output = synchronized(collected) { collected.toString().trimEnd() }
            if (!finished) {
                onProblem("su -c timed out after ${timeoutSeconds}s")
                return ShellResult(NO_SU_EXIT, output)
            }
            return ShellResult(process.exitValue(), output)
        }
    }

    /** The libsu shell, asking again if the last one was not root. Null when libsu could not create one at all. */
    private fun shellOrNull(): Shell? {
        val current = shell
        if (current != null && current.isAlive && current.isRoot) return current
        if (current != null && current.isAlive) lastError = "libsu shell is not root (it fell back to sh)"
        // Dropping libsu's own cache matters as much as dropping ours: `Shell.getCachedShell()` is what `build()`
        // would otherwise hand back. The two caches usually hold the same object, so close it once.
        val libsuCached = Shell.getCachedShell()
        shell = null
        current?.close()
        if (libsuCached !== current) libsuCached?.close()
        val created =
            try {
                Shell.Builder
                    .create()
                    .setTimeout(PROMPT_TIMEOUT_SECONDS)
                    .build()
            } catch (e: Exception) {
                lastError = "libsu: ${e.javaClass.simpleName}: ${e.message}"
                return null
            }
        if (!created.isAlive) {
            created.close()
            lastError = "libsu shell died immediately"
            return null
        }
        shell = created
        if (!created.isRoot) lastError = "libsu reported a non-root shell"
        return created
    }

    /** Releases the shell (app shutdown, or after a failed session). */
    fun close() {
        shell?.close()
        shell = null
    }
}
