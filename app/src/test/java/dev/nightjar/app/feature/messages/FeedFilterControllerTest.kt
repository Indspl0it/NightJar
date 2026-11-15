package dev.nightjar.app.feature.messages

import dev.nightjar.app.core.settings.DataStoreSettingsStore
import dev.nightjar.app.core.settings.InMemoryPreferencesDataStore
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.model.AuditEntry
import dev.nightjar.app.data.model.FilterSpec
import dev.nightjar.app.data.repository.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The feed filter starts from the saved "Delivery reports" setting. */
class FeedFilterControllerTest {
    private lateinit var scope: CoroutineScope
    private lateinit var settings: DataStoreSettingsStore

    private class NoAudit : AuditLog {
        override suspend fun record(
            action: AuditAction,
            actor: AuditActor,
            target: String?,
            before: String?,
            after: String?,
        ) = Unit

        override fun observe(limit: Int): Flow<List<AuditEntry>> = MutableStateFlow(emptyList())

        override suspend fun since(atWallMs: Long): List<AuditEntry> = emptyList()

        override suspend fun purgeBefore(cutoffWallMs: Long): Int = 0
    }

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // In memory: the file-backed DataStore cannot replace its file on a Windows JVM.
        settings = DataStoreSettingsStore(InMemoryPreferencesDataStore(), NoAudit(), Clock { 0L }, scope)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `clear filters keeps the saved delivery report setting`() =
        runBlocking {
            settings.setShowDeliveryReports(false)
            val controller = FeedFilterController(settings, scope)
            settings.loaded.first { it }
            withTimeout(TIMEOUT_MS) { settings.settings.first { !it.showDeliveryReports } }
            controller.apply(FilterSpec(search = "x", showDeliveryReports = true))
            controller.reset()
            assertFalse(controller.spec.value.showDeliveryReports)
            assertTrue(controller.spec.value.search == null)
        }

    @Test
    fun `an untouched filter follows the saved setting`() =
        runBlocking {
            settings.setShowDeliveryReports(false)
            val controller = FeedFilterController(settings, scope)
            val spec = withTimeout(TIMEOUT_MS) { controller.spec.first { !it.showDeliveryReports } }
            assertFalse(spec.showDeliveryReports)
        }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
