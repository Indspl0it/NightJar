package dev.nightjar.app.domain.detect

import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Severity

/**
 * The per-class detectors. Each one is a pure function of the event, the decoded facts and the
 * detector configuration, so the JVM tests need no database.
 *
 * A detector returns an empty list when it has nothing to say; the class default severity then stands.
 */
class ClassDetectors(
    private val config: DetectorConfig,
    private val history: DetectionHistory,
    private val knownOriginators: KnownOriginatorMatcher,
) {
    suspend fun evaluate(
        event: EventFacts,
        context: DetectContext,
    ): List<DetectionDraft> =
        when (event.classId) {
            ClassId.SIM_DD -> listOfNotNull(simOta(event, context))
            ClassId.WAP_CP -> listOfNotNull(omaCp(event, context))
            ClassId.WAP_SUPL -> listOfNotNull(supl(event, context))
            ClassId.INJECTED -> listOfNotNull(injection(event))
            ClassId.MO_DATA, ClassId.MO_RAW, ClassId.MO_STK, ClassId.MO_UNPERSISTED -> listOfNotNull(outbound(event))
            else -> emptyList()
        }

    // ------------------------------------------------------------------ DET-02 SIM OTA

    /**
     * Suspicious when the SPI shows neither a cryptographic checksum/signature nor ciphering, or when the TAR
     * is on the S@T/WIB list. Each sentence states only what the facts show. Without decoded facts the class default
     * (Suspicious) stands: guessing would either hide a real attack or cry wolf on every carrier update
     * .
     */
    private fun simOta(
        event: EventFacts,
        context: DetectContext,
    ): DetectionDraft? {
        val facts = context.simOta ?: return null
        val denylisted = config.tarDenylist.contains(facts.tar)
        val sentences =
            buildList {
                if (facts.insecure) add(TextKey.DET_SIM_OTA_INSECURE)
                if (denylisted) add(TextKey.DET_SIM_OTA_TAR)
            }
        if (sentences.isEmpty()) return null
        return draft(
            detectorId = DetectorId.SIM_OTA,
            event = event,
            severity = Severity.SUSPICIOUS,
            summaryKey = sentences.first(),
            notes = sentences.drop(1),
            args = mapOf("tar" to facts.tarHex),
        )
    }

    // ------------------------------------------------------------------ DET-03 OMA CP

    /** Suspicious unless the originator is a known carrier; the explanation lists APN, proxy and server. */
    private fun omaCp(
        event: EventFacts,
        context: DetectContext,
    ): DetectionDraft? {
        if (event.direction != Direction.IN) return null
        val knownCarrier = knownCarrier(event.originator)
        val notes = if (context.omaCp.isEmpty) emptyList() else listOf(TextKey.DET_OMA_CP_VALUES)
        return draft(
            detectorId = DetectorId.OMA_CP,
            event = event,
            severity = if (knownCarrier) Severity.INFO else Severity.SUSPICIOUS,
            summaryKey = if (knownCarrier) TextKey.DET_OMA_CP_KNOWN_CARRIER else TextKey.DET_OMA_CP_UNKNOWN,
            notes = notes,
            args =
                mapOf(
                    "apn" to (context.omaCp.apn ?: UNKNOWN),
                    "proxy" to (context.omaCp.proxy ?: UNKNOWN),
                    "server" to (context.omaCp.server ?: UNKNOWN),
                ),
        )
    }

    // ------------------------------------------------------------------ DET-04 SUPL

    /**
     * A location request is expected while an emergency call is active and for `detect.supl.graceMin` minutes
     * after it ends; outside that window nothing on the phone asked to be located, so it is Suspicious.
     */
    private fun supl(
        event: EventFacts,
        context: DetectContext,
    ): DetectionDraft {
        val withinGrace = context.withinEmergencyGrace(event.receivedAtWallMs, config.suplGraceMs)
        return draft(
            detectorId = DetectorId.SUPL,
            event = event,
            severity = if (withinGrace) Severity.INFO else Severity.SUSPICIOUS,
            summaryKey = if (withinGrace) TextKey.DET_SUPL_EMERGENCY else TextKey.DET_SUPL_UNEXPECTED,
            notes = emptyList(),
            args = emptyMap(),
        )
    }

    // ------------------------------------------------------------------ DET-05 outbound

    /**
     * A hidden outbound message is Suspicious when the destination is a short code (≤ 6 digits), matches a
     * premium pattern, or the calling package has never sent a message on this device.
     *
     * STK sends have no calling package, so the "never sent before" rule does not apply to them. Instead, the detector
     * treats a destination that the phone never sent to before as suspicious.
     */
    private suspend fun outbound(event: EventFacts): DetectionDraft? {
        val digits = event.destination?.filter(Char::isDigit).orEmpty()
        val shortCode = digits.isNotEmpty() && digits.length <= SHORT_CODE_MAX_DIGITS
        val premium = config.premium.matches(event.destination)
        val packageName = event.callingPackage
        val firstSend =
            packageName != null &&
                packageName != INTERNAL_PACKAGE &&
                !history.packageSentBefore(packageName, event.receivedAtWallMs)
        val stkUnknownDestination =
            event.classId == ClassId.MO_STK && !history.destinationSentBefore(event.destination, event.receivedAtWallMs)
        val suspicious = shortCode || premium || firstSend || stkUnknownDestination
        if (!suspicious) return null

        val notes =
            buildList {
                if (shortCode) add(TextKey.DET_OUTBOUND_SHORT_CODE)
                if (premium) add(TextKey.DET_OUTBOUND_PREMIUM)
                if (firstSend || stkUnknownDestination) add(TextKey.DET_OUTBOUND_FIRST_SEND)
            }
        return draft(
            detectorId = DetectorId.OUTBOUND,
            event = event,
            severity = Severity.SUSPICIOUS,
            summaryKey = notes.first(),
            notes = notes.drop(1),
            args = mapOf("number" to (event.destination ?: UNKNOWN)),
        )
    }

    // ------------------------------------------------------------------ DET-06 injection

    /** An app-inserted message is Suspicious; the pipeline turns it Info when the package is trusted. */
    private fun injection(event: EventFacts): DetectionDraft =
        draft(
            detectorId = DetectorId.INJECTION,
            event = event,
            severity = Severity.SUSPICIOUS,
            summaryKey = TextKey.DET_INJECTED_UNTRUSTED,
            notes = emptyList(),
            args = mapOf("package" to (event.callingPackage ?: UNKNOWN)),
        )

    // ------------------------------------------------------------------ helpers

    private fun draft(
        detectorId: DetectorId,
        event: EventFacts,
        severity: Severity,
        summaryKey: TextKey,
        notes: List<TextKey>,
        args: Map<String, String>,
    ): DetectionDraft =
        DetectionDraft(
            detectorId = detectorId,
            severity = severity,
            summaryKey = summaryKey,
            summaryArgs = args,
            eventIds = listOf(event.eventId),
            firstAtWallMs = event.receivedAtWallMs,
            lastAtWallMs = event.receivedAtWallMs,
            count = 1,
            notes = notes,
        )

    private fun knownCarrier(originator: String?): Boolean = knownOriginators.isCarrier(originator)

    companion object {
        const val SHORT_CODE_MAX_DIGITS = 6

        /** Phone-internal sends have no calling package; the payload reports this pseudo-package. */
        const val INTERNAL_PACKAGE = "android.phone.internal"

        /** A value the detector does not know. The explainer leaves this value out ([DetectionDraft.UNKNOWN]). */
        const val UNKNOWN = DetectionDraft.UNKNOWN
    }
}
