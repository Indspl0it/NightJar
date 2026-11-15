package dev.nightjar.app.feature.firewall

import dev.nightjar.app.domain.protection.ProtectionReason
import dev.nightjar.app.domain.protection.ProtectionReport
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.ProtectionState
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.Preset
import dev.nightjar.rules.Rule
import dev.nightjar.rules.RuleOrigin
import dev.nightjar.rules.RulesConfig
import dev.nightjar.rules.StringMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Class-action matrix, guard-rail dialogs, preset replacement, blocking switch and read-only mode. */
@OptIn(ExperimentalCoroutinesApi::class)
class FirewallViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var port: FakePort
    private lateinit var viewModel: FirewallViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        port = FakePort()
        viewModel = FirewallViewModel(port)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the rows come from the enforcement matrix and the custom count from the snapshot`() =
        runTest(dispatcher.scheduler) {
            port.rulesFlow.value =
                RulesConfig(
                    1,
                    3,
                    Preset.CUSTOM,
                    mapOf(ClassId.WAP_CP to Verdict.QUARANTINE),
                    listOf(
                        Rule(
                            5L,
                            1,
                            true,
                            RuleOrigin.USER,
                            "mine",
                            Verdict.ALLOW,
                            dev.nightjar.rules.Conditions
                                .builder()
                                .originator(StringMatch(StringMatch.Op.IS, "+919"))
                                .build(),
                        ),
                    ),
                    emptyList(),
                    emptyList(),
                    5,
                )
            collectState()
            val state = viewModel.state.value
            assertEquals(PresetId.CUSTOM, state.preset)
            assertEquals(1, state.userRuleCount)
            assertEquals(Verdict.QUARANTINE, state.action(ClassId.WAP_CP))
            val silent = state.rows.first { it.classId == ClassId.SILENT }
            assertEquals(listOf(Verdict.ALLOW, Verdict.NOTIFY), silent.allowedActions)
            assertTrue(state.rows.none { it.classId == ClassId.NORMAL }, "normal messages have no class action")
        }

    @Test
    fun `blocking SIM updates waits for the confirmation`() =
        runTest(dispatcher.scheduler) {
            collectState()
            viewModel.requestClassAction(ClassId.SIM_DD, Verdict.BLOCK)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.dialog is FirewallDialog.GuardSimUpdate)
            assertTrue(port.classActions.isEmpty())

            viewModel.confirmDialog()
            advanceUntilIdle()
            assertEquals(listOf(ClassId.SIM_DD to Verdict.BLOCK), port.classActions)
            assertNull(viewModel.state.value.dialog)
        }

    @Test
    fun `cancelling a guard rail keeps the previous action`() =
        runTest(dispatcher.scheduler) {
            collectState()
            viewModel.requestClassAction(ClassId.SIM_DD, Verdict.BLOCK)
            advanceUntilIdle()
            viewModel.dismissDialog()
            advanceUntilIdle()
            assertNull(viewModel.state.value.dialog)
            assertTrue(port.classActions.isEmpty())
        }

    @Test
    fun `blocking a voicemail class warns first`() =
        runTest(dispatcher.scheduler) {
            collectState()
            // CL-MWI accepts BLOCK. CL-VVM does not: no hook can stop it.
            viewModel.requestClassAction(ClassId.MWI, Verdict.BLOCK)
            advanceUntilIdle()
            val dialog = viewModel.state.value.dialog
            assertTrue(dialog is FirewallDialog.GuardConsumerClass)
            assertEquals(ClassId.MWI, (dialog as FirewallDialog.GuardConsumerClass).classId)
        }

    @Test
    fun `a preset change over custom actions asks for confirmation and cancel does nothing`() =
        runTest(dispatcher.scheduler) {
            port.rulesFlow.value = RulesConfig(1, 0, Preset.CUSTOM, emptyMap(), emptyList(), emptyList(), emptyList(), 5)
            collectState()
            viewModel.requestPreset(PresetId.RECOMMENDED)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.dialog is FirewallDialog.PresetReplace)
            viewModel.dismissDialog()
            advanceUntilIdle()
            assertTrue(port.presets.isEmpty())

            viewModel.requestPreset(PresetId.RECOMMENDED)
            advanceUntilIdle()
            viewModel.confirmDialog()
            advanceUntilIdle()
            assertEquals(listOf(Preset.RECOMMENDED), port.presets)
        }

    @Test
    fun `turning blocking off asks first, then writes`() =
        runTest(dispatcher.scheduler) {
            collectState()
            viewModel.requestBlocking(false)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.dialog is FirewallDialog.BlockingOffConfirm)
            assertTrue(port.blockingWrites.isEmpty())

            viewModel.confirmDialog()
            advanceUntilIdle()
            assertEquals(listOf(false), port.blockingWrites)
            assertNull(viewModel.state.value.dialog)
        }

    @Test
    fun `turning blocking on writes at once`() =
        runTest(dispatcher.scheduler) {
            port.blockingFlow.value = false
            collectState()
            viewModel.requestBlocking(true)
            advanceUntilIdle()
            assertNull(viewModel.state.value.dialog)
            assertEquals(listOf(true), port.blockingWrites)
        }

    @Test
    fun `an unavailable firewall makes the screen read-only`() =
        runTest(dispatcher.scheduler) {
            port.protection.value =
                ProtectionReport(
                    state = ProtectionState.MONITOR_ONLY,
                    reason = ProtectionReason.NONE,
                    blockingPaused = false,
                    firewallAvailable = false,
                    firewallEnabled = false,
                )
            collectState()
            val state = viewModel.state.value
            assertTrue(state.readOnly)
            assertTrue(state.reasons.contains(FirewallReason.HOOKS_FAILED))

            viewModel.requestClassAction(ClassId.WAP_CP, Verdict.QUARANTINE)
            advanceUntilIdle()
            assertNull(state.dialog)
            assertTrue(port.classActions.isEmpty())
        }

    @Test
    fun `actions not in the matrix are ignored`() =
        runTest(dispatcher.scheduler) {
            collectState()
            viewModel.requestClassAction(ClassId.SILENT, Verdict.QUARANTINE)
            advanceUntilIdle()
            assertNull(viewModel.state.value.dialog)
            assertTrue(port.classActions.isEmpty())
        }

    @Test
    fun `the firewall state is refreshed each time the screen is shown`() =
        runTest(dispatcher.scheduler) {
            collectState()
            viewModel.onShown()
            advanceUntilIdle()
            assertEquals(1, port.refreshCount)
            viewModel.onShown()
            advanceUntilIdle()
            assertEquals(2, port.refreshCount)
        }

    @Test
    fun `a stored preset with other class actions shows Custom and a preset change asks for confirmation`() =
        runTest(dispatcher.scheduler) {
            port.rulesFlow.value =
                RulesConfig(
                    1,
                    0,
                    Preset.MONITOR,
                    mapOf(ClassId.WAP_CP to Verdict.QUARANTINE),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    5,
                )
            collectState()
            assertEquals(PresetId.CUSTOM, viewModel.state.value.preset)
            viewModel.requestPreset(PresetId.MONITOR)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.dialog is FirewallDialog.PresetReplace)
            assertTrue(port.presets.isEmpty())
        }

    @Test
    fun `turning notify on writes NOTIFY without a dialog`() =
        runTest(dispatcher.scheduler) {
            collectState()
            val row =
                viewModel.state.value.rows
                    .first { it.classId == ClassId.SILENT }
            val verdict = verdictForNotify(row, notify = true)
            assertEquals(Verdict.NOTIFY, verdict)
            viewModel.requestClassAction(ClassId.SILENT, Verdict.NOTIFY)
            advanceUntilIdle()
            assertNull(viewModel.state.value.dialog)
            assertEquals(listOf(ClassId.SILENT to Verdict.NOTIFY), port.classActions)
        }

    private fun kotlinx.coroutines.test.TestScope.collectState() {
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
    }

    private class FakePort : FirewallPort {
        val rulesFlow = MutableStateFlow(RulesConfig.DEFAULT)
        override val rules: Flow<RulesConfig> = rulesFlow
        val blockingFlow = MutableStateFlow(true)
        override val blockingOn: Flow<Boolean> = blockingFlow
        override val protection: MutableStateFlow<ProtectionReport?> =
            MutableStateFlow(
                ProtectionReport(
                    state = ProtectionState.PROTECTED,
                    reason = ProtectionReason.NONE,
                    blockingPaused = false,
                    firewallAvailable = true,
                    firewallEnabled = true,
                ),
            )

        val classActions = mutableListOf<Pair<ClassId, Verdict>>()
        val presets = mutableListOf<Preset>()
        val blockingWrites = mutableListOf<Boolean>()
        var refreshCount = 0

        override suspend fun setClassAction(
            classId: ClassId,
            verdict: Verdict,
        ) {
            classActions += classId to verdict
        }

        override suspend fun applyPreset(preset: Preset) {
            presets += preset
        }

        override suspend fun setBlocking(on: Boolean): Boolean {
            blockingWrites += on
            blockingFlow.value = on
            return true
        }

        override suspend fun refreshProtection() {
            refreshCount++
        }
    }
}
