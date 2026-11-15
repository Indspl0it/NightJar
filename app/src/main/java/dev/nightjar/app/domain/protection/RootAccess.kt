package dev.nightjar.app.domain.protection

/** Result of one root operation (`ShellResult`). */
data class RootResult(
    val exitCode: Int,
    val stdout: String,
) {
    val ok: Boolean get() = exitCode == 0

    companion object {
        val OK = RootResult(0, "")
    }
}

/**
 * The root operations the protection layer needs (allow-list).
 *
 * `core.root.RootOps` is the only implementation that may run a shell command; this interface exists so the module
 * lifecycle and the protection state machine stay unit-testable without root, and so `core.root` keeps no protection
 * logic.
 * Every method suspends: the adapter wraps `RootOps`, which serialises shell use.
 *
 * NightJar is an Xposed module: there is no module directory to read or write, no module zip to install and no
 * safe-mode flag file.
 */
interface RootAccess {
    /** Root solution and version. */
    suspend fun detectRoot(): RootSolution

    /** True when an Xposed framework (Vector / LSPosed) is present on the device. */
    suspend fun detectXposed(): Boolean = false

    /** Kill `com.android.phone`. */
    suspend fun restartPhoneProcess(): RootResult

    /** reboot. */
    suspend fun reboot(): RootResult
}

/**
 * An action that changes protection state, for the audit log. [ModuleManager] records them. NightJar is an
 * Xposed module, so the app installs and removes nothing and there is no safe-mode flag to clear.
 */
enum class ProtectionAuditAction {
    PHONE_RESTART,
    REBOOT,
}
