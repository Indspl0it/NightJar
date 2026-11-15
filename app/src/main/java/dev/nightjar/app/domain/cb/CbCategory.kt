package dev.nightjar.app.domain.cb

import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Subtype
import dev.nightjar.pduparse.CbCategories

/**
 * Title, explanation and default severity of a cell broadcast by its category.
 *
 * The category comes from the event subtype that the module sends. A row from before IPC version 2 has no subtype:
 * then the category comes from the message identifier through the same table the module uses
 * ([CbCategories], 3GPP TS 23.041). The message text never decides the category.
 *
 * Every CL-CB title or explanation shows the channel number (`{channel}`, filled by the Explainer from
 * `cb.messageId`). CL-CB-SIM has no channel for the user: its explanation names the message identifier.
 */
object CbCategory {
    /** True for a cell broadcast class. */
    fun isCb(classId: ClassId): Boolean = classId == ClassId.CB || classId == ClassId.CB_SIM

    /**
     * The class to show: CL-CB-SIM when the message identifier is in the CB data download range, even when an old row
     * says CL-CB. A CL-CB-SIM row stays CL-CB-SIM (the payload decided it from the SIM path, not only from the
     * identifier). Any other class is returned as it is.
     */
    fun classOf(
        classId: ClassId,
        messageId: Int?,
    ): ClassId {
        if (classId != ClassId.CB || messageId == null || messageId < 0) return classId
        return CbCategories.classOf(messageId)
    }

    /**
     * The [Subtype.Cb] code of a CL-CB broadcast: [subtype] when it is a known code, else the code of [messageId].
     * Null for CL-CB-SIM, for another class, and for a CL-CB row with neither.
     */
    fun subtypeOf(
        classId: ClassId,
        subtype: String?,
        messageId: Int?,
    ): String? {
        if (classOf(classId, messageId) != ClassId.CB) return null
        if (subtype != null && subtype in Subtype.known(ClassId.CB)) return subtype
        if (messageId == null || messageId < 0) return null
        return CbCategories.subtypeOf(messageId)
    }

    /** Title key of a cell broadcast, or null for another class. */
    fun titleKey(
        classId: ClassId,
        subtype: String?,
        messageId: Int?,
    ): TextKey? {
        if (!isCb(classId)) return null
        if (classOf(classId, messageId) == ClassId.CB_SIM) return TextKey.TITLE_CB_SIM
        return when (subtypeOf(classId, subtype, messageId)) {
            Subtype.Cb.PRESIDENTIAL -> TextKey.TITLE_CB_PRESIDENTIAL
            Subtype.Cb.EXTREME -> TextKey.TITLE_CB_EXTREME
            Subtype.Cb.SEVERE -> TextKey.TITLE_CB_SEVERE
            Subtype.Cb.AMBER -> TextKey.TITLE_CB_AMBER
            Subtype.Cb.TEST -> TextKey.TITLE_CB_TEST
            Subtype.Cb.EXERCISE -> TextKey.TITLE_CB_EXERCISE
            Subtype.Cb.PUBLIC_SAFETY -> TextKey.TITLE_CB_PUBLIC_SAFETY
            Subtype.Cb.OPERATOR -> TextKey.TITLE_CB_OPERATOR
            Subtype.Cb.ETWS_EQ -> TextKey.TITLE_CB_ETWS_EQ
            Subtype.Cb.ETWS_TSUNAMI -> TextKey.TITLE_CB_ETWS_TSUNAMI
            Subtype.Cb.ETWS_BOTH -> TextKey.TITLE_CB_ETWS_BOTH
            Subtype.Cb.ETWS_TEST -> TextKey.TITLE_CB_ETWS_TEST
            Subtype.Cb.ETWS_OTHER -> TextKey.TITLE_CB_ETWS_OTHER
            Subtype.Cb.AREA_INFO -> TextKey.TITLE_CB_AREA_INFO
            else -> TextKey.TITLE_CB_CHANNEL
        }
    }

    /** Explanation key of a cell broadcast (one per row of), or null for another class. */
    fun explanationKey(
        classId: ClassId,
        subtype: String?,
        messageId: Int?,
    ): TextKey? {
        if (!isCb(classId)) return null
        if (classOf(classId, messageId) == ClassId.CB_SIM) return TextKey.EXP_CB_SIM
        val code = subtypeOf(classId, subtype, messageId)
        return when {
            code == null || code == Subtype.Cb.CHANNEL -> TextKey.EXP_CB_CHANNEL
            code == Subtype.Cb.AREA_INFO -> TextKey.EXP_CB_AREA_INFO
            code in ETWS -> TextKey.EXP_CB_ETWS
            else -> TextKey.EXP_CB_CMAS
        }
    }

    /**
     * Default severity of Info for an emergency alert, an ETWS warning and area information, Notice for
     * any other channel, Suspicious for CL-CB-SIM. Null for another class.
     */
    fun defaultSeverity(
        classId: ClassId,
        subtype: String?,
        messageId: Int?,
    ): Severity? {
        if (!isCb(classId)) return null
        if (classOf(classId, messageId) == ClassId.CB_SIM) return Severity.SUSPICIOUS
        return when (subtypeOf(classId, subtype, messageId)) {
            null, Subtype.Cb.CHANNEL -> Severity.NOTABLE
            else -> Severity.INFO
        }
    }

    /** The ETWS codes (ETWS row). */
    private val ETWS =
        setOf(Subtype.Cb.ETWS_EQ, Subtype.Cb.ETWS_TSUNAMI, Subtype.Cb.ETWS_BOTH, Subtype.Cb.ETWS_TEST, Subtype.Cb.ETWS_OTHER)
}
