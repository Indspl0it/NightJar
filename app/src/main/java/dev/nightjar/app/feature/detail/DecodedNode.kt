package dev.nightjar.app.feature.detail

import dev.nightjar.app.data.model.Pdu
import dev.nightjar.decoder.Decoders
import dev.nightjar.decoder.Field

/** One flattened decoder node, ready for the Raw tab tree. */
data class DecodedNode(
    val name: String,
    val value: String?,
    val offset: Int,
    val length: Int,
    val depth: Int,
    val error: String?,
) {
    val range: IntRange get() = offset until (offset + length).coerceAtLeast(offset)
}

/** Flattens a decoder tree in decode order, keeping the depth for indentation. */
fun flattenFields(
    field: Field,
    depth: Int = 0,
): List<DecodedNode> =
    listOf(
        DecodedNode(
            name = field.name,
            value = field.value?.toString(),
            offset = field.offset,
            length = field.length,
            depth = depth,
            error = field.error,
        ),
    ) + field.children.flatMap { flattenFields(it, depth + 1) }

/** The deepest node whose byte range contains [offset]; the reverse link of the Raw tab. */
fun deepestNodeAt(
    nodes: List<DecodedNode>,
    offset: Int,
): DecodedNode? =
    nodes
        .filter { node -> node.length > 0 && offset >= node.offset && offset < node.offset + node.length }
        .maxByOrNull { it.depth }

/** The decoder to use for a stored PDU format: `cb` (GSM) and `cb_umts` as in [dev.nightjar.app.domain.cb.CbPage.of]. */
fun decoderKind(format: String?): String =
    when {
        format.equals(Decoders.CELL_BROADCAST, ignoreCase = true) -> Decoders.CELL_BROADCAST
        format.equals(Decoders.CELL_BROADCAST_UMTS, ignoreCase = true) -> Decoders.CELL_BROADCAST_UMTS
        else -> Decoders.TPDU
    }

/** Decodes every PDU; a malformed part yields an error node rather than an exception. */
fun decodePdu(pdu: Pdu): Field = Decoders.decodeBytes(decoderKind(pdu.format), pdu.bytes)

/** Concatenates the stored PDU bytes, the block the Raw tab dumps. */
fun rawBytes(pdus: List<Pdu>): ByteArray {
    val size = pdus.sumOf { it.bytes.size }
    val out = ByteArray(size)
    var at = 0
    for (pdu in pdus) {
        pdu.bytes.copyInto(out, at)
        at += pdu.bytes.size
    }
    return out
}

/** Whether the message offers a Release button at all: on hold, releasable, and a releaser bound. */
fun canRelease(
    quarantined: Boolean,
    releasable: Boolean,
    releaserBound: Boolean,
): Boolean = quarantined && releasable && releaserBound
