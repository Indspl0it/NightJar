package dev.nightjar.app.feature.detail

import androidx.annotation.StringRes
import dev.nightjar.app.R
import dev.nightjar.contract.AnomalyCode
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Wire

/**
 * The name of each anomaly code. A map instead of a `when`: 24 branches are over the detekt complexity limit, and
 * `AnomalyNamesTest` fails when a code has no entry.
 */
private val AnomalyNames: Map<AnomalyCode, Int> =
    mapOf(
        AnomalyCode.ADDR_TRUNC to R.string.anomaly_name_addr_trunc,
        AnomalyCode.ADDR_LEN to R.string.anomaly_name_addr_len,
        AnomalyCode.ADDR_TOA to R.string.anomaly_name_addr_toa,
        AnomalyCode.PDU_SHORT to R.string.anomaly_name_pdu_short,
        AnomalyCode.MTI to R.string.anomaly_name_mti,
        AnomalyCode.PID_RSV to R.string.anomaly_name_pid_rsv,
        AnomalyCode.DCS_RSV to R.string.anomaly_name_dcs_rsv,
        AnomalyCode.UDL_LONG to R.string.anomaly_name_udl_long,
        AnomalyCode.UDL_SHORT to R.string.anomaly_name_udl_short,
        AnomalyCode.UDH_LEN to R.string.anomaly_name_udh_len,
        AnomalyCode.IE_OVERRUN to R.string.anomaly_name_ie_overrun,
        AnomalyCode.IE_RSV to R.string.anomaly_name_ie_rsv,
        AnomalyCode.IE_DUP to R.string.anomaly_name_ie_dup,
        AnomalyCode.PORT_ZERO to R.string.anomaly_name_port_zero,
        AnomalyCode.CONCAT_BAD to R.string.anomaly_name_concat_bad,
        AnomalyCode.CONCAT_DUP to R.string.anomaly_name_concat_dup,
        AnomalyCode.WSP_TYPE to R.string.anomaly_name_wsp_type,
        AnomalyCode.WSP_HLEN to R.string.anomaly_name_wsp_hlen,
        AnomalyCode.WSP_CTYPE to R.string.anomaly_name_wsp_ctype,
        AnomalyCode.WSP_APPID to R.string.anomaly_name_wsp_appid,
        AnomalyCode.WBXML_VER to R.string.anomaly_name_wbxml_ver,
        AnomalyCode.WBXML_STR to R.string.anomaly_name_wbxml_str,
        AnomalyCode.WBXML_TOK to R.string.anomaly_name_wbxml_tok,
        AnomalyCode.OTA_HDR to R.string.anomaly_name_ota_hdr,
    )

/**
 * The plain-words name of an anomaly. The text is lower case, see `strings_anomaly.xml`. A code without an
 * entry gets the "unknown format problem" name.
 */
@StringRes
fun anomalyNameRes(code: AnomalyCode): Int = AnomalyNames[code] ?: R.string.anomaly_name_unknown

/** The name of a wire code; a code that this app does not know gets the "unknown format problem" name. */
@StringRes
fun anomalyNameRes(wire: String): Int =
    Wire.parse(AnomalyCode::class.java, wire, null)?.let(::anomalyNameRes) ?: R.string.anomaly_name_unknown

/**
 * The anomaly rows of the Evidence card, one for each code in parser order. A repeated code shows once.
 *
 * Every anomaly is at least Suspicious (`evidenceFloor` in the classifier). The message severity is used when it is
 * higher, so a row never looks less serious than the header badge. [name] turns a string id into text.
 */
fun anomalyRows(
    codes: List<String>,
    messageSeverity: Severity,
    name: (Int) -> String,
): List<AnomalyRow> {
    // Severity constants are in ascending order, so the enum order is the severity order.
    val severity = maxOf(messageSeverity, Severity.SUSPICIOUS)
    return codes.distinct().map { code ->
        AnomalyRow(
            text = name(anomalyNameRes(code)).replaceFirstChar { it.uppercase() },
            severity = severity,
            code = code,
        )
    }
}
