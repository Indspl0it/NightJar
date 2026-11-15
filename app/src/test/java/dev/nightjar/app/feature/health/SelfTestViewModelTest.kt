package dev.nightjar.app.feature.health

import androidx.lifecycle.ViewModelStore
import dev.nightjar.app.data.model.Coverage
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.domain.selftest.CanaryClass
import dev.nightjar.app.domain.selftest.ClassResult
import dev.nightjar.app.domain.selftest.SelfTestObservation
import dev.nightjar.app.domain.selftest.SelfTestOutcome
import dev.nightjar.app.domain.selftest.SelfTestStatus
import dev.nightjar.app.domain.selftest.TraceStepView
import dev.nightjar.contract.ClassId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The Health self-test state machine, cancel, LOCKED, port failures and the one-time record. */
@OptIn(ExperimentalCoroutinesApi::class)
class SelfTestViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var port: FakePort
    private lateinit var appScope: CoroutineScope
    private lateinit var viewModel: SelfTestViewModel
    private var now = 1_000L

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        port = FakePort()
        appScope = CoroutineScope(dispatcher + SupervisorJob())
        viewModel = SelfTestViewModel(port, Clock { now }, appScope)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `idle to running to report stores and shows a full pass`() =
        runTest(dispatcher.scheduler) {
            viewModel.open()
            advanceUntilIdle()
            assertEquals(SelfTestPhase.PICKER, viewModel.state.value.phase)
            assertTrue(viewModel.state.value.canStart)

            viewModel.start()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(SelfTestPhase.REPORT, state.phase)
            assertEquals(CanaryClass.order.size, state.results.size)
            assertTrue(state.results.all { it.status == SelfTestStatus.PASS })
            assertEquals(
                CanaryClass.order.size,
                port.recorded
                    .single()
                    .results.size,
            )
            assertEquals(port.recorded.single(), state.lastRun)
            assertEquals(CanaryClass.order, port.startedWith.single())
        }

    @Test
    fun `a locked payload reports LOCKED and injects nothing`() =
        runTest(dispatcher.scheduler) {
            port.startResult = SelfTestStart.Locked
            viewModel.open()
            advanceUntilIdle()
            viewModel.start()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(SelfTestPhase.PICKER, state.phase)
            assertEquals(SelfTestError.LOCKED, state.error)
            assertTrue(port.recorded.isEmpty())
            assertTrue(port.awaited.isEmpty())
        }

    @Test
    fun `busy and invalid replies are surfaced without a run`() =
        runTest(dispatcher.scheduler) {
            port.startResult = SelfTestStart.Busy
            viewModel.open()
            advanceUntilIdle()
            viewModel.start()
            advanceUntilIdle()
            assertEquals(SelfTestError.BUSY, viewModel.state.value.error)

            port.startResult = SelfTestStart.Invalid
            viewModel.dismissError()
            viewModel.start()
            advanceUntilIdle()
            assertEquals(SelfTestError.INVALID, viewModel.state.value.error)
            assertTrue(port.recorded.isEmpty())
        }

    @Test
    fun `cancel during waiting reports the remaining canaries as cancelled`() =
        runTest(dispatcher.scheduler) {
            port.awaitSuspends = true
            viewModel.open()
            advanceUntilIdle()
            viewModel.start()
            advanceUntilIdle()
            assertEquals(SelfTestPhase.RUNNING, viewModel.state.value.phase)
            assertEquals(CanaryClass.FLASH, viewModel.state.value.current)

            viewModel.cancel()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(SelfTestPhase.REPORT, state.phase)
            assertEquals(listOf(FakePort.RUN_ID), port.cancelledRuns)
            val outcome = port.recorded.single()
            assertTrue(outcome.cancelled)
            assertTrue(outcome.results.all { it.status == SelfTestStatus.CANCELLED })
        }

    @Test
    fun `leaving the screen during a run cancels the port and still records the outcome`() =
        runTest(dispatcher.scheduler) {
            port.awaitSuspends = true
            viewModel.open()
            advanceUntilIdle()
            viewModel.start()
            advanceUntilIdle()
            assertEquals(SelfTestPhase.RUNNING, viewModel.state.value.phase)

            val store = ViewModelStore()
            store.put("self-test", viewModel)
            store.clear()
            advanceUntilIdle()

            assertEquals(listOf(FakePort.RUN_ID), port.cancelledRuns)
            val outcome = port.recorded.single()
            assertTrue(outcome.cancelled)
            assertEquals(CanaryClass.order.size, outcome.results.size)
            assertTrue(outcome.results.all { it.status == SelfTestStatus.CANCELLED })
        }

    @Test
    fun `blocking rules ask for confirmation before injecting`() =
        runTest(dispatcher.scheduler) {
            port.blockingRules = true
            viewModel.open()
            advanceUntilIdle()
            viewModel.start()
            advanceUntilIdle()

            assertEquals(SelfTestPhase.CONFIRM, viewModel.state.value.phase)
            assertTrue(port.startedWith.isEmpty())

            viewModel.dismissConfirm()
            advanceUntilIdle()
            assertEquals(SelfTestPhase.PICKER, viewModel.state.value.phase)
            assertTrue(port.startedWith.isEmpty())

            viewModel.start()
            advanceUntilIdle()
            viewModel.confirmStart()
            advanceUntilIdle()
            assertEquals(SelfTestPhase.REPORT, viewModel.state.value.phase)
            assertEquals(1, port.startedWith.size)
        }

    @Test
    fun `a class whose hook is not LIVE is reported SKIPPED and never awaited`() =
        runTest(dispatcher.scheduler) {
            port.hookStates = FakePort.LIVE_HOOKS + ("HK-14" to "FAILED")
            viewModel.open()
            advanceUntilIdle()
            viewModel.start()
            advanceUntilIdle()

            val results =
                viewModel.state.value.results
                    .associateBy { it.canary }
            assertEquals("SKIPPED:HK-14", results.getValue(CanaryClass.WAP_SI).reason)
            assertEquals(SelfTestStatus.SKIPPED, results.getValue(CanaryClass.WAP_CP).status)
            assertFalse(port.awaited.contains(CanaryClass.WAP_SI))
            assertFalse(port.awaited.contains(CanaryClass.WAP_CP))
            assertEquals(SelfTestStatus.PASS, results.getValue(CanaryClass.FLASH).status)
        }

    @Test
    fun `a throwing start ends in UNAVAILABLE instead of a crash`() =
        runTest(dispatcher.scheduler) {
            port.startThrows = true
            viewModel.open()
            advanceUntilIdle()
            viewModel.start()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(SelfTestPhase.PICKER, state.phase)
            assertEquals(SelfTestError.UNAVAILABLE, state.error)
            assertFalse(state.busy)
            assertTrue(port.recorded.isEmpty())
        }

    @Test
    fun `a throwing wait counts as a timeout and the run still reports`() =
        runTest(dispatcher.scheduler) {
            port.awaitThrows = true
            viewModel.open()
            advanceUntilIdle()
            viewModel.start()
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(SelfTestPhase.REPORT, state.phase)
            assertTrue(state.results.all { it.status == SelfTestStatus.TIMEOUT })
            assertEquals(1, port.recorded.size)
        }

    @Test
    fun `leaving the screen after the run finished does not record it again`() =
        runTest(dispatcher.scheduler) {
            viewModel.open()
            advanceUntilIdle()
            viewModel.start()
            advanceUntilIdle()
            assertEquals(SelfTestPhase.REPORT, viewModel.state.value.phase)

            val store = ViewModelStore()
            store.put("self-test", viewModel)
            store.clear()
            advanceUntilIdle()

            assertEquals(1, port.recorded.size)
            assertTrue(port.cancelledRuns.isEmpty())
        }

    @Test
    fun `the last stored run and coverage load when the screen opens`() =
        runTest(dispatcher.scheduler) {
            val stored = SelfTestOutcome("old-run", 5, 6, false, listOf(ClassResult(CanaryClass.FLASH, SelfTestStatus.PASS)))
            port.stored = stored
            port.coverageFlow.value = listOf(Coverage(ClassId.SILENT, 42L, null, null))
            val second = SelfTestViewModel(port, Clock { now }, appScope)
            advanceUntilIdle()

            assertEquals(stored, second.state.value.lastRun)
            assertEquals(stored.results, second.state.value.results)
            assertEquals(
                42L,
                second.state.value.coverage
                    .single { it.classId == ClassId.SILENT }
                    .lastSeenFromNetwork,
            )
            assertNotNull(second.state.value.lastRun)
            assertTrue(second.state.value.lastRunLoaded)
        }

    @Test
    fun `no stored run is loaded as never ran, and a failed read is not`() =
        runTest(dispatcher.scheduler) {
            // The setup view model reads at once on the test dispatcher, so only the end state is checked here.
            advanceUntilIdle()
            assertTrue(viewModel.state.value.lastRunLoaded)
            assertEquals(null, viewModel.state.value.lastRun)

            port.recentThrows = true
            val broken = SelfTestViewModel(port, Clock { now }, appScope)
            advanceUntilIdle()
            assertFalse(broken.state.value.lastRunLoaded)
        }

    @Test
    fun `show last report opens the stored run without a new run`() =
        runTest(dispatcher.scheduler) {
            val failed = ClassResult(CanaryClass.FLASH, SelfTestStatus.FAIL)
            port.stored = SelfTestOutcome("old-run", 5, 6, false, listOf(failed))
            val second = SelfTestViewModel(port, Clock { now }, appScope)
            advanceUntilIdle()

            second.showLastReport()

            assertEquals(SelfTestPhase.REPORT, second.state.value.phase)
            assertEquals(listOf(failed), second.state.value.results)
            assertTrue(port.startedWith.isEmpty())
        }

    @Test
    fun `reload picks up a run that another screen stored`() =
        runTest(dispatcher.scheduler) {
            advanceUntilIdle()
            val stored = SelfTestOutcome("other-run", 5, 6, false, listOf(ClassResult(CanaryClass.FLASH, SelfTestStatus.PASS)))
            port.stored = stored

            viewModel.reloadLastRun()
            advanceUntilIdle()

            assertEquals(stored, viewModel.state.value.lastRun)
        }

    @Test
    fun `show last report does nothing when no run is stored`() =
        runTest(dispatcher.scheduler) {
            advanceUntilIdle()
            viewModel.showLastReport()
            assertEquals(SelfTestPhase.IDLE, viewModel.state.value.phase)
        }

    private class FakePort : SelfTestPort {
        val coverageFlow = MutableStateFlow<List<Coverage>>(emptyList())
        override val coverage: Flow<List<Coverage>> = coverageFlow
        var hookStates: Map<String, String> = LIVE_HOOKS
        var blockingRules = false
        var startResult: SelfTestStart = SelfTestStart.Started(RUN_ID)
        var startThrows = false
        var observations: Map<ClassId, SelfTestObservation> = CanaryClass.order.associate { it.classId to pass(it) }
        var awaitSuspends = false
        var awaitThrows = false
        val awaited = mutableListOf<CanaryClass>()
        val startedWith = mutableListOf<List<CanaryClass>>()
        val cancelledRuns = mutableListOf<String>()
        val recorded = mutableListOf<SelfTestOutcome>()
        var stored: SelfTestOutcome? = null
        var recentThrows = false

        override suspend fun hookStates(): Map<String, String> = hookStates

        override suspend fun blockingRulesExist(): Boolean = blockingRules

        override suspend fun start(classes: List<CanaryClass>): SelfTestStart {
            startedWith += classes
            if (startThrows) error("binder died")
            return startResult
        }

        override suspend fun await(
            runId: String,
            canary: CanaryClass,
            timeoutMs: Long,
        ): SelfTestObservation? {
            awaited += canary
            if (awaitSuspends) awaitCancellation()
            if (awaitThrows) error("binder died")
            return observations[canary.classId]
        }

        override suspend fun cancel(runId: String) {
            cancelledRuns += runId
        }

        override suspend fun record(outcome: SelfTestOutcome) {
            recorded += outcome
            stored = outcome
        }

        override suspend fun recentResults(limit: Int): List<SelfTestOutcome> {
            if (recentThrows) error("database closed")
            return listOfNotNull(stored)
        }

        companion object {
            const val RUN_ID = "run-1"

            val LIVE_HOOKS: Map<String, String> =
                listOf("HK-06", "HK-07", "HK-08", "HK-14", "HK-15", "HK-23").associateWith { "LIVE" }

            fun pass(canary: CanaryClass): SelfTestObservation =
                SelfTestObservation(
                    runId = RUN_ID,
                    canaryClass = canary.classId,
                    classId = canary.classId,
                    fate = canary.expectedFate.wire(),
                    trace =
                        listOf(
                            TraceStepView(
                                canary.expectedStage,
                                canary.expectedDetail?.let { "ct=x $it" },
                            ),
                        ),
                    receivedAtWallMs = 0,
                )
        }
    }
}
