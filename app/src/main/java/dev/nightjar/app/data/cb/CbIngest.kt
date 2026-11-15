package dev.nightjar.app.data.cb

import dev.nightjar.app.data.repository.IncomingEvent
import dev.nightjar.app.data.repository.PduPayload
import dev.nightjar.app.domain.cb.CbAssembler
import dev.nightjar.app.domain.cb.CbPage
import dev.nightjar.app.domain.cb.previewText
import dev.nightjar.contract.ClassId
import dev.nightjar.decoder.Decoders

/**
 * Turns the payload's one-event-per-page CB stream into one row per broadcast.
 *
 * Runs between [dev.nightjar.app.core.ipc.EventJson] parsing and `MessageStore.upsertBatch`, so the store keeps its
 * plain upsert contract: every page produces exactly one [IncomingEvent] whose `eventId` is the group and
 * whose `revision` is the number of pages seen so far. A duplicate page therefore arrives with a revision the store
 * already has, is counted as a duplicate and is still acknowledged — no second row, and the payload's retry never
 * spins.
 *
 * Non-CB events pass through untouched and in order. CL-CB-SIM (CB data download to the SIM) is a cell
 * broadcast too and folds the same way.
 *
 * The assembler is in memory, so after a process restart or an eviction it does not know the pages
 * that are already stored. Before a page is folded, the pages of the stored row of its group are given to the
 * assembler again (a page that it already has changes nothing). The revision then counts every page, stored or new,
 * and a later page is never taken for a duplicate of the stored row.
 */
class CbIngest(
    private val assembler: CbAssembler = CbAssembler(),
) {
    /**
     * Rewrites the CB events of one IPC batch; returns the batch to persist. [stored] gives the PDUs of the stored row
     * with a given event id (empty when there is none).
     */
    suspend fun prepare(
        events: List<IncomingEvent>,
        stored: suspend (eventId: String) -> List<PduPayload> = { emptyList() },
    ): List<IncomingEvent> {
        if (events.isEmpty()) return events
        if (events.none { isCb(it) }) return events
        val out = ArrayList<IncomingEvent>(events.size)
        for (event in events) {
            if (!isCb(event)) {
                out += event
            } else {
                out += fold(event, stored)
            }
        }
        return out
    }

    /** Number of broadcast groups currently held. Exposed for tests and diagnostics. */
    fun groups(): Int = assembler.size()

    fun clear() = assembler.clear()

    private suspend fun fold(
        event: IncomingEvent,
        stored: suspend (eventId: String) -> List<PduPayload>,
    ): IncomingEvent {
        val pdu = event.pdus.firstOrNull() ?: return event
        val page = pageOf(pdu.bytes, pdu.format)
        // Undecodable: keep the row exactly as the payload sent it rather than inventing an identity.
        if (page.messageId < 0 || page.serial < 0) return event

        for (part in stored(groupId(event.subId, page))) {
            val storedPage = pageOf(part.bytes, part.format)
            if (storedPage.messageId >= 0 && storedPage.serial >= 0) assembler.accept(storedPage, part.bytes, event.subId)
        }
        val assembly = assembler.accept(page, pdu.bytes, event.subId)
        val text = assembly.text
        return event.copy(
            eventId = groupId(assembly.key.subId, page),
            revision = assembly.pagesSeen.coerceAtLeast(1),
            textBody = text.ifEmpty { null },
            textPreview = previewText(text),
            partsTotal = assembly.pagesTotal,
            partsHave = assembly.pagesSeen,
            pdus =
                assembly.parts.mapIndexed { index, part ->
                    PduPayload(index + 1, part.bytes, part.page.format)
                },
        )
    }

    /**
     * One UMTS CB message holds every page of the broadcast (TS 25.324), and the payload reports it as page 1 of 1. The
     * decoder's `number_of_pages` counts the pages inside it, so the assembler would wait for pages that never come.
     *
     * VR01-A13: a PDU shorter than the 6-octet header (GSM and ETWS: serial, id, DCS or warning type; UMTS: type, id,
     * serial, DCS) has no identity. The decoder reads the missing octets as zero for its display tree, so without this
     * check a cut-off PDU got a made-up message id (for example 0x1100, an ETWS channel).
     */
    private fun pageOf(
        bytes: ByteArray,
        format: String,
    ): CbPage {
        if (bytes.size < CB_HEADER_OCTETS) return CbPage.UNKNOWN
        val page = CbPage.of(bytes, format)
        return if (page.format == Decoders.CELL_BROADCAST_UMTS) page.copy(page = 1, pages = 1) else page
    }

    companion object {
        private val CB_WIRES = setOf(ClassId.CB.wire(), ClassId.CB_SIM.wire())

        /** TS 23.041 the fixed header of a GSM page and of a UMTS message. */
        private const val CB_HEADER_OCTETS = 6

        /** True for an event of a cell broadcast class (CL-CB or CL-CB-SIM). */
        fun isCb(event: IncomingEvent): Boolean = event.classId in CB_WIRES

        /**
         * Stable row identity of one broadcast: serial, message id and update number, plus the subscription.
         * Two SIMs receiving the same alert are two rows; a re-sent alert (new update number) is a new row.
         */
        fun groupId(
            subId: Int,
            page: CbPage,
        ): String = "cb:$subId:${page.serial}:${page.messageId}:${page.updateNumber}"
    }
}
