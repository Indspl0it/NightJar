package dev.nightjar.app.core.ipc

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.domain.protection.HandshakeSnapshot
import dev.nightjar.app.domain.protection.ProfileStatus
import dev.nightjar.app.feature.protection.HandshakeStore
import dev.nightjar.app.feature.protection.SessionRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.FileDescriptor

/** The handshake bookkeeping the protection state and the module row depend on. */
class IpcSessionStoreTest {
    private val clock = Clock { 1_000L }

    /** Records the death recipient so a test can kill the "payload". */
    private class FakeBinder : IBinder {
        var recipient: IBinder.DeathRecipient? = null
        var alive = true

        override fun getInterfaceDescriptor(): String? = null

        override fun pingBinder(): Boolean = alive

        override fun isBinderAlive(): Boolean = alive

        override fun queryLocalInterface(descriptor: String): IInterface? = null

        override fun dump(
            fd: FileDescriptor,
            args: Array<out String>?,
        ) = Unit

        override fun dumpAsync(
            fd: FileDescriptor,
            args: Array<out String>?,
        ) = Unit

        override fun transact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean = false

        override fun linkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ) {
            this.recipient = recipient
        }

        override fun unlinkToDeath(
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ): Boolean {
            val had = this.recipient === recipient
            this.recipient = null
            return had
        }

        fun die() {
            alive = false
            recipient?.binderDied()
        }
    }

    private fun begin(
        store: IpcSessionStore,
        control: IBinder? = null,
        hookHealth: String? = null,
    ) = store.begin(
        payloadVersion = "1.0",
        buildFingerprint = "fp",
        bootCount = 7,
        userUnlocked = true,
        control = control,
        droppedCount = 0,
        hookHealthJson = hookHealth,
    )

    @Test
    fun `a payload crash ends the session at once`() {
        val registry = SessionRegistry(clock)
        val store = IpcSessionStore(registry, clock)
        val binder = FakeBinder()

        begin(store, binder)
        assertNotNull(registry.handshake.value)

        binder.die()

        assertNull(store.session)
        assertNull(registry.handshake.value)
    }

    @Test
    fun `the death of a replaced session does not end its successor`() {
        val registry = SessionRegistry(clock)
        val store = IpcSessionStore(registry, clock)
        val first = FakeBinder()
        val firstRecipient: IBinder.DeathRecipient

        begin(store, first)
        firstRecipient = checkNotNull(first.recipient)
        begin(store, FakeBinder())

        // A late death notification of the old binder arrives after the new hello.
        firstRecipient.binderDied()

        assertNotNull(store.session)
        assertNotNull(registry.handshake.value)
    }

    @Test
    fun `enabling the firewall keeps the profile the payload reported`() {
        val registry = SessionRegistry(clock)
        val store = IpcSessionStore(registry, clock)
        begin(store, hookHealth = """{"profile":"KNOWN","hooks":{}}""")
        assertEquals(ProfileStatus.KNOWN, registry.handshake.value?.profile)

        store.setFirewallEnabled(true)

        assertEquals(ProfileStatus.KNOWN, registry.handshake.value?.profile)
        assertEquals(true, registry.handshake.value?.firewallEnabled)
    }

    @Test
    fun `a handshake saved in this boot is restored in a new process`() =
        runTest {
            val saved = snapshot(bootCount = 7)
            val registry = SessionRegistry(clock, storeOf(HandshakeStore.restorable(saved, currentBootCount = 7)))

            registry.restore()

            assertEquals(saved, registry.handshake.value)
        }

    @Test
    fun `a handshake from an earlier boot is not restored`() {
        assertNull(HandshakeStore.restorable(snapshot(bootCount = 6), currentBootCount = 7))
        assertNull(HandshakeStore.restorable(snapshot(bootCount = null), currentBootCount = 7))
        assertNull(HandshakeStore.restorable(snapshot(bootCount = 7), currentBootCount = null))
    }

    @Test
    fun `a live handshake wins over a restored one`() =
        runTest {
            val registry = SessionRegistry(clock, storeOf(snapshot(bootCount = 7, version = "old")))
            registry.onHandshake(7, "new", "fp", ProfileStatus.KNOWN, true, true, false)

            registry.restore()

            assertEquals("new", registry.handshake.value?.payloadVersion)
        }

    @Test
    fun `a lost session is not resurrected by restore`() =
        runTest {
            val registry = SessionRegistry(clock, storeOf(snapshot(bootCount = 7)))
            registry.onSessionLost()

            registry.restore()

            assertNull(registry.handshake.value)
        }

    private fun snapshot(
        bootCount: Int?,
        version: String = "1.0",
    ) = HandshakeSnapshot(
        bootCount = bootCount,
        payloadVersion = version,
        buildFingerprint = "fp",
        profile = ProfileStatus.KNOWN,
        captureTierLive = true,
        firewallTierLive = true,
        firewallEnabled = false,
        receivedAtWallMs = 5L,
    )

    private fun storeOf(saved: HandshakeSnapshot?): HandshakeStore =
        object : HandshakeStore {
            override fun load(): HandshakeSnapshot? = saved

            override fun save(snapshot: HandshakeSnapshot) = Unit

            override fun clear() = Unit
        }
}
