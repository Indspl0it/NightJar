package dev.nightjar.app.domain.ingest

import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.AckResult
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** FT-UNKNOWN becomes the true fate the event shows, and a rejection names its reason. */
class TrueFateTest {
    @Test
    fun `a known fate stays as it is`() {
        for (fate in Fate.entries.filter { it != Fate.UNKNOWN }) {
            assertEquals(fate, TrueFate.of(fate, Direction.IN, null), fate.wire())
            assertEquals(fate, TrueFate.of(fate, Direction.OUT, AckResult.DUPLICATED, replay = true, noDispatch = true))
        }
    }

    @Test
    fun `an unknown received message gets the fate its dispatch result shows`() {
        assertEquals(Fate.NOT_DISPATCHED, TrueFate.of(Fate.UNKNOWN, Direction.IN, null))
        assertEquals(Fate.ACCEPTED, TrueFate.of(Fate.UNKNOWN, Direction.IN, null, replay = true))
        assertEquals(Fate.ACCEPTED, TrueFate.of(Fate.UNKNOWN, Direction.IN, AckResult.HANDLED))
        assertEquals(Fate.ACCEPTED, TrueFate.of(Fate.UNKNOWN, Direction.IN, AckResult.OK))
        assertEquals(Fate.NACK_STORAGE, TrueFate.of(Fate.UNKNOWN, Direction.IN, AckResult.OUT_OF_MEMORY))
        assertEquals(Fate.REJECTED, TrueFate.of(Fate.UNKNOWN, Direction.IN, AckResult.DUPLICATED))
        assertEquals(Fate.REJECTED, TrueFate.of(Fate.UNKNOWN, Direction.IN, 0))
    }

    @Test
    fun `an unknown sent message is unconfirmed, or not sent when it never reached the dispatcher`() {
        assertEquals(Fate.SEND_UNCONFIRMED, TrueFate.of(Fate.UNKNOWN, Direction.OUT, null))
        assertEquals(Fate.NOT_SENT, TrueFate.of(Fate.UNKNOWN, Direction.OUT, null, noDispatch = true))
    }

    @Test
    fun `each rejection code names a reason`() {
        assertEquals(TextKey.FATE_REJECT_GENERIC, TrueFate.rejectReason(AckResult.GENERIC_ERROR))
        assertEquals(TextKey.FATE_REJECT_UNSUPPORTED, TrueFate.rejectReason(AckResult.UNSUPPORTED))
        assertEquals(TextKey.FATE_REJECT_DUPLICATE, TrueFate.rejectReason(AckResult.DUPLICATED))
        assertEquals(TextKey.FATE_REJECT_DISPATCH, TrueFate.rejectReason(AckResult.DISPATCH_FAILURE))
        assertEquals(TextKey.FATE_REJECT_UNREADABLE, TrueFate.rejectReason(AckResult.NULL_PDU))
        assertEquals(TextKey.FATE_REJECT_UNREADABLE, TrueFate.rejectReason(AckResult.NULL_MESSAGE))
        assertEquals(TextKey.FATE_REJECT_LOCKED, TrueFate.rejectReason(AckResult.RECEIVED_WHILE_ENCRYPTED))
        assertEquals(TextKey.FATE_REJECT_DATABASE, TrueFate.rejectReason(AckResult.DATABASE_ERROR))
        assertEquals(TextKey.FATE_REJECT_DATABASE, TrueFate.rejectReason(AckResult.INVALID_URI))
        assertEquals(TextKey.FATE_REJECT_GENERIC, TrueFate.rejectReason(99))
        assertNull(TrueFate.rejectReason(null))
        assertNull(TrueFate.rejectReason(AckResult.HANDLED))
        assertNull(TrueFate.rejectReason(AckResult.OK))
        assertNull(TrueFate.rejectReason(AckResult.OUT_OF_MEMORY))
    }
}
