package dev.nightjar.app.core.ipc

import dev.nightjar.app.domain.selftest.CanaryClass
import dev.nightjar.app.domain.selftest.SelfTestObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The self-test observations the ingest boundary produced.
 *
 * Ingest is the only place that still sees `flags.runId`/`flags.canaryClass`: the `message` table does not store them.
 * [EventProvider] publishes every canary event here after its transaction commits, and the evaluation reads the small
 * in-memory buffer instead of polling the database.
 *
 * The buffer keeps the last [MAX] observations and is never persisted: a run that outlives the process is simply a
 * timed-out canary, which is what the report says.
 */
@Singleton
class SelfTestEvents
    @Inject
    constructor() {
        private val buffer = MutableStateFlow<List<SelfTestObservation>>(emptyList())

        val observations: StateFlow<List<SelfTestObservation>> = buffer.asStateFlow()

        fun publish(observation: SelfTestObservation) {
            buffer.update { (it + observation).takeLast(MAX) }
        }

        fun clear() {
            buffer.value = emptyList()
        }

        /**
         * Waits at most [timeoutMs] for the event of [canary] in [runId].
         *
         * An event that already arrived while an earlier canary was being awaited is returned immediately, so the
         * order of injection never decides the result.
         */
        suspend fun await(
            runId: String,
            canary: CanaryClass,
            timeoutMs: Long,
        ): SelfTestObservation? =
            withTimeoutOrNull(timeoutMs) {
                observations
                    .map { list -> list.firstOrNull { it.runId == runId && it.canaryClass == canary.classId } }
                    .filterNotNull()
                    .first()
            }

        private companion object {
            /** One run has eight canaries; keep room for a couple of runs' worth of retries. */
            const val MAX = 32
        }
    }
