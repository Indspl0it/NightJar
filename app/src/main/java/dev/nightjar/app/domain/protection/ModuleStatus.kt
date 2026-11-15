package dev.nightjar.app.domain.protection

/** Name and version of the framework that loads the payload ([ModuleManager.XPOSED_FRAMEWORK]). */
data class ModuleProp(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
)

/** What the app can tell about the module right now. */
enum class ModuleInstallState {
    NOT_INSTALLED,
    INSTALLED,
    UPDATE_AVAILABLE,
    PENDING_REBOOT,
    DISABLED,
    REMOVAL_PENDING,
}

/**
 * Framework status for Health and the onboarding checklist.
 *
 * @param installed [ModuleManager.XPOSED_FRAMEWORK] when an Xposed framework is present, null when absent
 * @param pendingUpdate always null in the Xposed design (nothing is staged)
 * @param disabled always false in the Xposed design (the framework manager decides the scope)
 * @param removalPending always false in the Xposed design
 * @param bundledVersionCode always 0 in the Xposed design (the app is the module)
 */
data class ModuleStatus(
    val installed: ModuleProp?,
    val pendingUpdate: ModuleProp?,
    val disabled: Boolean,
    val removalPending: Boolean,
    val bundledVersionCode: Int,
    /** True when the payload handshook during the current boot. */
    val activeThisBoot: Boolean = false,
) {
    val state: ModuleInstallState
        get() =
            when {
                removalPending -> ModuleInstallState.REMOVAL_PENDING
                pendingUpdate != null -> ModuleInstallState.PENDING_REBOOT
                installed == null -> ModuleInstallState.NOT_INSTALLED
                disabled -> ModuleInstallState.DISABLED
                updateAvailable -> ModuleInstallState.UPDATE_AVAILABLE
                else -> ModuleInstallState.INSTALLED
            }

    /** A bundled version newer than the installed one offers an update. */
    val updateAvailable: Boolean
        get() = installed != null && bundledVersionCode > installed.versionCode

    companion object {
        val UNKNOWN = ModuleStatus(null, null, disabled = false, removalPending = false, bundledVersionCode = 0)
    }
}
