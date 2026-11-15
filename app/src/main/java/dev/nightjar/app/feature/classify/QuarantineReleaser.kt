package dev.nightjar.app.feature.classify

/**
 * Release of a quarantined message.
 *
 * The actual re-injection happens inside `com.android.phone` (`injectSmsPdu` through the payload control binder),
 * so this port is what the notification Release action and Detail's Release button call. It is a `Set` multibinding
 * so that the notification receiver builds and behaves correctly without a releaser: without a registered releaser the
 * action posts the "couldn't release" notification rather than pretending to have succeeded.
 */
interface QuarantineReleaser {
    suspend fun release(messageId: Long): ReleaseOutcome
}

/** Result of a release attempt. */
sealed interface ReleaseOutcome {
    data object Released : ReleaseOutcome

    /** [reason] is a short, localisable phrase; it never contains message content. */
    data class Failed(
        val reason: String,
    ) : ReleaseOutcome
}
