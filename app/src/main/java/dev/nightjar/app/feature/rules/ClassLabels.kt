package dev.nightjar.app.feature.rules

import androidx.annotation.StringRes
import dev.nightjar.app.R
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.StringMatch

/**
 * Short copy for the class lists on Protection, Custom rules and Rule editor.
 *
 * The long titles in `title_cl_*` carry placeholders (`{port}`, `{contentType}`, `{channel}`) that a settings row cannot
 * fill, so the row labels live next to the screens instead. This is copy only: which actions a class accepts still
 * comes from [dev.nightjar.rules.EnforcementMatrix] and is never repeated here.
 *
 * One entry per [ClassId]. A map, not a `when`, keeps the function under the complexity limit without a suppression
 * ; [ClassLabelsTest] fails when a class has no label or two classes share one.
 */
@StringRes
fun classLabelRes(classId: ClassId): Int = classLabels.getValue(classId)

private val classLabels: Map<ClassId, Int> =
    mapOf(
        ClassId.NORMAL to R.string.class_normal,
        ClassId.SILENT to R.string.class_silent_ping,
        ClassId.FLASH to R.string.class_flash,
        ClassId.REPLACE to R.string.class_replace,
        ClassId.MWI to R.string.class_mwi,
        ClassId.VVM to R.string.class_vvm,
        ClassId.MISSED_CALL to R.string.class_missed_call,
        ClassId.CARRIER to R.string.class_carrier,
        ClassId.RCS_PROV to R.string.class_rcs_setup,
        ClassId.APP_TOKEN to R.string.class_app_token,
        ClassId.DATA_PORT to R.string.class_data_port,
        ClassId.WAP_MMS to R.string.class_mms,
        ClassId.WAP_SISL to R.string.class_web_link,
        ClassId.WAP_CP to R.string.class_settings_push,
        ClassId.WAP_DM to R.string.class_device_management,
        ClassId.WAP_SUPL to R.string.class_location_request,
        ClassId.WAP_OTHER to R.string.class_push_other,
        ClassId.SIM_DD to R.string.class_sim_update,
        ClassId.SIM_STORED to R.string.class_sim_stored,
        ClassId.STATUS to R.string.class_status,
        ClassId.CB to R.string.class_broadcast,
        ClassId.OEM to R.string.class_oem,
        ClassId.INJECTED to R.string.class_injected,
        ClassId.MO_VISIBLE to R.string.class_mo_visible,
        ClassId.MO_UNPERSISTED to R.string.class_mo_unpersisted,
        ClassId.MO_DATA to R.string.class_mo_data,
        ClassId.MO_RAW to R.string.class_mo_raw,
        ClassId.MO_STK to R.string.class_mo_stk,
        ClassId.MO_VVM to R.string.class_mo_vvm,
        ClassId.RETURN_CALL to R.string.class_return_call,
        ClassId.ME_DOWNLOAD to R.string.class_me_download,
        ClassId.ME_DEPERSO to R.string.class_me_deperso,
        ClassId.CLASS3 to R.string.class_class3,
        ClassId.TELEMATIC to R.string.class_telematic,
        ClassId.EMPTY to R.string.class_empty,
        ClassId.BINARY to R.string.class_binary,
        ClassId.COMPRESSED to R.string.class_compressed,
        ClassId.MULTIPART_INCOMPLETE to R.string.class_multipart_incomplete,
        ClassId.MALFORMED_SMS to R.string.class_malformed_sms,
        ClassId.SMART to R.string.class_smart,
        ClassId.LOC_SUPL to R.string.class_loc_supl,
        ClassId.WAP_CO to R.string.class_wap_co,
        ClassId.WAP_DS to R.string.class_wap_ds,
        ClassId.WAP_EMN to R.string.class_wap_emn,
        ClassId.WAP_DRM to R.string.class_wap_drm,
        ClassId.WAP_VCARD to R.string.class_wap_vcard,
        ClassId.WAP_IMPS to R.string.class_wap_imps,
        ClassId.WAP_UNREADABLE to R.string.class_wap_unreadable,
        ClassId.CB_SIM to R.string.class_cb_sim,
        ClassId.UNREADABLE_SMS to R.string.firewall_class_unreadable_sms,
    )

/** Action name: Allow, Allow + notify, Hold, Block. */
@StringRes
fun verdictLabelRes(verdict: Verdict): Int =
    when (verdict) {
        Verdict.ALLOW -> R.string.action_allow
        Verdict.NOTIFY -> R.string.action_notify
        Verdict.QUARANTINE -> R.string.action_quarantine
        Verdict.BLOCK -> R.string.action_block
    }

/** One-line consequence shown in the class action sheet and beside the rule editor's action choice. */
@StringRes
fun verdictDescriptionRes(verdict: Verdict): Int =
    when (verdict) {
        Verdict.ALLOW -> R.string.action_desc_allow
        Verdict.NOTIFY -> R.string.action_desc_notify
        Verdict.QUARANTINE -> R.string.action_desc_quarantine
        Verdict.BLOCK -> R.string.action_desc_block
    }

/** Operator names for the From row and the condition rows. */
@StringRes
fun matchOpLabelRes(op: StringMatch.Op): Int =
    when (op) {
        StringMatch.Op.IS -> R.string.rule_edit_op_is
        StringMatch.Op.IS_NOT -> R.string.rule_edit_op_is_not
        StringMatch.Op.PREFIX -> R.string.rule_edit_op_starts_with
        StringMatch.Op.REGEX -> R.string.rule_edit_op_matches_regex
    }

/**
 * Inbound classes offered by the rule editor's Type field.
 *
 * `CL-NORMAL` is included because a rule with a sender/port/text condition may act on it. Outbound classes and
 * counter-only rows are not, because the firewall does not act on them.
 */
val ruleEditorClasses: List<ClassId> =
    ClassId.entries.filter { classId ->
        when (classId) {
            ClassId.MO_VISIBLE,
            ClassId.MO_UNPERSISTED,
            ClassId.MO_DATA,
            ClassId.MO_RAW,
            ClassId.MO_STK,
            ClassId.MO_VVM,
            ClassId.SIM_STORED,
            ClassId.STATUS,
            -> false

            else -> true
        }
    }
