package dev.nightjar.app.core.ipc

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import dev.nightjar.app.core.log.NoopALog
import dev.nightjar.app.core.settings.DataStoreSettingsStore
import dev.nightjar.app.core.settings.OutgoingBlockStore
import dev.nightjar.app.core.settings.Settings
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.model.AuditEntry
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.domain.protection.ModuleSettingsFactory
import dev.nightjar.app.feature.protection.SessionRegistry
import dev.nightjar.contract.Control
import dev.nightjar.contract.SettingsKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every settings push carries the complete document.
 *
 * The defect was `SettingsSync` writing only the mirrored keys: the first settings change dropped `appPackage` and
 * `appCertSha256`, so the payload withheld every message (empty pin). the push goes over the control
 * binder (`UPDATE_SETTINGS`); there is no root write any more. the blocked sender packages are part of the
 * document (`outgoing.blocked`) and a block or unblock pushes it. no `firewallEnabledFor` key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsSyncTest {
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStoreSettingsStore
    private lateinit var control: RecordingControl
    private lateinit var blocks: OutgoingBlockStore
    private lateinit var sync: SettingsSync

    /** The payload's control binder in the same process: it records each transaction code. */
    private class RecordingControl : android.os.Binder() {
        val codes = java.util.concurrent.CopyOnWriteArrayList<Int>()

        override fun onTransact(
            code: Int,
            data: android.os.Parcel,
            reply: android.os.Parcel?,
            flags: Int,
        ): Boolean {
            codes += code
            return true
        }

        fun settingsPushes(): Int = codes.count { it == Control.Code.UPDATE_SETTINGS }
    }

    /** A connection with a live session whose control binder is [binder]. */
    private fun connectionTo(binder: android.os.IBinder): PayloadConnection {
        val sessions = IpcSessionStore(SessionRegistry(Clock { 1L }), Clock { 1L })
        sessions.begin(
            payloadVersion = "test",
            buildFingerprint = null,
            bootCount = null,
            userUnlocked = true,
            control = binder,
            droppedCount = 0,
            hookHealthJson = null,
        )
        return PayloadConnection(sessions)
    }

    /** The publish target: the file the module reads after the next boot. */
    private class DataStoreDouble : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock {
                val updated = transform(state.value)
                state.value = updated
                updated
            }
    }

    private class RecordingAudit : AuditLog {
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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = DataStoreSettingsStore(DataStoreDouble(), RecordingAudit(), Clock { 1L }, scope)
        control = RecordingControl()
        blocks = OutgoingBlockStore(context)
        val factory =
            ModuleSettingsFactory(
                appPackage = { context.packageName },
                appCertSha256 = { "AB:CD" },
                outgoingBlocked = { blocks.blocked().sorted() },
                current = { store.settings.value },
            )
        sync =
            SettingsSync(
                settings = store,
                connection = connectionTo(control),
                log = NoopALog,
                moduleSettings = factory,
                outgoingBlocks = blocks,
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `publish pushes the whole document including the pin`() {
        val current = Settings()
        sync.publish(current)

        assertEquals(1, control.settingsPushes())
        // publish sends json(current) unchanged; the document itself is checked here.
        val written = sync.json(current)
        assertTrue(written, written.contains("\"appPackage\":\""))
        assertTrue(written, written.contains("\"appCertSha256\":\"AB:CD\""))
        assertFalse(written, written.contains("firewallEnabledFor"))
        assertTrue(written, written.contains("\"schema\":"))
        // The body is not the reduced "mirrored keys only" document.
        assertTrue(written, written.contains("\"safeMode\":false"))
    }

    @Test
    fun `json always carries the certificate and an empty blocked list by default`() {
        val json = sync.json(Settings())
        assertTrue(json, json.contains("\"appCertSha256\":\"AB:CD\""))
        assertEquals(emptyList<String>(), blockedIn(json))
    }

    @Test
    fun `block puts the package in outgoing blocked and unblock removes it`() {
        // The next document after a block carries the package; after the unblock it is gone again.
        blocks.block("com.example.sender")
        blocks.block("com.example.other")
        assertEquals(listOf("com.example.other", "com.example.sender"), blockedIn(sync.json(Settings())))

        blocks.unblock("com.example.sender")
        assertEquals(listOf("com.example.other"), blockedIn(sync.json(Settings())))
    }

    @Test
    fun `a block pushes the settings document`() =
        runBlocking {
            sync.launch(scope)
            kotlinx.coroutines.withTimeout(5_000) {
                while (control.settingsPushes() == 0) kotlinx.coroutines.delay(20)
            }
            val before = control.settingsPushes()

            blocks.block("com.example.sender")

            kotlinx.coroutines.withTimeout(5_000) {
                while (control.settingsPushes() == before) kotlinx.coroutines.delay(20)
            }
            assertEquals(before + 1, control.settingsPushes())
        }

    private fun blockedIn(json: String): List<String> {
        val array =
            org.json
                .JSONObject(json)
                .getJSONObject(SettingsKeys.OUTGOING)
                .getJSONArray(SettingsKeys.OUTGOING_BLOCKED)
        return (0 until array.length()).map(array::getString)
    }

    @Test
    fun `a mirrored setting changes the projection and a UI-only one does not`() {
        val before = sync.mirrored(Settings(), emptySet())
        assertEquals(before, sync.mirrored(Settings(), emptySet()))
        assertTrue(before != sync.mirrored(Settings(safeMode = true), emptySet()))
        // A block is a mirrored change too.
        assertTrue(before != sync.mirrored(Settings(), setOf("com.example.sender")))
        // A UI-only value never appears in the projection, so it cannot cause a push.
        assertFalse(sync.mirrored(Settings(), emptySet()).contains("theme"))
    }

    /** A store whose first read is held back, like DataStore on a cold process. */
    private class HeldDataStore(
        private val prefs: Preferences,
    ) : DataStore<Preferences> {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reading = kotlinx.coroutines.CompletableDeferred<Unit>()

        override val data: Flow<Preferences> =
            kotlinx.coroutines.flow.flow {
                reading.complete(Unit)
                gate.await()
                emit(prefs)
            }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(prefs)
    }

    @Test
    fun `nothing is published before the store has loaded`() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val held =
                HeldDataStore(
                    androidx.datastore.preferences.core.preferencesOf(
                        androidx.datastore.preferences.core
                            .booleanPreferencesKey("advanced.safeMode") to true,
                    ),
                )
            val heldStore = DataStoreSettingsStore(held, RecordingAudit(), Clock { 1L }, scope)
            val factory =
                ModuleSettingsFactory(
                    appPackage = { context.packageName },
                    appCertSha256 = { "AB:CD" },
                    outgoingBlocked = { emptyList() },
                    current = { heldStore.settings.value },
                )
            val heldControl = RecordingControl()
            val heldSync =
                SettingsSync(
                    settings = heldStore,
                    connection = connectionTo(heldControl),
                    log = NoopALog,
                    moduleSettings = factory,
                    outgoingBlocks = blocks,
                )

            heldSync.launch(scope)
            // Wait until the store is reading (its first read is held at the gate).
            kotlinx.coroutines.withTimeout(5_000) { held.reading.await() }
            // A check that something did not happen needs a short window; a SettingsSync without the gate pushes at
            // once, well inside it.
            kotlinx.coroutines.delay(NEGATIVE_WINDOW_MS)
            // The default snapshot (safe mode off) must not be pushed over the user's settings.
            assertFalse(heldStore.loaded.value)
            assertEquals(0, heldControl.settingsPushes())

            held.gate.complete(Unit)
            kotlinx.coroutines.withTimeout(5_000) {
                heldStore.loaded.first { it }
                while (heldControl.settingsPushes() == 0) kotlinx.coroutines.delay(20)
            }
            assertEquals(1, heldControl.settingsPushes())
            assertTrue(heldStore.settings.value.safeMode)
            val written = heldSync.json(heldStore.settings.value)
            assertTrue(written, written.contains("\"safeMode\":true"))
        }

    private companion object {
        const val NEGATIVE_WINDOW_MS = 100L
    }
}
