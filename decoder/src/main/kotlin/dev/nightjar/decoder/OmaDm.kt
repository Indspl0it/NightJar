package dev.nightjar.decoder

import java.nio.charset.StandardCharsets

/**
 * OMA DM 1.2 Notification, package #0 (OMA-TS-DM_Notification-V1_2 section 6). The push has
 * content type `application/vnd.syncml.notification` and application id `x-wap-application:syncml.dm`.
 *
 * Layout: digest (128 bits, MD5), then the trigger header: version (10 bits), ui-mode (2), initiator (1),
 * future-use (27), session id (16), server identifier length (8), server identifier; then the vendor-specific
 * trigger body.
 */
object OmaDm {
    private const val DIGEST_OCTETS = 16

    fun decode(bytes: ByteArray): Field = guarded("oma_dm_notification", bytes) { build(bytes, 0) }

    fun decode(hex: String): Field = guardedHex("oma_dm_notification", hex) { decode(it) }

    /** A notification at absolute [baseOffset], for a tree nested in a push. */
    internal fun decodeAt(
        bytes: ByteArray,
        baseOffset: Int,
    ): Field = guarded("oma_dm_notification", bytes) { build(bytes, baseOffset) }

    private fun build(
        bytes: ByteArray,
        baseOffset: Int,
    ): Field {
        val reader = BitReader(bytes, baseOffset)
        val children = mutableListOf<Field>()
        val digest = reader.octets(DIGEST_OCTETS)
        if (!reader.truncated) children.add(reader.field("digest", hex(digest), 0))
        if (!reader.truncated) children.add(header(reader))
        if (!reader.truncated && reader.remainingBits >= 8) {
            val bodyStart = reader.bitPos
            val body = reader.octets(reader.remainingBits / 8)
            children.add(reader.field("trigger_body", hex(body), bodyStart))
        }
        if (reader.truncated) children.add(reader.truncatedError())
        return field("oma_dm_notification", null, baseOffset, bytes.size, children)
    }

    /** The trigger header. A field cut by the end of the data gets no node; the root has the error node. */
    private fun header(reader: BitReader): Field {
        val start = reader.bitPos
        val children = mutableListOf<Field>()

        fun read(
            name: String,
            bits: Int,
            value: (Long) -> Any,
        ): Long? {
            val fieldStart = reader.bitPos
            val raw = reader.bits(bits)
            if (reader.truncated) return null
            children.add(reader.field(name, value(raw), fieldStart))
            return raw
        }

        read("version", 10) { "${it / 10}.${it % 10}" }
        read("ui_mode", 2) { uiModeName(it.toInt()) }
        read("initiator", 1) { if (it == 1L) "server" else "user" }
        read("future_use", 27) { it }
        read("session_id", 16) { hexDigits(it, 4) }
        val serverLength = read("server_id_length", 8) { it.toInt() }?.toInt() ?: 0
        val serverStart = reader.bitPos
        val server = reader.octets(serverLength)
        if (!reader.truncated && serverLength > 0) {
            children.add(reader.field("server_id", String(server, StandardCharsets.UTF_8), serverStart))
        }
        return reader.group("trigger_header", start, children)
    }

    private fun uiModeName(value: Int): String =
        when (value) {
            0 -> "not-specified"
            1 -> "background"
            2 -> "informative"
            else -> "user-interaction"
        }
}
