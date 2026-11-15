package dev.nightjar.app.feature.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nightjar.app.data.model.Coverage
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.domain.selftest.CanaryClass
import dev.nightjar.app.domain.selftest.ClassResult
import dev.nightjar.app.domain.selftest.SelfTestObservation
import dev.nightjar.app.domain.selftest.SelfTestOutcome
import dev.nightjar.app.domain.selftest.SelfTestResults
import dev.nightjar.app.domain.selftest.SelfTestStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** `IDLE → CONFIRM → INJECTING/WAITING → REPORT → IDLE`. */
enum class SelfTestPhase { IDLE, PICKER, CONFIRM, RUNNING, REPORT }

/** Why a run could not start. */
enum class SelfTestError { LOCKED, BUSY, INVALID, UNAVAILABLE }

/** One Health self-test state (03). */
data class SelfTestUiState(
    val phase: SelfTestPhase = SelfTestPhase.IDLE,
    val selected: Set<CanaryClass> = CanaryClass.order.toSet(),
    val blockingRules: Boolean = false,
    val results: List<ClassResult> = emptyList(),
    val current: CanaryClass? = null,
    val completed: Int = 0,
    val total: Int = 0,
    val lastRun: SelfTestOutcome? = null,
    /** True after the stored last run was read, so a null [lastRun] means "never ran" and not "not read yet". */
    val lastRunLoaded: Boolean = false,
    val coverage: List<Coverage> = emptyList(),
    val busy: Boolean = false,
    val error: SelfTestError? = null,
) {
    val canStart: Boolean get() = phase == SelfTestPhase.PICKER && selected.isNotEmpty() && !busy

    val allSelected: Boolean get() = selected.size == CanaryClass.order.size
}

/**
 * Self-test.
 *
 * The ViewModel owns the state machine; [SelfTestPort] owns the binder, the event buffer and storage. Cancelling
 * races the pending canary wait instead of cancelling the coroutine, so the report is still written (the
 * pending canaries stay intercepted, and the user still gets a result). The self-test only reports: it never offers
 * to turn on blocking (the former offer is removed).
 */
@HiltViewModel
class SelfTestViewModel
    @Inject
    constructor(
        private val port: SelfTestPort,
        private val clock: Clock,
        /** Application scope ([dev.nightjar.app.di.AppModule.appScope]): cleanup must outlive this ViewModel. */
        private val appScope: CoroutineScope,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(SelfTestUiState())
        val state: StateFlow<SelfTestUiState> = mutableState.asStateFlow()

        private val cancelRequested = MutableStateFlow(false)

        private var runJob: Job? = null

        private var runId: String? = null

        private var startedAt: Long = 0

        /** The classes of the current run, in evaluation order (: needed to close an abandoned report). */
        private var runClasses: List<CanaryClass> = emptyList()

        /**
         * The run ids that [finish] already stored. [onCleared] and the run job can both reach [finish] for the same run
         * (Back during the last wait), and the run must be stored one time only.
         */
        private val finishedRuns: MutableSet<String> = ConcurrentHashMap.newKeySet()

        init {
            viewModelScope.launch {
                port.coverage.collect { rows -> mutableState.update { it.copy(coverage = rows) } }
            }
            reloadLastRun()
        }

        /**
         * Reads the stored last run again. Home calls it on resume: a run on Protection status uses another
         * instance of this ViewModel, so the self-test line of Home must not keep the old result. It changes nothing
         * while a sheet is open.
         */
        fun reloadLastRun() {
            viewModelScope.launch {
                // A failed read keeps what the state has: lastRunLoaded stays false until one read works.
                val last = runCatching { port.recentResults(1).firstOrNull() }.getOrElse { return@launch }
                if (mutableState.value.phase != SelfTestPhase.IDLE) return@launch
                mutableState.update { it.copy(lastRun = last, lastRunLoaded = true, results = last?.results ?: emptyList()) }
            }
        }

        /**
         * Back during RUNNING clears the screen and with it `viewModelScope`, before [evaluateRun] can report.
         * The cancel must still reach the payload (: pending canaries otherwise stay intercepted) and the run
         * must still be stored, so both are handed to [appScope], which outlives this ViewModel.
         */
        override fun onCleared() {
            val abandoned = runId
            if (abandoned != null && mutableState.value.phase == SelfTestPhase.RUNNING) {
                val done = mutableState.value.results
                val pending =
                    runClasses.drop(done.size).map { canary -> ClassResult(canary, SelfTestStatus.CANCELLED) }
                appScope.launch {
                    runCatching { port.cancel(abandoned) }
                    finish(abandoned, done + pending, cancelled = true)
                }
            }
        }

        /** "Run self-test" / self-test banner action: the self-test sheet with every class on. */
        fun open() {
            if (mutableState.value.phase == SelfTestPhase.RUNNING) return
            cancelRequested.value = false
            mutableState.update {
                it.copy(
                    phase = SelfTestPhase.PICKER,
                    selected = CanaryClass.order.toSet(),
                    results = emptyList(),
                    current = null,
                    completed = 0,
                    total = 0,
                    error = null,
                )
            }
            viewModelScope.launch {
                val blocking = runCatching { port.blockingRulesExist() }.getOrDefault(false)
                mutableState.update { it.copy(blockingRules = blocking) }
            }
        }

        fun close() {
            if (mutableState.value.phase == SelfTestPhase.RUNNING) return
            mutableState.update { it.copy(phase = SelfTestPhase.IDLE, error = null) }
        }

        /** checkbox. */
        fun toggle(canary: CanaryClass) {
            if (mutableState.value.phase != SelfTestPhase.PICKER) return
            mutableState.update { current ->
                val next = current.selected.toMutableSet()
                if (!next.add(canary)) next.remove(canary)
                current.copy(selected = next)
            }
        }

        fun setAllSelected(selected: Boolean) {
            if (mutableState.value.phase != SelfTestPhase.PICKER) return
            mutableState.update {
                it.copy(selected = if (selected) CanaryClass.order.toSet() else emptySet())
            }
        }

        /** "Start": the blocking-rules warning first when blocking rules exist, otherwise inject immediately. */
        fun start() {
            val current = mutableState.value
            if (!current.canStart) return
            if (current.blockingRules) {
                mutableState.update { it.copy(phase = SelfTestPhase.CONFIRM) }
            } else {
                beginRun()
            }
        }

        /** "Run". */
        fun confirmStart() {
            if (mutableState.value.phase != SelfTestPhase.CONFIRM) return
            beginRun()
        }

        /** "Cancel": back to the picker, nothing injected. */
        fun dismissConfirm() {
            if (mutableState.value.phase == SelfTestPhase.CONFIRM) {
                mutableState.update { it.copy(phase = SelfTestPhase.PICKER) }
            }
        }

        /** Cancel during INJECTING/WAITING; the report still lists what was evaluated. */
        fun cancel() {
            if (mutableState.value.phase != SelfTestPhase.RUNNING) return
            cancelRequested.value = true
            runId?.let { id -> viewModelScope.launch { runCatching { port.cancel(id) } } }
        }

        /** Home "Show results": the report of the stored last run, without a new run. */
        fun showLastReport() {
            val current = mutableState.value
            val last = current.lastRun ?: return
            if (current.phase != SelfTestPhase.IDLE) return
            mutableState.update { it.copy(phase = SelfTestPhase.REPORT, results = last.results, error = null) }
        }

        /** REPORT "Done". */
        fun dismissReport() {
            if (mutableState.value.phase == SelfTestPhase.REPORT) {
                mutableState.update { it.copy(phase = SelfTestPhase.IDLE) }
            }
        }

        fun dismissError() {
            mutableState.update { it.copy(error = null) }
        }

        private fun beginRun() {
            if (runJob?.isActive == true) return
            val classes = mutableState.value.selected.sortedBy { it.ordinal }
            if (classes.isEmpty()) {
                mutableState.update { it.copy(error = SelfTestError.INVALID) }
                return
            }
            startedAt = clock.now()
            runClasses = classes
            cancelRequested.value = false
            mutableState.update {
                it.copy(
                    phase = SelfTestPhase.RUNNING,
                    results = emptyList(),
                    current = null,
                    completed = 0,
                    total = classes.size,
                    busy = true,
                    error = null,
                )
            }
            runJob =
                viewModelScope.launch {
                    val hookStates = runCatching { port.hookStates() }.getOrDefault(emptyMap())
                    val skipped = skippedCanaries(classes, hookStates)
                    // A binder or IPC failure must end in an error dialog, not in a crash or a sheet stuck at RUNNING.
                    val start =
                        try {
                            port.start(classes)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            SelfTestStart.Unavailable
                        }
                    when (start) {
                        is SelfTestStart.Started -> evaluateRun(start.runId, classes, skipped)
                        SelfTestStart.Locked -> fail(SelfTestError.LOCKED)
                        SelfTestStart.Busy -> fail(SelfTestError.BUSY)
                        SelfTestStart.Invalid -> fail(SelfTestError.INVALID)
                        SelfTestStart.Unavailable -> fail(SelfTestError.UNAVAILABLE)
                    }
                }
        }

        private suspend fun evaluateRun(
            runId: String,
            classes: List<CanaryClass>,
            skipped: Map<CanaryClass, String>,
        ) {
            this.runId = runId
            val results = ArrayList<ClassResult>(classes.size)
            var completed = 0
            for (canary in classes) {
                val missingHook = skipped[canary]
                val result =
                    when {
                        missingHook != null -> {
                            ClassResult(canary, SelfTestStatus.SKIPPED, "SKIPPED:$missingHook")
                        }

                        cancelRequested.value -> {
                            ClassResult(canary, SelfTestStatus.CANCELLED)
                        }

                        else -> {
                            mutableState.update { it.copy(current = canary) }
                            // A failed wait counts as "no event" (TIMEOUT), so the run still ends with a report.
                            val observation =
                                try {
                                    awaitCanary(runId, canary)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    null
                                }
                            if (cancelRequested.value) {
                                ClassResult(canary, SelfTestStatus.CANCELLED)
                            } else {
                                SelfTestResults.evaluate(canary, observation)
                            }
                        }
                    }
                results += result
                completed++
                mutableState.update {
                    it.copy(results = results.toList(), completed = completed)
                }
            }
            finish(runId, results)
        }

        /** Races the canary wait against the cancel request; both sides are cleaned up. */
        private suspend fun awaitCanary(
            runId: String,
            canary: CanaryClass,
        ): SelfTestObservation? =
            coroutineScope {
                val wait = async { port.await(runId, canary, TIMEOUT_MS) }
                val cancelled = async { cancelRequested.first { it } }
                try {
                    select {
                        wait.onAwait { it }
                        cancelled.onAwait { null }
                    }
                } finally {
                    wait.cancel()
                    cancelled.cancel()
                }
            }

        /**
         * The report is written from a scope that may already be cancelled (Back during RUNNING), so the whole
         * block runs under [NonCancellable] and [cancelled] is passed in instead of read from the live flag.
         */
        private suspend fun finish(
            runId: String,
            results: List<ClassResult>,
            cancelled: Boolean = cancelRequested.value,
        ) = withContext(NonCancellable) {
            if (!finishedRuns.add(runId)) return@withContext
            val finishedAt = clock.now()
            val outcome = SelfTestOutcome(runId, startedAt, finishedAt, cancelled, results)
            runCatching { port.record(outcome) }
            val stored = runCatching { port.recentResults(1).firstOrNull() }.getOrNull()
            val report = stored ?: outcome
            mutableState.update {
                it.copy(
                    phase = SelfTestPhase.REPORT,
                    results = report.results,
                    lastRun = report,
                    lastRunLoaded = true,
                    current = null,
                    completed = report.results.size,
                    total = report.results.size,
                    busy = false,
                )
            }
            this@SelfTestViewModel.runId = null
        }

        private fun fail(error: SelfTestError) {
            mutableState.update {
                it.copy(phase = SelfTestPhase.PICKER, busy = false, error = error, current = null)
            }
        }

        private companion object {
            /** Each canary must arrive within five seconds. */
            const val TIMEOUT_MS = 5_000L
        }
    }
