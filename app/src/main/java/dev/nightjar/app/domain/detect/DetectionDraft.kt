package dev.nightjar.app.domain.detect

import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.Severity

/**
 * Detector identity. [wire] is the stable detector id that `detection.detectorId` stores, so a stored row names the
 * detector or pattern that fired.
 */
enum class DetectorId(
    val wire: String,
) {
    SILENT_PING("DET-01"),
    SIM_OTA("DET-02"),
    OMA_CP("DET-03"),
    SUPL("DET-04"),
    OUTBOUND("DET-05"),
    INJECTION("DET-06"),

    // Suspicious patterns. One id per pattern, so the stored row names the rule that fired.
    PAT_SILENT_BURST("PAT-01"),
    PAT_SILENT_UNKNOWN_SENDER("PAT-02"),
    PAT_SUPL_UNKNOWN_SENDER("PAT-03"),
    PAT_CP_NO_NETWORK_PIN("PAT-04"),
    PAT_SIM_BROWSER("PAT-05"),
    PAT_SIM_SENDS_AFTER_UPDATE("PAT-06"),
    PAT_UNKNOWN_PORT_REPEAT("PAT-07"),
    PAT_MALFORMED_BURST("PAT-08"),
    PAT_REPLACE_NO_FIRST("PAT-09"),
    ;

    /** True for a suspicious pattern. Its severity is a floor that the known-sender downgrade cannot lower. */
    val pattern: Boolean get() = wire.startsWith(PATTERN_PREFIX)

    companion object {
        private const val PATTERN_PREFIX = "PAT-"

        fun fromWire(wire: String?): DetectorId? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One detector finding.
 *
 * @param severity the severity the detector wants for the event. The severity pipeline applies it *before* the
 *  known-originator downgrade, so detectors never need to know about known senders.
 * @param summaryKey + [summaryArgs] the one-line paragraph shown under the class explanation and in the
 *  notification title when a burst grows
 * @param notes further sentences appended to the summary, rendered with the same arguments (e.g. the decoded APN and
 *  proxy of an OMA CP push, DET-03)
 * @param mergeKey non-null only when the finding belongs to an open detection that grows instead of creating a new
 *  row (silent-ping bursts). The store updates the open row with this key.
 * @param eventIds the messages that belong to the detection; for a burst this is every member so far.
 */
data class DetectionDraft(
    val detectorId: DetectorId,
    val severity: Severity,
    val summaryKey: TextKey,
    val summaryArgs: Map<String, String>,
    val eventIds: List<String>,
    val firstAtWallMs: Long,
    val lastAtWallMs: Long,
    val count: Int,
    val notes: List<TextKey> = emptyList(),
    val mergeKey: String? = null,
) {
    /** True when this finding should update an existing open detection rather than open a new one. */
    val merges: Boolean get() = mergeKey != null

    /** Every sentence of the detection paragraph, summary first. */
    val paragraphKeys: List<TextKey> get() = listOf(summaryKey) + notes

    /**
     * The key the store files this finding under: [mergeKey] for a growing detection, else one key per message. A
     * retried message then updates its own row instead of adding a second one.
     */
    val storeKey: String get() = mergeKey ?: (ONE_SHOT_PREFIX + eventIds.firstOrNull().orEmpty())

    companion object {
        /** [storeKey] prefix of a one-shot finding. Such a row never continues a burst. */
        const val ONE_SHOT_PREFIX = "event:"

        /** A [summaryArgs] value the detector does not know: the explainer shows its "unknown" word. */
        const val UNKNOWN = ""
    }
}
