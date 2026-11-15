package dev.nightjar.app.domain.known

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction

/**
 * Which app a message belongs to for trust (B4).
 *
 * The Detail "Trust app" action, the severity decision at ingest and the reclassifier must name the same app. Before
 * B4 the detail screen took the consumer package first and the classifier took the calling package first, so a trust
 * could be stored for one package while the classifier checked another one, and the message stayed Suspicious.
 *
 * Trust covers an app's own traffic only:
 *  - an outgoing message: the sending app. The payload gives it as the calling package; the package that gets the send
 *    result (`fateDetail.package`) is the same app, so both are candidates;
 *  - an injected message (CL-INJECTED): the injecting app, the calling package only. The consumer of an injected
 *    message is the SMS app that shows it, and trusting that app must not hide injections by other apps.
 *
 * Inbound network messages are never covered: a sender on the network is not an app on the phone.
 */
object TrustedApps {
    /** The calling package of an injection that the payload could not attribute (payload `Correlator`). */
    const val UNATTRIBUTED = "unknown"

    /** The internal sender of the phone process (`INTERNAL_PACKAGE`). It is not an app the user can trust. */
    const val INTERNAL_PACKAGE = "android.phone.internal"

    /** Longest package name Android accepts. A longer value is not a package name. */
    private const val MAX_PACKAGE_LENGTH = 255

    /** True when trust can change the severity of a message of this direction and class. */
    fun covers(
        direction: Direction,
        classId: ClassId,
    ): Boolean = direction == Direction.OUT || classId == ClassId.INJECTED

    /** The packages that identify the app of the message, best first. Empty when trust does not cover the message. */
    fun candidates(
        direction: Direction,
        classId: ClassId,
        callingPackage: String?,
        consumerPackage: String?,
    ): List<String> =
        when {
            !covers(direction, classId) -> {
                emptyList()
            }

            classId == ClassId.INJECTED -> {
                listOfNotNull(callingPackage?.trim()?.takeIf(::isTrustable))
            }

            else -> {
                listOfNotNull(callingPackage, consumerPackage)
                    .map { it.trim() }
                    .filter(::isTrustable)
                    .distinct()
            }
        }

    /** The package that the "Trust app" action stores, or null when the message has no app that can be trusted. */
    fun target(
        direction: Direction,
        classId: ClassId,
        callingPackage: String?,
        consumerPackage: String?,
    ): String? = candidates(direction, classId, callingPackage, consumerPackage).firstOrNull()

    /** The trusted package of the message, or null when no candidate is in [trusted]. */
    fun trustedPackage(
        trusted: Set<String>,
        direction: Direction,
        classId: ClassId,
        callingPackage: String?,
        consumerPackage: String?,
    ): String? {
        if (trusted.isEmpty()) return null
        return candidates(direction, classId, callingPackage, consumerPackage).firstOrNull { it in trusted }
    }

    /** True for a value that names a real app: not blank, not a placeholder, no white space. */
    fun isTrustable(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (packageName.length > MAX_PACKAGE_LENGTH) return false
        if (packageName == UNATTRIBUTED || packageName == INTERNAL_PACKAGE || packageName == "null") return false
        return packageName.none { it.isWhitespace() }
    }
}
