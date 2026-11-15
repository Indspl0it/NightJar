package dev.nightjar.app.domain

import dev.nightjar.app.domain.cb.CbCategory
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Subtype

/**
 * Subtype rows below the class level. Subtype codes share a row when they have the same title,
 * explanation and severity. The order inside a class is the display order. Cell broadcast rows are in
 * [CbCategory].
 *
 * The table is in its own file so that the registry stays a small lookup object. Read it only through
 * [ClassRegistry].
 */
internal val SUBTYPE_PROFILES: Map<ClassId, List<SubtypeProfile>> =
    listOf(
        row(ClassId.SILENT, Severity.SUSPICIOUS, TextKey.TITLE_CL_SILENT, TextKey.EXP_CL_SILENT, Subtype.Silent.TYPE0),
        row(
            ClassId.SILENT,
            Severity.HIGH,
            TextKey.TITLE_ST_SILENT_TYPE0_PORT,
            TextKey.EXP_ST_SILENT_TYPE0_PORT,
            Subtype.Silent.TYPE0_PORT,
        ),
        row(ClassId.FLASH, Severity.NOTABLE, TextKey.TITLE_CL_FLASH, TextKey.EXP_CL_FLASH, Subtype.Flash.CLASS0),
        row(
            ClassId.FLASH,
            Severity.SUSPICIOUS,
            TextKey.TITLE_ST_FLASH_CLASS0_EMPTY,
            TextKey.EXP_ST_FLASH_CLASS0_EMPTY,
            Subtype.Flash.CLASS0_EMPTY,
        ),
        row(
            ClassId.REPLACE,
            Severity.NOTABLE,
            TextKey.TITLE_ST_REPLACE_TYPE,
            TextKey.EXP_CL_REPLACE,
            Subtype.Replace.TYPE1,
            Subtype.Replace.TYPE2,
            Subtype.Replace.TYPE3,
            Subtype.Replace.TYPE4,
            Subtype.Replace.TYPE5,
            Subtype.Replace.TYPE6,
            Subtype.Replace.TYPE7,
        ),
        // SAT, WIB and NOSEC are High. RFM, RAM and OTHER are Suspicious.
        row(ClassId.SIM_DD, Severity.HIGH, TextKey.TITLE_ST_SIM_DD_SAT, TextKey.EXP_ST_SIM_DD_SAT, Subtype.SimOta.SAT),
        row(ClassId.SIM_DD, Severity.HIGH, TextKey.TITLE_ST_SIM_DD_WIB, TextKey.EXP_ST_SIM_DD_WIB, Subtype.SimOta.WIB),
        row(ClassId.SIM_DD, Severity.HIGH, TextKey.TITLE_ST_SIM_DD_NOSEC, TextKey.EXP_ST_SIM_DD_NOSEC, Subtype.SimOta.NOSEC),
        row(ClassId.SIM_DD, Severity.SUSPICIOUS, TextKey.TITLE_ST_SIM_DD_RFM, TextKey.EXP_ST_SIM_DD_RFM, Subtype.SimOta.RFM),
        row(ClassId.SIM_DD, Severity.SUSPICIOUS, TextKey.TITLE_ST_SIM_DD_RAM, TextKey.EXP_ST_SIM_DD_RAM, Subtype.SimOta.RAM),
        row(
            ClassId.SIM_DD,
            Severity.SUSPICIOUS,
            TextKey.TITLE_ST_SIM_DD_OTHER,
            TextKey.EXP_ST_SIM_DD_OTHER,
            Subtype.SimOta.OTHER,
        ),
        row(
            ClassId.SIM_STORED,
            Severity.NOTABLE,
            TextKey.TITLE_CL_SIM_STORED,
            TextKey.EXP_CL_SIM_STORED,
            Subtype.SimStored.CLASS2,
        ),
        telematic(Subtype.Telematic.FAX, TextKey.TITLE_ST_TELEMATIC_FAX),
        telematic(Subtype.Telematic.TELEX, TextKey.TITLE_ST_TELEMATIC_TELEX),
        telematic(Subtype.Telematic.PAGING, TextKey.TITLE_ST_TELEMATIC_PAGING),
        telematic(Subtype.Telematic.VOICE, TextKey.TITLE_ST_TELEMATIC_VOICE),
        telematic(Subtype.Telematic.ERMES, TextKey.TITLE_ST_TELEMATIC_ERMES),
        telematic(Subtype.Telematic.X400, TextKey.TITLE_ST_TELEMATIC_X400),
        telematic(Subtype.Telematic.EMAIL, TextKey.TITLE_ST_TELEMATIC_EMAIL),
        telematic(Subtype.Telematic.OTHER, TextKey.TITLE_ST_TELEMATIC_OTHER),
        row(ClassId.MWI, Severity.INFO, TextKey.TITLE_CL_MWI, TextKey.EXP_CL_MWI, Subtype.Mwi.VOICEMAIL),
        row(ClassId.MWI, Severity.INFO, TextKey.TITLE_ST_MWI_FAX, TextKey.EXP_CL_MWI, Subtype.Mwi.FAX),
        row(ClassId.MWI, Severity.INFO, TextKey.TITLE_ST_MWI_EMAIL, TextKey.EXP_CL_MWI, Subtype.Mwi.EMAIL),
        row(ClassId.MWI, Severity.INFO, TextKey.TITLE_ST_MWI_OTHER, TextKey.EXP_CL_MWI, Subtype.Mwi.OTHER, Subtype.Mwi.EXTENDED),
        row(
            ClassId.MWI,
            Severity.SUSPICIOUS,
            TextKey.TITLE_ST_MWI_DISCARD_SILENT,
            TextKey.EXP_ST_MWI_DISCARD_SILENT,
            Subtype.Mwi.DISCARD_SILENT,
        ),
        row(ClassId.STATUS, Severity.INFO, TextKey.TITLE_ST_STATUS_DELIVERED, TextKey.EXP_CL_STATUS, Subtype.Status.DELIVERED),
        row(ClassId.STATUS, Severity.INFO, TextKey.TITLE_ST_STATUS_PENDING, TextKey.EXP_CL_STATUS, Subtype.Status.PENDING),
        row(ClassId.STATUS, Severity.INFO, TextKey.TITLE_ST_STATUS_FAILED, TextKey.EXP_CL_STATUS, Subtype.Status.FAILED),
        row(
            ClassId.DATA_PORT,
            Severity.SUSPICIOUS,
            TextKey.TITLE_CL_DATA_PORT,
            TextKey.EXP_CL_DATA_PORT,
            Subtype.DataPort.UNKNOWN,
        ),
        row(
            ClassId.DATA_PORT,
            Severity.SUSPICIOUS,
            TextKey.TITLE_CL_DATA_PORT,
            TextKey.EXP_ST_DATA_PORT_PORT0,
            Subtype.DataPort.PORT0,
        ),
        knownPort(Subtype.DataPort.WAP_PUSH, TextKey.TITLE_ST_DATA_PORT_WAP_PUSH),
        knownPort(Subtype.DataPort.WAP_PUSH_SECURE, TextKey.TITLE_ST_DATA_PORT_WAP_PUSH_SECURE),
        knownPort(Subtype.DataPort.WAP_CONNECTIONLESS_SESSION, TextKey.TITLE_ST_DATA_PORT_WAP_CONNECTIONLESS_SESSION),
        knownPort(Subtype.DataPort.WAP_SESSION, TextKey.TITLE_ST_DATA_PORT_WAP_SESSION),
        knownPort(
            Subtype.DataPort.WAP_CONNECTIONLESS_SESSION_SECURE,
            TextKey.TITLE_ST_DATA_PORT_WAP_CONNECTIONLESS_SESSION_SECURE,
        ),
        knownPort(Subtype.DataPort.WAP_SESSION_SECURE, TextKey.TITLE_ST_DATA_PORT_WAP_SESSION_SECURE),
        knownPort(Subtype.DataPort.WAP_VCARD_VCALENDAR, TextKey.TITLE_ST_DATA_PORT_WAP_VCARD_VCALENDAR),
        knownPort(
            Subtype.DataPort.WAP_WTA_CONNECTIONLESS_SESSION_SECURE,
            TextKey.TITLE_ST_DATA_PORT_WAP_WTA_CONNECTIONLESS_SESSION_SECURE,
        ),
        knownPort(Subtype.DataPort.WAP_WTA_SESSION_SECURE, TextKey.TITLE_ST_DATA_PORT_WAP_WTA_SESSION_SECURE),
        servicePort(Subtype.DataPort.SERVICE_CARD, TextKey.TITLE_ST_DATA_PORT_SERVICE_CARD),
        servicePort(Subtype.DataPort.INTERNET_ACCESS_CONFIG, TextKey.TITLE_ST_DATA_PORT_INTERNET_ACCESS_CONFIG),
        servicePort(Subtype.DataPort.DYNAMIC_MENU_CONTROL, TextKey.TITLE_ST_DATA_PORT_DYNAMIC_MENU_CONTROL),
        servicePort(Subtype.DataPort.MESSAGE_ACCESS, TextKey.TITLE_ST_DATA_PORT_MESSAGE_ACCESS),
        servicePort(Subtype.DataPort.EMAIL_NOTIFICATION, TextKey.TITLE_ST_DATA_PORT_EMAIL_NOTIFICATION),
        servicePort(Subtype.DataPort.SYNCML_OTA_CONFIG, TextKey.TITLE_ST_DATA_PORT_SYNCML_OTA_CONFIG),
        servicePort(Subtype.DataPort.WAP_OTA_CONFIG, TextKey.TITLE_ST_DATA_PORT_WAP_OTA_CONFIG),
        smart(Subtype.Smart.RINGTONE, TextKey.TITLE_ST_SMART_RINGTONE),
        smart(Subtype.Smart.OPLOGO, TextKey.TITLE_ST_SMART_OPLOGO),
        smart(Subtype.Smart.CLILOGO, TextKey.TITLE_ST_SMART_CLILOGO),
        smart(Subtype.Smart.PICTURE, TextKey.TITLE_ST_SMART_PICTURE),
        smart(Subtype.Smart.VCARD, TextKey.TITLE_ST_SMART_VCARD),
        smart(Subtype.Smart.VCAL, TextKey.TITLE_ST_SMART_VCAL),
        row(ClassId.LOC_SUPL, Severity.SUSPICIOUS, TextKey.TITLE_CL_LOC_SUPL, TextKey.EXP_CL_LOC_SUPL, Subtype.LocSupl.SMS),
        row(ClassId.WAP_MMS, Severity.INFO, TextKey.TITLE_CL_WAP_MMS, TextKey.EXP_CL_WAP_MMS, Subtype.WapMms.NOTIFY),
        row(
            ClassId.WAP_MMS,
            Severity.INFO,
            TextKey.TITLE_ST_WAP_MMS_DELIVERY_REPORT,
            TextKey.EXP_CL_WAP_MMS,
            Subtype.WapMms.DELIVERY_REPORT,
        ),
        row(
            ClassId.WAP_MMS,
            Severity.INFO,
            TextKey.TITLE_ST_WAP_MMS_READ_REPORT,
            TextKey.EXP_CL_WAP_MMS,
            Subtype.WapMms.READ_REPORT,
        ),
        row(ClassId.WAP_SISL, Severity.NOTABLE, TextKey.TITLE_CL_WAP_SISL, TextKey.EXP_CL_WAP_SISL, Subtype.WapSisl.SI),
        row(
            ClassId.WAP_SISL,
            Severity.NOTABLE,
            TextKey.TITLE_ST_WAP_SISL_SI_DELETE,
            TextKey.EXP_ST_WAP_SISL_SI_DELETE,
            Subtype.WapSisl.SI_DELETE,
        ),
        row(ClassId.WAP_SISL, Severity.HIGH, TextKey.TITLE_ST_WAP_SISL_SL, TextKey.EXP_ST_WAP_SISL_SL, Subtype.WapSisl.SL),
        settingsPush(Subtype.WapCp.NETWPIN, TextKey.TITLE_ST_WAP_CP_NETWPIN),
        settingsPush(Subtype.WapCp.USERPIN, TextKey.TITLE_ST_WAP_CP_USERPIN),
        settingsPush(Subtype.WapCp.USERNETWPIN, TextKey.TITLE_ST_WAP_CP_USERNETWPIN),
        settingsPush(Subtype.WapCp.USERPINMAC, TextKey.TITLE_ST_WAP_CP_USERPINMAC),
        row(ClassId.WAP_CP, Severity.HIGH, TextKey.TITLE_ST_WAP_CP_NOSEC, TextKey.EXP_ST_WAP_CP_NOSEC, Subtype.WapCp.NOSEC),
        row(ClassId.WAP_DM, Severity.SUSPICIOUS, TextKey.TITLE_CL_WAP_DM, TextKey.EXP_CL_WAP_DM, Subtype.WapDm.NOTIFY),
        row(
            ClassId.WAP_DM,
            Severity.SUSPICIOUS,
            TextKey.TITLE_ST_WAP_DM_BOOTSTRAP,
            TextKey.EXP_ST_WAP_DM_BOOTSTRAP,
            Subtype.WapDm.BOOTSTRAP,
        ),
        row(ClassId.WAP_SUPL, Severity.SUSPICIOUS, TextKey.TITLE_CL_WAP_SUPL, TextKey.EXP_CL_WAP_SUPL, Subtype.WapSupl.NOTIFY),
        row(
            ClassId.WAP_SUPL,
            Severity.SUSPICIOUS,
            TextKey.TITLE_ST_WAP_SUPL_VERIFY,
            TextKey.EXP_ST_WAP_SUPL_VERIFY,
            Subtype.WapSupl.VERIFY,
        ),
        row(
            ClassId.WAP_SUPL,
            Severity.HIGH,
            TextKey.TITLE_ST_WAP_SUPL_SILENT,
            TextKey.EXP_ST_WAP_SUPL_SILENT,
            Subtype.WapSupl.SILENT,
        ),
        row(ClassId.WAP_VCARD, Severity.NOTABLE, TextKey.TITLE_CL_WAP_VCARD, TextKey.EXP_CL_WAP_VCARD, Subtype.WapVcard.VCARD),
        row(ClassId.WAP_VCARD, Severity.NOTABLE, TextKey.TITLE_ST_WAP_VCARD_VCAL, TextKey.EXP_CL_WAP_VCARD, Subtype.WapVcard.VCAL),
        row(ClassId.MO_STK, Severity.HIGH, TextKey.TITLE_CL_MO_STK, TextKey.EXP_CL_MO_STK, Subtype.MoStk.SEND_SMS),
    ).groupBy { it.classId }

private fun row(
    classId: ClassId,
    severity: Severity,
    titleKey: TextKey,
    explanationKey: TextKey,
    vararg codes: String,
): SubtypeProfile = SubtypeProfile(classId, codes.toSet(), severity, titleKey, explanationKey)

private fun telematic(
    code: String,
    titleKey: TextKey,
) = row(ClassId.TELEMATIC, Severity.NOTABLE, titleKey, TextKey.EXP_CL_TELEMATIC, code)

private fun knownPort(
    code: String,
    titleKey: TextKey,
) = row(ClassId.DATA_PORT, Severity.NOTABLE, titleKey, TextKey.EXP_ST_DATA_PORT_KNOWN, code)

/**
 * The ports below 16000 that the phone keeps for its own services (JSR 120), other than the WAP ports of
 * [knownPort].
 */
private fun servicePort(
    code: String,
    titleKey: TextKey,
) = row(ClassId.DATA_PORT, Severity.NOTABLE, titleKey, TextKey.EXP_ST_DATA_PORT_SERVICE, code)

private fun smart(
    code: String,
    titleKey: TextKey,
) = row(ClassId.SMART, Severity.NOTABLE, titleKey, TextKey.EXP_CL_SMART, code)

private fun settingsPush(
    code: String,
    titleKey: TextKey,
) = row(ClassId.WAP_CP, Severity.SUSPICIOUS, titleKey, TextKey.EXP_CL_WAP_CP, code)
