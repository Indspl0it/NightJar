package dev.nightjar.app.core.root

/** Root solution detected on the device (consumes this). */
enum class RootManager(
    val displayName: String,
) {
    MAGISK("Magisk"),
    KERNEL_SU("KernelSU"),
    APATCH("APatch"),
    UNKNOWN("Unknown"),
}

/** Result of one detected root environment. */
data class RootSolution(
    val manager: RootManager,
    /** e.g. `30.4` (Magisk) / `1.0.3` (KernelSU) / `10933` (APatch); null when the version string was unreadable. */
    val version: String?,
    /**
     * True when a UID-0 shell was obtained. This is what the checks ask about; [manager] only names the root solution,
     * and an unrecognised CLI (a new Magisk layout, a manager not probed here) must not turn working root into
     * "denied".
     */
    val rooted: Boolean = manager != RootManager.UNKNOWN,
    /** Why root is unavailable, in user-facing words; null when it worked or when nothing went wrong. */
    val diagnostic: String? = null,
)

/**
 * Exit status of one root operation. `stdout` already has stderr merged in (libsu default) and is shown only inside the
 * install dialog's expandable section; it is never logged.
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
) {
    val ok: Boolean get() = exitCode == 0
}

/** A root operation that could not even be attempted (no shell, refused, malformed input). */
class RootUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The complete root surface. Nothing outside this interface may run a shell command.
 *
 * Every method is a typed root operation; there is no generic `exec`. The shell is requested lazily on the first call,
 * never at launch, and each operation logs its name and exit code to L2.
 */
interface RootOps {
    /**
     * Root solution and its version. The Zygisk implementation is not probed: the Xposed design
     * needs no particular one, and no screen shows it.
     */
    suspend fun detectRootSolution(): RootSolution

    /**
     * True when an Xposed framework (Vector / LSPosed) is present. Nightjar is an Xposed module scoped to
     * `com.android.phone`, so this is the only framework probe.
     */
    suspend fun detectXposed(): Boolean = false

    /** Pid of the running `com.android.phone` process, or null when it is not running or root is unavailable. */
    suspend fun phonePid(): Int? = null

    /** The Nightjar lines of the Vector/LSPosed log, or null when the log cannot be read. */
    suspend fun readCompanionLog(): String?

    /** Kill `com.android.phone`. */
    suspend fun restartPhoneProcess(): ShellResult

    /** reboot. */
    suspend fun reboot(): ShellResult

    companion object {
        /** The directory of the root manager modules; [LibsuRootOps.detectXposed] lists it for a Vector or LSPosed module. */
        const val MODULES_ROOT = "/data/adb/modules"
    }
}
