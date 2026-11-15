package dev.nightjar.app.feature.health

import android.os.Build
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.nightjar.app.core.ipc.ControlResult
import dev.nightjar.app.core.ipc.PayloadConnection
import dev.nightjar.app.core.ipc.SelfTestEvents
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.model.Coverage
import dev.nightjar.app.data.model.SelfTestRun
import dev.nightjar.app.data.repository.CoverageRepository
import dev.nightjar.app.data.repository.RulesRepository
import dev.nightjar.app.data.repository.SelfTestRepository
import dev.nightjar.app.domain.selftest.CanaryClass
import dev.nightjar.app.domain.selftest.SelfTestObservation
import dev.nightjar.app.domain.selftest.SelfTestOutcome
import dev.nightjar.app.domain.selftest.SelfTestResults
import dev.nightjar.app.domain.selftest.SelfTestStatus
import dev.nightjar.app.feature.protection.OtaMonitor
import dev.nightjar.app.feature.protection.SessionRegistry
import dev.nightjar.contract.Control
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of `runSelfTest`. */
sealed interface SelfTestStart {
    data class Started(
        val runId: String,
    ) : SelfTestStart

    /** The payload refuses while the user is locked. */
    data object Locked : SelfTestStart

    /** A run is already injecting. */
    data object Busy : SelfTestStart

    /** Empty or unparseable class list. */
    data object Invalid : SelfTestStart

    /** No control binder: the payload is not running this boot. */
    data object Unavailable : SelfTestStart
}

/**
 * The narrow surface the self-test screen needs (T04, T05, T06).
 *
 * A port rather than the binder and the repositories directly: the ViewModel's state machine is then testable on the
 * JVM with a fake, and the screen cannot reach root or the payload by accident.
 */
interface SelfTestPort {
    /** Coverage per class (-04). */
    val coverage: Flow<List<Coverage>>

    /** `getHookHealth()` hook states, for the Appendix F preconditions. */
    suspend fun hookStates(): Map<String, String>

    /** Condition: a class action or enabled rule would block something. */
    suspend fun blockingRulesExist(): Boolean

    suspend fun start(classes: List<CanaryClass>): SelfTestStart

    /** Waits at most [timeoutMs] for the canary's event. */
    suspend fun await(
        runId: String,
        canary: CanaryClass,
        timeoutMs: Long,
    ): SelfTestObservation?

    /** `cancelSelfTest`; canaries already in flight are still intercepted. */
    suspend fun cancel(runId: String)

    /** Stores `self_test_run`, updates coverage and audits `SELF_TEST_RUN`. */
    suspend fun record(outcome: SelfTestOutcome)

    /** The stored reports, newest first; malformed rows are skipped. */
    suspend fun recentResults(limit: Int = 1): List<SelfTestOutcome>
}

@Singleton
@Suppress("LongParameterList") // Hilt constructor: one parameter per collaborator
class RepositorySelfTestPort
    @Inject
    constructor(
        private val connection: PayloadConnection,
        private val events: SelfTestEvents,
        private val selfTests: SelfTestRepository,
        private val coverageRepository: CoverageRepository,
        private val rules: RulesRepository,
        private val audit: AuditLog,
        private val sessions: SessionRegistry,
        private val ota: OtaMonitor,
    ) : SelfTestPort {
        override val coverage: Flow<List<Coverage>> = coverageRepository.observeAll()

        override suspend fun hookStates(): Map<String, String> =
            when (val result = withContext(Dispatchers.IO) { connection.getHookHealth() }) {
                is ControlResult.Ok -> hookStatesFromHealth(result.value)
                else -> emptyMap()
            }

        override suspend fun blockingRulesExist(): Boolean =
            runCatching { hasBlockingRules(rules.snapshot()) }.getOrDefault(false)

        override suspend fun start(classes: List<CanaryClass>): SelfTestStart =
            when (val result = withContext(Dispatchers.IO) { connection.runSelfTest(SelfTestResults.classesJson(classes)) }) {
                is ControlResult.Ok -> classifyReply(result.value)
                ControlResult.NoSession, ControlResult.Dead -> SelfTestStart.Unavailable
                is ControlResult.Failed -> SelfTestStart.Unavailable
            }

        override suspend fun await(
            runId: String,
            canary: CanaryClass,
            timeoutMs: Long,
        ): SelfTestObservation? = events.await(runId, canary, timeoutMs)

        override suspend fun cancel(runId: String) {
            withContext(Dispatchers.IO) { connection.cancelSelfTest(runId) }
        }

        override suspend fun record(outcome: SelfTestOutcome) {
            val handshake = sessions.handshake.value
            val json = SelfTestResults.encode(outcome)
            selfTests.save(
                SelfTestRun(
                    runId = outcome.runId,
                    startedAt = outcome.startedAt,
                    buildFingerprint = handshake?.buildFingerprint ?: Build.FINGERPRINT,
                    payloadVersion = handshake?.payloadVersion,
                    resultsJson = json,
                ),
            )
            // Only a canary that was injected and evaluated counts as "self-tested": SKIPPED and CANCELLED ones were
            // never tested.
            for (result in outcome.results.filter { it.status in TESTED }) {
                coverageRepository.markSelfTest(
                    classId = result.canary.classId,
                    atWallMs = outcome.finishedAt,
                    ok = result.status == SelfTestStatus.PASS,
                )
            }
            runCatching { selfTests.keepNewest(20) }
            // Asks for exactly this: a self-test on the running build clears the "re-check" prompt. A cancelled
            // run did not test the build, so the prompt stays.
            if (!outcome.cancelled) runCatching { ota.acknowledge() }
            audit.record(
                action = AuditAction.SELF_TEST_RUN,
                actor = AuditActor.USER,
                target = outcome.runId,
                after = json,
            )
        }

        override suspend fun recentResults(limit: Int): List<SelfTestOutcome> =
            selfTests.recent(limit).mapNotNull { SelfTestResults.decode(it.resultsJson) }

        private fun classifyReply(reply: String): SelfTestStart {
            if (reply.isBlank()) return SelfTestStart.Invalid
            if (!reply.startsWith(Control.SelfTestError.PREFIX)) return SelfTestStart.Started(reply)
            return when (reply.removePrefix(Control.SelfTestError.PREFIX)) {
                Control.SelfTestError.LOCKED -> SelfTestStart.Locked
                Control.SelfTestError.BUSY -> SelfTestStart.Busy
                else -> SelfTestStart.Invalid
            }
        }

        private companion object {
            /** The results of a canary that was injected and evaluated. */
            val TESTED = setOf(SelfTestStatus.PASS, SelfTestStatus.FAIL, SelfTestStatus.TIMEOUT)
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class SelfTestBindings {
    @Binds
    @Singleton
    abstract fun selfTestPort(impl: RepositorySelfTestPort): SelfTestPort
}
