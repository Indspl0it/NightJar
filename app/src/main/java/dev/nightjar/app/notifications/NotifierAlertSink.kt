package dev.nightjar.app.notifications

import dev.nightjar.app.data.repository.MuteRepository
import dev.nightjar.app.domain.ingest.AlertSink
import dev.nightjar.app.domain.ingest.ProcessedEvent
import dev.nightjar.app.domain.notify.NotificationInput
import dev.nightjar.app.feature.classify.ClassifierFactory
import dev.nightjar.app.feature.classify.SeverityConfigFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last step of the ingest pipeline: decide and post the alert.
 *
 * Mutes are read per event because a mute can be created while ingest is running; the rate limit lives in the shared
 * [dev.nightjar.app.domain.notify.RateLimiter]. A message never posts a summary: the weekly digest comes from
 * [DigestWorker] only.
 */
@Singleton
class NotifierAlertSink
    @Inject
    constructor(
        private val factory: ClassifierFactory,
        private val mutes: MuteRepository,
        private val notifier: Notifier,
        private val clock: dev.nightjar.app.data.repository.Clock,
    ) : AlertSink {
        override suspend fun alert(processed: ProcessedEvent) {
            val event = processed.facts
            val config = factory.classifierSettings()
            val muteMatcher = SeverityConfigFactory.activeMutes(mutes, clock.now())
            val router = factory.router(config)
            val plan =
                router.route(
                    NotificationInput(
                        event = event,
                        messageId = processed.messageId,
                        severity = processed.severity,
                        detection = processed.primaryDetection,
                        muted = muteMatcher.isMuted(event.classId, event.originator, event.direction),
                        beforeFirstUnlock = !event.userUnlocked,
                    ),
                )
            if (plan != null) notifier.post(plan)
        }
    }
