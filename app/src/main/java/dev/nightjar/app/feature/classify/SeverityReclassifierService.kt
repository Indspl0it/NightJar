package dev.nightjar.app.feature.classify

import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.db.DetectionDao
import dev.nightjar.app.data.db.MessageDao
import dev.nightjar.app.data.db.MessageEntity
import dev.nightjar.app.data.model.KnownOriginatorKind
import dev.nightjar.app.data.repository.KnownOriginatorRepository
import dev.nightjar.app.data.repository.MessageRepository
import dev.nightjar.app.data.repository.TrustedPackageRepository
import dev.nightjar.app.domain.classify.Finding
import dev.nightjar.app.domain.classify.ReclassifyTrigger
import dev.nightjar.app.domain.classify.SeverityReclassifier
import dev.nightjar.app.domain.classify.StoredSeverityRow
import dev.nightjar.app.domain.detect.DetectorId
import dev.nightjar.app.domain.ingest.detectContext
import dev.nightjar.app.domain.ingest.toFacts
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recomputes stored severities after `severity.<class>` overrides, known originators or trusted packages change
 * .
 *
 * Each row goes through the same severity decision as ingest: its stored facts, its SIM OTA header and the detector
 * findings linked to it. Rows whose severity and override flag are unchanged are not written, so a no-op settings
 * change costs one query per class and nothing else.
 */
@Singleton
class SeverityReclassifierService
    @Inject
    constructor(
        private val messages: MessageDao,
        private val detections: DetectionDao,
        private val repository: MessageRepository,
        private val factory: ClassifierFactory,
    ) {
        /**
         * Applies the current configuration to stored rows of [classes].
         *
         * @return the number of rows that changed
         */
        suspend fun reclassify(
            classes: Collection<ClassId> = ClassId.entries,
            limitPerClass: Int = ROW_LIMIT,
        ): Int {
            val reclassifier = SeverityReclassifier(factory.severityConfig())
            var changed = 0
            for (classId in classes.distinct()) {
                // A row that waits for the pipeline gets its severity there, with the configuration of that pass.
                val stored =
                    messages
                        .recentForDetector(classId.wire(), 0, limitPerClass)
                        .filterNot { it.needsProcessing }
                        .mapNotNull { it.toStoredRow() }
                for (change in reclassifier.reclassify(stored)) {
                    repository.applySeverity(change.id, change.to, overridden = change.overridden)
                    changed++
                }
            }
            return changed
        }

        /**
         * The findings linked to the row. A detection that grows (a burst) keeps the strongest severity it reached, so
         * each member of the burst gets that severity.
         */
        private suspend fun MessageEntity.toStoredRow(): StoredSeverityRow? {
            val current = Wire.parse(Severity::class.java, severity, null) ?: return null
            val findings =
                detections.forMessage(id).mapNotNull { detection ->
                    val detectorId = DetectorId.fromWire(detection.detectorId) ?: return@mapNotNull null
                    val found = Wire.parse(Severity::class.java, detection.severity, null) ?: return@mapNotNull null
                    Finding(detectorId, found)
                }
            return StoredSeverityRow(
                id = id,
                event = toFacts(),
                context = detectContext(),
                findings = findings,
                currentSeverity = current,
                currentOverridden = severityOverridden,
            )
        }

        private companion object {
            /**
             * Reclassification is a background repair, not a migration: the newest rows are what the user sees on Home
             * and in the feed, and older rows keep a severity that only affects a historical chip.
             */
            const val ROW_LIMIT = 2_000
        }
    }

/**
 * Starts [SeverityReclassifierService] when an input of the severity decision changes: the `severity.<class>`
 * overrides, the known originators and the trusted packages. The first value is the state at start and starts nothing.
 * Only the classes that the change can affect are read ([ReclassifyTrigger.affects]).
 */
@Singleton
class SeverityReclassifyWatcher
    @Inject
    constructor(
        private val settings: SettingsStore,
        private val knownOriginators: KnownOriginatorRepository,
        private val trustedPackages: TrustedPackageRepository,
        private val service: SeverityReclassifierService,
        private val log: ALog,
    ) {
        private val started = AtomicBoolean(false)

        /** Idempotent. Call it after the user unlocked the phone (the settings and the database are then readable). */
        @OptIn(FlowPreview::class)
        fun start(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) {
            if (!started.compareAndSet(false, true)) return
            scope.launch {
                settings.loaded.first { it }
                var previous: Inputs? = null
                val inputs =
                    combine(
                        settings.settings.map { it.severityOverrides },
                        knownOriginators.observeAll().map { rows -> rows.map { it.kind to it.value }.toSet() },
                        trustedPackages.observeAll().map { rows -> rows.map { it.packageName }.toSet() },
                    ) { overrides, known, trusted -> Inputs(overrides, known, trusted) }
                inputs
                    .distinctUntilChanged()
                    .debounce(DEBOUNCE_MS)
                    .collect { next ->
                        val before = previous
                        previous = next
                        if (before == null) return@collect
                        val classes = changedClasses(before, next)
                        if (classes.isEmpty()) return@collect
                        runCatching { service.reclassify(classes) }
                            .onSuccess { log.i(TAG, "severities recomputed", listOf("rows" to it, "classes" to classes.size)) }
                            .onFailure { log.w(TAG, "severities not recomputed", it) }
                    }
            }
        }

        private data class Inputs(
            val overrides: Map<ClassId, Severity>,
            val known: Set<Pair<KnownOriginatorKind, String>>,
            val trusted: Set<String>,
        )

        private fun changedClasses(
            before: Inputs,
            next: Inputs,
        ): Set<ClassId> {
            val out = LinkedHashSet<ClassId>()
            (before.overrides.keys + next.overrides.keys)
                .filterTo(out) { before.overrides[it] != next.overrides[it] }
            if (before.known != next.known) ClassId.entries.filterTo(out) { ReclassifyTrigger.KNOWN_ORIGINATOR.affects(it) }
            if (before.trusted != next.trusted) ClassId.entries.filterTo(out) { ReclassifyTrigger.TRUSTED_PACKAGE.affects(it) }
            return out
        }

        private companion object {
            const val TAG = "Reclassify"

            /** One settings screen edit can write several values; they are recomputed once. */
            const val DEBOUNCE_MS = 2_000L
        }
    }
