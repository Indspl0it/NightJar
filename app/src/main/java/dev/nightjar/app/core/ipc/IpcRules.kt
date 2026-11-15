package dev.nightjar.app.core.ipc

import dev.nightjar.contract.Ipc

/**
 * Pure rules of the payload ↔ app IPC, kept out of the provider so they can be unit tested
 * without a `ContentProvider` or a binder.
 */
object IpcRules {
    /**
     * Error replies of the app (C.2 `error`). Every error reply means "nothing was acknowledged, retry later"; it is
     * never a refusal of the event. [UNKNOWN_SESSION], [LOCKED] and [STAGING_UNAVAILABLE] are contract values; the
     * others are app values that the contract does not list yet, kept here as the one place they are spelled.
     */
    const val UNKNOWN_SESSION = Ipc.Error.UNKNOWN_SESSION
    const val LOCKED = Ipc.Error.LOCKED
    const val STAGING_UNAVAILABLE = Ipc.Error.STAGING_UNAVAILABLE
    const val BUSY = "BUSY"
    const val INTERNAL = "INTERNAL"
    const val UNKNOWN_METHOD = "UNKNOWN_METHOD"
    const val EMPTY_BATCH = "EMPTY_BATCH"
    const val MALFORMED_BATCH = "MALFORMED_BATCH"
    const val TOO_MANY_EVENTS = "TOO_MANY_EVENTS"
    const val BATCH_TOO_LARGE = "BATCH_TOO_LARGE"

    /** At most 50 events and 256 KB per `events` call. */
    fun eventsBatchError(
        eventCount: Int,
        bytes: Int,
    ): String? =
        when {
            eventCount > Ipc.MAX_BATCH_EVENTS -> TOO_MANY_EVENTS
            bytes > Ipc.MAX_BATCH_BYTES -> BATCH_TOO_LARGE
            else -> null
        }

    /** The rules snapshot sent to the payload must fit 64 KB. */
    fun rulesTooLarge(bytes: Int): Boolean = bytes > Ipc.MAX_RULES_BYTES

    /** Version negotiation: a payload newer than the app is reported by the UI. */
    fun isPayloadNewer(payloadIpcVersion: Int): Boolean = payloadIpcVersion > Ipc.VERSION
}

/** One acknowledged event: `eventId:revision` (`Ipc.ackKey`). */
fun ackKey(
    eventId: String,
    revision: Int,
): String = Ipc.ackKey(eventId, revision)

/**
 * Result of one method call, mirroring the IPC reply bundles. Kept as a data type so the provider's
 * behaviour is testable and the binder only has to copy values into a `Bundle`.
 */
sealed interface IpcReply {
    /**
     * `rules`/`settings` are null when the app cannot read them yet — before the first unlock the tables are behind
     * credential-protected storage, and the payload skips a null snapshot instead of applying an empty one that would
     * look like "no rules, no certificate".
     *
     * `firewallEnabled` is null when the app cannot know the persisted per-build grant (before the first unlock): the
     * reply then has no `firewallEnabled` key and the payload keeps its own last value.
     */
    data class Hello(
        val sessionId: String,
        val rules: String?,
        val settings: String?,
        val firewallEnabled: Boolean?,
    ) : IpcReply

    data class Acked(
        val acked: List<String>,
    ) : IpcReply

    data object Ok : IpcReply

    data class Error(
        val message: String,
    ) : IpcReply

    companion object {
        val UNKNOWN_SESSION = Error(IpcRules.UNKNOWN_SESSION)
    }
}

/** Converts a reply into the reply `Bundle` of the IPC protocol. */
fun IpcReply.toBundle(): android.os.Bundle =
    android.os.Bundle().apply {
        putInt(Ipc.Key.IPC_VERSION, Ipc.VERSION)
        when (this@toBundle) {
            is IpcReply.Hello -> {
                putString(Ipc.Key.SESSION_ID, sessionId)
                putString(Ipc.Key.RULES, rules)
                putString(Ipc.Key.SETTINGS, settings)
                if (firewallEnabled != null) putBoolean(Ipc.Key.FIREWALL_ENABLED, firewallEnabled)
            }

            is IpcReply.Acked -> {
                putString(Ipc.Key.ACKED, org.json.JSONArray(acked).toString())
            }

            IpcReply.Ok -> {
                putBoolean(Ipc.Key.OK, true)
            }

            is IpcReply.Error -> {
                putString(Ipc.Key.ERROR, message)
            }
        }
    }
