package dev.nightjar.app.domain.ingest

import dev.nightjar.app.domain.text.TextKey
import dev.nightjar.contract.AckResult
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Fate

/**
 * No message reaches the UI with no outcome: "unknown" is never shown.
 *
 * The payload no longer emits FT-UNKNOWN. It stays the guard value of a parse that finds no fate: an event from an older
 * payload, or a row stored by an older version. [of] replaces it with the true fate that the event itself shows, with
 * the same rules as the payload sealer.
 */
object TrueFate {
    fun of(
        fate: Fate,
        direction: Direction,
        ackResult: Int?,
        replay: Boolean = false,
        noDispatch: Boolean = false,
    ): Fate {
        if (fate != Fate.UNKNOWN) return fate
        return when {
            direction == Direction.OUT && noDispatch -> Fate.NOT_SENT
            direction == Direction.OUT -> Fate.SEND_UNCONFIRMED
            ackResult == null && replay -> Fate.ACCEPTED
            ackResult == null -> Fate.NOT_DISPATCHED
            ackResult == AckResult.OUT_OF_MEMORY -> Fate.NACK_STORAGE
            AckResult.accepted(ackResult) -> Fate.ACCEPTED
            else -> Fate.REJECTED
        }
    }

    /**
     * Why Android rejected a message (fate FT-REJECTED), from its dispatch result (`ackResult`). Null when the
     * result is not a rejection.
     */
    fun rejectReason(ackResult: Int?): TextKey? =
        when (ackResult) {
            null -> null
            AckResult.UNSUPPORTED -> TextKey.FATE_REJECT_UNSUPPORTED
            AckResult.DUPLICATED -> TextKey.FATE_REJECT_DUPLICATE
            AckResult.DISPATCH_FAILURE -> TextKey.FATE_REJECT_DISPATCH
            AckResult.NULL_PDU, AckResult.NULL_MESSAGE -> TextKey.FATE_REJECT_UNREADABLE
            AckResult.RECEIVED_WHILE_ENCRYPTED -> TextKey.FATE_REJECT_LOCKED
            AckResult.DATABASE_ERROR, AckResult.INVALID_URI -> TextKey.FATE_REJECT_DATABASE
            AckResult.HANDLED, AckResult.OK, AckResult.OUT_OF_MEMORY -> null
            else -> TextKey.FATE_REJECT_GENERIC
        }
}
