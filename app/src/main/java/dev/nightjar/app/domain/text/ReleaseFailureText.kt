package dev.nightjar.app.domain.text

/**
 * The user text for a failed release. The release gateway reports a `RELEASE_*` code
 * (`ReleaseCodes.reason`, or a code of the gateway itself). The code stays in the log, and the user sees the sentence
 * of this key. A code without its own sentence gets the general one.
 */
object ReleaseFailureText {
    private val KEYS =
        mapOf(
            "RELEASE_NOT_FOUND" to TextKey.RELEASE_NOT_FOUND,
            "RELEASE_NOT_RELEASABLE" to TextKey.RELEASE_NOT_RELEASABLE,
            "RELEASE_NO_PDU" to TextKey.RELEASE_NO_PDU,
            "RELEASE_NOT_ACTIVE" to TextKey.RELEASE_NOT_ACTIVE,
            "RELEASE_TIMEOUT" to TextKey.RELEASE_TIMEOUT,
            "RELEASE_NO_DISPATCHER" to TextKey.RELEASE_NO_DISPATCHER,
            "RELEASE_CALLER_REJECTED" to TextKey.RELEASE_REJECTED,
        )

    fun keyOf(code: String?): TextKey = KEYS[code] ?: TextKey.RELEASE_FAILED
}
