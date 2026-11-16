package dev.nightjar.app.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.model.AuditEntry
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Defaults, clamping, the 24 h debug-logging window and one `SETTINGS_CHANGE` audit row per
 * changed key.
 *
 * The store is exercised against an in-memory [DataStore] rather than a file: `PreferenceDataStoreFactory` renames a
 * scratch file over the target on every write, and `File.renameTo` cannot replace an existing file on a Windows JVM, so
 * a second write would fail for reasons that have nothing to do with this class. The in-memory double implements the
 * real `DataStore` contract (`data` + `updateData`), so every assertion still covers the production code path.
 */
class DataStoreSettingsStoreTest {
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: InMemoryDataStore
    private lateinit var audit: RecordingAudit
    private var now = 10_000L

    /** The two-method `DataStore` contract, backed by a [MutableStateFlow]. */
    private class InMemoryDataStore(
        initial: Preferences = emptyPreferences(),
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        private val mutex = Mutex()

        /** Number of committed writes, so a test can prove that a no-op edit wrote nothing. */
        var writes = 0
            private set

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                val updated = transform(state.value)
                writes++
                state.value = updated
                updated
            }
    }

    private class RecordingAudit : AuditLog {
        val records = mutableListOf<Triple<AuditAction, String?, Any?>>()

        override suspend fun record(
            action: AuditAction,
            actor: AuditActor,
            target: String?,
            before: String?,
            after: String?,
        ) {
            records += Triple(action, target, "$before->$after")
        }

        override fun observe(limit: Int): Flow<List<AuditEntry>> = MutableStateFlow(emptyList())

        override suspend fun since(atWallMs: Long): List<AuditEntry> = emptyList()

        override suspend fun purgeBefore(cutoffWallMs: Long): Int = 0
    }

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = InMemoryDataStore()
        audit = RecordingAudit()
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    /** One store per test: it owns a collector on the DataStore. */
    private val store: SettingsStore by lazy { DataStoreSettingsStore(dataStore, audit, Clock { now }, scope) }

    @Test
    fun `fresh store exposes the catalogue defaults`() =
        runBlocking {
            assertEquals(Settings(), store.current())
        }

    @Test
    fun `values persist across store instances`() =
        runBlocking {
            store.setTheme(ThemeMode.BLACK)
            // A fresh wrapper over the same DataStore sees the persisted value (the wrapper keeps no state of its own).
            val second = DataStoreSettingsStore(dataStore, audit, Clock { now }, scope)
            assertEquals(ThemeMode.BLACK, second.current().theme)
        }

    @Test
    fun `clamping happens on write`() =
        runBlocking {
            val s = store
            // Offers 1 / 5 / 15 min; 9 is nearest to 5, 14 to 15.
            s.setNotifyRateLimit(9)
            assertEquals(5, s.current().notifyRateLimitMin)
            s.setNotifyRateLimit(14)
            assertEquals(15, s.current().notifyRateLimitMin)
            s.setDetectSilentCount(500)
            assertEquals(20, s.current().detectSilentCount)
            s.setDetectSuplGrace(1)
            assertEquals(5, s.current().detectSuplGraceMin)
            s.setDetectSilentWindow(59)
            assertEquals(60, s.current().detectSilentWindowMin)
        }

    @Test
    fun `one audit row per changed key`() =
        runBlocking {
            store.setTheme(ThemeMode.DARK)
            assertEquals(1, audit.records.size)
            val (action, target, _) = audit.records.single()
            assertEquals(AuditAction.SETTINGS_CHANGE, action)
            assertEquals("ui.theme", target)
        }

    @Test
    fun `re-applying the same value records nothing`() =
        runBlocking {
            val s = store
            s.setTheme(ThemeMode.DARK)
            val writesAfterFirst = dataStore.writes
            s.setTheme(ThemeMode.DARK)
            assertEquals(1, audit.records.size)
            assertEquals(writesAfterFirst, dataStore.writes, "a no-op edit must not write")
        }

    @Test
    fun `severity override records a per-class key and clears`() =
        runBlocking {
            val s = store
            s.setSeverityOverride(ClassId.SILENT, Severity.SUSPICIOUS)
            assertEquals(Severity.SUSPICIOUS, s.current().severityOverrides[ClassId.SILENT])
            assertEquals("severity.CL-SILENT", audit.records.last().second)
            s.setSeverityOverride(ClassId.SILENT, null)
            assertNull(s.current().severityOverrides[ClassId.SILENT])
            assertEquals("severity.CL-SILENT", audit.records.last().second)
        }

    @Test
    fun `debug logging turns itself off after 24 hours`() =
        runBlocking {
            val s = store
            s.setDebugLogging(true)
            val enabledAt = s.current().debugLoggingUntil
            assertEquals(now + Settings.DEBUG_LOGGING_WINDOW_MS, enabledAt)
            assertTrue(s.current().debugLoggingActive(now + 1))
            assertNull(s.enforceDebugLoggingWindow(now + 1))

            now = enabledAt + 1
            assertFalse(s.current().debugLoggingActive(now))
            val change = s.enforceDebugLoggingWindow(now)
            assertEquals("advanced.debugLogging", change?.key)
            assertFalse(s.current().debugLogging)
            assertEquals(0L, s.current().debugLoggingUntil)
        }

    @Test
    fun `mirrored json carries only the payload keys`() =
        runBlocking {
            val s = store
            s.setOutgoingEnabled(false)
            s.setCbAllChannels(true)
            s.setSafeMode(true)
            val json =
                dev.nightjar.app.domain.protection
                    .ModuleSettingsFactory(
                        appPackage = { "dev.nightjar.app" },
                        appCertSha256 = { "AB:CD" },
                        outgoingBlocked = { emptyList() },
                        current = { s.settings.value },
                    ).build()
            assertTrue(json.contains("\"enabled\":false"), json)
            assertTrue(json.contains("\"allChannels\":true"), json)
            assertTrue(json.contains("\"safeMode\":true"), json)
            // The payload-only identity fields are always present, whatever changed.
            assertTrue(json.contains("\"appCertSha256\":\"AB:CD\""), json)
            // A UI-only key must never reach the payload document.
            assertFalse(json.contains("theme"), json)
        }

    @Test
    fun `severity override map survives its json form`() {
        val map = mapOf(ClassId.SILENT to Severity.SUSPICIOUS, ClassId.MWI to Severity.INFO)
        val text = SettingsJson.writeSeverityOverrides(map)
        assertEquals(map, SettingsJson.readSeverityOverrides(text))
        assertTrue(SettingsJson.readSeverityOverrides(null).isEmpty())
        assertTrue(SettingsJson.readSeverityOverrides("{bad json").isEmpty())
        assertTrue(SettingsJson.readSeverityOverrides("""{"CL-UNKNOWN":"INFO"}""").isEmpty())
    }

    @Test
    fun `settings flow emits after a write`() =
        runBlocking {
            val s = store
            s.setMaskNumbers(true)
            val snapshot = s.settings.first { it.maskNumbers }
            assertTrue(snapshot.maskNumbers)
        }

    @Test
    fun `the preferences keys keep their stored names`() =
        runBlocking {
            val s = store
            s.setMode(UiMode.RESEARCHER)
            s.setRetention(Retention.DAYS_30)
            val stored = dataStore.data.first()
            // The DataStore keys are the catalogue keys, so an inspector sees the same names as the settings catalogue.
            assertTrue(stored.asMap().keys.any { it.name == "ui.mode" }, stored.asMap().keys.toString())
            assertTrue(stored.asMap().keys.any { it.name == "data.retentionDays" }, stored.asMap().keys.toString())
            assertEquals("RESEARCHER", stored[preferencesKeyOf("ui.mode")])
            assertEquals("DAYS_30", stored[preferencesKeyOf("data.retentionDays")])
        }

    @Test
    fun `overlapping edits of different keys all survive`() =
        runBlocking {
            val s = store
            repeat(20) {
                kotlinx.coroutines
                    .coroutineScope {
                        listOf(
                            async(Dispatchers.Default) { s.setMaskNumbers(true) },
                            async(Dispatchers.Default) { s.setHideInNotifications(true) },
                            async(Dispatchers.Default) { s.setBlockScreenshots(true) },
                            async(Dispatchers.Default) { s.setCbLog(false) },
                            async(Dispatchers.Default) { s.setMode(UiMode.RESEARCHER) },
                        ).awaitAll()
                    }
                val now = s.current()
                assertTrue(now.maskNumbers && now.hideInNotifications && now.blockScreenshots && !now.cbLog, now.toString())
                assertEquals(UiMode.RESEARCHER, now.mode)
                s.setMaskNumbers(false)
                s.setHideInNotifications(false)
                s.setBlockScreenshots(false)
                s.setCbLog(true)
                s.setMode(UiMode.SIMPLE)
            }
        }

    @Test
    fun `a store that is unreadable at first picks the settings up once it can be read`() =
        runBlocking {
            val prefs =
                androidx.datastore.preferences.core
                    .preferencesOf(
                        androidx.datastore.preferences.core
                            .booleanPreferencesKey("privacy.maskNumbers") to true,
                    )
            val failures =
                java.util.concurrent.atomic
                    .AtomicInteger(1)
            val failed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val flaky =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> =
                        kotlinx.coroutines.flow.flow {
                            // Credential-protected storage before the first unlock.
                            if (failures.getAndDecrement() > 0) {
                                failed.complete(Unit)
                                throw java.io.IOException("locked")
                            }
                            emit(prefs)
                        }

                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = prefs
                }
            val s = DataStoreSettingsStore(flaky, audit, Clock { now }, scope)

            // No crash, defaults meanwhile, the real value after the retry.
            kotlinx.coroutines.withTimeout(8_000) { failed.await() }
            assertFalse(s.settings.value.maskNumbers)
            // A failed read is not "loaded", so the defaults are not acted on (app lock, settings push).
            assertFalse(s.loaded.value)
            kotlinx.coroutines.withTimeout(8_000) {
                s.settings.first { it.maskNumbers }
            }
            assertTrue(s.loaded.value)
        }

    @Test
    fun `settings hold the stored values by the time loaded flips`() =
        runBlocking {
            val prefs =
                androidx.datastore.preferences.core
                    .preferencesOf(
                        androidx.datastore.preferences.core
                            .booleanPreferencesKey("advanced.safeMode") to true,
                    )
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val held =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> =
                        kotlinx.coroutines.flow.flow {
                            gate.await()
                            emit(prefs)
                        }

                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = prefs
                }
            val s = DataStoreSettingsStore(held, audit, Clock { now }, scope)
            // An unconfined watcher runs inside the write that flips `loaded`. It sees what the app lock and the
            // settings push see when they wait for `loaded` and then read `settings`: never the defaults.
            val seen = kotlinx.coroutines.CompletableDeferred<Boolean>()
            scope.launch(Dispatchers.Unconfined, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                s.loaded.first { it }
                seen.complete(s.settings.value.safeMode)
            }
            gate.complete(Unit)
            assertTrue(kotlinx.coroutines.withTimeout(5_000) { seen.await() })
        }

    private fun preferencesKeyOf(name: String) =
        androidx.datastore.preferences.core
            .stringPreferencesKey(name)
}
