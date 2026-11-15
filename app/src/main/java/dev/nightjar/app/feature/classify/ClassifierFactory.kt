package dev.nightjar.app.feature.classify

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.db.MessageDao
import dev.nightjar.app.data.repository.KnownOriginatorRepository
import dev.nightjar.app.data.repository.TrustedPackageRepository
import dev.nightjar.app.domain.classify.EventClassifier
import dev.nightjar.app.domain.detect.DetectionDraft
import dev.nightjar.app.domain.detect.DetectorConfig
import dev.nightjar.app.domain.detect.DetectorEngine
import dev.nightjar.app.domain.detect.PremiumPatterns
import dev.nightjar.app.domain.detect.TarDenylistJson
import dev.nightjar.app.domain.notify.NotificationRouter
import dev.nightjar.app.domain.notify.RateLimiter
import dev.nightjar.app.domain.settings.ClassifierSettings
import dev.nightjar.app.domain.severity.SeverityConfig
import dev.nightjar.app.domain.text.Explainer
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds one classification pass.
 *
 * Everything that depends on user configuration is snapshotted here, once per batch: the detectors and the severity
 * pipeline must see a consistent view, and a settings change mid-batch must not produce half-applied severities. The
 * rate limiter is injected rather than rebuilt, because the per-class rate limit counts alerts across batches.
 */
@Singleton
@Suppress("LongParameterList") // Hilt constructor: one parameter per collaborator
class ClassifierFactory
    @Inject
    constructor(
        private val settings: SettingsStore,
        private val history: DetectionHistoryAdapter,
        private val patternHistory: PatternHistoryAdapter,
        private val knownOriginators: KnownOriginatorRepository,
        private val trustedPackages: TrustedPackageRepository,
        private val rateLimiter: RateLimiter,
        private val premium: PremiumPatternsProvider,
        private val tarDenylist: TarDenylist,
        private val explainer: Explainer,
    ) {
        /** Current settings, coerced to their documented ranges. */
        fun classifierSettings(): ClassifierSettings {
            val s = settings.settings.value
            return ClassifierSettings(
                severityOverrides = s.severityOverrides,
                detectors =
                    DetectorConfig(
                        silentBurstCount = s.detectSilentCount,
                        silentWindowMin = s.detectSilentWindowMin,
                        silentFlagNewSender = s.detectSilentNewSender,
                        silentFlagRoaming = s.detectSilentRoaming,
                        suplGraceMin = s.detectSuplGraceMin,
                        premium = premium.patterns,
                        tarDenylist = tarDenylist.tars,
                    ).coerced(),
                showDeliveryReports = s.showDeliveryReports,
                maskNumbers = s.maskNumbers,
                hideNumbersInNotifications = s.hideInNotifications,
                notifySuspicious = s.notifySuspicious,
                notifyNotable = s.notifyNotable,
                notifyBroadcasts = s.notifyCb,
                notifyRateLimitMin = s.notifyRateLimitMin,
            )
        }

        suspend fun classifier(): EventClassifier {
            // The process that classifies the first batch is often the one the payload's `hello` just started, and
            // `settings.value` is the default object until DataStore answers: severity overrides, number masking and
            // the notification switches would all be ignored for that batch.
            settings.loaded.first { it }
            val config = classifierSettings()
            val severity = SeverityConfigFactory.load(settings.settings.value, knownOriginators, trustedPackages)
            val detectors =
                DetectorEngine(
                    config = config.detectors,
                    history = history,
                    knownOriginators = severity.knownOriginators,
                    // Without the adapter the history-based patterns (06, 07, 08, 09) never fire.
                    patternHistory = patternHistory,
                    clock = { System.currentTimeMillis() },
                )
            return EventClassifier(detectors, severity)
        }

        /** A router for the current settings; the rate limiter is shared across passes. */
        fun router(config: ClassifierSettings = classifierSettings()): NotificationRouter =
            NotificationRouter(config, explainer, rateLimiter)

        /** The severity snapshot alone, for reclassification. */
        suspend fun severityConfig(): SeverityConfig {
            settings.loaded.first { it }
            return SeverityConfigFactory.load(settings.settings.value, knownOriginators, trustedPackages)
        }
    }

/** Premium-rate destination patterns, loaded once from `assets/premium_numbers.json`. */
@Singleton
class PremiumPatternsProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        val patterns: PremiumPatterns by lazy {
            val text =
                runCatching {
                    context.assets
                        .open(PREMIUM_ASSET)
                        .bufferedReader()
                        .use { it.readText() }
                }.getOrNull()
            PremiumPatterns.parseJson(text)
        }

        private companion object {
            const val PREMIUM_ASSET = "premium_numbers.json"
        }
    }

/** TARs of SIM-toolkit applications used by SIM-Trojan families, loaded from assets. */
@Singleton
class TarDenylist
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        val tars: Set<Int> by lazy {
            val text =
                runCatching {
                    context.assets
                        .open(TAR_ASSET)
                        .bufferedReader()
                        .use { it.readText() }
                }.getOrNull()
            TarDenylistJson.parseJson(text)
        }

        private companion object {
            const val TAR_ASSET = "sim_tar_denylist.json"
        }
    }

/** Renders a detection's stored summary and resolves its event id to a message row id. */
@Singleton
class DetectionSummaryRendererImpl
    @Inject
    constructor(
        private val explainer: Explainer,
        private val messages: MessageDao,
    ) : DetectionSummaryRenderer {
        override fun summary(draft: DetectionDraft): String =
            explainer.detectorArgs(draft.summaryArgs).let { args -> draft.paragraphKeys.joinToString(" ") { explainer.render(it, args) } }

        override suspend fun messageIdOf(eventId: String): Long? = messages.findByEventId(eventId)?.id
    }
