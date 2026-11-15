package dev.nightjar.app.feature.known

import dev.nightjar.app.data.model.KnownOriginator
import dev.nightjar.app.data.model.KnownOriginatorKind
import dev.nightjar.app.data.model.Mute
import dev.nightjar.app.data.model.MuteScope
import dev.nightjar.app.data.model.TrustedPackage
import dev.nightjar.app.data.repository.KnownOriginatorRepository
import dev.nightjar.app.data.repository.MuteRepository
import dev.nightjar.app.data.repository.TrustedPackageRepository
import dev.nightjar.contract.ClassId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Edit with restore (.1), duplicate edit, token-based undo and fixed failure events. Senders are
 * synthetic short codes and sender IDs, never phone numbers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnownSendersViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var senders: FakeSenders
    private lateinit var apps: FakeApps
    private lateinit var mutes: FakeMutes
    private lateinit var viewModel: KnownSendersViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        senders = FakeSenders()
        apps = FakeApps()
        mutes = FakeMutes()
        senders.seed("NJ-TEST", "carrier")
        senders.seed("12345", null)
        viewModel = KnownSendersViewModel(senders, apps, mutes)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an edit that the store rejects puts the old sender back`() =
        runTest(dispatcher.scheduler) {
            val events = collectEvents()
            val original = senders.rows.value.first()
            senders.rejectAdds = 1
            viewModel.openEdit(original)
            advanceUntilIdle()
            viewModel.updateForm(
                viewModel.state.value.form!!
                    .copy(value = "NJ-NEW"),
            )
            viewModel.saveForm()
            advanceUntilIdle()

            assertEquals(
                listOf("NJ-TEST", "12345").sorted(),
                senders.rows.value
                    .map { it.value }
                    .sorted(),
            )
            assertEquals(KnownValidation.DUPLICATE, viewModel.state.value.validation)
            assertEquals(emptyList<KnownSendersEvent>(), events)
        }

    @Test
    fun `an edit that throws puts the old sender back and reports a fixed failure`() =
        runTest(dispatcher.scheduler) {
            val events = collectEvents()
            val original = senders.rows.value.first()
            senders.throwAdds = 1
            viewModel.openEdit(original)
            advanceUntilIdle()
            viewModel.updateForm(
                viewModel.state.value.form!!
                    .copy(value = "NJ-NEW"),
            )
            viewModel.saveForm()
            advanceUntilIdle()

            assertEquals(
                listOf("NJ-TEST", "12345").sorted(),
                senders.rows.value
                    .map { it.value }
                    .sorted(),
            )
            assertEquals(listOf<KnownSendersEvent>(KnownSendersEvent.Failed), events)
        }

    @Test
    fun `a failed restore is reported`() =
        runTest(dispatcher.scheduler) {
            val events = collectEvents()
            val original = senders.rows.value.first()
            senders.throwAdds = 2
            viewModel.openEdit(original)
            advanceUntilIdle()
            viewModel.updateForm(
                viewModel.state.value.form!!
                    .copy(value = "NJ-NEW"),
            )
            viewModel.saveForm()
            advanceUntilIdle()

            // One failure for the restore, one for the save.
            assertEquals(listOf<KnownSendersEvent>(KnownSendersEvent.Failed, KnownSendersEvent.Failed), events)
        }

    @Test
    fun `an edit to a value that exists under another id keeps both rows`() =
        runTest(dispatcher.scheduler) {
            collectEvents()
            val original = senders.rows.value.first()
            viewModel.openEdit(original)
            advanceUntilIdle()
            viewModel.updateForm(
                viewModel.state.value.form!!
                    .copy(value = "12345"),
            )
            viewModel.saveForm()
            advanceUntilIdle()

            assertEquals(2, senders.rows.value.size)
            assertEquals(0, senders.removed.size)
            assertEquals(KnownValidation.DUPLICATE, viewModel.state.value.validation)
        }

    @Test
    fun `a good edit closes the form and sends Saved`() =
        runTest(dispatcher.scheduler) {
            val events = collectEvents()
            viewModel.openEdit(senders.rows.value.first())
            advanceUntilIdle()
            viewModel.updateForm(
                viewModel.state.value.form!!
                    .copy(value = "NJ-NEW", label = "bank"),
            )
            viewModel.saveForm()
            advanceUntilIdle()

            assertNull(viewModel.state.value.form)
            assertEquals(listOf<KnownSendersEvent>(KnownSendersEvent.Saved), events)
            assertEquals(
                "bank",
                senders.rows.value
                    .single { it.value == "NJ-NEW" }
                    .label,
            )
        }

    @Test
    fun `each undo restores its own entry after two quick deletes`() =
        runTest(dispatcher.scheduler) {
            val events = collectEvents()
            val first = senders.rows.value[0]
            val second = senders.rows.value[1]
            viewModel.deleteSender(first)
            advanceUntilIdle()
            viewModel.deleteSender(second)
            advanceUntilIdle()
            val tokens = events.filterIsInstance<KnownSendersEvent.Deleted>()
            assertEquals(listOf(KnownEntryKind.SENDER, KnownEntryKind.SENDER), tokens.map { it.kind })

            viewModel.undoDelete(tokens.first().token)
            advanceUntilIdle()
            assertEquals(listOf(first.value), senders.rows.value.map { it.value })
            // A token is used once: a second tap restores nothing.
            viewModel.undoDelete(tokens.first().token)
            advanceUntilIdle()
            assertEquals(listOf(first.value), senders.rows.value.map { it.value })
        }

    @Test
    fun `undo of an unmute sends the mute kind and keeps the expiry`() =
        runTest(dispatcher.scheduler) {
            val events = collectEvents()
            val mute = Mute(id = 1, scope = MuteScope.CLASS, value = ClassId.NORMAL.wire(), until = 42L)
            mutes.rows.value = listOf(mute)
            viewModel.clearMute(mute)
            advanceUntilIdle()
            val deleted = events.single() as KnownSendersEvent.Deleted
            assertEquals(KnownEntryKind.MUTE, deleted.kind)

            viewModel.undoDelete(deleted.token)
            advanceUntilIdle()
            assertEquals(listOf(Triple(MuteScope.CLASS, ClassId.NORMAL.wire(), 42L)), mutes.muted)
            assertEquals(KnownSendersEvent.Restored(KnownEntryKind.MUTE), events.last())
        }

    @Test
    fun `a failed delete leaves no restore point`() =
        runTest(dispatcher.scheduler) {
            val events = collectEvents()
            val app = TrustedPackage(packageName = "com.example.app", label = null, addedAt = 0L)
            apps.failRemove = true
            viewModel.removeTrustedApp(app)
            advanceUntilIdle()
            assertEquals(listOf<KnownSendersEvent>(KnownSendersEvent.Failed), events)
        }

    @Test
    fun `the picker opens on the Apps tab and a saved change is reported once`() =
        runTest(dispatcher.scheduler) {
            val events = collectEvents()
            viewModel.openPicker()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.pickerOpen)
            assertEquals(KnownTab.APPS, viewModel.state.value.tab)

            // Close without a change: no message.
            viewModel.closePicker(saved = false)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.pickerOpen)
            assertEquals(emptyList<KnownSendersEvent>(), events)

            viewModel.openPicker()
            viewModel.closePicker(saved = true)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.pickerOpen)
            assertEquals(listOf<KnownSendersEvent>(KnownSendersEvent.TrustSaved), events)
        }

    @Test
    fun `an empty value is refused before any write`() =
        runTest(dispatcher.scheduler) {
            collectEvents()
            viewModel.openAdd()
            viewModel.saveForm()
            advanceUntilIdle()
            assertEquals(KnownValidation.EMPTY, viewModel.state.value.validation)
            assertEquals(2, senders.rows.value.size)
        }

    private fun TestScope.collectEvents(): MutableList<KnownSendersEvent> {
        val events = mutableListOf<KnownSendersEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
        advanceUntilIdle()
        return events
    }

    private class FakeSenders : KnownOriginatorRepository {
        val rows = MutableStateFlow<List<KnownOriginator>>(emptyList())
        val removed = mutableListOf<Long>()
        private var nextId = 1L

        /** The next n adds return null, as a lost race on the unique index does. */
        var rejectAdds = 0

        /** The next n adds throw. */
        var throwAdds = 0

        fun seed(
            value: String,
            label: String?,
        ) {
            rows.value = rows.value + KnownOriginator(nextId++, value, KnownOriginatorKind.EXACT, label, 0L)
        }

        override fun observeAll(): Flow<List<KnownOriginator>> = rows

        override suspend fun all(): List<KnownOriginator> = rows.value

        override suspend fun add(
            value: String,
            kind: KnownOriginatorKind,
            label: String?,
        ): KnownOriginator? {
            if (throwAdds > 0) {
                throwAdds--
                error("disk full")
            }
            if (rejectAdds > 0) {
                rejectAdds--
                return null
            }
            if (rows.value.any { it.value.equals(value, ignoreCase = true) && it.kind == kind }) return null
            val row = KnownOriginator(nextId++, value, kind, label, 0L)
            rows.value = rows.value + row
            return row
        }

        override suspend fun remove(id: Long): Boolean {
            removed += id
            val before = rows.value.size
            rows.value = rows.value.filterNot { it.id == id }
            return rows.value.size != before
        }

        override suspend fun removeValue(value: String): Boolean = false

        override suspend fun isKnown(originator: String): Boolean = rows.value.any { it.value == originator }
    }

    private class FakeApps : TrustedPackageRepository {
        val rows = MutableStateFlow<List<TrustedPackage>>(emptyList())
        var failRemove = false

        override fun observeAll(): Flow<List<TrustedPackage>> = rows

        override suspend fun all(): List<TrustedPackage> = rows.value

        override suspend fun add(
            packageName: String,
            label: String?,
        ) {
            rows.value = rows.value + TrustedPackage(packageName, label, 0L)
        }

        override suspend fun remove(packageName: String): Boolean {
            check(!failRemove) { "disk full" }
            rows.value = rows.value.filterNot { it.packageName == packageName }
            return true
        }

        override suspend fun isTrusted(packageName: String): Boolean = rows.value.any { it.packageName == packageName }
    }

    private class FakeMutes : MuteRepository {
        val rows = MutableStateFlow<List<Mute>>(emptyList())
        val muted = mutableListOf<Triple<MuteScope, String, Long>>()

        override fun observeActive(): Flow<List<Mute>> = rows

        override suspend fun active(): List<Mute> = rows.value

        override suspend fun mute(
            scope: MuteScope,
            value: String,
            untilWallMs: Long,
        ) {
            muted += Triple(scope, value, untilWallMs)
        }

        override suspend fun clear(
            scope: MuteScope,
            value: String,
        ): Boolean {
            rows.value = rows.value.filterNot { it.scope == scope && it.value == value }
            return true
        }

        override suspend fun clearExpired(): Int = 0

        override suspend fun isMuted(
            originator: String?,
            classId: ClassId,
            nowWallMs: Long,
        ): Boolean = false
    }
}
