package dev.nightjar.app.feature.rules

import dev.nightjar.contract.Verdict
import dev.nightjar.rules.Conditions
import dev.nightjar.rules.Preset
import dev.nightjar.rules.Rule
import dev.nightjar.rules.RuleOrigin
import dev.nightjar.rules.RulesConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import dev.nightjar.app.data.model.Rule as StoredRule

/** User/preset split, enable, drag reorder and undo-delete. */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomRulesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var port: FakePort
    private lateinit var viewModel: CustomRulesViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        port = FakePort()
        viewModel = CustomRulesViewModel(port)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `preset rules are separated from user rules`() =
        runTest(dispatcher.scheduler) {
            collect()
            val state = viewModel.state.value
            assertEquals(listOf(5L, 6L), state.userRules.map { it.id })
            assertEquals(listOf(1L), state.presetRules.map { it.id })
            assertTrue(state.presetRules.single().fromPreset)
            assertEquals(3, state.userRules.first().matchCount)
        }

    @Test
    fun `the switch writes through the port`() =
        runTest(dispatcher.scheduler) {
            collect()
            viewModel.setEnabled(5L, false)
            advanceUntilIdle()
            assertEquals(listOf(5L to false), port.enabled)
        }

    @Test
    fun `dragging rewrites the user-rule order`() =
        runTest(dispatcher.scheduler) {
            collect()
            viewModel.reorder(listOf(6L, 5L))
            advanceUntilIdle()
            assertEquals(listOf(listOf(6L, 5L)), port.reorders)
        }

    @Test
    fun `move is a one-step reorder for TalkBack`() =
        runTest(dispatcher.scheduler) {
            collect()
            viewModel.move(6L, -1)
            advanceUntilIdle()
            assertEquals(listOf(listOf(6L, 5L)), port.reorders)
        }

    @Test
    fun `delete then undo re-creates the rule at its old index`() =
        runTest(dispatcher.scheduler) {
            val events = mutableListOf<CustomRulesEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
            advanceUntilIdle()

            viewModel.delete(6L)
            advanceUntilIdle()
            assertEquals(listOf(6L), port.deleted)
            val deleted = events.single() as CustomRulesEvent.Deleted

            viewModel.undoDelete(deleted.token)
            advanceUntilIdle()
            assertEquals(1, port.created.size)
            // The re-created id (99) is inserted where the deleted rule was (second position).
            assertEquals(listOf(5L, 99L), port.reorders.last())
        }

    @Test
    fun `undo does not list the re-created id two times when the store already shows it`() =
        runTest(dispatcher.scheduler) {
            port.createShowsInStore = true
            val events = mutableListOf<CustomRulesEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
            advanceUntilIdle()

            viewModel.delete(6L)
            advanceUntilIdle()
            viewModel.undoDelete((events.single() as CustomRulesEvent.Deleted).token)
            advanceUntilIdle()
            assertEquals(listOf(5L, 99L), port.reorders.last())
        }

    @Test
    fun `each undo restores its own rule after two quick deletes`() =
        runTest(dispatcher.scheduler) {
            val events = mutableListOf<CustomRulesEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
            advanceUntilIdle()

            viewModel.delete(5L)
            advanceUntilIdle()
            viewModel.delete(6L)
            advanceUntilIdle()
            val first = events.filterIsInstance<CustomRulesEvent.Deleted>().first()
            viewModel.undoDelete(first.token)
            advanceUntilIdle()
            assertEquals(listOf("first"), port.created)
            // A token is used once: a second tap restores nothing.
            viewModel.undoDelete(first.token)
            advanceUntilIdle()
            assertEquals(listOf("first"), port.created)
        }

    @Test
    fun `a failed write sends a failure without the exception text`() =
        runTest(dispatcher.scheduler) {
            port.failWrites = true
            val events = mutableListOf<CustomRulesEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
            advanceUntilIdle()
            viewModel.setEnabled(5L, false)
            advanceUntilIdle()
            assertEquals(listOf(CustomRulesEvent.Failed), events)
        }

    @Test
    fun `a preset rule is not deleted by the list`() =
        runTest(dispatcher.scheduler) {
            collect()
            viewModel.delete(1L)
            advanceUntilIdle()
            assertTrue(port.deleted.isEmpty())
        }

    @Test
    fun `an empty list reports empty`() =
        runTest(dispatcher.scheduler) {
            port.configFlow.value = RulesConfig.DEFAULT
            port.storedFlow.value = emptyList()
            collect()
            assertTrue(viewModel.state.value.isEmpty)
        }

    private fun kotlinx.coroutines.test.TestScope.collect() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        advanceUntilIdle()
    }

    private class FakePort : CustomRulesPort {
        val configFlow = MutableStateFlow(rulesConfig())
        val storedFlow = MutableStateFlow(storedRules())
        override val config: Flow<RulesConfig> get() = configFlow
        override val storedRules: Flow<List<StoredRule>> get() = storedFlow
        override val maskNumbers: Flow<Boolean> = MutableStateFlow(false)

        /** When true, [create] puts the new rule into the flows before it returns, as Room can. */
        var createShowsInStore = false

        /** When true, every write throws. */
        var failWrites = false

        val enabled = mutableListOf<Pair<Long, Boolean>>()
        val reorders = mutableListOf<List<Long>>()
        val deleted = mutableListOf<Long>()
        val created = mutableListOf<String>()

        override suspend fun setEnabled(
            id: Long,
            enabled: Boolean,
        ) {
            check(!failWrites) { "disk full" }
            this.enabled += id to enabled
        }

        override suspend fun reorder(orderedIds: List<Long>) {
            reorders += orderedIds
        }

        override suspend fun delete(id: Long) {
            deleted += id
            configFlow.value = configFlow.value.withRules(configFlow.value.rules.filter { it.id() != id })
            storedFlow.value = storedFlow.value.filterNot { it.id == id }
        }

        override suspend fun create(
            name: String,
            action: Verdict,
            conditions: Conditions,
            enabled: Boolean,
        ): Long {
            created += name
            if (createShowsInStore) {
                val rule = Rule(99L, 9, enabled, RuleOrigin.USER, name, action, conditions)
                configFlow.value = configFlow.value.withRules(configFlow.value.rules + rule)
                storedFlow.value = storedFlow.value + StoredRule(99L, 9, enabled, name, "{}", action, "USER", 0L, 0L, 0L, null)
            }
            return 99L
        }

        private companion object {
            fun rulesConfig(): RulesConfig =
                RulesConfig(
                    1,
                    2,
                    Preset.MONITOR,
                    emptyMap(),
                    listOf(
                        Rule(1L, 0, true, RuleOrigin.PRESET, "preset", Verdict.QUARANTINE, Conditions.ANY),
                        Rule(5L, 1, true, RuleOrigin.USER, "first", Verdict.BLOCK, Conditions.ANY),
                        Rule(6L, 2, true, RuleOrigin.USER, "second", Verdict.ALLOW, Conditions.ANY),
                    ),
                    emptyList(),
                    emptyList(),
                    5,
                )

            fun storedRules(): List<StoredRule> =
                listOf(
                    StoredRule(1L, 0, true, "preset", "{}", Verdict.QUARANTINE, "PRESET", 0L, 0L, 0L, null),
                    StoredRule(5L, 1, true, "first", "{}", Verdict.BLOCK, "USER", 0L, 0L, 3L, 1_700_000_000_000L),
                    StoredRule(6L, 2, true, "second", "{}", Verdict.ALLOW, "USER", 0L, 0L, 0L, null),
                )
        }
    }
}
