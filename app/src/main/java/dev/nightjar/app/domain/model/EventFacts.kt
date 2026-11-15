package dev.nightjar.app.domain.model

import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.FateDetail
import dev.nightjar.contract.Transport
import dev.nightjar.contract.Verdict
import dev.nightjar.contract.Wire

/** Concatenation state of a multipart message. */
data class ConcatInfo(
    val ref: Int,
    val total: Int,
    val have: Int,
)

/** Cell-broadcast header fields. */
data class CbInfo(
    val serial: Int,
    val messageId: Int,
    val page: Int,
    val pages: Int,
    val emergency: Boolean,
)

/** Event flags from the payload (`flags`). */
data class EventFlags(
    val selfTest: Boolean = false,
    val runId: String? = null,
    val canaryClass: ClassId? = null,
    val replay: Boolean = false,
    val releaseOf: String? = null,
    val debug: Boolean = false,
) {
    /** A self test, a replay, a released copy or a legacy debug-flagged row: a stored message, but not new traffic. */
    val notNewTraffic: Boolean
        get() = selfTest || replay || debug || releaseOf != null
}

/**
 * The classification view of one captured event.
 *
 * This is a **domain** type, deliberately not the Room entity and not the IPC DTO: the classification
 * layer must stay testable on the JVM and must not depend on either. `EventFactsMapper` (ingest) converts the
 * parsed DTO into this type; unknown or absent fields keep their defaults, so a newer payload never breaks ingest.
 */
data class EventFacts(
    val eventId: String,
    val direction: Direction,
    val transport: Transport,
    val source: Int,
    val classId: ClassId,
    val fate: Fate,
    val receivedAtWallMs: Long,
    val revision: Int = 1,
    val sessionId: String = "",
    val subId: Int = -1,
    val slot: Int = -1,
    val carrierName: String? = null,
    /** Originator as received; never logged unmasked. */
    val originator: String? = null,
    val smsc: String? = null,
    val destination: String? = null,
    val destPort: Int? = null,
    val contentType: String? = null,
    /** Decoded text when the class has one. Never stored for CL-NORMAL. */
    val text: String? = null,
    val modemTsMs: Long? = null,
    val roaming: Boolean = false,
    val userUnlocked: Boolean = true,
    val callingPackage: String? = null,
    val attributionTag: String? = null,
    val persistFlag: Boolean? = null,
    val fateDetail: Map<String, String> = emptyMap(),
    val verdict: Verdict = Verdict.ALLOW,
    val ruleId: Long? = null,
    val ruleName: String? = null,
    val concat: ConcatInfo? = null,
    val cb: CbInfo? = null,
    val flags: EventFlags = EventFlags(),
    val buildFingerprint: String? = null,
    val payloadVersion: String? = null,
    /** Subtype code of [classId] (`Subtype`), or null when the class has none or the payload sent IPC version 1. */
    val subtype: String? = null,
    /**
     * The other detection rules that matched, in rule order: a bare subtype of [classId], `"<class wire>:<subtype>"`
     * for another class, or `"<class wire>"` for a rule without a subtype. [ExtraSubtype.parse] reads one entry.
     */
    val extraSubtypes: List<String> = emptyList(),
    /** `AnomalyCode` wire names in parser order. A code that this app does not know stays in the list. */
    val anomalies: List<String> = emptyList(),
) {
    /** True when the message reached this phone from the network (coverage). */
    val fromNetwork: Boolean
        get() =
            !flags.notNewTraffic && source != SOURCE_INJECTED_FROM_UNKNOWN

    /** True for an outgoing send that the outgoing-block gate stopped because the user blocked the app. */
    val blockedByOutgoingGate: Boolean
        get() =
            direction == Direction.OUT &&
                fate == Fate.BLOCKED &&
                fateDetail[FATE_DETAIL_REASON]?.let { it == REASON_OUTGOING_BLOCK || it.startsWith("$REASON_OUTGOING_BLOCK ") } == true

    /** Package that consumed or produced the message, when the payload reported one. */
    val consumerPackage: String?
        get() = fateDetail[FATE_DETAIL_PACKAGE] ?: callingPackage

    companion object {
        // Fate detail keys used by the app (`fateDetail`). The keys come from:contract FateDetail.
        const val FATE_DETAIL_PACKAGE = FateDetail.PACKAGE
        const val FATE_DETAIL_OEM_REASON = FateDetail.OEM_REASON
        const val FATE_DETAIL_ERROR_CODE = FateDetail.ERROR_CODE
        const val FATE_DETAIL_REASON = FateDetail.REASON

        /**
         * [FATE_DETAIL_REASON] of a send that the outgoing-block gate stopped: exactly this value, or this
         * value, a space and more detail.
         */
        const val REASON_OUTGOING_BLOCK = FateDetail.OUTBOUND_BLOCK

        /**
         * [source] of a PDU that an app injected (`InboundSmsHandler.SOURCE_INJECTED_FROM_UNKNOWN`): it did not come
         * from the network.
         */
        const val SOURCE_INJECTED_FROM_UNKNOWN = 2
    }
}

/**
 * One entry of [EventFacts.extraSubtypes], in the format described on [EventFacts.extraSubtypes].
 *
 * [classId] is null when the entry names a class this app does not know; [raw] keeps the entry for that case.
 */
data class ExtraSubtype(
    val classId: ClassId?,
    val subtype: String?,
    val raw: String,
) {
    companion object {
        /**
         * Reads [entry] against the event's own class [eventClass]:
         * - `"<class wire>:<subtype>"` is a subtype of another class (split at the first colon: a class wire has none);
         * - a bare class wire is a rule of that class without a subtype;
         * - an entry that starts with the class prefix but names no known class gives a null [classId];
         * - anything else is a bare subtype of [eventClass]. A free-form subtype (a content type) can contain a colon,
         *   so a prefix that does not look like a class wire does not count as a class.
         */
        fun parse(
            entry: String,
            eventClass: ClassId,
        ): ExtraSubtype {
            val colon = entry.indexOf(':')
            if (colon > 0 && entry.startsWith(CLASS_WIRE_PREFIX)) {
                val classId = Wire.parse(ClassId::class.java, entry.substring(0, colon), null)
                return ExtraSubtype(classId, entry.substring(colon + 1).ifEmpty { null }, entry)
            }
            val classOnly = Wire.parse(ClassId::class.java, entry, null)
            return when {
                classOnly != null -> ExtraSubtype(classOnly, null, entry)
                entry.startsWith(CLASS_WIRE_PREFIX) -> ExtraSubtype(null, null, entry)
                else -> ExtraSubtype(eventClass, entry, entry)
            }
        }

        /** Every `ClassId` wire name starts with this text. */
        private const val CLASS_WIRE_PREFIX = "CL-"
    }
}

/** Emergency-call state reported by the payload. */
data class EmergencyCallState(
    val active: Boolean = false,
    /** Wall-clock time the last emergency call ended, or null when none ended since boot. */
    val endedAtWallMs: Long? = null,
) {
    companion object {
        val NONE = EmergencyCallState()
    }
}

/**
 * True for a WAP push whose content type could not be read: none, blank or "unknown" (any case). Such a push is
 * malformed (CL-WAP-UNREADABLE), not a "push (unknown)". The titles and the severity floor share this test.
 */
fun EventFacts.wapContentTypeUnreadable(): Boolean {
    val contentType = contentType ?: subtype?.takeIf { classId == ClassId.WAP_OTHER }
    return contentType.isNullOrBlank() || contentType.equals(UNKNOWN_CONTENT_TYPE, ignoreCase = true)
}

/** The content type the payload writes when the WSP header has none it can read. */
private const val UNKNOWN_CONTENT_TYPE = "unknown"
