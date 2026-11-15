package dev.nightjar.app.domain.classify

import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.detect.DetectContext
import dev.nightjar.app.domain.detect.DetectionDraft
import dev.nightjar.app.domain.detect.DetectorId
import dev.nightjar.app.domain.detect.Detectors
import dev.nightjar.app.domain.known.TrustedApps
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.app.domain.model.wapContentTypeUnreadable
import dev.nightjar.app.domain.severity.SeverityConfig
import dev.nightjar.app.domain.severity.SeverityInput
import dev.nightjar.app.domain.severity.SeverityPipeline
import dev.nightjar.contract.AnomalyCode
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Verdict

/**
 * The result of classifying one event.
 *
 * @param severityOverridden true when `severity.<class>` supplied a starting severity other than the default of the
 *  row (column, [SeverityDecider.overridden])
 * @param detections every finding for this event, in detector order
 * @param primary the strongest detector finding, used for the detail paragraph and the notification. A severity floor
 *     can still decide the final [severity].
 */
data class Classification(
    val severity: Severity,
    val severityOverridden: Boolean,
    val detections: List<DetectionDraft>,
    val primary: DetectionDraft?,
)

/** The part of a detector finding that severity needs: which detector, and the severity it asked for. */
data class Finding(
    val detectorId: DetectorId,
    val severity: Severity,
)

/**
 * Applies the detectors and then the severity pipeline.
 *
 * The strongest finding becomes the detector's contribution, so a burst that has grown to Suspicious is not pulled
 * back down by a second, weaker finding on the same message.
 *
 * A suspicious pattern is a floor, applied after the pipeline like the evidence floor: the
 * known-sender downgrade cannot pull a High pattern back to Suspicious. Each pattern checks the sender itself where its
 * rule names one.
 */
class EventClassifier(
    private val detectors: Detectors,
    config: SeverityConfig,
) {
    private val decider = SeverityDecider(config)

    suspend fun classify(
        event: EventFacts,
        context: DetectContext = DetectContext.NONE,
    ): Classification {
        val detections = detectors.evaluate(event, context)
        return Classification(
            severity = decider.severityOf(event, context, detections.map { Finding(it.detectorId, it.severity) }),
            severityOverridden = decider.overridden(event),
            detections = detections,
            primary = detections.maxByOrNull { it.severity.ordinal },
        )
    }
}

/**
 * The severity of one event when its detector findings are known: the pipeline, then the evidence floor and the
 * pattern floor. Ingest ([EventClassifier]) and reclassification (`SeverityReclassifier`) share it, so a
 * settings change gives a stored row the severity that ingest would give it now.
 */
class SeverityDecider(
    private val config: SeverityConfig,
) {
    fun severityOf(
        event: EventFacts,
        context: DetectContext,
        findings: List<Finding>,
    ): Severity {
        val primary = findings.maxByOrNull { it.severity.ordinal }
        val subtype = ClassRegistry.effectiveSubtype(event.classId, event.subtype, event.destPort)
        // B4: one identity for trust (TrustedApps), the same one the Detail "Trust app" action stores. A trusted
        // candidate wins, so the calling package and the result package of one outgoing message both count.
        val trusted =
            TrustedApps.trustedPackage(
                config.trustedPackages,
                event.direction,
                event.classId,
                event.callingPackage,
                event.consumerPackage,
            )
        val packageName = trusted ?: TrustedApps.target(event.direction, event.classId, event.callingPackage, event.consumerPackage)
        val resolved =
            SeverityPipeline.resolve(
                config,
                SeverityInput(
                    classId = event.classId,
                    direction = event.direction,
                    originator = event.originator,
                    packageName = packageName,
                    simOtaInsecure = context.simOta?.insecure == true,
                    detectorSeverity = primary?.severity,
                    subtype = subtype,
                    cbMessageId = event.cb?.messageId,
                ),
            )
        // A trusted app's own traffic (outgoing, or injected by that app) is never raised by evidence about the message
        // shape. Trust does not cover inbound network messages, as in SeverityPipeline.
        val trustedOwnTraffic = trusted != null
        val evidence = if (trustedOwnTraffic) null else evidenceFloor(event, subtype, primary)
        val floor = listOfNotNull(evidence, patternFloor(findings)).maxOrNull()
        return if (floor == null || floor.ordinal <= resolved.ordinal) resolved else floor
    }

    /**
     * True when `severity.<class>` replaces the default of this row. The default is the one of the subtype and the CB
     * message id ([ClassRegistry.defaultSeverity]), so an override equal to that default is not an override
     * .
     */
    fun overridden(event: EventFacts): Boolean {
        val override = config.override(event.classId) ?: return false
        val subtype = ClassRegistry.effectiveSubtype(event.classId, event.subtype, event.destPort)
        return override != ClassRegistry.defaultSeverity(event.classId, subtype, event.cb?.messageId)
    }

    /**
     * What the message itself proves, independent of its class default:
     *  - a parse failure that matches a known crash pattern is High;
     *  - a subtype row with the severity High stays High, unless the user set a severity for the class or a
     *    detector proved the message expected (Info, for example a location request during an emergency call);
     *  - an anomaly makes the message Suspicious at least;
     *  - a push whose content type cannot be decoded is malformed, which is how the "SMS of death" family looks
     *    (Suspicious);
     *  - a push or port-addressed message NightJar had to block, or that no app could receive, is unsolicited traffic
     *    aimed at the phone rather than at an app (Notable at least).
     */
    private fun evidenceFloor(
        event: EventFacts,
        subtype: String?,
        primary: Finding?,
    ): Severity? =
        listOfNotNull(
            Severity.HIGH.takeIf { knownCrashPattern(event) },
            Severity.HIGH.takeIf { highRowStands(event, subtype, primary) },
            Severity.SUSPICIOUS.takeIf { event.anomalies.isNotEmpty() },
            pushFloor(event),
        ).maxOrNull()

    /** The strongest pattern finding, or null when no pattern fired. */
    private fun patternFloor(findings: List<Finding>): Severity? = findings.filter { it.detectorId.pattern }.maxOfOrNull { it.severity }

    /**
     * Only a detector that proves the message expected cancels a High row: the SUPL detector (DET-04) for a location
     * request during an emergency call. A known-sender result (for example "known carrier") does not, because a
     * sender can be spoofed.
     */
    private fun highRowStands(
        event: EventFacts,
        subtype: String?,
        primary: Finding?,
    ): Boolean =
        ClassRegistry.defaultSeverity(event.classId, subtype, event.cb?.messageId) == Severity.HIGH &&
            config.override(event.classId) == null &&
            !(primary?.detectorId == DetectorId.SUPL && primary.severity == Severity.INFO)

    private fun pushFloor(event: EventFacts): Severity? {
        if (event.direction != Direction.IN) return null
        // CL-WAP-OTHER is the class of a push the payload could not type; a missing content type there is a malformed
        // push. The other push classes are typed by their content type, so they cannot lack one.
        if (event.classId == ClassId.WAP_OTHER && event.wapContentTypeUnreadable()) return Severity.SUSPICIOUS
        val push = event.classId.wire().startsWith(WAP_CLASS_PREFIX)
        val blocked = event.verdict == Verdict.BLOCK || event.verdict == Verdict.QUARANTINE
        val portAddressed = push || event.classId == ClassId.DATA_PORT
        if (portAddressed && (blocked || event.fate == Fate.NO_RECEIVER)) return Severity.NOTABLE
        return null
    }

    /**
     * A parse failure (CL-MALFORMED-SMS, CL-WAP-UNREADABLE) with a damaged WSP header: the WAP push parser crash that
     * the "SMS of death" attacks use. The other parse failures stay Suspicious.
     */
    private fun knownCrashPattern(event: EventFacts): Boolean {
        if (event.classId != ClassId.MALFORMED_SMS && event.classId != ClassId.WAP_UNREADABLE) return false
        val codes = event.anomalies + listOfNotNull(event.subtype)
        return codes.any { it in CRASH_PATTERN_ANOMALIES }
    }

    private companion object {
        val CRASH_PATTERN_ANOMALIES =
            setOf(AnomalyCode.WSP_TYPE, AnomalyCode.WSP_HLEN, AnomalyCode.WSP_CTYPE).map { it.wire() }.toSet()

        /** Every WAP push class wire name starts with this text ([ClassId]). */
        const val WAP_CLASS_PREFIX = "CL-WAP"
    }
}
