package dev.nightjar.app.domain.classify

import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.domain.detect.DetectContext
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.app.domain.severity.SeverityConfig
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Severity

/**
 * What changed, for deciding which stored severities must be recomputed.
 *
 * Firewall rules (verdicts) do **not** change severity: severity is a property of the message, not of the action.
 * Detector thresholds affect new events only, so they are not a trigger.
 */
enum class ReclassifyTrigger {
    /** `severity.<classId>` changed. */
    SEVERITY_OVERRIDE,

    /** A known originator was added, edited or removed. */
    KNOWN_ORIGINATOR,

    /** A package was trusted or untrusted. */
    TRUSTED_PACKAGE,
    ;

    /**
     * True when this change can affect rows of [classId] at all, so the caller can skip a table scan. A known sender
     * downgrades inbound network messages only, and trust covers outgoing and injected messages only (SeverityPipeline).
     */
    fun affects(classId: ClassId): Boolean =
        when (this) {
            SEVERITY_OVERRIDE -> true
            KNOWN_ORIGINATOR -> ClassRegistry.of(classId).direction == Direction.IN && classId != ClassId.INJECTED
            TRUSTED_PACKAGE -> ClassRegistry.of(classId).direction == Direction.OUT || classId == ClassId.INJECTED
        }
}

/**
 * The stored inputs of one message, as far as severity is concerned.
 *
 * @param event the facts of the stored row (`MessageEntity.toFacts`), with the subtype, the CB message id and the
 *     anomalies that the evidence floor reads
 * @param context the SIM OTA header of the row (`MessageEntity.detectContext`)
 * @param findings the detector findings linked to the row. A pattern finding is a floor.
 * @param currentSeverity the severity currently on the row
 * @param currentOverridden the `severityOverridden` flag currently on the row
 */
data class StoredSeverityRow(
    val id: Long,
    val event: EventFacts,
    val context: DetectContext = DetectContext.NONE,
    val findings: List<Finding> = emptyList(),
    val currentSeverity: Severity,
    val currentOverridden: Boolean = false,
)

/** One row whose severity or override flag changed. */
data class SeverityChange(
    val id: Long,
    val from: Severity,
    val to: Severity,
    val overridden: Boolean,
)

/**
 * Recomputes stored severities after a configuration change.
 *
 * It uses the same [SeverityDecider] as ingest, so a stored row gets the severity that ingest would give it with the
 * new configuration: the subtype default, the CB message id, the evidence floor and the pattern floor all apply
 * . Removing a known originator gives back the severity of the detector, not the class default.
 */
class SeverityReclassifier(
    config: SeverityConfig,
) {
    private val decider = SeverityDecider(config)

    /**
     * Returns one [SeverityChange] per row whose severity or override flag would change. Rows that stay the same are
     * omitted, so a no-op configuration change produces an empty list and no database write.
     */
    fun reclassify(rows: List<StoredSeverityRow>): List<SeverityChange> =
        rows.mapNotNull { row ->
            val next = decider.severityOf(row.event, row.context, row.findings)
            val overridden = decider.overridden(row.event)
            if (next == row.currentSeverity && overridden == row.currentOverridden) {
                null
            } else {
                SeverityChange(row.id, row.currentSeverity, next, overridden)
            }
        }
}
