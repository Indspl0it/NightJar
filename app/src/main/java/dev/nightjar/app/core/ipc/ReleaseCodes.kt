package dev.nightjar.app.core.ipc

import dev.nightjar.contract.Control

/** Typed outcome of a control-binder call. Never thrown to the UI. */
sealed interface ControlResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : ControlResult<T>

    /** No live session or no control binder: the payload is not running (NOT_ACTIVE / re-handshake pending). */
    data object NoSession : ControlResult<Nothing>

    /** The binder died between the handshake and this call. */
    data object Dead : ControlResult<Nothing>

    /** The remote call threw, e.g. `TransactionTooLargeException` or a `SecurityException` from the payload. */
    data class Failed(
        val message: String,
    ) : ControlResult<Nothing>
}

/** Result of `release` (`Control.ReleaseResult`), mapped to the copy of the release snackbars. */
sealed interface ReleaseResult {
    data object Released : ReleaseResult

    data class Failed(
        val code: Int,
        val reason: String,
    ) : ReleaseResult
}

/**
 * Result codes → a short reason for the "release failed" snackbar. Pure so the mapping is unit tested without a binder.
 */
object ReleaseCodes {
    const val HANDLED = Control.ReleaseResult.HANDLED
    const val GENERIC_FAILURE = Control.ReleaseResult.GENERIC_FAILURE
    const val OUT_OF_MEMORY = Control.ReleaseResult.OUT_OF_MEMORY
    const val INVALID_ARGUMENTS = Control.ReleaseResult.INVALID_ARGUMENTS
    const val NOT_RELEASABLE = Control.ReleaseResult.NOT_RELEASABLE
    const val TIMEOUT = Control.ReleaseResult.TIMEOUT
    const val NO_DISPATCHER = Control.ReleaseResult.NO_DISPATCHER
    const val CALLER_REJECTED = Control.ReleaseResult.CALLER_REJECTED
    const val NOT_SUPPORTED = Control.ReleaseResult.NOT_SUPPORTED

    fun map(code: Int): ReleaseResult =
        if (code == HANDLED) {
            ReleaseResult.Released
        } else {
            ReleaseResult.Failed(code, reason(code))
        }

    /** Not localised: these are the technical reasons the dialog/Snackbar shows. */
    fun reason(code: Int): String =
        when (code) {
            GENERIC_FAILURE -> "RELEASE_GENERIC_FAILURE"
            OUT_OF_MEMORY -> "RELEASE_OUT_OF_MEMORY"
            INVALID_ARGUMENTS -> "RELEASE_INVALID_ARGUMENTS"
            NOT_RELEASABLE -> "RELEASE_NOT_RELEASABLE"
            TIMEOUT -> "RELEASE_TIMEOUT"
            NO_DISPATCHER -> "RELEASE_NO_DISPATCHER"
            CALLER_REJECTED -> "RELEASE_CALLER_REJECTED"
            NOT_SUPPORTED -> "RELEASE_NOT_SUPPORTED"
            else -> "RELEASE_UNKNOWN_ERROR"
        }
}
