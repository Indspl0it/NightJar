package dev.nightjar.app.feature.detail

import androidx.annotation.StringRes
import dev.nightjar.app.R

/**
 * The user text for a release failure (APPDATA-X03). [code] is a technical `RELEASE_*` name from the release gateway and
 * is never shown. An unknown code gets the general text. Home and Detail use the same texts.
 */
@StringRes
fun releaseFailureRes(code: String): Int =
    when (code) {
        "RELEASE_NOT_FOUND" -> R.string.release_not_found
        "RELEASE_NOT_RELEASABLE" -> R.string.release_not_releasable
        "RELEASE_NO_PDU" -> R.string.release_no_pdu
        "RELEASE_NOT_ACTIVE" -> R.string.release_not_active
        "RELEASE_TIMEOUT" -> R.string.release_timeout
        "RELEASE_NO_DISPATCHER" -> R.string.release_no_dispatcher
        "RELEASE_CALLER_REJECTED" -> R.string.release_rejected
        else -> R.string.release_failed
    }
