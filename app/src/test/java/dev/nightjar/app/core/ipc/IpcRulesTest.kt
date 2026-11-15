package dev.nightjar.app.core.ipc

import dev.nightjar.contract.Control
import dev.nightjar.contract.Ipc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Batch limits, version negotiation, ack format and release-code mapping.
 *
 * Robolectric supplies a real `android.os.Bundle`; without it `Bundle.putInt` is the stub that throws.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IpcRulesTest {
    @Test
    fun `a batch inside the limits is accepted`() {
        assertNull(IpcRules.eventsBatchError(eventCount = Ipc.MAX_BATCH_EVENTS, bytes = Ipc.MAX_BATCH_BYTES))
        assertNull(IpcRules.eventsBatchError(eventCount = 1, bytes = 100))
    }

    @Test
    fun `too many events and an oversized batch are rejected separately`() {
        assertEquals("TOO_MANY_EVENTS", IpcRules.eventsBatchError(Ipc.MAX_BATCH_EVENTS + 1, 10))
        assertEquals("BATCH_TOO_LARGE", IpcRules.eventsBatchError(1, Ipc.MAX_BATCH_BYTES + 1))
    }

    @Test
    fun `the rules snapshot is limited to 64 KB`() {
        assertFalse(IpcRules.rulesTooLarge(Ipc.MAX_RULES_BYTES))
        assertTrue(IpcRules.rulesTooLarge(Ipc.MAX_RULES_BYTES + 1))
    }

    @Test
    fun `a newer payload is detected for MSG-UPD-01`() {
        assertFalse(IpcRules.isPayloadNewer(Ipc.VERSION))
        assertTrue(IpcRules.isPayloadNewer(Ipc.VERSION + 1))
    }

    @Test
    fun `acks use the eventId revision key`() {
        assertEquals("abc:3", ackKey("abc", 3))
        assertEquals(Ipc.ackKey("abc", 3), ackKey("abc", 3))
    }

    @Test
    fun `release codes map to release reasons`() {
        assertEquals(ReleaseResult.Released, ReleaseCodes.map(Control.ReleaseResult.HANDLED))
        assertEquals(
            ReleaseResult.Failed(
                Control.ReleaseResult.NOT_RELEASABLE,
                "RELEASE_NOT_RELEASABLE",
            ),
            ReleaseCodes.map(Control.ReleaseResult.NOT_RELEASABLE),
        )
        assertEquals(
            "RELEASE_TIMEOUT",
            (ReleaseCodes.map(Control.ReleaseResult.TIMEOUT) as ReleaseResult.Failed).reason,
        )
        assertEquals(
            "RELEASE_CALLER_REJECTED",
            (ReleaseCodes.map(Control.ReleaseResult.CALLER_REJECTED) as ReleaseResult.Failed).reason,
        )
        assertEquals(
            "RELEASE_UNKNOWN_ERROR",
            (ReleaseCodes.map(-999) as ReleaseResult.Failed).reason,
        )
    }

    @Test
    fun `every contract release code has a distinct reason`() {
        val codes =
            listOf(
                Control.ReleaseResult.GENERIC_FAILURE,
                Control.ReleaseResult.OUT_OF_MEMORY,
                Control.ReleaseResult.INVALID_ARGUMENTS,
                Control.ReleaseResult.NOT_RELEASABLE,
                Control.ReleaseResult.TIMEOUT,
                Control.ReleaseResult.NO_DISPATCHER,
                Control.ReleaseResult.CALLER_REJECTED,
                Control.ReleaseResult.NOT_SUPPORTED,
            )
        val reasons = codes.map { ReleaseCodes.reason(it) }
        assertEquals(codes.size, reasons.toSet().size)
        assertTrue(reasons.none { it == "RELEASE_UNKNOWN_ERROR" })
    }

    @Test
    fun `the phone uid is the only accepted caller`() {
        assertEquals(1001, EventProvider.PHONE_UID)
    }

    @Test
    fun `replies keep the contract keys`() {
        val hello =
            IpcReply.Hello("s", "{}", "{}", true).toBundle()
        assertEquals("s", hello.getString(Ipc.Key.SESSION_ID))
        assertEquals(Ipc.VERSION, hello.getInt(Ipc.Key.IPC_VERSION))
        assertTrue(hello.getBoolean(Ipc.Key.FIREWALL_ENABLED))

        val acked = IpcReply.Acked(listOf("a:1", "b:2")).toBundle()
        assertEquals("[\"a:1\",\"b:2\"]", acked.getString(Ipc.Key.ACKED))

        assertEquals("UNKNOWN_SESSION", (IpcReply.UNKNOWN_SESSION as IpcReply.Error).message)
        assertTrue(IpcReply.Ok.toBundle().getBoolean(Ipc.Key.OK))
    }
}
