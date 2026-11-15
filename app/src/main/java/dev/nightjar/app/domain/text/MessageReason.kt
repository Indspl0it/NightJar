package dev.nightjar.app.domain.text

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity

/**
 * The one-line "why you are seeing this" shared by the feed row, the Home card and the notification.
 *
 * It answers what a person asks of a flagged message: what is it trying to do to me. Normal messages and anything
 * outgoing have no reason. Every incoming class has a line; the `when` is exhaustive, so a new class cannot
 * ship without one.
 */
object MessageReason {
    fun key(
        classId: ClassId,
        severity: Severity,
        direction: Direction,
        fate: Fate,
        undecodable: Boolean = false,
        titleKey: TextKey? = null,
    ): TextKey? {
        if (direction != Direction.IN || severity == Severity.INFO) return null
        if (undecodable) return TextKey.REASON_MALFORMED
        val reason = SMART_CONTENT[titleKey]?.takeIf { classId == ClassId.SMART } ?: byClass(classId)
        // A message that nothing on the phone received is explained by that, unless its class says something sharper.
        return if (fate == Fate.NO_RECEIVER && (reason == null || reason in GENERIC)) TextKey.REASON_NOBODY_HANDLES else reason
    }

    /** Reason lines that only name the kind of message: "nobody received it" says more. */
    private val GENERIC =
        setOf(
            TextKey.REASON_HIDDEN_DATA,
            TextKey.REASON_PUSH,
            TextKey.REASON_SERVICE,
            TextKey.REASON_RARE_FORMAT,
            TextKey.REASON_RARE_CLASS3,
            TextKey.REASON_RARE_TELEMATIC,
            TextKey.REASON_RARE_EMPTY,
            TextKey.REASON_RARE_BINARY,
            TextKey.REASON_RARE_COMPRESSED,
            TextKey.REASON_RARE_SMART,
            TextKey.REASON_SMART_RINGTONE,
            TextKey.REASON_SMART_OPLOGO,
            TextKey.REASON_SMART_CLILOGO,
            TextKey.REASON_SMART_PICTURE,
            TextKey.REASON_SMART_VCARD,
            TextKey.REASON_SMART_VCAL,
        )

    /** A Smart Messaging subtype title to the reason line that names its content ("contains a ringtone"). */
    private val SMART_CONTENT: Map<TextKey, TextKey> =
        mapOf(
            TextKey.TITLE_ST_SMART_RINGTONE to TextKey.REASON_SMART_RINGTONE,
            TextKey.TITLE_ST_SMART_OPLOGO to TextKey.REASON_SMART_OPLOGO,
            TextKey.TITLE_ST_SMART_CLILOGO to TextKey.REASON_SMART_CLILOGO,
            TextKey.TITLE_ST_SMART_PICTURE to TextKey.REASON_SMART_PICTURE,
            TextKey.TITLE_ST_SMART_VCARD to TextKey.REASON_SMART_VCARD,
            TextKey.TITLE_ST_SMART_VCAL to TextKey.REASON_SMART_VCAL,
        )

    /**
     * The Smart Messaging subtype title key of a stored list title, or null. A list row keeps only its title, so the
     * subtype is found by its title text ("Ringtone").
     */
    fun smartTitleKey(
        storedTitle: String,
        strings: Strings,
    ): TextKey? = SMART_CONTENT.keys.firstOrNull { strings.get(it) == storedTitle }

    @Suppress("CyclomaticComplexMethod")
    private fun byClass(classId: ClassId): TextKey? =
        when (classId) {
            ClassId.NORMAL -> null

            ClassId.SILENT -> TextKey.REASON_SILENT

            ClassId.FLASH -> TextKey.REASON_FLASH

            ClassId.REPLACE -> TextKey.REASON_REPLACE

            ClassId.WAP_CP -> TextKey.REASON_SETTINGS_PUSH

            ClassId.WAP_SUPL, ClassId.LOC_SUPL -> TextKey.REASON_LOCATION

            ClassId.WAP_DM -> TextKey.REASON_DEVICE_MGMT

            ClassId.WAP_SISL -> TextKey.REASON_WEB_LINK

            ClassId.DATA_PORT -> TextKey.REASON_HIDDEN_DATA

            ClassId.APP_TOKEN -> TextKey.REASON_APP_ONLY

            ClassId.INJECTED -> TextKey.REASON_INJECTED

            ClassId.SIM_DD, ClassId.SIM_STORED, ClassId.CB_SIM -> TextKey.REASON_SIM

            ClassId.ME_DOWNLOAD, ClassId.ME_DEPERSO -> TextKey.REASON_PHONE_CHANGE

            ClassId.MALFORMED_SMS, ClassId.WAP_UNREADABLE -> TextKey.REASON_MALFORMED

            ClassId.UNREADABLE_SMS -> TextKey.REASON_UNREADABLE

            ClassId.MULTIPART_INCOMPLETE -> TextKey.REASON_INCOMPLETE

            ClassId.CB -> TextKey.REASON_BROADCAST

            ClassId.WAP_MMS, ClassId.WAP_OTHER, ClassId.WAP_CO, ClassId.WAP_DS, ClassId.WAP_EMN, ClassId.WAP_DRM,
            ClassId.WAP_VCARD, ClassId.WAP_IMPS,
            -> TextKey.REASON_PUSH

            ClassId.MWI, ClassId.VVM, ClassId.MISSED_CALL, ClassId.CARRIER, ClassId.RCS_PROV, ClassId.OEM,
            ClassId.STATUS, ClassId.RETURN_CALL,
            -> TextKey.REASON_SERVICE

            ClassId.CLASS3 -> TextKey.REASON_RARE_CLASS3

            ClassId.TELEMATIC -> TextKey.REASON_RARE_TELEMATIC

            ClassId.EMPTY -> TextKey.REASON_RARE_EMPTY

            ClassId.BINARY -> TextKey.REASON_RARE_BINARY

            ClassId.COMPRESSED -> TextKey.REASON_RARE_COMPRESSED

            ClassId.SMART -> TextKey.REASON_RARE_SMART

            // Outgoing classes never reach this point (direction OUT has no reason).
            ClassId.MO_VISIBLE, ClassId.MO_UNPERSISTED, ClassId.MO_DATA, ClassId.MO_RAW, ClassId.MO_STK, ClassId.MO_VVM,
            -> null
        }
}
