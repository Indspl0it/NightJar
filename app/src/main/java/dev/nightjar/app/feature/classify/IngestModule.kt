package dev.nightjar.app.feature.classify

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.nightjar.app.domain.ingest.AlertSink
import dev.nightjar.app.domain.ingest.CounterSink
import dev.nightjar.app.domain.ingest.CoverageSink
import dev.nightjar.app.domain.ingest.DetectionSink
import dev.nightjar.app.domain.ingest.IngestProcessor
import dev.nightjar.app.domain.ingest.MessageSeveritySink
import dev.nightjar.app.domain.ingest.PipelineFactory
import dev.nightjar.app.domain.ingest.ProcessingPipeline
import dev.nightjar.app.domain.ingest.RuleMatchSink
import javax.inject.Singleton

/** Wiring for [IngestProcessor]. The domain package keeps no Hilt module. */
@Module
@InstallIn(SingletonComponent::class)
object IngestModule {
    @Provides
    @Singleton
    fun pipelineFactory(
        factory: ClassifierFactory,
        detections: DetectionSink,
        coverage: CoverageSink,
        counters: CounterSink,
        ruleMatches: RuleMatchSink,
        severities: MessageSeveritySink,
        alerts: AlertSink,
    ): PipelineFactory =
        PipelineFactory {
            ProcessingPipeline(
                classifier = factory.classifier(),
                detections = detections,
                coverage = coverage,
                counters = counters,
                ruleMatches = ruleMatches,
                severities = severities,
                alerts = alerts,
            )
        }
}
