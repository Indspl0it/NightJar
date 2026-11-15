package dev.nightjar.app.domain.protection

/** Result of an install/update/remove/restart operation. */
sealed interface ModuleOpResult {
    data class Success(
        val output: String,
        val needsReboot: Boolean,
    ) : ModuleOpResult

    data class Failure(
        val reason: ModuleFailure,
        val output: String = "",
    ) : ModuleOpResult
}

/** Why an operation failed; each maps to one sentence in the expandable output of the install dialog. */
enum class ModuleFailure {
    CHECKSUM_MISMATCH,
    FILE_UNREADABLE,
    ROOT_DENIED,
    CLI_FAILED,
}

/** Audit hook. The adapter maps this onto `AuditLog.record` with the matching `AuditAction`. */
fun interface ProtectionAudit {
    suspend fun record(
        action: ProtectionAuditAction,
        target: String,
    )

    companion object {
        val NONE = ProtectionAudit { _, _ -> }
    }
}

/**
 * Nightjar's protection lifecycle through root.
 *
 * Nightjar is an Xposed module (Vector / LSPosed) scoped to `com.android.phone`; the app is the module, so there is
 * nothing to flash. What remains is detecting the framework, restarting the phone process and rebooting. Whether the
 * module is actually loaded is decided only by the payload handshake ("active this boot").
 */
class ModuleManager(
    private val root: RootAccess,
    private val audit: ProtectionAudit = ProtectionAudit.NONE,
) {
    /** Root solution. */
    suspend fun detectRoot(): RootSolution = root.detectRoot()

    /**
     * Framework status for Health. [ModuleStatus.installed] stands for "an Xposed framework is present"; there is
     * no bundled version, so no update and no staged state.
     */
    suspend fun status(
        bundledVersionCode: Int = 0,
        activeThisBoot: Boolean,
    ): ModuleStatus =
        ModuleStatus(
            installed = if (root.detectXposed()) XPOSED_FRAMEWORK else null,
            pendingUpdate = null,
            disabled = false,
            removalPending = false,
            bundledVersionCode = bundledVersionCode,
            activeThisBoot = activeThisBoot,
        )

    /** Restart the phone process so a new payload loads without a reboot. */
    suspend fun restartPhoneService(): ModuleOpResult {
        val result = root.restartPhoneProcess()
        if (!result.ok) return ModuleOpResult.Failure(ModuleFailure.CLI_FAILED, result.stdout)
        audit.record(ProtectionAuditAction.PHONE_RESTART, target = PHONE_PROCESS)
        return ModuleOpResult.Success(result.stdout, needsReboot = false)
    }

    /** "Reboot now". */
    suspend fun reboot(): ModuleOpResult {
        val result = root.reboot()
        if (!result.ok) return ModuleOpResult.Failure(ModuleFailure.CLI_FAILED, result.stdout)
        audit.record(ProtectionAuditAction.REBOOT, target = "user")
        return ModuleOpResult.Success(result.stdout, needsReboot = true)
    }

    companion object {
        const val PHONE_PROCESS = "com.android.phone"

        /** Marker for "an Xposed framework was found"; carries no version. */
        val XPOSED_FRAMEWORK = ModuleProp(id = "xposed", name = "Vector/LSPosed", version = "", versionCode = 0)
    }
}
