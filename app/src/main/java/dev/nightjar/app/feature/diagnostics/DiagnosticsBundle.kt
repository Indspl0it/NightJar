package dev.nightjar.app.feature.diagnostics

import dev.nightjar.app.feature.logs.LogRedactor
import dev.nightjar.contract.Masker
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One text file inside the diagnostics ZIP. */
data class DiagnosticsEntry(
    val name: String,
    val text: String,
)

/** Hook health as it appears in `hooks.txt`. */
data class HookRow(
    val id: String,
    val state: String,
    val errorCount: Int,
    val lastError: String?,
)

/** One audit record for `audit.json` (: last 30 days). The builder masks [target], [before] and [after]. */
data class AuditRow(
    val at: Long,
    val actor: String,
    val action: String,
    val target: String?,
    val before: String?,
    val after: String?,
)

/**
 * One message row for `messages.txt`. Only present when the user ticked "Include message list" in the diagnostics
 * dialog.
 * Metadata only: the row has no field for the message body, so no caller can put text in the file. The builder
 * masks [originator]. The package names are not private data: they trace trust and outgoing problems.
 */
data class MessageRow(
    val id: Long,
    val receivedAtWall: Long,
    val classId: String,
    val severity: String,
    val direction: String,
    val slot: Int,
    val originator: String?,
    /** The app that sent an outgoing message, or null. */
    val callingPackage: String? = null,
    /** The app that received an incoming message, or null. */
    val consumerPackage: String? = null,
)

/**
 * Builds the diagnostics bundle an unsupported build needs to be supported (03).
 *
 * The bundle is plain text so it can be read in a bug report without a parser. Every entry carries the
 * [MARKER] line first: a marker a triage can grep for, and a test asserts the bundle never contains a
 * raw number or a message body.
 *
 * The builder is pure and JVM-only, so the redaction contract is unit-tested without a device.
 */
object DiagnosticsBundle {
    /** First line of every entry: `nightjar-diagnostics:<schema>`. */
    const val MARKER = "nightjar-diagnostics:1"

    /** Cap on the app log copied into the ZIP: the last two rotated files are enough for triage. */
    const val MAX_LOG_CHARS = 400_000

    /**
     * The app log text from the rotated files, which the log sink lists newest first. The text is oldest first, so the
     * [MAX_LOG_CHARS] cap in [build] drops the oldest lines.
     */
    fun appLogText(filesNewestFirst: List<String>): String = filesNewestFirst.asReversed().joinToString("\n")

    fun build(
        device: List<Pair<String, String>>,
        hooks: List<HookRow>,
        audit: List<AuditRow>,
        appLog: String,
        messages: List<MessageRow>?,
        payloadLog: List<String>? = null,
        phoneProcess: List<Pair<String, String>>? = null,
    ): List<DiagnosticsEntry> {
        val entries = ArrayList<DiagnosticsEntry>(7)
        entries += entry("device.txt", deviceLines(device))
        entries += entry("hooks.txt", hookLines(hooks))
        entries += entry("audit.json", auditJson(audit))
        entries += entry("app.log", LogRedactor.redact(appLog.takeLast(MAX_LOG_CHARS)))
        if (payloadLog != null) {
            // Oldest first; the payload already masks its lines, the redactor is a second net.
            entries += entry("payload-log.txt", LogRedactor.redact(payloadLog.joinToString("\n").takeLast(MAX_LOG_CHARS)))
        }
        if (phoneProcess != null) {
            entries += entry("phone-process.txt", deviceLines(phoneProcess))
        }
        if (messages != null) {
            entries += entry("messages.txt", messageLines(messages))
        }
        return entries
    }

    /** Writes [entries] as a ZIP at [target], creating the parent directory. */
    fun writeZip(
        target: File,
        entries: List<DiagnosticsEntry>,
    ) {
        target.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            entries.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.name))
                zip.write(entry.text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun entry(
        name: String,
        body: String,
    ): DiagnosticsEntry = DiagnosticsEntry(name, MARKER + "\n" + body + "\n")

    private fun deviceLines(device: List<Pair<String, String>>): String =
        device.joinToString("\n") { (key, value) -> "$key=$value" }

    private fun hookLines(hooks: List<HookRow>): String {
        if (hooks.isEmpty()) return "no hook rows recorded for this build"
        return hooks.joinToString("\n") { hook ->
            listOf(
                hook.id,
                hook.state,
                "errors=${hook.errorCount}",
                hook.lastError?.let { error -> "last=" + Masker.maskText(error) },
            ).filterNotNull().joinToString("\t")
        }
    }

    private fun auditJson(audit: List<AuditRow>): String =
        buildString {
            append("[\n")
            audit.forEachIndexed { index, row ->
                if (index > 0) append(",\n")
                append("{\"at\":").append(row.at)
                append(",\"actor\":").append(jsonString(row.actor))
                append(",\"action\":").append(jsonString(row.action))
                // A known-sender or rule change has the number in these fields.
                append(",\"target\":").append(jsonString(row.target?.let(LogRedactor::redact)))
                append(",\"before\":").append(jsonString(row.before?.let(LogRedactor::redact)))
                append(",\"after\":").append(jsonString(row.after?.let(LogRedactor::redact)))
                append('}')
            }
            append("\n]\n")
        }

    private fun messageLines(messages: List<MessageRow>): String =
        buildString {
            append("id\treceivedAtWall\tclass\tseverity\tdirection\tslot\toriginator\tcallingPackage\tconsumerPackage\n")
            messages.forEach { row ->
                append(row.id).append('\t')
                append(row.receivedAtWall).append('\t')
                append(row.classId).append('\t')
                append(row.severity).append('\t')
                append(row.direction).append('\t')
                append(row.slot).append('\t')
                append(row.originator?.let(Masker::maskNumber).orEmpty()).append('\t')
                append(oneCell(row.callingPackage)).append('\t')
                append(oneCell(row.consumerPackage))
                append('\n')
            }
        }

    /** A package name in one cell: a tab or a line break would move the other columns. */
    private fun oneCell(value: String?): String =
        value
            .orEmpty()
            .replace('\t', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')

    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        return buildString(value.length + 2) {
            append('"')
            value.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
    }
}
