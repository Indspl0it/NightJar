package dev.nightjar.decoder

import dev.nightjar.contract.AnomalyCode
import dev.nightjar.pduparse.Anomalies
import dev.nightjar.pduparse.OtaParser
import dev.nightjar.pduparse.TpduParser
import dev.nightjar.pduparse.UdhParser
import dev.nightjar.pduparse.WbxmlScanner
import dev.nightjar.pduparse.WspParser
import java.util.Locale

/**
 * Entry points for payload decoders.
 *
 * Every method is total: malformed or truncated input yields a field tree with an `error` node at
 * the failing byte instead of an exception. The tree can be rendered with [FieldJson].
 */
object Decoders {
    const val TPDU = "tpdu"
    const val UDH = "udh"
    const val WSP = "wsp"
    const val WBXML = "wbxml"
    const val WBXML_SI = "wbxml_si"
    const val WBXML_SL = "wbxml_sl"
    const val WBXML_CO = "wbxml_co"
    const val OMA_CP = "oma_cp"
    const val OMA_DM = "oma_dm"
    const val SUPL = "supl"
    const val SMS_PP = "sms_pp"
    const val SMS_PP_RESPONSE = "sms_pp_response"
    const val CELL_BROADCAST = "cb"
    const val CELL_BROADCAST_UMTS = "cb_umts"

    val knownKinds: List<String> =
        listOf(
            TPDU,
            UDH,
            WSP,
            WBXML,
            WBXML_SI,
            WBXML_SL,
            WBXML_CO,
            OMA_CP,
            OMA_DM,
            SUPL,
            SMS_PP,
            SMS_PP_RESPONSE,
            CELL_BROADCAST,
            CELL_BROADCAST_UMTS,
        )

    /**
     * Dispatches by [kind]; unknown kinds return an error tree rather than throwing. Invalid hex gives the
     * "not valid hex" tree of [guardedHex]. One dispatch table: [decodeBytes].
     */
    fun decode(kind: String, hex: String): Field {
        if (kind.lowercase(Locale.ROOT) !in knownKinds) return unknownKind(kind, 0)
        return guardedHex(kind.lowercase(Locale.ROOT), hex) { decodeBytes(kind, it) }
    }

    fun decodeBytes(kind: String, bytes: ByteArray): Field =
        when (kind.lowercase(Locale.ROOT)) {
            TPDU -> Tpdu.decode(bytes)
            UDH -> guarded("udh", bytes) { Udh.decode(ByteReader(bytes)) }
            WSP -> Wsp.decode(bytes)
            WBXML -> Wbxml.decode(bytes).field
            WBXML_SI -> Wbxml.decode(bytes, WbxmlKind.SI).field
            WBXML_SL -> Wbxml.decode(bytes, WbxmlKind.SL).field
            WBXML_CO -> Wbxml.decode(bytes, WbxmlKind.CO).field
            OMA_CP -> OmaCp.decode(bytes)
            OMA_DM -> OmaDm.decode(bytes)
            SUPL -> Supl.decode(bytes)
            SMS_PP -> SmsPp.decode(bytes)
            SMS_PP_RESPONSE -> SmsPp.decodeResponse(bytes)
            CELL_BROADCAST -> CellBroadcast.decode(bytes, CbFormat.GSM)
            CELL_BROADCAST_UMTS -> CellBroadcast.decode(bytes, CbFormat.UMTS)
            else -> unknownKind(kind, bytes.size)
        }

    private fun unknownKind(
        kind: String,
        length: Int,
    ): Field = Field("decoder", offset = 0, length = length, error = "The decoder kind '$kind' is not known.")

    /**
     * Anomaly codes of [bytes] for a decoder [kind], in contract order. The checks run in the shared parser
     * (`:pduparse`, the same code as the payload): `tpdu` takes the radio form with the SMSC prefix and also checks
     * the UDH, a SIM OTA header and a single-part WAP push with its WBXML body. Kinds without checks give an empty
     * list. Total, like every decoder.
     */
    fun anomalies(kind: String, bytes: ByteArray): List<AnomalyCode> =
        when (kind.lowercase(Locale.ROOT)) {
            TPDU -> {
                TpduParser.parseRadio(bytes).anomalies()
            }

            UDH -> {
                UdhParser.parse(bytes).anomalies()
            }

            WSP -> {
                val push = WspParser.parse(bytes)
                val body = WbxmlScanner.scanPushBody(push, bytes, 0)
                Anomalies.list(push.anomalyMask() or (body?.anomalyMask() ?: 0))
            }

            WBXML, WBXML_SI, WBXML_SL, WBXML_CO -> {
                WbxmlScanner.scan(bytes).anomalies()
            }

            SMS_PP -> {
                OtaParser.parseCommand(bytes).anomalies()
            }

            SMS_PP_RESPONSE -> {
                OtaParser.parse(bytes, 0, bytes.size, true, false).anomalies()
            }

            else -> {
                emptyList()
            }
        }

    /** [anomalies] for hex input; invalid hex gives an empty list. */
    fun anomalies(kind: String, hex: String): List<AnomalyCode> = Hex.decodeOrNull(hex)?.let { anomalies(kind, it) } ?: emptyList()
}
