package dev.nightjar.app.domain.text

import dev.nightjar.contract.ClassId

/**
 * The title a push gets when its content type could not be read: "Malformed WAP push: content type is unreadable"
 * (AN-WSP-CTYPE). Rows from before CL-WAP-UNREADABLE stored such a push as CL-WAP-OTHER.
 */
fun unreadablePushTitle(strings: Strings): String =
    Templates.format(strings.get(TextKey.TITLE_CL_WAP_UNREADABLE), mapOf("anomaly" to strings.get(TextKey.ANOMALY_WSP_CTYPE)))

/** True for a stored push title whose content type could not be read (older rows say "Push message (unknown)"). */
fun isUnreadablePushTitle(
    classId: ClassId,
    storedTitle: String,
    strings: Strings,
): Boolean =
    classId == ClassId.WAP_OTHER &&
        (storedTitle.contains("(unknown)", ignoreCase = true) || storedTitle == unreadablePushTitle(strings))

/**
 * The headline to show for a stored message. Titles are written when a message arrives, with the subtype, the port
 * and the app filled in, so the stored title is shown as it is. Only a push row from before CL-WAP-UNREADABLE
 * that says "(unknown)" is rewritten here.
 */
fun displayTitle(
    classId: ClassId,
    storedTitle: String,
    strings: Strings,
): String =
    when {
        isUnreadablePushTitle(classId, storedTitle, strings) -> unreadablePushTitle(strings)
        else -> storedTitle
    }
