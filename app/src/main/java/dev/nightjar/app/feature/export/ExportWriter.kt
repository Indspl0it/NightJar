package dev.nightjar.app.feature.export

import dev.nightjar.contract.Ipc
import dev.nightjar.contract.Masker
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Streaming writers for the three export formats.
 *
 * Each writer is fed one message at a time, so a 10,000-message export never builds a list in memory. The output is
 * plain UTF-8 text written through an [Appendable]; the ViewModel supplies the file, SAF stream or clipboard.
 *
 * Redaction: with [ExportOptions.maskNumbers] the sender and SMSC go through the shared
 * [Masker] and phone-like runs inside text are masked too. A raw PDU holds the sender, the SMSC and the message text,
 * so with numbers masked or message text off the PDU hex is replaced by [Masker.maskText] (its length only), and the
 * decoded tree is left out ([ExportOptions.showsRawPdu]).
 *
 * Schema `nightjarExport`: 1 was the first file layout. 2 adds the subtype keys (`subtype`, `extraSubtypes`,
 * `anomalies`), the `rawPdu` header flag, and a masked `hex` value when `rawPdu` is false. The PDU hex format carries
 * the same version in its first comment line. CSV has no version line: the header row names every column.
 */
object ExportJson {
    const val SCHEMA_KEY = "nightjarExport"
    const val SCHEMA_VERSION = 2
}

/**
 * True when the export may contain the raw PDU and the decoded tree. A PDU contains the sender, the SMSC and the
 * message text, so it is shown only with message text on and numbers not masked.
 */
val ExportOptions.showsRawPdu: Boolean get() = includeText && !maskNumbers

/** The hex of one PDU as the export writes it: the hex itself, or only its length when the PDU must stay private. */
internal fun ExportOptions.pduHex(hex: String): String = if (showsRawPdu) hex else Masker.maskText(hex)

/** One writer for one export format. The export run and the tests use the same writers through [exportWriter]. */
interface ExportStreamWriter {
    fun begin()

    fun message(message: ExportMessage)

    fun end(count: Int)
}

/** The writer for [format]. */
fun exportWriter(
    format: ExportFormat,
    out: Appendable,
    options: ExportOptions,
    exportedAt: Long,
    appVersion: String,
): ExportStreamWriter =
    when (format) {
        ExportFormat.JSON -> JsonExportWriter(out, options, exportedAt, appVersion)
        ExportFormat.CSV -> CsvExportWriter(out, options)
        ExportFormat.PDU_HEX -> PduHexWriter(out, options, exportedAt)
    }

/** `nightjar-export-<yyyyMMdd-HHmmss>.<ext>` for SAF and the share sheet. */
fun exportFileName(
    format: ExportFormat,
    atMillis: Long,
): String {
    val stamp =
        DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(atMillis))
    return "nightjar-export-$stamp.${format.extension}"
}

/** RFC 4180 field: quotes when the value contains a comma, quote, CR or LF. */
fun csvField(value: String?): String {
    if (value == null) return ""
    val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuotes) return value
    return "\"" + value.replace("\"", "\"\"") + "\""
}

/** JSON string escaping; every non-ASCII and control code unit becomes `\uXXXX`. */
fun jsonString(value: String?): String {
    if (value == null) return "null"
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (c in value) {
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c.code < 0x20 || c.code > 0x7E -> out.append("\\u").append("%04x".format(c.code))
            else -> out.append(c)
        }
    }
    out.append('"')
    return out.toString()
}

/** A JSON array of strings, escaped like [jsonString]. */
fun jsonArray(values: List<String>): String = values.joinToString(",", prefix = "[", postfix = "]") { jsonString(it) }

/** A list in one CSV cell: the entries joined with `|`, which no subtype or anomaly code contains. */
fun csvList(values: List<String>): String = values.joinToString("|")

/**
 * Spreadsheets run a cell that starts with `=`, `+`, `-`, `@`, tab or CR as a formula, and a sender or an SMS body is
 * chosen by whoever sent it. Such a cell gets a leading apostrophe; a plain phone number keeps its leading `+`.
 */
fun csvSafe(value: String?): String? {
    if (value.isNullOrEmpty()) return value
    if (value[0] !in FORMULA_STARTS) return value
    if (plainNumber.matches(value)) return value
    return "'" + value
}

private const val FORMULA_STARTS = "=+-@\t\r"

private val plainNumber = Regex("""\+?[0-9 ().-]+""")

private val phoneLike = Regex("""\+?\d[\d ().-]{5,}\d""")

/** Masks phone-like runs inside free text when the export asks for redaction. */
fun redactText(value: String?, mask: Boolean): String? {
    if (value == null || !mask) return value
    return phoneLike.replace(value) { Masker.maskNumber(it.value) }
}

private fun number(value: String?, mask: Boolean): String? =
    if (!mask || value == null) value else Masker.maskNumber(value)

/** JSON export: `{nightjarExport: 2, exportedAt, appVersion, includeText, …, messages: […], count}`. */
class JsonExportWriter(
    private val out: Appendable,
    private val options: ExportOptions,
    private val exportedAt: Long,
    private val appVersion: String,
) : ExportStreamWriter {
    private var first = true

    override fun begin() {
        out.append('{')
        out
            .append('"')
            .append(ExportJson.SCHEMA_KEY)
            .append("\":")
            .append(ExportJson.SCHEMA_VERSION.toString())
        out.append(",\"exportedAt\":").append(exportedAt.toString())
        out.append(",\"appVersion\":").append(jsonString(appVersion))
        out.append(",\"includeText\":").append(options.includeText.toString())
        out.append(",\"includeDecodedTree\":").append((options.includeTree && options.showsRawPdu).toString())
        out.append(",\"maskedNumbers\":").append(options.maskNumbers.toString())
        out.append(",\"rawPdu\":").append(options.showsRawPdu.toString())
        out.append(",\"messages\":[")
    }

    override fun message(message: ExportMessage) {
        if (!first) out.append(',')
        first = false
        val mask = options.maskNumbers
        out.append('{')
        out.append("\"id\":").append(message.id.toString())
        out.append(",\"eventId\":").append(jsonString(message.eventId))
        out.append(",\"revision\":").append(message.revision.toString())
        out.append(",\"receivedAt\":").append(message.receivedAtWall.toString())
        out.append(",\"direction\":").append(jsonString(message.direction))
        out.append(",\"transport\":").append(jsonString(message.transport))
        out.append(",\"source\":").append(message.source.toString())
        out.append(",\"subId\":").append(message.subId.toString())
        out.append(",\"slot\":").append(message.slot.toString())
        out.append(",\"carrierName\":").append(jsonString(message.carrierName))
        out.append(",\"classId\":").append(jsonString(message.classId))
        out.append(",\"severity\":").append(jsonString(message.severity))
        out.append(",\"detectorSeverity\":").append(jsonString(message.detectorSeverity))
        out.append(",\"severityOverridden\":").append(message.severityOverridden.toString())
        out.append(",\"title\":").append(jsonString(redactText(message.title, mask)))
        out.append(",\"originator\":").append(jsonString(number(message.originator, mask)))
        out.append(",\"smsc\":").append(jsonString(number(message.smsc, mask)))
        out.append(",\"destPort\":").append(message.destPort?.toString() ?: "null")
        out.append(",\"contentType\":").append(jsonString(message.contentType))
        if (options.includeText) {
            out.append(",\"text\":").append(jsonString(redactText(message.textBody, mask)))
            out.append(",\"textPreview\":").append(jsonString(redactText(message.textPreview, mask)))
        }
        out.append(",\"verdict\":").append(jsonString(message.verdict))
        out.append(",\"ruleId\":").append(message.ruleId?.toString() ?: "null")
        out.append(",\"fate\":").append(jsonString(message.fate))
        out.append(",\"fateDetail\":").append(jsonString(redactText(message.fateDetail, mask)))
        out.append(",\"consumerPackage\":").append(jsonString(message.consumerPackage))
        out.append(",\"callingPackage\":").append(jsonString(message.callingPackage))
        out.append(",\"persistFlag\":").append(message.persistFlag?.toString() ?: "null")
        out.append(",\"concatRef\":").append(message.concatRef?.toString() ?: "null")
        out.append(",\"partsTotal\":").append(message.partsTotal.toString())
        out.append(",\"partsHave\":").append(message.partsHave.toString())
        out.append(",\"isQuarantined\":").append(message.isQuarantined.toString())
        out.append(",\"isReviewed\":").append(message.isReviewed.toString())
        out.append(",\"isStarred\":").append(message.isStarred.toString())
        out.append(",\"isReplay\":").append(message.isReplay.toString())
        out.append(",\"buildFingerprint\":").append(jsonString(message.buildFingerprint))
        out.append(",\"payloadVersion\":").append(jsonString(message.payloadVersion))
        // New keys only, so a reader of schema 1 that ignores unknown keys still reads the file.
        // The keys are the IPC event keys, so the export and the wire use one name for each field.
        out
            .append(",\"")
            .append(Ipc.EventKey.SUBTYPE)
            .append("\":")
            .append(jsonString(message.subtype))
        out
            .append(",\"")
            .append(Ipc.EventKey.EXTRA_SUBTYPES)
            .append("\":")
            .append(jsonArray(message.extraSubtypes))
        out
            .append(",\"")
            .append(Ipc.EventKey.ANOMALIES)
            .append("\":")
            .append(jsonArray(message.anomalies))
        out.append(",\"pdus\":[")
        message.pdus.forEachIndexed { index, pdu ->
            if (index > 0) out.append(',')
            out.append('{')
            out.append("\"partNo\":").append(pdu.partNo.toString())
            out.append(",\"format\":").append(jsonString(pdu.format))
            out.append(",\"hex\":").append(jsonString(options.pduHex(pdu.hex)))
            if (options.includeTree && options.showsRawPdu && pdu.decodedJson != null) {
                out.append(",\"decoded\":").append(pdu.decodedJson)
            }
            out.append('}')
        }
        out.append("],\"trace\":[")
        message.trace.forEachIndexed { index, step ->
            if (index > 0) out.append(',')
            out.append('{')
            out.append("\"seq\":").append(step.seq.toString())
            out.append(",\"stage\":").append(jsonString(step.stage))
            out.append(",\"tNs\":").append(step.tNs.toString())
            out.append(",\"detail\":").append(jsonString(redactText(step.detail, mask)))
            out.append('}')
        }
        out.append("]}")
    }

    override fun end(count: Int) {
        out.append("],\"count\":").append(count.toString()).append('}')
    }
}

/** CSV: one row per message, no PDUs. */
class CsvExportWriter(
    private val out: Appendable,
    private val options: ExportOptions,
) : ExportStreamWriter {
    override fun begin() {
        val columns =
            listOf(
                "id",
                "eventId",
                "receivedAt",
                "classId",
                "severity",
                "title",
                "originator",
                "subId",
                "slot",
                "transport",
                "direction",
                "destPort",
                "contentType",
                "verdict",
                "ruleId",
                "fate",
                "isQuarantined",
                "isReviewed",
                "isStarred",
                "partsHave",
                "partsTotal",
                // New columns go after the old ones and before "text", so "text" stays the last column.
                Ipc.EventKey.SUBTYPE,
                Ipc.EventKey.EXTRA_SUBTYPES,
                Ipc.EventKey.ANOMALIES,
            ) + if (options.includeText) listOf("text") else emptyList()
        out.append(columns.joinToString(",")).append("\r\n")
    }

    override fun message(message: ExportMessage) {
        val mask = options.maskNumbers
        // Every cell that holds text from the network or from a sender goes through csvSafe (formula injection).
        val cells =
            mutableListOf(
                message.id.toString(),
                csvSafe(message.eventId) ?: "",
                message.receivedAtWall.toString(),
                message.classId,
                message.severity,
                csvSafe(redactText(message.title, mask)) ?: "",
                csvSafe(number(message.originator, mask)) ?: "",
                message.subId.toString(),
                message.slot.toString(),
                message.transport,
                message.direction,
                message.destPort?.toString() ?: "",
                csvSafe(message.contentType) ?: "",
                message.verdict,
                message.ruleId?.toString() ?: "",
                message.fate,
                message.isQuarantined.toString(),
                message.isReviewed.toString(),
                message.isStarred.toString(),
                message.partsHave.toString(),
                message.partsTotal.toString(),
                csvSafe(message.subtype) ?: "",
                csvSafe(csvList(message.extraSubtypes)) ?: "",
                csvSafe(csvList(message.anomalies)) ?: "",
            )
        if (options.includeText) cells += csvSafe(redactText(message.textBody ?: message.textPreview, mask)) ?: ""
        out.append(cells.joinToString(",") { csvField(it) }).append("\r\n")
    }

    override fun end(count: Int) = Unit
}

/** PDU hex: a comment block per message, then one line per part. */
class PduHexWriter(
    private val out: Appendable,
    private val options: ExportOptions,
    private val exportedAt: Long,
) : ExportStreamWriter {
    override fun begin() {
        out
            .append("# ")
            .append(ExportJson.SCHEMA_KEY)
            .append(": ")
            .append(ExportJson.SCHEMA_VERSION.toString())
            .append('\n')
        out.append("# exportedAt=").append(exportedAt.toString()).append('\n')
        if (!options.showsRawPdu) out.append("# rawPdu=false: each part shows only its length\n")
    }

    override fun message(message: ExportMessage) {
        val mask = options.maskNumbers
        out
            .append("# message id=")
            .append(message.id.toString())
            .append(" eventId=")
            .append(message.eventId)
            .append(" receivedAt=")
            .append(message.receivedAtWall.toString())
            .append(" class=")
            .append(message.classId)
            .append(" severity=")
            .append(message.severity)
            .append(" originator=")
            .append(number(message.originator, mask) ?: "-")
            .append('\n')
        for (pdu in message.pdus) {
            out
                .append("# part ")
                .append(pdu.partNo.toString())
                .append(' ')
                .append(pdu.format)
                .append('\n')
            // A hidden part is a comment line, so a tool that reads the hex lines never takes the length for data.
            val line = if (options.showsRawPdu) pdu.hex else "# " + options.pduHex(pdu.hex)
            out.append(line).append('\n')
        }
    }

    override fun end(count: Int) {
        out.append("# count=").append(count.toString()).append('\n')
    }
}
