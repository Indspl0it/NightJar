package dev.nightjar.app.core.ipc

import dev.nightjar.app.data.repository.IncomingEvent
import dev.nightjar.app.data.repository.PduPayload
import dev.nightjar.app.data.repository.TracePayload
import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.ingest.TrueFate
import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.app.domain.model.CbInfo
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.app.domain.text.Explainer
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.FateDetail
import dev.nightjar.contract.Ipc
import dev.nightjar.contract.Transport
import dev.nightjar.contract.Verdict
import dev.nightjar.contract.Wire
import org.json.JSONArray
import org.json.JSONObject

/** A counter event from the payload: `{day, subId, direction, classId, n}`. */
data class CounterEvent(
    val day: Int,
    val subId: Int,
    val direction: Direction,
    val classId: ClassId,
    val count: Int,
)

/** Parsed `hello` extras. */
data class HelloRequest(
    val ipcVersion: Int,
    val payloadVersion: String,
    val buildFingerprint: String?,
    val bootCount: Int?,
    val droppedCount: Long,
    val userUnlocked: Boolean,
)

/** Parsed `hookHealth` extras (C.2/C.8). */
data class HookHealthRequest(
    val sessionId: String?,
    val hookHealthJson: String?,
    val droppedCount: Long,
)

/**
 * Parses the IPC JSON into the store's row type.
 *
 * The payload is the only writer and it runs inside `com.android.phone`; a malformed field is still treated as
 * untrusted input: values are clamped to the contract enums and unknown keys are ignored, so a newer payload
 * cannot crash ingest.
 */
object EventJson {
    /** Parses a batch. Returns the parsed events in payload order; malformed items are skipped, never thrown. */
    fun parseBatch(
        json: String?,
        title: (EventJsonFields) -> String,
    ): List<IncomingEvent> {
        if (json.isNullOrBlank()) return emptyList()
        val array =
            runCatching { JSONArray(json) }.getOrNull()
                ?: return emptyList()
        val out = ArrayList<IncomingEvent>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            runCatching { parseEvent(o, title) }.getOrNull()?.let(out::add)
        }
        return out
    }

    /** Fields the title needs, exposed so the caller can render it with the shared [Explainer] catalogue. */
    data class EventJsonFields(
        val classId: ClassId,
        val originator: String?,
        val destination: String?,
        val destPort: Int? = null,
        val contentType: String? = null,
        val callingPackage: String? = null,
        val consumerPackage: String? = null,
        val direction: Direction = Direction.IN,
        val cbMessageId: Int? = null,
        val cbEmergency: Boolean = false,
        /** IPC version 2 fields: null and empty for a version 1 event. */
        val subtype: String? = null,
        val extraSubtypes: List<String> = emptyList(),
        val anomalies: List<String> = emptyList(),
        val cbSerial: Int? = null,
        /** APPDATA-R21: the payload sent a class id that this app does not know; [classId] is the visible fallback. */
        val unknownClass: Boolean = false,
        /**
         * Every non-null `fateDetail` key as text (for example the reason, package and class of an outgoing send
         * the gate blocked), so the title at ingest reads the same facts as the stored row (StoredFacts).
         */
        val fateDetail: Map<String, String> = emptyMap(),
    )

    /** The facts the title and explanation templates read, built from what a title can know at ingest. */
    fun factsFor(fields: EventJsonFields): EventFacts =
        EventFacts(
            eventId = "",
            direction = fields.direction,
            transport = Transport.CS,
            source = -1,
            classId = fields.classId,
            fate = Fate.UNKNOWN,
            receivedAtWallMs = 0L,
            originator = fields.originator,
            destination = fields.destination,
            destPort = fields.destPort,
            contentType = fields.contentType,
            callingPackage = fields.callingPackage,
            fateDetail =
                fields.consumerPackage
                    ?.takeIf { FateDetail.PACKAGE !in fields.fateDetail }
                    ?.let { fields.fateDetail + (FateDetail.PACKAGE to it) }
                    ?: fields.fateDetail,
            cb =
                fields.cbMessageId?.let {
                    CbInfo(serial = fields.cbSerial ?: 0, messageId = it, page = 1, pages = 1, emergency = fields.cbEmergency)
                },
            subtype = fields.subtype,
            extraSubtypes = fields.extraSubtypes,
            anomalies = fields.anomalies,
        )

    fun parseEvent(
        o: JSONObject,
        title: (EventJsonFields) -> String,
    ): IncomingEvent? {
        val eventId = o.optText("eventId") ?: return null
        val flags = o.optJSONObject("flags")
        val env = o.optJSONObject("env")
        val fateDetail = o.optJSONObject("fateDetail")
        val concat = o.optJSONObject("concat")
        val fields = fieldsOf(o, fateDetail)
        val direction = fields.direction
        val finalClass = fields.classId
        val verdict = verdictOf(o)
        val pdus = o.optJSONArray("pdus").toByteArrays()
        val format = o.optText("format") ?: FORMAT_3GPP
        val ackResult = o.optIntOrNull("ackResult")
        val replay = flags?.optBoolean("replay", false) ?: false
        // FT-UNKNOWN (an older payload, or no fate at all) is stored as the true fate the event shows.
        val fate =
            TrueFate.of(
                Wire.parse(Fate::class.java, o.optText("fate"), Fate.UNKNOWN),
                direction,
                ackResult,
                replay = replay,
                noDispatch = fateDetail?.optBoolean(FateDetail.NO_DISPATCH, false) ?: false,
            )
        val selfTest = flags?.optBoolean("selfTest", false) ?: false
        // The event has no text field; the text comes from the PDUs. a shown normal SMS keeps no text.
        val decoded = PduText.decode(pdus, format)
        val keepText = !(finalClass == ClassId.NORMAL && fate == Fate.SHOWN)
        val text = if (keepText) decoded.text else null
        return IncomingEvent(
            eventId = eventId,
            revision = (o.optIntOrNull("revision") ?: 1).coerceAtLeast(1),
            sessionId = o.optText(Ipc.Key.SESSION_ID),
            direction = direction.wire(),
            transport = Wire.parse(Transport::class.java, o.optText("transport"), Transport.CS).wire(),
            source = o.optIntOrNull("source") ?: -1,
            subId = o.optIntOrNull("subId") ?: -1,
            slot = o.optIntOrNull("slot") ?: -1,
            carrierName = o.optText("carrierName"),
            classId = finalClass.wire(),
            // Severity is the app's decision; ingest starts from the subtype row (subtype, CB channel) and the
            // pipeline refines it.
            severity = ClassRegistry.defaultSeverity(finalClass, fields.subtype, fields.cbMessageId).wire(),
            detectorSeverity = null,
            severityOverridden = false,
            title = title(fields),
            originator = fields.originator,
            smsc = o.optText("smsc"),
            destPort = fields.destPort,
            contentType = fields.contentType,
            textBody = text,
            textPreview = preview(text),
            receivedAtWall = o.optLongOrNull("receivedAtWall") ?: 0L,
            receivedAtElapsedNs = o.optLongOrNull("receivedAtElapsedNs") ?: 0L,
            // The payload does not send `modemTs`; TP-SCTS of the first part is the same value.
            modemTs = o.optLongOrNull("modemTs") ?: decoded.modemTs,
            ackResult = ackResult,
            verdict = verdict.action.wire(),
            ruleId = verdict.ruleId,
            fate = fate.wire(),
            fateDetail = fateDetail?.toString(),
            consumerPackage = fields.consumerPackage,
            callingPackage = fields.callingPackage,
            persistFlag = o.opt("persistFlag") as? Boolean,
            concatRef = concat?.optIntOrNull("ref")?.takeIf { it >= 0 },
            partsTotal = (concat?.optIntOrNull("total") ?: 1).coerceAtLeast(1),
            partsHave = (concat?.optIntOrNull("have") ?: 1).coerceAtLeast(1),
            // `[{seq,tNs}]` of the parts that arrived.
            partsJson = o.optJSONArray("parts")?.takeIf { it.length() > 0 }?.toString(),
            // An orphan multipart group carries the time when the payload purges it.
            purgeAt = fateDetail?.optLongOrNull("purgeAt")?.takeIf { it > 0 },
            isQuarantined = fate == Fate.QUARANTINED || verdict.action == Verdict.QUARANTINE,
            isReviewed = false,
            isStarred = false,
            isReplay = replay,
            isSelfTest = selfTest,
            // The store resolves `releaseOf` to the row id of the original.
            releasedFromId = null,
            buildFingerprint = o.optText(Ipc.Key.BUILD_FINGERPRINT),
            payloadVersion = o.optText(Ipc.Key.PAYLOAD_VERSION),
            pdus = pdus.mapIndexed { index, bytes -> PduPayload(index + 1, bytes, format) },
            trace = o.optJSONArray("trace").toTrace(),
            runId = flags?.optText("runId"),
            canaryClass = flags?.optText("canaryClass"),
            // A delivery report links to the sent message with the same TP-MR and destination.
            tpMr = if (direction == Direction.OUT) o.optIntOrNull("messageRef")?.takeIf { it >= 0 } else null,
            // The same normalization as originatorNorm, so the detector and report adapters compare equal values.
            destinationNorm = if (direction == Direction.OUT) KnownOriginatorMatcher.normalize(fields.destination) else null,
            subtype = fields.subtype,
            extraSubtypes = fields.extraSubtypes,
            anomalies = fields.anomalies,
            destination = fields.destination,
            roaming = env?.opt("roaming") as? Boolean,
            userUnlocked = env?.opt(Ipc.Key.USER_UNLOCKED) as? Boolean,
            cbMessageId = fields.cbMessageId,
            cbSerial = fields.cbSerial,
            releaseOf = flags?.optText("releaseOf"),
            clampedFrom = verdict.clampedFrom?.wire(),
            clampReason = verdict.clampReason,
            // Header fields only (the payload never sends the secured data).
            simOtaHeader = o.optJSONObject(Ipc.EventKey.SIM_OTA_HEADER)?.toString(),
            emergencyCallActive = env?.opt("emergencyCallActive") as? Boolean,
            // Legacy flag of a removed debug injector. Always null in current builds; kept so existing
            // databases keep their schema.
            debugInjected = (flags?.opt("debug") as? Boolean)?.takeIf { it },
        )
    }

    /**
     * The title facts of [o]: the class (the fallback), the addresses, the CB header and the IPC version 2
     * fields. [fateDetail] is the event's `fateDetail` object, read once by the caller.
     */
    private fun fieldsOf(
        o: JSONObject,
        fateDetail: JSONObject?,
    ): EventJsonFields {
        val direction = Wire.parse(Direction::class.java, o.optText("direction"), Direction.IN)
        val transport = Wire.parse(Transport::class.java, o.optText("transport"), Transport.CS)
        val knownClass =
            Wire.parse(ClassId::class.java, o.optText("finalClass"), null)
                ?: Wire.parse(ClassId::class.java, o.optText("provisionalClass"), null)
        val classSent = o.optText("finalClass") != null || o.optText("provisionalClass") != null
        val cbObject = o.optJSONObject("cb")
        return EventJsonFields(
            classId = knownClass ?: unknownClass(o, direction, transport),
            originator = o.optText("originator"),
            destination = o.optText("destination"),
            destPort = o.optIntOrNull("destPort")?.takeIf { it >= 0 },
            contentType = o.optText("contentType"),
            callingPackage = o.optText("callingPackage"),
            consumerPackage = fateDetail?.optText(FateDetail.PACKAGE),
            direction = direction,
            cbMessageId = cbObject?.optIntOrNull("messageId"),
            cbEmergency = cbObject?.optBoolean("emergency", false) ?: false,
            // IPC version 2: a version 1 payload omits the keys, which gives null and empty lists.
            subtype = o.optText(Ipc.EventKey.SUBTYPE),
            extraSubtypes = o.optJSONArray(Ipc.EventKey.EXTRA_SUBTYPES).toTexts(),
            anomalies = o.optJSONArray(Ipc.EventKey.ANOMALIES).toTexts(),
            cbSerial = cbObject?.optIntOrNull("serial"),
            unknownClass = knownClass == null && classSent,
            fateDetail = fateDetail.toTextMap(),
        )
    }

    /** The `verdict` of an event: an object (C.3) or, from an older payload, the action text alone. */
    private class VerdictFacts(
        val action: Verdict,
        val ruleId: Long?,
        val clampedFrom: Verdict?,
        val clampReason: String?,
    )

    private fun verdictOf(o: JSONObject): VerdictFacts {
        val raw = o.opt("verdict")
        val obj = raw as? JSONObject
        return VerdictFacts(
            action = Wire.parse(Verdict::class.java, obj?.optText("action") ?: raw as? String, Verdict.ALLOW),
            ruleId = obj?.optLongOrNull("ruleId")?.takeIf { it >= 0 },
            clampedFrom = obj?.optText("clampedFrom")?.let { Wire.parse(Verdict::class.java, it, null) },
            clampReason = obj?.optText("clampReason"),
        )
    }

    /**
     * A class id that this app does not know (a newer payload) must stay visible. CL-NORMAL is counter-only
     * and would hide the message. A cell broadcast stays a CB message, a sent message is a raw send, and any other
     * received message shows as handled by the phone software (CL-OEM) until the app knows the new class.
     */
    private fun unknownClass(
        o: JSONObject,
        direction: Direction,
        transport: Transport,
    ): ClassId {
        val sent = o.optText("finalClass") ?: o.optText("provisionalClass")
        return when {
            sent == null -> ClassId.NORMAL
            transport == Transport.CB -> ClassId.CB
            direction == Direction.OUT -> ClassId.MO_RAW
            else -> ClassId.OEM
        }
    }

    /** Preview is derived and capped at 120 characters. */
    fun preview(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val collapsed = text.replace('\n', ' ').replace('\r', ' ').trim()
        if (collapsed.length <= 120) return collapsed
        var cut = 119
        if (Character.isHighSurrogate(collapsed[cut - 1])) cut--
        return collapsed.substring(0, cut) + "…"
    }

    /** Counters. Malformed rows are skipped; the caller is acknowledged either way. */
    fun parseCounters(json: String?): List<CounterEvent> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<CounterEvent>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val classId = Wire.parse(ClassId::class.java, o.optString("classId", null), null) ?: continue
            out +=
                CounterEvent(
                    day = o.optInt("day", 0),
                    subId = o.optInt("subId", -1),
                    direction = Wire.parse(Direction::class.java, o.optString("direction", null), Direction.IN),
                    classId = classId,
                    count = o.optInt("n", 1).coerceAtLeast(0),
                )
        }
        return out
    }

    /** `hello` extras. Unknown fields are ignored; a missing version is treated as the current one. */
    fun parseHello(
        ipcVersion: Int,
        payloadVersion: String?,
        buildFingerprint: String?,
        bootCount: Int,
        droppedCount: Long,
        userUnlocked: Boolean,
    ): HelloRequest =
        HelloRequest(
            ipcVersion = if (ipcVersion > 0) ipcVersion else Ipc.VERSION,
            payloadVersion = payloadVersion ?: "0",
            buildFingerprint = buildFingerprint,
            bootCount = bootCount.takeIf { it >= 0 },
            droppedCount = droppedCount.coerceAtLeast(0),
            userUnlocked = userUnlocked,
        )

    /** A number field as an Int, or null when it is missing or not a number (`optInt` would give 0). */
    private fun JSONObject.optIntOrNull(key: String): Int? = (opt(key) as? Number)?.toInt()

    private fun JSONObject.optLongOrNull(key: String): Long? = (opt(key) as? Number)?.toLong()

    /** Format of a 3GPP TPDU (C.3 `format`); the default when an event has no format. */
    private const val FORMAT_3GPP = "3gpp"

    private fun JSONArray?.toByteArrays(): List<ByteArray> {
        if (this == null) return emptyList()
        val out = ArrayList<ByteArray>(length())
        for (i in 0 until length()) {
            val b64 = optString(i, "").ifEmpty { continue }
            // The payload writes `java.util.Base64` without line breaks (EventDto); the same decoder runs in JVM tests.
            runCatching {
                java.util.Base64
                    .getDecoder()
                    .decode(b64)
            }.getOrNull()?.let(out::add)
        }
        return out
    }

    /**
     * A string field, or null when it is missing, empty, JSON null or not a string. `optString` turns JSON null into the
     * text "null", so it cannot be used here.
     */
    private fun JSONObject.optText(key: String): String? =
        (opt(key) as? String)
            ?.take(MAX_CODE_CHARS)
            ?.ifEmpty { null }

    /** The string entries of an array, in order. Other entries are skipped. The count and the length are capped. */
    private fun JSONArray?.toTexts(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(minOf(length(), MAX_CODES))
        for (i in 0 until length()) {
            if (out.size == MAX_CODES) break
            val text = (opt(i) as? String)?.take(MAX_CODE_CHARS)?.ifEmpty { null } ?: continue
            out += text
        }
        return out
    }

    /**
     * `fateDetail` as text pairs, the same reading as StoredFacts: every non-null scalar becomes its text, nested
     * objects and arrays are skipped. The count and the length are capped.
     */
    private fun JSONObject?.toTextMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in keys()) {
            if (out.size == MAX_CODES) break
            val value = opt(key)?.takeUnless { it == JSONObject.NULL } ?: continue
            if (value is JSONObject || value is JSONArray) continue
            out[key.take(MAX_CODE_CHARS)] = value.toString().take(MAX_CODE_CHARS)
        }
        return out
    }

    /** Caps for the subtype and anomaly fields: far above what the parser sends, low enough to keep a row small. */
    private const val MAX_CODES = 32
    private const val MAX_CODE_CHARS = 160

    private fun JSONArray?.toTrace(): List<TracePayload> {
        if (this == null) return emptyList()
        val out = ArrayList<TracePayload>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            out +=
                TracePayload(
                    seq = i,
                    stage = o.optString("stage", ""),
                    tNs = o.optLong("tNs", 0),
                    detail = o.optString("detail", "").ifEmpty { null },
                )
        }
        return out
    }
}
