package dev.nightjar.app.domain.text

import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.contract.ClassId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The short title and the description of a Messages list row. Detail and Protection keep the full type
 * names.
 *
 * Rule: the list title is the type name without the subtype. The app cuts the full title before the first ": ", " ("
 * or ", " ("Malformed SMS: unknown message type" becomes "Malformed SMS"). [SHORT] names the titles where the cut is not
 * enough or gives the wrong word. The words that the cut drops go to the start of the description where they help
 * ("Unknown message type. A damaged header like this can crash a phone.").
 *
 * A list row keeps only its stored title, so the title key is found again from the title text, like
 * [MessageReason.smartTitleKey]. The placeholders of the key give back the values ({anomaly}, {app}, {port}).
 */
object ListTitle {
    /** The longest list title, in characters (about 24). `ListTitleTest` checks every class and subtype. */
    const val MAX_LENGTH = 24

    /** Full title key to the short list title. The value is used as it is, with the placeholders of the full title. */
    private val SHORT: Map<TextKey, TextKey> =
        buildMap {
            put(TextKey.TITLE_CL_RCS_PROV, TextKey.TITLE_CL_RCS_PROV)
            put(TextKey.TITLE_CL_DATA_PORT, TextKey.TITLE_LIST_DATA_PORT)
            put(TextKey.TITLE_CL_WAP_DM, TextKey.TITLE_LIST_DEVICE_MGMT)
            put(TextKey.TITLE_CL_OEM, TextKey.TITLE_LIST_OEM)
            put(TextKey.TITLE_CL_MO_UNPERSISTED, TextKey.TITLE_LIST_MO_HIDDEN)
            put(TextKey.TITLE_CL_MO_RAW, TextKey.TITLE_LIST_MO_RAW)
            put(TextKey.TITLE_CL_ME_DOWNLOAD, TextKey.TITLE_LIST_ME_DOWNLOAD)
            put(TextKey.TITLE_CL_CLASS3, TextKey.TITLE_LIST_CLASS3)
            put(TextKey.TITLE_CL_BINARY, TextKey.TITLE_LIST_BINARY)
            put(TextKey.TITLE_CL_MULTIPART_INCOMPLETE, TextKey.TITLE_LIST_INCOMPLETE)
            put(TextKey.TITLE_ST_FLASH_CLASS0_EMPTY, TextKey.TITLE_LIST_FLASH)
            put(TextKey.TITLE_ST_MWI_DISCARD_SILENT, TextKey.TITLE_LIST_MWI_SILENT)
            put(TextKey.TITLE_ST_DATA_PORT_INTERNET_ACCESS_CONFIG, TextKey.TITLE_LIST_INTERNET_CONFIG)
            put(TextKey.TITLE_CB_ETWS_BOTH, TextKey.TITLE_LIST_QUAKE_TSUNAMI)
            put(TextKey.TITLE_CB_SIM, TextKey.TITLE_LIST_CB_SIM)
            for (key in SIM_UPDATES) put(key, TextKey.TITLE_LIST_SIM_UPDATE)
            for (key in WAP_SESSIONS) put(key, TextKey.TITLE_ST_DATA_PORT_WAP_PUSH)
        }

    /** Full title key to the list description that replaces the reason line of the class. */
    private val DESCRIPTION: Map<TextKey, TextKey> =
        buildMap {
            put(TextKey.TITLE_CL_MALFORMED_SMS, TextKey.REASON_LIST_MALFORMED)
            put(TextKey.TITLE_CL_WAP_UNREADABLE, TextKey.REASON_LIST_MALFORMED)
            put(TextKey.TITLE_CL_BINARY, TextKey.REASON_LIST_BINARY)
            put(TextKey.TITLE_ST_FLASH_CLASS0_EMPTY, TextKey.REASON_LIST_FLASH_EMPTY)
            for (key in TELEMATIC) put(key, TextKey.REASON_LIST_TELEMATIC)
            for (key in WAP_SESSIONS.filter { it.name.endsWith("_SESSION_SECURE") }) put(key, TextKey.REASON_LIST_WAP_SECURE)
        }

    /** Full title keys whose {app} names the app that sent or added the message. */
    private val APP_SENT = setOf(TextKey.TITLE_CL_MO_VISIBLE, TextKey.TITLE_CL_MO_UNPERSISTED, TextKey.TITLE_CL_MO_RAW)

    /** The keys to try for any class after its own keys: a stored title can come from an older classifier. */
    private val ALL_TITLES: List<TextKey> =
        TextKey.entries.filter { it.name.startsWith("TITLE_") && !it.name.startsWith("TITLE_LIST_") }

    private val PLACEHOLDER = Regex("""\{(\w+)\}""")
    private val CUTS = listOf(": ", " (", ", ")

    /** One compiled title template: the pattern and the names of its placeholders, in order. */
    private class TitlePattern(
        val regex: Regex,
        val names: List<String>,
    )

    private val patterns = ConcurrentHashMap<String, TitlePattern>()

    /** The full title, its key when one matches, and the placeholder values of that key. */
    private class Match(
        val title: String,
        val key: TextKey?,
        val args: Map<String, String>,
    )

    /** The short list title of a stored title. A title from no known key gets the cut rule. */
    fun title(
        classId: ClassId,
        storedTitle: String,
        strings: Strings,
    ): String {
        val match = match(classId, storedTitle, strings)
        val short = match.key?.let { SHORT[it] }
        return if (short != null) Templates.format(strings.get(short), match.args) else cut(match.title)
    }

    /**
     * The description of a list row, or null when there is none. [reasonKey] is the reason line of the row
     * ([MessageReason.key]). A list description replaces it only when the reason is about the class: "Nothing on your
     * phone receives this message" stays. The dropped subtype words and the sending app come first.
     */
    fun description(
        classId: ClassId,
        storedTitle: String,
        reasonKey: TextKey?,
        strings: Strings,
    ): String? {
        val match = match(classId, storedTitle, strings)
        val key = match.key
        val detail = detailOf(match.title)
        // The anomaly and the subtype words start a sentence here. An app name keeps its own spelling.
        val args =
            match.args +
                listOfNotNull(
                    match.args["anomaly"]?.let { "anomaly" to capitalised(it) },
                    "detail" to capitalised(detail.orEmpty()),
                )
        val special = key?.let { DESCRIPTION[it] }?.takeIf { reasonKey != null && reasonKey != TextKey.REASON_NOBODY_HANDLES }
        if (special != null) return Templates.format(strings.get(special), args)
        val parts =
            listOfNotNull(
                detail?.let { Templates.format(strings.get(TextKey.REASON_LIST_DETAIL), args) },
                match.args["app"]?.takeIf { it.isNotBlank() }?.let { app ->
                    val appKey = if (key in APP_SENT) TextKey.REASON_LIST_APP_SENT else TextKey.REASON_LIST_APP_ADDED
                    Templates.format(strings.get(appKey), mapOf("app" to app))
                },
                reasonKey?.let { strings.get(it) },
            )
        return parts.joinToString(" ").ifBlank { null }
    }

    /** The full title cut before its first subtype mark; the title itself when the cut leaves nothing. */
    fun cut(title: String): String {
        val at = CUTS.mapNotNull { mark -> title.indexOf(mark).takeIf { it > 0 } }.minOrNull() ?: return title.trim()
        return title.substring(0, at).trim().ifEmpty { title.trim() }
    }

    private fun match(
        classId: ClassId,
        storedTitle: String,
        strings: Strings,
    ): Match {
        val title = displayTitle(classId, storedTitle, strings)
        val profile = ClassRegistry.of(classId)
        val own = listOf(profile.titleKey) + ClassRegistry.subtypes(classId).map { it.titleKey }
        for (key in own.asSequence() + ALL_TITLES.asSequence()) {
            val pattern = patternOf(strings.get(key))
            val result = pattern.regex.matchEntire(title) ?: continue
            return Match(title, key, pattern.names.zip(result.groupValues.drop(1)).toMap())
        }
        return Match(title, null, emptyMap())
    }

    private fun patternOf(template: String): TitlePattern =
        patterns.getOrPut(template) {
            val names = ArrayList<String>()
            val source = StringBuilder()
            var start = 0
            for (found in PLACEHOLDER.findAll(template)) {
                source.append(Regex.escape(template.substring(start, found.range.first))).append("(.+)")
                names += found.groupValues[1]
                start = found.range.last + 1
            }
            source.append(Regex.escape(template.substring(start)))
            TitlePattern(Regex(source.toString()), names)
        }

    /** The words after the first ": " of a full title ("changes files on the SIM"), or null. */
    private fun detailOf(title: String): String? = title.substringAfter(": ", "").trim().takeIf { it.isNotEmpty() }

    private fun capitalised(text: String): String = text.replaceFirstChar { it.titlecase(Locale.ROOT) }

    private val SIM_UPDATES: List<TextKey>
        get() =
            listOf(
                TextKey.TITLE_CL_SIM_DD,
                TextKey.TITLE_ST_SIM_DD_RFM,
                TextKey.TITLE_ST_SIM_DD_RAM,
                TextKey.TITLE_ST_SIM_DD_SAT,
                TextKey.TITLE_ST_SIM_DD_WIB,
                TextKey.TITLE_ST_SIM_DD_NOSEC,
                TextKey.TITLE_ST_SIM_DD_OTHER,
            )

    /** The WAP port subtypes of CL-DATA-PORT: the list calls them all "WAP push". */
    private val WAP_SESSIONS: List<TextKey>
        get() =
            listOf(
                TextKey.TITLE_ST_DATA_PORT_WAP_PUSH_SECURE,
                TextKey.TITLE_ST_DATA_PORT_WAP_CONNECTIONLESS_SESSION,
                TextKey.TITLE_ST_DATA_PORT_WAP_CONNECTIONLESS_SESSION_SECURE,
                TextKey.TITLE_ST_DATA_PORT_WAP_SESSION,
                TextKey.TITLE_ST_DATA_PORT_WAP_SESSION_SECURE,
                TextKey.TITLE_ST_DATA_PORT_WAP_WTA_CONNECTIONLESS_SESSION_SECURE,
                TextKey.TITLE_ST_DATA_PORT_WAP_WTA_SESSION_SECURE,
            )

    private val TELEMATIC: List<TextKey>
        get() =
            listOf(
                TextKey.TITLE_ST_TELEMATIC_FAX,
                TextKey.TITLE_ST_TELEMATIC_TELEX,
                TextKey.TITLE_ST_TELEMATIC_PAGING,
                TextKey.TITLE_ST_TELEMATIC_VOICE,
                TextKey.TITLE_ST_TELEMATIC_ERMES,
                TextKey.TITLE_ST_TELEMATIC_X400,
                TextKey.TITLE_ST_TELEMATIC_EMAIL,
                TextKey.TITLE_ST_TELEMATIC_OTHER,
            )
}
