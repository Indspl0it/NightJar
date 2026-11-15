package dev.nightjar.app.domain.text

import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.cb.CbCategory
import dev.nightjar.app.domain.detect.DetectionDraft
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.app.domain.model.ExtraSubtype
import dev.nightjar.app.domain.model.wapContentTypeUnreadable
import dev.nightjar.contract.AnomalyCode
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Masker
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Subtype
import dev.nightjar.contract.Wire
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Resolves an app label for a package name (QUERY_ALL_PACKAGES, rows). */
fun interface PackageLabels {
    fun labelOf(packageName: String?): String?
}

/** Locale-aware date formatting for copy such as FT-RELEASED "Released by you on <date>". */
fun interface DateFormatter {
    fun date(wallMs: Long): String
}

/**
 * Turns an event into the copy the user reads.
 *
 * Every sentence comes from [Strings]; this class only fills placeholders and decides which entry applies. Numbers are
 * masked through the shared [Masker] when the caller asks for it (`privacy.maskNumbers`, and always for the
 * public notification version), so the feed, the detail screen and notifications share one code path.
 */
class Explainer(
    private val strings: Strings,
    private val labels: PackageLabels = PackageLabels { null },
    private val dates: DateFormatter = IsoDates,
    private val maskNumbers: () -> Boolean = { false },
    private val defaultSmsAppLabel: () -> String? = { null },
) {
    /** Renders any catalogue entry with [args]. */
    fun render(
        key: TextKey,
        args: Map<String, String> = emptyMap(),
    ): String = Templates.format(strings.get(key), args)

    fun severityLabel(severity: Severity): String =
        strings.get(
            when (severity) {
                Severity.INFO -> TextKey.SEVERITY_INFO
                Severity.NOTABLE -> TextKey.SEVERITY_NOTABLE
                Severity.SUSPICIOUS -> TextKey.SEVERITY_SUSPICIOUS
                Severity.HIGH -> TextKey.SEVERITY_HIGH
            },
        )

    /** Row title and Detail headline. */
    fun title(event: EventFacts): String = render(titleKey(event), args(event))

    /**
     * "What is this?" card: the type explanation, then the evidence (anomalies and the other types the message
     * matches), then the detector paragraph when a detector fired (AC2).
     */
    fun explanation(
        event: EventFacts,
        detection: DetectionDraft? = null,
    ): String {
        val args = args(event)
        val base = render(explanationKey(event), args)
        val evidence = evidence(event, args)
        val text = if (evidence.isEmpty()) base else base + "\n\n" + evidence
        if (detection == null) return text
        return text + "\n\n" + detectionParagraph(event, detection)
    }

    /**
     * The evidence line: the anomalies that the title does not already name, and the extra subtypes. Empty
     * when the message has neither.
     */
    fun evidence(event: EventFacts): String = evidence(event, args(event))

    /** The plain-word name of an anomaly code, or the code itself for a code this app does not know. */
    fun anomalyName(code: String): String {
        val anomaly = Wire.parse(AnomalyCode::class.java, code, null) ?: return code
        return strings.get(anomalyKey(anomaly))
    }

    private fun evidence(
        event: EventFacts,
        args: Map<String, String>,
    ): String {
        val named = namedAnomaly(event)
        val extraAnomalies = event.anomalies.filter { it != named }
        val sentences = mutableListOf<String>()
        if (extraAnomalies.isNotEmpty()) {
            val names = extraAnomalies.joinToString(", ") { anomalyName(it) }
            sentences += render(TextKey.EXP_ANOMALIES, mapOf(ARG_ANOMALIES to names))
        }
        val types = args.getValue("types")
        if (types.isNotEmpty()) sentences += render(TextKey.EXP_EXTRA_TYPES, mapOf("types" to types))
        return sentences.joinToString(" ")
    }

    /** The detector's own sentences, summary first (AC2, values). */
    fun detectionParagraph(
        event: EventFacts,
        detection: DetectionDraft,
    ): String {
        val args = detectionArgs(event, detection)
        return detection.paragraphKeys.joinToString(" ") { render(it, args) }
    }

    /** The event arguments with the detector's own values on top. */
    private fun detectionArgs(
        event: EventFacts,
        detection: DetectionDraft,
    ): Map<String, String> = args(event) + detectorArgs(detection.summaryArgs)

    /**
     * A detector's own values, ready to render: a number is masked like any other (privacy.maskNumbers), and an
     * unknown value ([DetectionDraft.UNKNOWN]) becomes the "unknown" word.
     */
    fun detectorArgs(args: Map<String, String>): Map<String, String> =
        args.mapValues { (name, value) ->
            when {
                value == DetectionDraft.UNKNOWN -> strings.get(TextKey.GENERIC_UNKNOWN)
                name in NUMBER_ARGS -> number(value)
                else -> value
            }
        }

    /** First sentence of [explanation]: the BigText line of a notification. */
    fun explanationFirstSentence(
        event: EventFacts,
        detection: DetectionDraft? = null,
    ): String = firstSentence(explanation(event, detection))

    /** "What happened" line (AC2). */
    fun fateText(event: EventFacts): String {
        // A block with no rule behind it (a malformed push, a self-test canary) has no name to put in the brackets.
        val noRule = event.ruleName == null && event.ruleId == null
        val key =
            when {
                // Name the app that the user blocked; a gate block has no rule either.
                event.blockedByOutgoingGate -> TextKey.FATE_TXT_BLOCKED_APP

                event.fate == Fate.BLOCKED && noRule -> TextKey.FATE_TXT_BLOCKED_NO_RULE

                // HK-31 can redirect without a package name; "Sent to an app" would name an app nobody saw.
                event.fate == Fate.OEM_REDIRECTED && event.namedPackage == null -> TextKey.FATE_TXT_OEM_REDIRECTED_NO_APP

                else -> fateKey(event.fate)
            }
        return render(key, args(event))
    }

    /** Line 2: who/where; null when the message carries no sender at all (callers leave the part out). */
    fun whoOrNull(event: EventFacts): String? =
        when {
            event.direction == Direction.OUT -> {
                event.destination?.takeIf { it.isNotBlank() }?.let(::number)
                    ?: event.callingPackage?.let { appLabel(event) }
            }

            !event.originator.isNullOrBlank() -> {
                number(event.originator)
            }

            event.callingPackage != null -> {
                appLabel(event)
            }

            else -> {
                null
            }
        }

    /** Same as [whoOrNull] with the "unknown sender" wording, for places that must print something. */
    fun who(event: EventFacts): String = whoOrNull(event) ?: strings.get(TextKey.GENERIC_NO_SENDER)

    /** Why a flagged message is flagged, in one line; null for normal and outgoing traffic. */
    fun reason(
        event: EventFacts,
        severity: Severity,
    ): String? {
        val title = titleKey(event)
        return MessageReason
            .key(
                event.classId,
                severity,
                event.direction,
                event.fate,
                undecodable = title in MALFORMED_TITLES,
                titleKey = title,
            )?.let { render(it) }
    }

    /** The label of an installed app, or the package name itself when the app has no label (kept f). */
    fun appName(packageName: String): String = labels.labelOf(packageName)?.takeIf { it.isNotBlank() } ?: packageName

    /** "Jio · SIM 1", or empty when neither the operator nor the slot is known. */
    fun sim(event: EventFacts): String = strings.simDisplay(event.slot, event.carrierName)

    /** Masks a number when configured; null/blank becomes the "unknown sender" string. */
    fun number(value: String?): String {
        if (value.isNullOrBlank()) return strings.get(TextKey.GENERIC_NO_SENDER)
        return if (maskNumbers()) Masker.maskNumber(value) else value
    }

    /** Notification title for a detection that has grown: the summary sentence only (: "6 silent pings in 10 min"). */
    fun detectionSummary(
        detection: DetectionDraft,
        event: EventFacts,
    ): String = render(detection.summaryKey, detectionArgs(event, detection))

    private fun titleKey(event: EventFacts): TextKey {
        // A cell broadcast takes its category title from CbCategory.
        CbCategory.titleKey(event.classId, event.subtype, event.cb?.messageId)?.let { return it }
        ClassRegistry.subtype(event.classId, event.planSubtype)?.let { return it.titleKey }
        return when {
            // A push whose content type could not be read is a malformed message, not a "push (unknown)".
            event.classId == ClassId.WAP_OTHER && event.wapContentTypeUnreadable() -> {
                TextKey.TITLE_CL_WAP_UNREADABLE
            }

            else -> {
                ClassRegistry.of(event.classId).titleKey
            }
        }
    }

    private fun explanationKey(event: EventFacts): TextKey {
        CbCategory.explanationKey(event.classId, event.subtype, event.cb?.messageId)?.let { return it }
        ClassRegistry.subtype(event.classId, event.planSubtype)?.let { return it.explanationKey }
        if (titleKey(event) == TextKey.TITLE_CL_WAP_UNREADABLE) return TextKey.EXP_CL_WAP_UNREADABLE
        return ClassRegistry.of(event.classId).explanationKey
    }

    /**
     * The anomaly code that the title already names: the anomaly subtype, or for a malformed message without one (a
     * row from an older version) the first anomaly. Null when the title names no anomaly.
     */
    private fun namedAnomaly(event: EventFacts): String? {
        val subtype = event.planSubtype
        if (subtype != null && Wire.parse(AnomalyCode::class.java, subtype, null) != null) return subtype
        return if (titleKey(event) in MALFORMED_TITLES) event.anomalies.firstOrNull() else subtype
    }

    private fun appLabel(event: EventFacts): String {
        if (event.classId == ClassId.NORMAL && event.fate == Fate.SHOWN) {
            return defaultSmsAppLabel() ?: strings.get(TextKey.GENERIC_DEFAULT_SMS_APP)
        }
        val pkg = event.namedPackage ?: return strings.get(TextKey.GENERIC_UNKNOWN_APP)
        return labels.labelOf(pkg) ?: pkg
    }

    /**
     * The placeholder values of [event]. Each value is computed only when a template asks for it: the app label is a
     * package manager call and most templates do not use it.
     */
    private fun args(event: EventFacts): Map<String, String> {
        val unknown by lazy { strings.get(TextKey.GENERIC_UNKNOWN) }
        // CL-MO-DATA carries its port and CL-WAP-OTHER its content type as the subtype (Subtype.isFreeForm).
        val port = event.destPort ?: event.subtype?.takeIf { event.classId == ClassId.MO_DATA }?.toIntOrNull()
        val contentType = event.contentType ?: event.subtype?.takeIf { event.classId == ClassId.WAP_OTHER }
        return LazyArgs(
            mapOf(
                "port" to { port?.toString() ?: unknown },
                "contentType" to { contentType ?: unknown },
                "package" to { event.consumerPackage ?: event.callingPackage ?: event.packageSubtype ?: unknown },
                "app" to { appLabel(event) },
                "number" to { number(event.destination ?: event.originator) },
                "detail" to { event.fateDetail[EventFacts.FATE_DETAIL_OEM_REASON] ?: unknown },
                "rule" to { ruleLabel(event) },
                "date" to { dates.date(event.receivedAtWallMs) },
                "code" to { event.fateDetail[EventFacts.FATE_DETAIL_ERROR_CODE] ?: unknown },
                "n" to { event.missingParts.toString() },
                "channel" to { event.cb?.messageId?.toString() ?: unknown },
                // "SIM 1" for slot 0; the notification line and the CB/port copy share it.
                "sim" to { sim(event) },
                "anomaly" to { anomalyArg(event, unknown) },
                ARG_ANOMALIES to { event.anomalies.joinToString(", ") { anomalyName(it) }.ifEmpty { unknown } },
                "replaceType" to { replaceType(event, unknown) },
                "types" to { extraTypes(event) },
            ),
        )
    }

    /**
     * The anomaly a malformed title names: the anomaly subtype of CL-MALFORMED-SMS and CL-WAP-UNREADABLE, else the
     * first anomaly. A stored push whose content type could not be read names AN-WSP-CTYPE.
     */
    private fun anomalyArg(
        event: EventFacts,
        unknown: String,
    ): String {
        val subtypeCode = event.subtype?.takeIf { Wire.parse(AnomalyCode::class.java, it, null) != null }
        val code = subtypeCode ?: event.anomalies.firstOrNull()
        return when {
            code != null -> anomalyName(code)
            event.classId == ClassId.WAP_OTHER && event.wapContentTypeUnreadable() -> strings.get(TextKey.ANOMALY_WSP_CTYPE)
            else -> unknown
        }
    }

    /** CL-REPLACE subtypes TYPE1 to TYPE7 (TP-PID 0x41 to 0x47) give the replace type 1 to 7. */
    private fun replaceType(
        event: EventFacts,
        unknown: String,
    ): String {
        val code = event.subtype
        if (event.classId != ClassId.REPLACE || code == null) return unknown
        val index = Subtype.known(ClassId.REPLACE).indexOf(code)
        return if (index < 0) unknown else (index + 1).toString()
    }

    /**
     * The names of the other types the message matches, read with [ExtraSubtype.parse]. An entry with an
     * unknown class is left out.
     */
    private fun extraTypes(event: EventFacts): String =
        event.extraSubtypes
            .mapNotNull { entry ->
                val extra = ExtraSubtype.parse(entry, event.classId)
                // The generic name: the values of this event do not belong to the other type.
                extra.classId?.let {
                    val key =
                        CbCategory.titleKey(it, extra.subtype, null)
                            ?: ClassRegistry.subtype(it, extra.subtype)?.titleKey
                            ?: ClassRegistry.of(it).titleKey
                    strings.genericTitle(key)
                }
            }.distinct()
            .joinToString(", ")

    private fun ruleLabel(event: EventFacts): String {
        event.ruleName?.let { return it }
        val id = event.ruleId ?: return strings.get(TextKey.GENERIC_UNKNOWN)
        return render(TextKey.GENERIC_UNKNOWN_RULE, mapOf("id" to id.toString()))
    }

    companion object {
        /** Detector arguments that hold a phone number: masked like the event's own number (privacy.maskNumbers). */
        private val NUMBER_ARGS = setOf("number")

        private const val ARG_ANOMALIES = "anomalies" // contract-ok: template placeholder {anomalies}, not an IPC key

        /** Titles of a message that NightJar cannot read: the reason line says "malformed". */
        private val MALFORMED_TITLES = setOf(TextKey.TITLE_CL_WAP_UNREADABLE, TextKey.TITLE_CL_MALFORMED_SMS)

        /** The name of each anomaly code. One branch per [AnomalyCode], so a new code cannot ship without a name. */
        @Suppress("CyclomaticComplexMethod")
        fun anomalyKey(anomaly: AnomalyCode): TextKey =
            when (anomaly) {
                AnomalyCode.ADDR_TRUNC -> TextKey.ANOMALY_ADDR_TRUNC
                AnomalyCode.ADDR_LEN -> TextKey.ANOMALY_ADDR_LEN
                AnomalyCode.ADDR_TOA -> TextKey.ANOMALY_ADDR_TOA
                AnomalyCode.PDU_SHORT -> TextKey.ANOMALY_PDU_SHORT
                AnomalyCode.MTI -> TextKey.ANOMALY_MTI
                AnomalyCode.PID_RSV -> TextKey.ANOMALY_PID_RSV
                AnomalyCode.DCS_RSV -> TextKey.ANOMALY_DCS_RSV
                AnomalyCode.UDL_LONG -> TextKey.ANOMALY_UDL_LONG
                AnomalyCode.UDL_SHORT -> TextKey.ANOMALY_UDL_SHORT
                AnomalyCode.UDH_LEN -> TextKey.ANOMALY_UDH_LEN
                AnomalyCode.IE_OVERRUN -> TextKey.ANOMALY_IE_OVERRUN
                AnomalyCode.IE_RSV -> TextKey.ANOMALY_IE_RSV
                AnomalyCode.IE_DUP -> TextKey.ANOMALY_IE_DUP
                AnomalyCode.PORT_ZERO -> TextKey.ANOMALY_PORT_ZERO
                AnomalyCode.CONCAT_BAD -> TextKey.ANOMALY_CONCAT_BAD
                AnomalyCode.CONCAT_DUP -> TextKey.ANOMALY_CONCAT_DUP
                AnomalyCode.WSP_TYPE -> TextKey.ANOMALY_WSP_TYPE
                AnomalyCode.WSP_HLEN -> TextKey.ANOMALY_WSP_HLEN
                AnomalyCode.WSP_CTYPE -> TextKey.ANOMALY_WSP_CTYPE
                AnomalyCode.WSP_APPID -> TextKey.ANOMALY_WSP_APPID
                AnomalyCode.WBXML_VER -> TextKey.ANOMALY_WBXML_VER
                AnomalyCode.WBXML_STR -> TextKey.ANOMALY_WBXML_STR
                AnomalyCode.WBXML_TOK -> TextKey.ANOMALY_WBXML_TOK
                AnomalyCode.OTA_HDR -> TextKey.ANOMALY_OTA_HDR
            }

        /** Default [DateFormatter]: ISO date in the current device zone, enough for tests and previews. */
        object IsoDates : DateFormatter {
            override fun date(wallMs: Long): String =
                DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(wallMs).atZone(ZoneId.systemDefault()))
        }

        fun fateKey(fate: Fate): TextKey =
            when (fate) {
                Fate.SHOWN -> TextKey.FATE_TXT_SHOWN
                Fate.APP -> TextKey.FATE_TXT_APP
                Fate.NO_RECEIVER -> TextKey.FATE_TXT_NO_RECEIVER
                Fate.VVM -> TextKey.FATE_TXT_VVM
                Fate.CARRIER -> TextKey.FATE_TXT_CARRIER
                Fate.MISSED_CALL -> TextKey.FATE_TXT_MISSED_CALL
                Fate.SYSTEM -> TextKey.FATE_TXT_SYSTEM
                Fate.SIM -> TextKey.FATE_TXT_SIM
                Fate.OEM_DROPPED -> TextKey.FATE_TXT_OEM_DROPPED
                Fate.OEM_REDIRECTED -> TextKey.FATE_TXT_OEM_REDIRECTED
                Fate.BLOCKLIST -> TextKey.FATE_TXT_BLOCKLIST
                Fate.BLOCKED -> TextKey.FATE_TXT_BLOCKED
                Fate.QUARANTINED -> TextKey.FATE_TXT_QUARANTINED
                Fate.RELEASED -> TextKey.FATE_TXT_RELEASED
                Fate.NACK_STORAGE -> TextKey.FATE_TXT_NACK_STORAGE
                Fate.INCOMPLETE -> TextKey.FATE_TXT_INCOMPLETE
                Fate.PENDING_UNLOCK -> TextKey.FATE_TXT_PENDING_UNLOCK
                Fate.SENT -> TextKey.FATE_TXT_SENT
                Fate.SEND_FAILED -> TextKey.FATE_TXT_SEND_FAILED
                Fate.REJECTED -> TextKey.FATE_TXT_REJECTED
                Fate.ACCEPTED -> TextKey.FATE_TXT_ACCEPTED
                Fate.NOT_DISPATCHED -> TextKey.FATE_TXT_NOT_DISPATCHED
                Fate.PHONE_STOPPED -> TextKey.FATE_TXT_PHONE_STOPPED
                Fate.UNLOCK_EXPIRED -> TextKey.FATE_TXT_UNLOCK_EXPIRED
                Fate.NOT_SENT -> TextKey.FATE_TXT_NOT_SENT
                Fate.SEND_UNCONFIRMED -> TextKey.FATE_TXT_SEND_UNCONFIRMED
                Fate.UNKNOWN -> TextKey.FATE_TXT_UNKNOWN
            }

        /** First sentence of [text], including its full stop (BigText). */
        fun firstSentence(text: String): String {
            val cut = text.indexOf(". ")
            return if (cut < 0) text else text.substring(0, cut + 1)
        }
    }
}

// Facts read from the event alone. They are extension properties outside [Explainer], so the class keeps only the
// functions that need its strings, labels and settings.

/** The subtype code: the payload code, or the port table entry for a port class (rows from before). */
private val EventFacts.planSubtype: String?
    get() = ClassRegistry.effectiveSubtype(classId, subtype, destPort)

/** The package that sent, added or received the message, or null when the event names none. */
private val EventFacts.namedPackage: String?
    get() = callingPackage ?: consumerPackage ?: packageSubtype

/** The package name that CL-INJECTED and the app-sent classes carry as their subtype ([Subtype.isFreeForm]). */
private val EventFacts.packageSubtype: String?
    get() = subtype?.takeIf { classId in PACKAGE_SUBTYPE_CLASSES }

/** The number of parts of a concatenated message that did not arrive; 0 for a single-part message. */
private val EventFacts.missingParts: Int
    get() = concat?.let { (it.total - it.have).coerceAtLeast(0) } ?: 0

private val PACKAGE_SUBTYPE_CLASSES = setOf(ClassId.INJECTED, ClassId.MO_VISIBLE, ClassId.MO_UNPERSISTED, ClassId.MO_RAW)

/** Placeholder values that are computed on the first read and then kept. Not thread-safe: one render at a time. */
private class LazyArgs(
    private val providers: Map<String, () -> String>,
) : AbstractMap<String, String>() {
    // Not named `values`: that would hide AbstractMap.values (VR01-DOM01).
    private val cache = HashMap<String, String>()

    override fun get(key: String): String? = providers[key]?.let { cache.getOrPut(key, it) }

    override fun containsKey(key: String): Boolean = key in providers

    override val entries: Set<Map.Entry<String, String>>
        get() = providers.keys.associateWith { getValue(it) }.entries
}
