package dev.nightjar.app.domain.detect

import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.app.domain.model.EventFacts
import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Subtype

/**
 * The suspicious patterns (`PAT-01`…`PAT-09`). A pattern marks a message Suspicious or High from what the message and
 * the messages before it show, not only from its type.
 *
 * Each rule reads at most one bounded window through [PatternHistory] ([HISTORY_LIMIT] rows), so memory stays flat
 * however many messages a sender sends. The rules run on the ingest worker, never on the main thread.
 *
 * Each finding has its own reason sentence, which the detail screen lists under "Why NightJar marks this as
 * suspicious". The finding's severity is a floor: [dev.nightjar.app.domain.classify.EventClassifier] never lowers a
 * pattern result with the known-sender downgrade.
 *
 * Windows are measured back from the event's arrival time, so a batch processed late gives the same result. The
 * [clock] only stops a future arrival time (a wrong phone clock) from stretching a window.
 */
class PatternDetectors(
    private val config: DetectorConfig,
    private val history: PatternHistory,
    private val knownOriginators: KnownOriginatorMatcher,
    private val clock: WallClock,
) {
    suspend fun evaluate(
        event: EventFacts,
        context: DetectContext,
    ): List<DetectionDraft> {
        // A self test, a replay, a released copy or a legacy debug row is not new traffic: counting it would make a
        // burst out of one message.
        if (event.flags.notNewTraffic) return emptyList()
        val now = minOf(event.receivedAtWallMs, clock.nowWallMs())
        val draft =
            when (event.classId) {
                ClassId.SILENT -> silentBurst(event, now) ?: silentUnknownSender(event, now)
                ClassId.WAP_SUPL, ClassId.LOC_SUPL -> suplUnknownSender(event, context, now)
                ClassId.WAP_CP -> settingsPushWithoutNetworkPin(event, now)
                ClassId.SIM_DD -> simBrowserCommand(event, now)
                ClassId.MO_STK -> simSendsAfterUpdate(event, now)
                ClassId.DATA_PORT -> unknownPortRepeat(event, now)
                ClassId.MALFORMED_SMS, ClassId.WAP_UNREADABLE -> malformedBurst(event, now)
                ClassId.REPLACE -> replaceWithoutFirst(event, now)
                else -> null
            }
        return listOfNotNull(draft)
    }

    // ------------------------------------------------------------------ PAT-01 silent SMS burst

    /** 3 or more Type 0 messages from one sender in 60 minutes: High. */
    private suspend fun silentBurst(
        event: EventFacts,
        now: Long,
    ): DetectionDraft? {
        if (event.direction != Direction.IN) return null
        val sender = normalized(event.originator) ?: return null
        val prior =
            history
                .recentOfClasses(SILENT_CLASSES, now - SILENT_BURST_WINDOW_MS, now, HISTORY_LIMIT)
                .filter { it.eventId != event.eventId && normalized(it.originator) == sender }
        val total = prior.size + 1
        if (total < SILENT_BURST_COUNT) return null
        return burstDraft(DetectorId.PAT_SILENT_BURST, event, now, Severity.HIGH, TextKey.PAT_SILENT_BURST, sender, prior)
    }

    // ------------------------------------------------------------------ PAT-02 silent SMS from an unknown sender

    /** Type 0 from a sender that is not in Known senders: Suspicious. */
    private fun silentUnknownSender(
        event: EventFacts,
        now: Long,
    ): DetectionDraft? {
        if (event.direction != Direction.IN || knownOriginators.matches(event.originator)) return null
        return draft(
            detectorId = DetectorId.PAT_SILENT_UNKNOWN_SENDER,
            event = event,
            now = now,
            severity = Severity.SUSPICIOUS,
            summaryKey = TextKey.PAT_SILENT_UNKNOWN_SENDER,
        )
    }

    // ------------------------------------------------------------------ PAT-03 location request from an unknown sender

    /**
     * A location request (SUPL) from a sender that is not a known carrier number: High.
     *
     * An active emergency call and the `detect.supl.graceMin` minutes after it are exempt: the network
     * locates the phone for the emergency service then, and the sender is often not in the list.
     */
    private fun suplUnknownSender(
        event: EventFacts,
        context: DetectContext,
        now: Long,
    ): DetectionDraft? {
        if (event.direction != Direction.IN) return null
        if (context.withinEmergencyGrace(event.receivedAtWallMs, config.suplGraceMs)) return null
        if (knownOriginators.isCarrier(event.originator)) return null
        return draft(
            detectorId = DetectorId.PAT_SUPL_UNKNOWN_SENDER,
            event = event,
            now = now,
            severity = Severity.HIGH,
            summaryKey = TextKey.PAT_SUPL_UNKNOWN_SENDER,
        )
    }

    // ------------------------------------------------------------------ PAT-04 settings push without network PIN

    /** CL-WAP-CP with subtype NOSEC or USERPIN from a sender that is not in Known senders: High. */
    private fun settingsPushWithoutNetworkPin(
        event: EventFacts,
        now: Long,
    ): DetectionDraft? {
        if (event.direction != Direction.IN) return null
        if (event.subtype != Subtype.WapCp.NOSEC && event.subtype != Subtype.WapCp.USERPIN) return null
        if (knownOriginators.matches(event.originator)) return null
        return draft(
            detectorId = DetectorId.PAT_CP_NO_NETWORK_PIN,
            event = event,
            now = now,
            severity = Severity.HIGH,
            summaryKey = TextKey.PAT_CP_NO_NETWORK_PIN,
        )
    }

    // ------------------------------------------------------------------ PAT-05 SIM browser command

    /** SIM-OTA-SAT or SIM-OTA-WIB, as the subtype or as an extra subtype: High (Simjacker, WIBattack). */
    private fun simBrowserCommand(
        event: EventFacts,
        now: Long,
    ): DetectionDraft? {
        val key =
            when {
                event.hasSubtype(Subtype.SimOta.SAT) -> TextKey.PAT_SIM_BROWSER_SAT
                event.hasSubtype(Subtype.SimOta.WIB) -> TextKey.PAT_SIM_BROWSER_WIB
                else -> return null
            }
        return draft(
            detectorId = DetectorId.PAT_SIM_BROWSER,
            event = event,
            now = now,
            severity = Severity.HIGH,
            summaryKey = key,
        )
    }

    // ------------------------------------------------------------------ PAT-06 SIM sends after a SIM update

    /** CL-MO-STK within 10 minutes after a CL-SIM-DD: High. This is the Simjacker sequence. */
    private suspend fun simSendsAfterUpdate(
        event: EventFacts,
        now: Long,
    ): DetectionDraft? {
        val update =
            history
                .recentOfClasses(SIM_UPDATE_CLASSES, now - SIM_SEND_WINDOW_MS, now, HISTORY_LIMIT)
                .filter { it.eventId != event.eventId }
                .maxByOrNull { it.atWallMs } ?: return null
        return draft(
            detectorId = DetectorId.PAT_SIM_SENDS_AFTER_UPDATE,
            event = event,
            now = now,
            severity = Severity.HIGH,
            summaryKey = TextKey.PAT_SIM_SENDS_AFTER_UPDATE,
            firstAtWallMs = update.atWallMs,
            extraEventIds = listOf(update.eventId),
        )
    }

    // ------------------------------------------------------------------ PAT-07 repeated messages to an unknown port

    /** 3 or more CL-DATA-PORT with subtype UNKNOWN from one sender in 24 hours: Suspicious. */
    private suspend fun unknownPortRepeat(
        event: EventFacts,
        now: Long,
    ): DetectionDraft? {
        if (event.direction != Direction.IN || event.subtype != Subtype.DataPort.UNKNOWN) return null
        val sender = normalized(event.originator) ?: return null
        val prior =
            history
                .recentOfClasses(DATA_PORT_CLASSES, now - UNKNOWN_PORT_WINDOW_MS, now, HISTORY_LIMIT)
                .filter {
                    it.eventId != event.eventId &&
                        it.subtype == Subtype.DataPort.UNKNOWN &&
                        normalized(it.originator) == sender
                }
        val total = prior.size + 1
        if (total < UNKNOWN_PORT_COUNT) return null
        return burstDraft(
            DetectorId.PAT_UNKNOWN_PORT_REPEAT,
            event,
            now,
            Severity.SUSPICIOUS,
            TextKey.PAT_UNKNOWN_PORT_REPEAT,
            sender,
            prior,
        )
    }

    // ------------------------------------------------------------------ PAT-08 malformed burst

    /**
     * 2 or more malformed messages (CL-MALFORMED-SMS or CL-WAP-UNREADABLE) in 10 minutes, from any sender: High. A
     * malformed message often has no address that can be read, so this rule counts for the phone, not for a sender.
     */
    private suspend fun malformedBurst(
        event: EventFacts,
        now: Long,
    ): DetectionDraft? {
        if (event.direction != Direction.IN) return null
        val prior =
            history
                .recentOfClasses(MALFORMED_CLASSES, now - MALFORMED_WINDOW_MS, now, HISTORY_LIMIT)
                .filter { it.eventId != event.eventId }
        val total = prior.size + 1
        if (total < MALFORMED_COUNT) return null
        return burstDraft(DetectorId.PAT_MALFORMED_BURST, event, now, Severity.HIGH, TextKey.PAT_MALFORMED_BURST, null, prior)
    }

    // ------------------------------------------------------------------ PAT-09 replacement without a first message

    /**
     * CL-REPLACE with no earlier message of the same replace type from the sender: Suspicious. A replacement message
     * replaces an earlier one with the same PID and sender (TS 23.040); with nothing to replace, it can be a
     * probe. Off without stored history ([PatternHistory.NONE]), and off when the sender or the type cannot be read.
     */
    private suspend fun replaceWithoutFirst(
        event: EventFacts,
        now: Long,
    ): DetectionDraft? {
        if (event.direction != Direction.IN || history === PatternHistory.NONE) return null
        val type = event.subtype ?: return null
        val sender = normalized(event.originator) ?: return null
        val earlier =
            history
                .recentFromOriginator(sender, now, HISTORY_LIMIT)
                .any { it.eventId != event.eventId && it.classId == ClassId.REPLACE && it.subtype == type }
        return if (earlier) {
            null
        } else {
            draft(
                detectorId = DetectorId.PAT_REPLACE_NO_FIRST,
                event = event,
                now = now,
                severity = Severity.SUSPICIOUS,
                summaryKey = TextKey.PAT_REPLACE_NO_FIRST,
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    /** One detection for [event] and the earlier messages in [extraEventIds]; the count is all of them. */
    private fun draft(
        detectorId: DetectorId,
        event: EventFacts,
        now: Long,
        severity: Severity,
        summaryKey: TextKey,
        args: Map<String, String> = emptyMap(),
        firstAtWallMs: Long = now,
        extraEventIds: List<String> = emptyList(),
        mergeKey: String? = null,
    ): DetectionDraft =
        DetectionDraft(
            detectorId = detectorId,
            severity = severity,
            summaryKey = summaryKey,
            summaryArgs = args,
            eventIds = listOf(event.eventId) + extraEventIds,
            firstAtWallMs = firstAtWallMs,
            lastAtWallMs = now,
            count = 1 + extraEventIds.size,
            mergeKey = mergeKey,
        )

    /**
     * A burst of [event] and the [prior] messages in the window: "{count}" in the summary, the oldest message as the
     * start, and one [burstKey] per burst so a later message grows this detection.
     */
    private fun burstDraft(
        detectorId: DetectorId,
        event: EventFacts,
        now: Long,
        severity: Severity,
        summaryKey: TextKey,
        sender: String?,
        prior: List<PatternRecord>,
    ): DetectionDraft =
        draft(
            detectorId = detectorId,
            event = event,
            now = now,
            severity = severity,
            summaryKey = summaryKey,
            args = mapOf("count" to (prior.size + 1).toString()),
            firstAtWallMs = prior.minOfOrNull { it.atWallMs } ?: now,
            extraEventIds = prior.map { it.eventId },
            mergeKey = burstKey(detectorId, sender, prior, now),
        )

    /**
     * One key per burst, so each later message of the burst grows one detection instead of opening a new one: the
     * pattern, the sender (as a hash, never the number) and the arrival of the oldest message still in the window.
     */
    private fun burstKey(
        detectorId: DetectorId,
        sender: String?,
        prior: List<PatternRecord>,
        now: Long,
    ): String {
        val first = prior.minOfOrNull { it.atWallMs } ?: now
        val who = sender?.let { Integer.toHexString(it.hashCode()) } ?: ANY_SENDER
        return "${detectorId.wire}:$who:$first"
    }

    private fun normalized(originator: String?): String? = KnownOriginatorMatcher.normalize(originator)

    companion object {
        const val SILENT_BURST_COUNT = 3
        const val SILENT_BURST_WINDOW_MS = 60 * 60_000L
        const val SIM_SEND_WINDOW_MS = 10 * 60_000L
        const val UNKNOWN_PORT_COUNT = 3
        const val UNKNOWN_PORT_WINDOW_MS = 24 * 60 * 60_000L
        const val MALFORMED_COUNT = 2
        const val MALFORMED_WINDOW_MS = 10 * 60_000L

        /** No window needs more rows than this to reach its count; a flood beyond it is already a burst. */
        const val HISTORY_LIMIT = 200

        /** The sender part of a [burstKey] for a pattern that counts for the phone, not for one sender. */
        private const val ANY_SENDER = "any"

        private val SILENT_CLASSES = setOf(ClassId.SILENT)
        private val SIM_UPDATE_CLASSES = setOf(ClassId.SIM_DD)
        private val DATA_PORT_CLASSES = setOf(ClassId.DATA_PORT)
        private val MALFORMED_CLASSES = setOf(ClassId.MALFORMED_SMS, ClassId.WAP_UNREADABLE)
    }
}

/**
 * True when [code] is the event's subtype, or one of its extra subtypes. An extra subtype of the same class is a bare
 * code or `<class wire>:<code>`; both forms count.
 */
internal fun EventFacts.hasSubtype(code: String): Boolean {
    if (subtype == code) return true
    val qualified = classId.wire() + ":" + code
    return extraSubtypes.any { it == code || it == qualified }
}
