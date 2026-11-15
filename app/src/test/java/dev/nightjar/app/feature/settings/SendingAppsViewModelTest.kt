package dev.nightjar.app.feature.settings

import dev.nightjar.app.core.settings.SendingApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** The list follows the query, and a block happens only after the confirm. */
@OptIn(ExperimentalCoroutinesApi::class)
class SendingAppsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val apps = MutableStateFlow<List<SendingApp>>(emptyList())
    private val writes = mutableListOf<Pair<String, Boolean>>()
    private var failWrites = false
    private var defaultSms: String? = "com.example.sms"
    private var failDefaultLookup = false
    private lateinit var viewModel: SendingAppsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel =
            SendingAppsViewModel(
                apps = apps,
                setSendBlocked = { packageName, blocked ->
                    if (failWrites) error("disk full")
                    writes += packageName to blocked
                },
                defaultSmsPackage = {
                    if (failDefaultLookup) error("no role service")
                    defaultSms
                },
            )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the list follows the query`() =
        runTest(dispatcher.scheduler) {
            collect()
            assertFalse(viewModel.state.value.loading)
            assertTrue(
                viewModel.state.value.apps
                    .isEmpty(),
            )

            val first = listOf(app("com.example.one"))
            apps.value = first
            advanceUntilIdle()
            assertEquals(first, viewModel.state.value.apps)

            val second = listOf(app("com.example.two"), app("com.example.one", blocked = true))
            apps.value = second
            advanceUntilIdle()
            assertEquals(second, viewModel.state.value.apps)
        }

    @Test
    fun `turning a switch on opens the confirm and blocks nothing`() =
        runTest(dispatcher.scheduler) {
            collect()
            viewModel.onSwitch("com.example.one", block = true)
            advanceUntilIdle()
            assertEquals("com.example.one", viewModel.state.value.confirmBlock)
            assertTrue(writes.isEmpty())
        }

    @Test
    fun `the confirm blocks the app and closes`() =
        runTest(dispatcher.scheduler) {
            collect()
            viewModel.onSwitch("com.example.one", block = true)
            advanceUntilIdle()
            viewModel.confirmBlock()
            advanceUntilIdle()
            assertEquals(listOf("com.example.one" to true), writes)
            assertNull(viewModel.state.value.confirmBlock)
        }

    @Test
    fun `cancel blocks nothing`() =
        runTest(dispatcher.scheduler) {
            collect()
            viewModel.onSwitch("com.example.one", block = true)
            advanceUntilIdle()
            viewModel.dismissBlock()
            viewModel.confirmBlock()
            advanceUntilIdle()
            assertTrue(writes.isEmpty())
            assertNull(viewModel.state.value.confirmBlock)
        }

    @Test
    fun `turning a switch off allows sending at once`() =
        runTest(dispatcher.scheduler) {
            collect()
            viewModel.onSwitch("com.example.one", block = false)
            advanceUntilIdle()
            assertEquals(listOf("com.example.one" to false), writes)
            assertNull(viewModel.state.value.confirmBlock)
        }

    @Test
    fun `the confirm warns only for the default SMS app`() =
        runTest(dispatcher.scheduler) {
            collect()
            viewModel.onSwitch("com.example.one", block = true)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.confirmDefaultSmsApp)
            viewModel.dismissBlock()

            viewModel.onSwitch("com.example.sms", block = true)
            advanceUntilIdle()
            assertEquals("com.example.sms", viewModel.state.value.confirmBlock)
            assertTrue(viewModel.state.value.confirmDefaultSmsApp)
            assertTrue(writes.isEmpty())

            viewModel.confirmBlock()
            advanceUntilIdle()
            assertEquals(listOf("com.example.sms" to true), writes)
            assertFalse(viewModel.state.value.confirmDefaultSmsApp)
        }

    @Test
    fun `a failed default SMS app lookup still opens the confirm, without the warning`() =
        runTest(dispatcher.scheduler) {
            collect()
            failDefaultLookup = true
            viewModel.onSwitch("com.example.sms", block = true)
            advanceUntilIdle()
            assertEquals("com.example.sms", viewModel.state.value.confirmBlock)
            assertFalse(viewModel.state.value.confirmDefaultSmsApp)
        }

    @Test
    fun `no default SMS app gives no warning`() =
        runTest(dispatcher.scheduler) {
            collect()
            defaultSms = null
            viewModel.onSwitch("com.example.sms", block = true)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.confirmDefaultSmsApp)
        }

    @Test
    fun `a failed write shows the save error`() =
        runTest(dispatcher.scheduler) {
            val events = collect()
            failWrites = true
            viewModel.onSwitch("com.example.one", block = false)
            advanceUntilIdle()
            assertEquals(listOf<SendingAppsEvent>(SendingAppsEvent.SaveFailed), events)
        }

    private fun app(
        packageName: String,
        blocked: Boolean = false,
    ) = SendingApp(packageName, lastSentWall = 1_000L, count = 1, blocked = blocked)

    private fun TestScope.collect(): MutableList<SendingAppsEvent> {
        val events = mutableListOf<SendingAppsEvent>()
        // Unconfined: the event is recorded at the send. advanceUntilIdle stops when only background work is left, so
        // a background collector on the standard dispatcher may not run before the assertion.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
        return events
    }
}
