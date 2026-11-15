package dev.nightjar.app.domain.protection

import dev.nightjar.app.domain.text.TextKey

/**
 * Root solution in use.
 *
 * A domain copy of `core.root.RootManager`: the classification/protection logic is pure Kotlin and must not
 * depend on libsu, so the adapter maps one enum onto the other. The [labelKey] keeps the copy localisable.
 */
enum class RootManager(
    val wire: String,
    val labelKey: TextKey,
) {
    MAGISK("MAGISK", TextKey.ROOT_MANAGER_MAGISK),
    KERNELSU("KERNELSU", TextKey.ROOT_MANAGER_KERNELSU),
    APATCH("APATCH", TextKey.ROOT_MANAGER_APATCH),
    UNKNOWN("UNKNOWN", TextKey.ROOT_MANAGER_UNKNOWN),
}

/** The detected root environment. The Xposed design needs no Zygisk, so the app does not detect it. */
data class RootSolution(
    val manager: RootManager,
    val version: String?,
    /** A UID-0 shell was obtained; [manager] is only the name of the solution that provided it. */
    val rooted: Boolean = manager != RootManager.UNKNOWN,
    /** Why root is unavailable, in user-facing words; shown on the checks row instead of a bare failure. */
    val diagnostic: String? = null,
) {
    val isRooted: Boolean get() = rooted

    companion object {
        val UNKNOWN = RootSolution(RootManager.UNKNOWN, null)
    }
}
