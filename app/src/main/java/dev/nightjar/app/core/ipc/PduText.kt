package dev.nightjar.app.core.ipc

import dev.nightjar.decoder.Decoders
import dev.nightjar.decoder.Field
import java.time.OffsetDateTime

/**
 * The message text and the modem time stamp, read from the event PDUs with the shared `:decoder`.
 *
 * The payload event has no text field. The app reads the text from the same bytes that the Raw tab shows, so the
 * stored text and the decoded tree cannot differ. The text is never logged.
 */
internal object PduText {
    data class Decoded(
        /** The joined text of all parts, or null when no part has text (8-bit data, a WAP push, an unknown format). */
        val text: String?,
        /** TP-SCTS of the first part in wall-clock milliseconds, or null when the PDU has none (C.3 `modemTs`). */
        val modemTs: Long?,
    )

    private val NONE = Decoded(text = null, modemTs = null)

    /** 3GPP TPDU (with the SMSC address in front), as the payload stores it for format `3gpp`. */
    private const val FORMAT_3GPP = "3gpp"

    /**
     * Decodes [pdus] of [format]. A multipart TPDU message is joined in UDH sequence order, and a part without a
     * sequence number keeps its place. A part that does not decode adds no text. Never throws.
     */
    fun decode(
        pdus: List<ByteArray>,
        format: String,
    ): Decoded {
        if (pdus.isEmpty()) return NONE
        return runCatching {
            when {
                format.equals(FORMAT_3GPP, ignoreCase = true) -> tpdu(pdus)

                format.equals(Decoders.CELL_BROADCAST, ignoreCase = true) ||
                    format.equals(Decoders.CELL_BROADCAST_UMTS, ignoreCase = true) -> cb(pdus, format)

                else -> NONE
            }
        }.getOrDefault(NONE)
    }

    private fun tpdu(pdus: List<ByteArray>): Decoded {
        val trees = pdus.map { Decoders.decodeBytes(Decoders.TPDU, it) }
        val ordered =
            trees.withIndex().sortedWith(compareBy({ sequenceOf(it.value) ?: (it.index + 1) }, { it.index })).map { it.value }
        val text = join(ordered.map { it.textValue("user_data/text") })
        val modemTs = trees.firstOrNull()?.textValue("scts")?.let(::epochMs)
        return Decoded(text, modemTs)
    }

    /** The payload sends one event for each CB page; the text of a page is `content/text` (8-bit pages have none). */
    private fun cb(
        pdus: List<ByteArray>,
        format: String,
    ): Decoded {
        val umts = format.equals(Decoders.CELL_BROADCAST_UMTS, ignoreCase = true)
        val kind = if (umts) Decoders.CELL_BROADCAST_UMTS else Decoders.CELL_BROADCAST
        return Decoded(join(pdus.map { Decoders.decodeBytes(kind, it).textValue("content/text") }), modemTs = null)
    }

    private fun join(parts: List<String?>): String? = parts.filterNotNull().joinToString("").ifEmpty { null }

    /**
     * The `sequence` of the concatenation IE (8-bit or 16-bit reference), or null when the part has none. The decoder
     * names every element node `information_element` and puts the element type in its `name` child (VR01-A01).
     */
    private fun sequenceOf(tree: Field): Int? {
        val udh = tree.child("user_data/udh") ?: return null
        val concat =
            udh.children.firstOrNull { ie ->
                ie.name == "information_element" && ie.textValue("name")?.startsWith("concatenated_") == true
            } ?: return null
        return (concat.childValue("sequence") as? Number)?.toInt()
    }

    /** `2026-11-13T10:20:30+05:30` (the decoder's ISO form) to epoch milliseconds; null for an invalid date. */
    private fun epochMs(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
}
