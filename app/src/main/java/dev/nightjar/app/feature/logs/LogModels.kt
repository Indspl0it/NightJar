package dev.nightjar.app.feature.logs

import dev.nightjar.app.core.log.LogLevel
import dev.nightjar.contract.Masker

/** Tabs (01). */
enum class LogTab {
    PAYLOAD,
    APP,
    COMPANION,
    AUDIT,
}

/** One parsed L2 line. [raw] is the redacted text that is displayed and copied. */
data class LogLine(
    val at: String,
    val level: LogLevel?,
    val tag: String,
    val message: String,
    val raw: String,
)

data class LogsUiState(
    val tab: LogTab = LogTab.APP,
    val lines: List<LogLine> = emptyList(),
    val levels: Set<LogLevel> = LogLevel.entries.toSet(),
    val query: String = "",
    val loading: Boolean = false,
    /** True when the Module tab has no line yet: the payload did not push its log since it connected. */
    val payloadUnavailable: Boolean = false,
    val companionNeedsRoot: Boolean = false,
) {
    /** Level and search are presentation filters; the file itself is never rewritten. */
    val visibleLines: List<LogLine>
        get() =
            lines.filter { line ->
                (line.level == null || line.level in levels) &&
                    (query.isBlank() || line.raw.contains(query, ignoreCase = true))
            }
}

/**
 * Defence in depth for every L2 line is already masked where it is written, and the viewer masks anything
 * that still looks like a phone number before it is shown, copied or exported.
 *
 * Three forms are masked: a number with a leading `+`, a run of seven or more digits, and a national number written in
 * groups ("07700 900123", "07700-900123", "0770 090 0123") with nine or more digits in total. The timestamp prefix is
 * left alone: its digit groups touch a letter or a colon (`2026-11-13T10:22:33`) and have fewer than nine digits.
 */
object LogRedactor {
    private val international = Regex("""\+\d[\d\s().-]{5,}\d""")
    private val grouped = Regex("""(?<![\w.:+-])\d{2,5}(?:[ .-]\d{2,6}){1,4}(?![\w:-]|\.\d)""")
    private val longDigits = Regex("""(?<![\w-])\d{7,}(?![\w-])""")

    /** A digit group of fewer digits is a date, a version or a count, not a phone number. */
    private const val GROUPED_MIN_DIGITS = 9

    fun redact(line: String): String {
        var out = international.replace(line) { Masker.maskNumber(it.value) }
        out =
            grouped.replace(out) { match ->
                if (match.value.count(Char::isDigit) >= GROUPED_MIN_DIGITS) Masker.maskNumber(match.value) else match.value
            }
        out = longDigits.replace(out) { Masker.maskNumber(it.value) }
        return out
    }
}
