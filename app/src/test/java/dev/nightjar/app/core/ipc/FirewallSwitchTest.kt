package dev.nightjar.app.core.ipc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.nightjar.app.core.log.NoopALog
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.model.AuditEntry
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.feature.protection.SessionRegistry
import dev.nightjar.contract.Control
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Firewall screen switch stores the user's choice, writes the audit record and sends
 * ENABLE_FIREWALL. Without a choice the firewall reads as on (the payload default).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirewallSwitchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = FirewallChoiceStore(context)
    private val audit = RecordingAudit()
    private val sessions = IpcSessionStore(SessionRegistry(Clock { 1L }), Clock { 1L })

    /** The payload's control binder in the same process: it records the value of each ENABLE_FIREWALL call. */
    private class RecordingControl : android.os.Binder() {
        val enables = java.util.concurrent.CopyOnWriteArrayList<Int>()

        override fun onTransact(
            code: Int,
            data: android.os.Parcel,
            reply: android.os.Parcel?,
            flags: Int,
        ): Boolean {
            if (code == Control.Code.ENABLE_FIREWALL) {
                // The int is the last value after the interface token.
                data.setDataPosition(data.dataSize() - Int.SIZE_BYTES)
                enables += data.readInt()
            }
            return true
        }
    }

    private class RecordingAudit : AuditLog {
        val records = java.util.concurrent.CopyOnWriteArrayList<Pair<AuditAction, AuditActor>>()

        override suspend fun record(
            action: AuditAction,
            actor: AuditActor,
            target: String?,
            before: String?,
            after: String?,
        ) {
            records += action to actor
        }

        override fun observe(limit: Int): Flow<List<AuditEntry>> = MutableStateFlow(emptyList())

        override suspend fun since(atWallMs: Long): List<AuditEntry> = emptyList()

        override suspend fun purgeBefore(cutoffWallMs: Long): Int = 0
    }

    private fun connect(binder: android.os.IBinder) {
        sessions.begin(
            payloadVersion = "test",
            buildFingerprint = null,
            bootCount = null,
            userUnlocked = true,
            control = binder,
            droppedCount = 0,
            hookHealthJson = null,
        )
    }

    private fun switch() = FirewallSwitch(store, PayloadConnection(sessions), audit, NoopALog)

    @Test
    fun `without a choice the switch reads on and nothing is stored`() =
        runBlocking {
            assertNull(store.choice())
            assertTrue(switch().state.first())
        }

    @Test
    fun `turning the firewall off stores the choice, audits and sends ENABLE_FIREWALL 0`() =
        runBlocking {
            val control = RecordingControl()
            connect(control)
            val switch = switch()

            val result = switch.set(false)

            assertEquals(ControlResult.Ok(Unit), result)
            assertEquals(false, store.choice())
            assertFalse(switch.state.first())
            assertEquals(listOf(AuditAction.FIREWALL_DISABLE to AuditActor.USER), audit.records.toList())
            assertEquals(listOf(0), control.enables.toList())
            assertFalse(sessions.isFirewallEnabled)
        }

    @Test
    fun `turning the firewall on audits FIREWALL_ENABLE and sends 1`() =
        runBlocking {
            val control = RecordingControl()
            connect(control)

            switch().set(true)

            assertEquals(true, store.choice())
            assertEquals(listOf(AuditAction.FIREWALL_ENABLE to AuditActor.USER), audit.records.toList())
            assertEquals(listOf(1), control.enables.toList())
        }

    @Test
    fun `without a payload the choice is still stored for the next hello`() =
        runBlocking {
            val result = switch().set(false)

            assertEquals(ControlResult.NoSession, result)
            assertEquals(false, store.choice())
            assertEquals(listOf(AuditAction.FIREWALL_DISABLE to AuditActor.USER), audit.records.toList())
        }

    @Test
    fun `the stored choice is sent once after the unlock, and nothing without a choice`() =
        runBlocking {
            val control = RecordingControl()
            connect(control)
            val switch = switch()

            switch.pushStoredChoice()
            assertTrue(control.enables.isEmpty())

            store.set(false)
            switch.pushStoredChoice()
            assertEquals(listOf(0), control.enables.toList())
            // No user action, so no audit record.
            assertTrue(audit.records.isEmpty())
        }
}
