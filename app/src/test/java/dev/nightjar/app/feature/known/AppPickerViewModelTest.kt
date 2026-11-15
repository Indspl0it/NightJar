package dev.nightjar.app.feature.known

import dev.nightjar.app.data.model.TrustedPackage
import dev.nightjar.app.data.repository.TrustedPackageRepository
import dev.nightjar.app.domain.known.AppFilter
import dev.nightjar.app.domain.known.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The trust picker state, selection and save. Example packages only. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppPickerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val notes = InstalledApp("dev.example.notes", "Notes", system = false)
    private val alarm = InstalledApp("dev.example.alarm", "alarm", system = false)
    private val phone = InstalledApp("dev.example.phone", "Phone services", system = true)
    private lateinit var source: FakeSource
    private lateinit var trusted: FakeTrustedPackages
    private lateinit var viewModel: AppPickerViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        source = FakeSource(listOf(notes, phone, alarm))
        trusted = FakeTrustedPackages()
        viewModel = AppPickerViewModel(source, trusted)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `start loads the user apps sorted by label and selects the trusted ones`() =
        runTest(dispatcher.scheduler) {
            trusted.add(notes.packageName, "Notes")
            viewModel.start()
            assertTrue(viewModel.state.value.loading)
            advanceUntilIdle()
            val state = viewModel.state.value
            assertFalse(state.loading)
            assertEquals(listOf(alarm, notes), state.rows)
            assertEquals(setOf(notes.packageName), state.selected)
            assertEquals(0, state.changes)
        }

    @Test
    fun `filter and search change the rows, not the selection`() =
        runTest(dispatcher.scheduler) {
            started()
            viewModel.toggle(alarm.packageName)
            viewModel.setFilter(AppFilter.SYSTEM)
            assertEquals(listOf(phone), viewModel.state.value.rows)
            viewModel.setFilter(AppFilter.ALL)
            viewModel.setQuery("NOT")
            assertEquals(listOf(notes), viewModel.state.value.rows)
            assertEquals(setOf(alarm.packageName), viewModel.state.value.selected)
        }

    @Test
    fun `select all, invert and clear act on the shown rows`() =
        runTest(dispatcher.scheduler) {
            started()
            viewModel.selectAll()
            assertEquals(setOf(alarm.packageName, notes.packageName), viewModel.state.value.selected)
            viewModel.toggle(notes.packageName)
            viewModel.invert()
            assertEquals(setOf(notes.packageName), viewModel.state.value.selected)
            viewModel.setFilter(AppFilter.ALL)
            viewModel.toggle(phone.packageName)
            viewModel.setFilter(AppFilter.USER)
            viewModel.clear()
            assertEquals(setOf(phone.packageName), viewModel.state.value.selected)
            assertEquals(1, viewModel.state.value.changes)
        }

    @Test
    fun `save adds new checks with their label and removes cleared trusts`() =
        runTest(dispatcher.scheduler) {
            trusted.add(notes.packageName, "Notes")
            trusted.add("dev.example.gone", null)
            trusted.writes.clear()
            val events = collectEvents()
            started()
            viewModel.toggle(notes.packageName)
            viewModel.toggle(alarm.packageName)
            assertEquals(2, viewModel.state.value.changes)
            viewModel.save()
            advanceUntilIdle()

            assertEquals(listOf("remove:dev.example.notes", "add:dev.example.alarm"), trusted.writes)
            // A trusted app that is not installed is not in the list and keeps its trust.
            assertEquals(
                setOf(alarm.packageName, "dev.example.gone"),
                trusted.rows.value
                    .map { it.packageName }
                    .toSet(),
            )
            assertEquals(
                "alarm",
                trusted.rows.value
                    .first { it.packageName == alarm.packageName }
                    .label,
            )
            assertEquals(listOf<AppPickerEvent>(AppPickerEvent.Saved(added = 1, removed = 1)), events)
            assertEquals(0, viewModel.state.value.changes)
        }

    @Test
    fun `save with no change writes nothing and closes`() =
        runTest(dispatcher.scheduler) {
            val events = collectEvents()
            started()
            viewModel.save()
            advanceUntilIdle()
            assertTrue(trusted.writes.isEmpty())
            assertEquals(listOf<AppPickerEvent>(AppPickerEvent.Saved(0, 0)), events)
        }

    @Test
    fun `a failed write reports Failed and compares the next save with the stored trusts`() =
        runTest(dispatcher.scheduler) {
            val events = collectEvents()
            started()
            viewModel.toggle(alarm.packageName)
            viewModel.toggle(notes.packageName)
            trusted.failAddOf = notes.packageName
            viewModel.save()
            advanceUntilIdle()
            assertEquals(listOf<AppPickerEvent>(AppPickerEvent.Failed), events)
            assertFalse(viewModel.state.value.saving)
            // alarm is stored; only notes is still a change.
            assertEquals(1, viewModel.state.value.changes)
        }

    @Test
    fun `the checks do not change while a save runs`() =
        runTest(dispatcher.scheduler) {
            started()
            viewModel.toggle(alarm.packageName)
            viewModel.save()
            assertTrue(viewModel.state.value.saving)
            viewModel.toggle(notes.packageName)
            viewModel.selectAll()
            viewModel.invert()
            viewModel.clear()
            assertEquals(setOf(alarm.packageName), viewModel.state.value.selected)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.saving)
            // After the save the checks change again.
            viewModel.toggle(notes.packageName)
            assertEquals(setOf(alarm.packageName, notes.packageName), viewModel.state.value.selected)
        }

    @Test
    fun `a save result that arrives after close does not reach the next opening`() =
        runTest(dispatcher.scheduler) {
            started()
            viewModel.toggle(alarm.packageName)
            viewModel.save()
            // The user closes the picker during the save: no collector. The save ends and its result waits.
            advanceUntilIdle()
            viewModel.start()
            val events = collectEvents()
            advanceUntilIdle()
            assertEquals(emptyList<AppPickerEvent>(), events)
            assertEquals(setOf(alarm.packageName), viewModel.state.value.selected)
            assertEquals(0, viewModel.state.value.changes)
        }

    @Test
    fun `a save that ends during the next opening does not change it`() =
        runTest(dispatcher.scheduler) {
            val events = collectEvents()
            started()
            viewModel.toggle(alarm.packageName)
            trusted.failAddOf = alarm.packageName
            viewModel.save()
            // The picker closes and opens again before the save runs.
            viewModel.start()
            advanceUntilIdle()
            // No Failed snackbar and no close in the new opening, and its state is its own.
            assertEquals(emptyList<AppPickerEvent>(), events)
            val state = viewModel.state.value
            assertFalse(state.saving)
            assertFalse(state.loading)
            assertEquals(emptySet<String>(), state.selected)
            assertEquals(0, state.changes)
            // The new opening still saves and closes.
            viewModel.toggle(notes.packageName)
            viewModel.save()
            advanceUntilIdle()
            assertEquals(listOf<AppPickerEvent>(AppPickerEvent.Saved(added = 1, removed = 0)), events)
        }

    @Test
    fun `a failed read shows the error and start reads again`() =
        runTest(dispatcher.scheduler) {
            source.fail = true
            viewModel.start()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.failed)
            source.fail = false
            viewModel.start()
            advanceUntilIdle()
            assertFalse(viewModel.state.value.failed)
            assertEquals(listOf(alarm, notes), viewModel.state.value.rows)
        }

    private fun TestScope.started() {
        viewModel.start()
        advanceUntilIdle()
    }

    private fun TestScope.collectEvents(): List<AppPickerEvent> {
        val out = mutableListOf<AppPickerEvent>()
        // Unconfined: advanceUntilIdle does not run a backgroundScope task that waits on the standard dispatcher.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(out) }
        return out
    }

    private class FakeSource(
        private val apps: List<InstalledApp>,
    ) : InstalledAppSource {
        var fail = false

        override suspend fun load(): List<InstalledApp> {
            check(!fail) { "package manager not ready" }
            return apps
        }
    }

    /** In-memory trust store with the upsert semantics of the Room table (one row per package). Example packages only. */
    private class FakeTrustedPackages : TrustedPackageRepository {
        val rows = MutableStateFlow<List<TrustedPackage>>(emptyList())
        val writes = mutableListOf<String>()

        /** The next add of this package throws. */
        var failAddOf: String? = null

        override fun observeAll(): Flow<List<TrustedPackage>> = rows

        override suspend fun all(): List<TrustedPackage> = rows.value

        override suspend fun add(
            packageName: String,
            label: String?,
        ) {
            if (packageName == failAddOf) {
                failAddOf = null
                error("disk full")
            }
            writes += "add:$packageName"
            rows.value = rows.value.filterNot { it.packageName == packageName } + TrustedPackage(packageName, label, 0L)
        }

        override suspend fun remove(packageName: String): Boolean {
            writes += "remove:$packageName"
            val before = rows.value.size
            rows.value = rows.value.filterNot { it.packageName == packageName }
            return rows.value.size != before
        }

        override suspend fun isTrusted(packageName: String): Boolean = rows.value.any { it.packageName == packageName }
    }
}
