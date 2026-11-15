package dev.nightjar.app.feature.classify

import dev.nightjar.app.core.settings.Settings
import dev.nightjar.app.data.db.DetectionDao
import dev.nightjar.app.data.db.MessageDao
import dev.nightjar.app.data.db.MessageEntity
import dev.nightjar.app.data.model.KnownOriginatorKind
import dev.nightjar.app.data.model.Mute
import dev.nightjar.app.data.model.MuteScope
import dev.nightjar.app.data.model.TrustedPackage
import dev.nightjar.app.data.repository.CounterRepository
import dev.nightjar.app.data.repository.CoverageRepository
import dev.nightjar.app.data.repository.DetectionRepository
import dev.nightjar.app.data.repository.KnownOriginatorRepository
import dev.nightjar.app.data.repository.MessageRepository
import dev.nightjar.app.data.repository.MuteRepository
import dev.nightjar.app.data.repository.RulesRepository
import dev.nightjar.app.data.repository.TrustedPackageRepository
import dev.nightjar.app.domain.detect.DetectionDraft
import dev.nightjar.app.domain.detect.DetectionHistory
import dev.nightjar.app.domain.detect.DetectorId
import dev.nightjar.app.domain.detect.OpenDetection
import dev.nightjar.app.domain.detect.PatternHistory
import dev.nightjar.app.domain.detect.PatternRecord
import dev.nightjar.app.domain.detect.SilentPingRecord
import dev.nightjar.app.domain.ingest.CounterSink
import dev.nightjar.app.domain.ingest.CoverageSink
import dev.nightjar.app.domain.ingest.DetectionSink
import dev.nightjar.app.domain.ingest.MessageSeveritySink
import dev.nightjar.app.domain.ingest.RuleMatchSink
import dev.nightjar.app.domain.known.KnownOriginator
import dev.nightjar.app.domain.known.KnownOriginatorMatcher
import dev.nightjar.app.domain.known.MuteMatcher
import dev.nightjar.app.domain.severity.SeverityConfig
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Wire
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import dev.nightjar.app.data.model.KnownOriginator as StoredKnownOriginator
import dev.nightjar.app.domain.known.Mute as DomainMute

/**
 * Binds the classification domain ports onto the data repositories.
 *
 * These adapters exist so the classification layer never imports a Room type: every mapping (wire string → enum,
 * repository model → domain model) is in one file, and a schema change touches nothing else.
 */
@Singleton
class DetectionHistoryAdapter
    @Inject
    constructor(
        private val messages: MessageDao,
        private val detections: DetectionDao,
    ) : DetectionHistory {
        // / DOMAIN-X09: the history queries leave out self-test rows, replays and released copies (a release
        // is not a second message), and they apply the time window in SQL before the limit.
        override suspend fun silentPingsSince(
            sinceWallMs: Long,
            limit: Int,
        ): List<SilentPingRecord> =
            messages
                .historyForDetector(ClassId.SILENT.wire(), sinceWallMs, Long.MAX_VALUE, limit)
                .map { SilentPingRecord(it.eventId, it.originator, it.receivedAtWall) }
                .sortedBy { it.atWallMs }

        // The argument is KnownOriginatorMatcher.normalize, the same function the store uses for originatorNorm
        // , so the DAO compares like with like.
        override suspend fun originatorSeenBefore(
            normalizedOriginator: String,
            beforeWallMs: Long,
        ): Boolean = messages.historyFromOriginator(normalizedOriginator, beforeWallMs - 1, 1).isNotEmpty()

        // EXISTS queries over the whole history, with the same exclusions as the history queries.
        override suspend fun packageSentBefore(
            packageName: String,
            beforeWallMs: Long,
        ): Boolean = messages.packageSentBefore(OUTBOUND_CLASS_WIRES, packageName, INTERNAL_PACKAGE, beforeWallMs)

        // destinationNorm is stored through KnownOriginatorMatcher.normalize too (EventJson), so the values compare.
        override suspend fun destinationSentBefore(
            destination: String?,
            beforeWallMs: Long,
        ): Boolean {
            val wanted = KnownOriginatorMatcher.normalize(destination) ?: return false
            return messages.destinationSentBefore(Direction.OUT.wire(), wanted, beforeWallMs)
        }

        override suspend fun openDetection(
            detectorId: DetectorId,
            mergeKey: String,
        ): OpenDetection? {
            val row = detections.findOpen(detectorId.wire, mergeKey) ?: return null
            return OpenDetection(
                detectorId = detectorId,
                mergeKey = mergeKey,
                firstAtWallMs = row.firstAt,
                lastAtWallMs = row.lastAt,
                count = row.count,
            )
        }

        override suspend fun latestOpenDetection(detectorId: DetectorId): OpenDetection? {
            val row =
                detections
                    .observeOpen()
                    .first()
                    .firstOrNull { it.detectorId == detectorId.wire && it.mergeKey?.startsWith(DetectionDraft.ONE_SHOT_PREFIX) == false }
                    ?: return null
            return OpenDetection(
                detectorId = detectorId,
                mergeKey = row.mergeKey!!,
                firstAtWallMs = row.firstAt,
                lastAtWallMs = row.lastAt,
                count = row.count,
            )
        }

        companion object {
            /** Sends without a calling package are attributed to the phone process. */
            const val INTERNAL_PACKAGE = "android.phone.internal"

            private val OUTBOUND_CLASS_WIRES =
                listOf(ClassId.MO_DATA, ClassId.MO_RAW, ClassId.MO_STK, ClassId.MO_UNPERSISTED, ClassId.MO_VISIBLE).map { it.wire() }
        }
    }

/**
 * [PatternHistory] over `MessageDao`. Both history queries skip self-test rows, replays and released copies,
 * apply the window end in SQL and are bounded by `limit`, so a pattern never reads more than one window.
 */
@Singleton
class PatternHistoryAdapter
    @Inject
    constructor(
        private val messages: MessageDao,
    ) : PatternHistory {
        override suspend fun recentOfClasses(
            classes: Set<ClassId>,
            sinceWallMs: Long,
            untilWallMs: Long,
            limit: Int,
        ): List<PatternRecord> =
            classes
                .flatMap { messages.historyForDetector(it.wire(), sinceWallMs, untilWallMs, limit) }
                .sortedByDescending { it.receivedAtWall }
                .take(limit)
                .map { it.toPatternRecord() }

        override suspend fun recentFromOriginator(
            normalizedOriginator: String,
            untilWallMs: Long,
            limit: Int,
        ): List<PatternRecord> =
            messages
                .historyFromOriginator(normalizedOriginator, untilWallMs, limit)
                .map { it.toPatternRecord() }

        private fun MessageEntity.toPatternRecord(): PatternRecord =
            PatternRecord(
                eventId = eventId,
                classId = Wire.parse(ClassId::class.java, classId, ClassId.NORMAL),
                // The stored subtype column (null for a row from IPC version 1 or a class without a subtype).
                subtype = subtype,
                originator = originator,
                atWallMs = receivedAtWall,
            )
    }

/** [DetectionSink] over `DetectionRepository.openOrTouch`. */
@Singleton
class DetectionSinkAdapter
    @Inject
    constructor(
        private val detections: DetectionRepository,
        private val render: DetectionSummaryRenderer,
    ) : DetectionSink {
        override suspend fun record(draft: DetectionDraft): Long =
            detections.openOrTouch(
                detectorId = draft.detectorId.wire,
                // A one-shot finding gets a key per message too, so a retried message does not add a second row.
                mergeKey = draft.storeKey,
                severity = draft.severity,
                summary = render.summary(draft),
                messageIds = draft.eventIds.mapNotNull { render.messageIdOf(it) },
                firstAtWallMs = draft.firstAtWallMs,
                lastAtWallMs = draft.lastAtWallMs,
                count = draft.count,
            )
    }

/** Renders a detection's stored summary and resolves event ids to message row ids. */
interface DetectionSummaryRenderer {
    fun summary(draft: DetectionDraft): String

    suspend fun messageIdOf(eventId: String): Long?
}

/** [CoverageSink] over `CoverageRepository`. */
@Singleton
class CoverageSinkAdapter
    @Inject
    constructor(
        private val coverage: CoverageRepository,
    ) : CoverageSink {
        override suspend fun seenFromNetwork(
            classId: ClassId,
            atWallMs: Long,
        ) = coverage.markSeenFromNetwork(classId, atWallMs)

        // The coverage row records that the canary arrived; SelfTestResults keeps the pass or fail verdict.
        override suspend fun selfTestArrived(
            classId: ClassId,
            atWallMs: Long,
        ) = coverage.markSelfTest(classId, atWallMs, ok = true)
    }

/** [CounterSink] over `CounterRepository`. */
@Singleton
class CounterSinkAdapter
    @Inject
    constructor(
        private val counters: CounterRepository,
    ) : CounterSink {
        override suspend fun add(
            day: Int,
            subId: Int,
            direction: Direction,
            classId: ClassId,
            n: Int,
        ) = counters.add(day, subId, direction, classId, n.toLong())
    }

/** [RuleMatchSink] over `RulesRepository.incrementMatch` (03). */
@Singleton
class RuleMatchSinkAdapter
    @Inject
    constructor(
        private val rules: RulesRepository,
    ) : RuleMatchSink {
        override suspend fun increment(
            ruleId: Long,
            atWallMs: Long,
        ) = rules.incrementMatch(ruleId, atWallMs)
    }

/** [MessageSeveritySink] over `MessageRepository.applySeverity`. */
@Singleton
class MessageSeveritySinkAdapter
    @Inject
    constructor(
        private val messages: MessageRepository,
    ) : MessageSeveritySink {
        override suspend fun apply(
            messageId: Long,
            severity: Severity,
            overridden: Boolean,
        ) {
            messages.applySeverity(messageId, severity, overridden)
        }
    }

/** Builds the severity inputs from the stored configuration. */
object SeverityConfigFactory {
    fun build(
        settings: Settings,
        known: List<StoredKnownOriginator>,
        trusted: List<TrustedPackage>,
    ): SeverityConfig =
        SeverityConfig(
            overrides = settings.severityOverrides,
            knownOriginators = knownOriginators(known),
            trustedPackages = trusted.map { it.packageName }.toSet(),
        )

    /** Reads the current configuration from the repositories (one ingest pass uses one snapshot). */
    suspend fun load(
        settings: Settings,
        knownOriginators: KnownOriginatorRepository,
        trustedPackages: TrustedPackageRepository,
    ): SeverityConfig = build(settings, knownOriginators.all(), trustedPackages.all())

    fun knownOriginators(rows: List<StoredKnownOriginator>): KnownOriginatorMatcher =
        KnownOriginatorMatcher(
            rows.map {
                KnownOriginator(
                    value = it.value,
                    kind =
                        when (it.kind) {
                            KnownOriginatorKind.EXACT -> KnownOriginator.Kind.EXACT
                            KnownOriginatorKind.PREFIX -> KnownOriginator.Kind.PREFIX
                        },
                    label = it.label,
                    addedAtWallMs = it.addedAt,
                )
            },
        )

    /** Mutes only silence notifications; the message is still stored. */
    fun muteMatcher(
        rows: List<Mute>,
        nowWallMs: Long,
    ): MuteMatcher =
        MuteMatcher(
            rows.map {
                DomainMute(
                    scope =
                        when (it.scope) {
                            MuteScope.ORIGINATOR -> DomainMute.Scope.ORIGINATOR
                            MuteScope.CLASS -> DomainMute.Scope.CLASS
                        },
                    value = it.value,
                    untilWallMs = it.until,
                )
            },
            nowWallMs,
        )

    /** Reads the active mutes for a notification decision. */
    suspend fun activeMutes(
        mutes: MuteRepository,
        nowWallMs: Long,
    ): MuteMatcher = muteMatcher(mutes.active(), nowWallMs)
}
