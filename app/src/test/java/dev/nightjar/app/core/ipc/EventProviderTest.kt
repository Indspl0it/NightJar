package dev.nightjar.app.core.ipc

import android.content.Context
import android.os.Bundle
import android.os.UserManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dagger.Lazy
import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.core.log.NoopALog
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.cb.CbIngest
import dev.nightjar.app.data.db.NightjarDatabase
import dev.nightjar.app.data.db.StagedEventEntity
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.data.repository.CounterRepository
import dev.nightjar.app.data.repository.HookStatusRepository
import dev.nightjar.app.data.repository.RoomMessageStore
import dev.nightjar.app.data.repository.RoomPackageLabelRepository
import dev.nightjar.app.data.repository.RulesRepository
import dev.nightjar.app.data.staging.StagingSink
import dev.nightjar.app.domain.ingest.DeliveryReportLinker
import dev.nightjar.app.domain.ingest.IngestProcessor
import dev.nightjar.app.domain.ingest.PackageLabelRecorder
import dev.nightjar.app.domain.ingest.PipelineFactory
import dev.nightjar.app.domain.protection.ModuleSettingsFactory
import dev.nightjar.app.domain.text.Explainer
import dev.nightjar.app.domain.text.MapStrings
import dev.nightjar.app.feature.protection.SessionRegistry
import dev.nightjar.contract.Ipc
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The provider's pre-unlock contract on the JVM.
 *
 * Two defects are pinned here: the unlock decision came from the payload's handshake extra, which is frozen before the
 * first unlock, so every later batch of the boot was staged and then evicted; and a failed staging write was
 * acknowledged, which made the payload delete the only copy of the event.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventProviderTest {
    private val clock = Clock { 1_000L }
    private val staging = FakeStaging()
    private val sessions = IpcSessionStore(SessionRegistry(clock), clock)
    private val userManager =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(UserManager::class.java)

    private fun sessionId(): String =
        sessions
            .begin(
                payloadVersion = "1.0",
                buildFingerprint = "fp",
                bootCount = 1,
                // Deliberately the opposite of the live state, which is what the payload sends on a fresh boot.
                userUnlocked = true,
                control = null,
                droppedCount = 0,
                hookHealthJson = null,
            ).sessionId

    private fun provider(
        uid: Int = EventProvider.PHONE_UID,
        ingest: EventIngest? = null,
        firewallChoice: FirewallChoiceStore? = null,
    ): EventProvider =
        EventProvider().apply {
            attach(FakeEntryPoint(staging, sessions, userManager, ingest, firewallChoice))
            callingUid = { uid }
        }

    private fun hello(provider: EventProvider): Bundle? =
        provider.call(
            Ipc.Method.HELLO,
            null,
            Bundle().apply {
                putString(Ipc.Key.PAYLOAD_VERSION, "1.0")
                putString(Ipc.Key.BUILD_FINGERPRINT, "fp")
            },
        )

    /** The real unlocked ingest over an in-memory database; the pipeline is never reached by the tests that use it. */
    private fun realIngest(db: NightjarDatabase): EventIngest {
        val store = RoomMessageStore(db, db.messageDao(), db.pduDao(), db.traceStepDao())
        val processor =
            IngestProcessor(
                store,
                db.messageDao(),
                PipelineFactory { error("no pipeline in this test") },
                DeliveryReportLinker(db.messageDao(), db.pduDao()),
                NoopALog,
                PackageLabelRecorder(RoomPackageLabelRepository(db.packageLabelDao(), Clock { 1L }), { null }),
            )
        return EventIngest(store, CbIngest(), Explainer(MapStrings()), SelfTestEvents(), processor, NoopALog)
    }

    private fun call(
        provider: EventProvider,
        batch: String,
        sessionId: String,
    ): Bundle? =
        provider.call(
            Ipc.Method.EVENTS,
            null,
            Bundle().apply {
                putString(Ipc.Key.SESSION_ID, sessionId)
                putString(Ipc.Key.BATCH, batch)
            },
        )

    private fun acked(bundle: Bundle?): List<String> {
        val raw = bundle?.getString(Ipc.Key.ACKED) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map(array::getString)
    }

    @Test
    fun `a locked user is staged although the handshake said unlocked`() {
        shadowOf(userManager).setUserUnlocked(false)
        val id = sessionId()

        val reply = call(provider(), """[{"eventId":"e1","revision":1}]""", id)

        assertEquals(listOf("e1:1"), acked(reply))
        assertEquals(listOf("e1"), staging.staged)
    }

    @Test
    fun `an event whose staging write failed is not acknowledged`() {
        shadowOf(userManager).setUserUnlocked(false)
        staging.failIds += "e2"
        val id = sessionId()

        val reply = call(provider(), """[{"eventId":"e1"},{"eventId":"e2"}]""", id)

        // The payload keeps every unacknowledged event in its spool; acknowledging e2 would lose it.
        assertEquals(listOf("e1:1"), acked(reply))
    }

    @Test
    fun `nothing staged means an error instead of an empty acknowledgement`() {
        shadowOf(userManager).setUserUnlocked(false)
        staging.failIds += "e1"
        val id = sessionId()

        val reply = call(provider(), """[{"eventId":"e1"}]""", id)

        assertEquals(Ipc.Error.STAGING_UNAVAILABLE, reply?.getString(Ipc.Key.ERROR))
    }

    @Test
    fun `a malformed batch while locked is an error and stages nothing`() {
        shadowOf(userManager).setUserUnlocked(false)
        val reply = call(provider(), "{not a batch", sessionId())

        assertEquals(IpcRules.MALFORMED_BATCH, reply?.getString(Ipc.Key.ERROR))
        assertEquals(emptyList<String>(), staging.staged)
    }

    @Test
    fun `a call without a batch is an empty batch error`() {
        shadowOf(userManager).setUserUnlocked(false)
        val reply =
            provider().call(
                Ipc.Method.EVENTS,
                null,
                Bundle().apply { putString(Ipc.Key.SESSION_ID, sessionId()) },
            )

        assertEquals(IpcRules.EMPTY_BATCH, reply?.getString(Ipc.Key.ERROR))
    }

    @Test
    fun `counters while locked answer LOCKED and never open the database`() {
        shadowOf(userManager).setUserUnlocked(false)
        val reply =
            provider().call(
                Ipc.Method.COUNTERS,
                null,
                Bundle().apply {
                    putString(Ipc.Key.SESSION_ID, sessionId())
                    putString(Ipc.Key.COUNTERS, "[]")
                },
            )

        // FakeEntryPoint.counters() throws if resolved; LOCKED proves it was not.
        assertEquals(IpcRules.LOCKED, reply?.getString(Ipc.Key.ERROR))
    }

    @Test
    fun `an unknown session is rejected before the batch is read, also when unlocked`() {
        shadowOf(userManager).setUserUnlocked(true)
        sessionId()

        val reply = call(provider(), """[{"eventId":"e1"}]""", "not-the-session")

        // FakeEntryPoint.ingest() throws if resolved; the session check comes first.
        assertEquals(IpcRules.UNKNOWN_SESSION, reply?.getString(Ipc.Key.ERROR))
        assertEquals(emptyList<String>(), staging.staged)
    }

    @Test
    fun `a batch where no event parses still acknowledges every event with an id`() {
        shadowOf(userManager).setUserUnlocked(true)
        val db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NightjarDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        try {
            val id = sessionId()
            // A numeric eventId is not a readable event (EventJson wants a string), but the ack can still name it.
            val reply = call(provider(ingest = realIngest(db)), """[{"eventId":101,"revision":2},{"eventId":102}]""", id)

            // Both are acknowledged, so the payload does not send the batch again for ever.
            assertEquals(listOf("101:2", "102:1"), acked(reply))
        } finally {
            db.close()
        }
    }

    @Test
    fun `a hello without a firewall choice leaves the firewall key out`() {
        shadowOf(userManager).setUserUnlocked(true)
        val choice = FirewallChoiceStore(ApplicationProvider.getApplicationContext())

        val reply = hello(provider(firewallChoice = choice))

        // No key, so the payload keeps its default ON.
        assertNotNull(reply?.getString(Ipc.Key.SESSION_ID))
        assertFalse(reply!!.containsKey(Ipc.Key.FIREWALL_ENABLED))
    }

    @Test
    fun `a hello carries the stored firewall choice`() {
        shadowOf(userManager).setUserUnlocked(true)
        val choice = FirewallChoiceStore(ApplicationProvider.getApplicationContext())
        choice.set(false)

        val reply = hello(provider(firewallChoice = choice))

        assertTrue(reply!!.containsKey(Ipc.Key.FIREWALL_ENABLED))
        assertFalse(reply.getBoolean(Ipc.Key.FIREWALL_ENABLED, true))
        assertFalse(sessions.isFirewallEnabled)
    }

    @Test
    fun `a hello before the unlock leaves the firewall key out`() {
        shadowOf(userManager).setUserUnlocked(false)

        // FakeEntryPoint.firewallChoice() throws if resolved; the locked hello must not read it.
        val reply = hello(provider())

        assertFalse(reply!!.containsKey(Ipc.Key.FIREWALL_ENABLED))
        assertTrue(sessions.isFirewallEnabled)
    }

    @Test
    fun `a caller that is not the phone process gets nothing`() {
        val reply = call(provider(uid = EventProvider.PHONE_UID + 1), """[{"eventId":"e1"}]""", sessionId())

        assertNull(reply)
        assertEquals(emptyList<String>(), staging.staged)
    }

    private class FakeStaging : StagingSink {
        val staged = mutableListOf<String>()
        val failIds = mutableSetOf<String>()

        override suspend fun stage(
            eventId: String,
            json: String,
            receivedAt: Long,
        ): Boolean {
            if (eventId in failIds) return false
            staged += eventId
            return true
        }

        override suspend fun drain(consume: suspend (StagedEventEntity) -> Boolean): Int = 0
    }

    /** Everything behind credential-protected storage must stay untouched while the user is locked. */
    private class FakeEntryPoint(
        private val staging: StagingSink,
        private val sessions: IpcSessionStore,
        private val userManager: UserManager,
        private val ingest: EventIngest? = null,
        private val firewallChoice: FirewallChoiceStore? = null,
    ) : EventProviderEntryPoint {
        override fun staging(): StagingSink = staging

        override fun sessions(): IpcSessionStore = sessions

        override fun log(): ALog = NoopALog

        override fun userManager(): UserManager = userManager

        override fun counters(): Lazy<CounterRepository> = unexpected()

        override fun rules(): Lazy<RulesRepository> = unexpected()

        override fun settings(): Lazy<SettingsStore> = unexpected()

        override fun syncCoordinator(): Lazy<IpcSyncCoordinator> = unexpected()

        override fun ingest(): Lazy<EventIngest> = ingest?.let { real -> Lazy { real } } ?: unexpected()

        override fun hookStatus(): Lazy<HookStatusRepository> = unexpected()

        override fun moduleSettings(): Lazy<ModuleSettingsFactory> = unexpected()

        override fun firewallChoice(): Lazy<FirewallChoiceStore> = firewallChoice?.let { real -> Lazy { real } } ?: unexpected()

        /**
         * The entry point exposes `dagger.Lazy`, so the double does too: a `kotlin.Lazy` here compiles
         * against the wrong type and the test would silently stop covering the "not resolved before
         * unlock" contract.
         */
        private fun <T> unexpected(): Lazy<T> =
            Lazy { error("credential-protected dependency resolved before unlock") }
    }
}
