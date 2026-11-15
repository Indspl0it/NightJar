package dev.nightjar.app.domain.protection

/** Result of comparing the stored build fingerprint with the running one. */
data class OtaEvent(
    val changed: Boolean,
    val firstRun: Boolean,
    val previous: String?,
    val current: String?,
) {
    /** Is shown once per fingerprint change. */
    fun shouldPrompt(alreadyPromptedFor: String?): Boolean = changed && current != null && alreadyPromptedFor != current
}

/** Detects an OTA between sessions. Pure: the caller stores the fingerprint and the "prompted" marker. */
object OtaDetector {
    fun check(
        storedFingerprint: String?,
        currentFingerprint: String?,
    ): OtaEvent {
        val current = currentFingerprint?.takeIf { it.isNotBlank() }
        val stored = storedFingerprint?.takeIf { it.isNotBlank() }
        return OtaEvent(
            changed = stored != null && current != null && stored != current,
            firstRun = stored == null,
            previous = stored,
            current = current,
        )
    }
}

/** Step 2 rows (02, 03) and the same list on Health. */
enum class InstallCheckId {
    ROOT,

    /** An Xposed framework (Vector / LSPosed) is present. Any Zygisk implementation will do. */
    FRAMEWORK,
    ANDROID_VERSION,
    BUILD_PROFILE,

    /** The Nightjar module is loaded into the phone process: true only when the payload handshook this boot. */
    MODULE,
}

/** One row of the onboarding checklist. */
data class InstallCheck(
    val id: InstallCheckId,
    val state: State,
    /** Manager name, framework name or fingerprint, for the row's second line. */
    val detail: String? = null,
) {
    enum class State {
        OK,
        PENDING,
        FAILED,

        /** Build not in the profile list: informational only, nothing is held back (AC3). */
        WARNING,
    }
}

/**
 * Evaluates the onboarding checks.
 *
 * `UNSUPPORTED`/`MONITOR_ONLY` never fail the checklist: an unknown build can still be monitored, and onboarding
 * continues (AC3), so the row is a warning rather than an error.
 */
object InstallChecklist {
    fun evaluate(
        root: RootSolution,
        sdkInt: Int,
        profile: ProfileStatus,
        module: ModuleStatus,
        activeThisBoot: Boolean,
    ): List<InstallCheck> {
        val checks = ArrayList<InstallCheck>(5)
        checks +=
            InstallCheck(
                id = InstallCheckId.ROOT,
                state = if (root.isRooted) InstallCheck.State.OK else InstallCheck.State.FAILED,
                // A failed row explains itself: "root was denied" alone cannot be acted on.
                detail = if (root.isRooted) root.version else root.diagnostic,
            )
        checks +=
            InstallCheck(
                id = InstallCheckId.FRAMEWORK,
                state = if (module.installed != null) InstallCheck.State.OK else InstallCheck.State.FAILED,
                detail = module.installed?.name,
            )
        checks +=
            InstallCheck(
                id = InstallCheckId.ANDROID_VERSION,
                state = if (sdkInt >= MIN_SDK) InstallCheck.State.OK else InstallCheck.State.FAILED,
                detail = sdkInt.toString(),
            )
        checks +=
            InstallCheck(
                id = InstallCheckId.BUILD_PROFILE,
                state = if (profile == ProfileStatus.KNOWN) InstallCheck.State.OK else InstallCheck.State.WARNING,
                detail = profile.name,
            )
        checks +=
            InstallCheck(
                id = InstallCheckId.MODULE,
                // "Active" is only ever "the payload handshook this boot". Without a framework the module cannot load
                // at all; with one but no handshake the user still has to enable it for Phone Services and reboot.
                state =
                    when {
                        activeThisBoot -> InstallCheck.State.OK
                        module.installed == null -> InstallCheck.State.FAILED
                        else -> InstallCheck.State.WARNING
                    },
                detail = null,
            )
        return checks
    }

    /** Android 15 (API 35) and newer. */
    const val MIN_SDK = 35
}
