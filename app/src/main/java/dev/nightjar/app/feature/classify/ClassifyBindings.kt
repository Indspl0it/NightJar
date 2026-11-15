package dev.nightjar.app.feature.classify

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.domain.detect.DetectionHistory
import dev.nightjar.app.domain.ingest.CounterSink
import dev.nightjar.app.domain.ingest.CoverageSink
import dev.nightjar.app.domain.ingest.DetectionSink
import dev.nightjar.app.domain.ingest.MessageSeveritySink
import dev.nightjar.app.domain.ingest.RuleMatchSink
import dev.nightjar.app.domain.notify.RateLimiter
import dev.nightjar.app.domain.notify.TimeFormatter
import dev.nightjar.app.domain.text.DateFormatter
import dev.nightjar.app.domain.text.Explainer
import dev.nightjar.app.domain.text.PackageLabels
import dev.nightjar.app.domain.text.Strings
import dev.nightjar.app.ui.text.ResourceStrings
import javax.inject.Singleton

/**
 * Bindings for classification, detectors and notifications.
 *
 * `Strings` is bound here because the classification layer is its first consumer; [ResourceStrings] resolves
 * [dev.nightjar.app.domain.text.TextKey.resource] by name, so a missing translation degrades to English.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ClassifyBindings {
    @Binds
    @Singleton
    abstract fun strings(impl: ResourceStrings): Strings

    @Binds
    @Singleton
    abstract fun packageLabels(impl: PackageLabelAdapter): PackageLabels

    @Binds
    @Singleton
    abstract fun dates(impl: AndroidDates): DateFormatter

    @Binds
    @Singleton
    abstract fun times(impl: AndroidDates): TimeFormatter

    @Binds
    @Singleton
    abstract fun detectionHistory(impl: DetectionHistoryAdapter): DetectionHistory

    @Binds
    @Singleton
    abstract fun detectionSink(impl: DetectionSinkAdapter): DetectionSink

    @Binds
    @Singleton
    abstract fun coverageSink(impl: CoverageSinkAdapter): CoverageSink

    @Binds
    @Singleton
    abstract fun counterSink(impl: CounterSinkAdapter): CounterSink

    @Binds
    @Singleton
    abstract fun ruleMatchSink(impl: RuleMatchSinkAdapter): RuleMatchSink

    @Binds
    @Singleton
    abstract fun severitySink(impl: MessageSeveritySinkAdapter): MessageSeveritySink

    @Binds
    @Singleton
    abstract fun detectionSummary(impl: DetectionSummaryRendererImpl): DetectionSummaryRenderer

    /** Contributes the real releaser; until then the set is empty and Release reports a failure honestly. */
    @Multibinds
    abstract fun quarantineReleasers(): Set<QuarantineReleaser>

    companion object {
        @Provides
        @Singleton
        fun resourceStrings(
            @ApplicationContext context: Context,
        ): ResourceStrings = ResourceStrings.from(context)

        @Provides
        @Singleton
        fun explainer(
            strings: Strings,
            labels: PackageLabels,
            dates: DateFormatter,
            settings: SettingsStore,
            defaultSmsApp: DefaultSmsApp,
        ): Explainer =
            // The only Explainer of the app: masking follows `privacy.maskNumbers` live.
            Explainer(
                strings = strings,
                labels = labels,
                dates = dates,
                maskNumbers = { settings.settings.value.maskNumbers },
                defaultSmsAppLabel = defaultSmsApp::label,
            )

        @Provides
        @Singleton
        fun rateLimiter(): RateLimiter = RateLimiter()
    }
}
