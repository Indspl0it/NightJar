package dev.nightjar.app.data.cb

import dev.nightjar.app.data.repository.IncomingEvent
import dev.nightjar.app.data.repository.PduPayload
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Fate
import dev.nightjar.contract.Severity
import dev.nightjar.contract.Verdict
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A CB page that arrives after a process restart is folded with the pages already stored.
 *
 * The PDUs are the decoder corpus page `gsm_multipage_2of3` (synthetic text) with the page byte changed.
 */
class CbIngestTest {
    /** Page [page] of 3 of one broadcast: the corpus PDU with byte 5 (page parameter) replaced. */
    private fun pagePdu(page: Int): ByteArray =
        hex(CORPUS_PAGE).also { it[PAGE_BYTE] = ((page shl 4) or PAGES).toByte() }

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun event(page: Int): IncomingEvent =
        IncomingEvent(
            eventId = "page-$page",
            revision = 1,
            sessionId = "s",
            direction = "IN",
            transport = "CS",
            source = 0,
            subId = 1,
            slot = 0,
            carrierName = null,
            classId = ClassId.CB.wire(),
            severity = Severity.INFO.wire(),
            detectorSeverity = null,
            severityOverridden = false,
            title = "Broadcast",
            originator = null,
            smsc = null,
            destPort = null,
            contentType = null,
            textBody = null,
            textPreview = "",
            receivedAtWall = 1_000L + page,
            receivedAtElapsedNs = 1,
            modemTs = null,
            ackResult = 1,
            verdict = Verdict.ALLOW.wire(),
            ruleId = null,
            fate = Fate.SHOWN.wire(),
            fateDetail = null,
            consumerPackage = null,
            callingPackage = null,
            persistFlag = null,
            concatRef = null,
            partsTotal = 1,
            partsHave = 1,
            partsJson = null,
            purgeAt = null,
            isQuarantined = false,
            isReviewed = false,
            isStarred = false,
            isReplay = false,
            isSelfTest = false,
            releasedFromId = null,
            buildFingerprint = null,
            payloadVersion = null,
            pdus = listOf(PduPayload(1, pagePdu(page), "cb")),
            trace = emptyList(),
        )

    @Test
    fun `pages in one process count up`() =
        runBlocking {
            val ingest = CbIngest()
            val first = ingest.prepare(listOf(event(1))).single()
            val second = ingest.prepare(listOf(event(2))).single()

            assertEquals(first.eventId, second.eventId)
            assertEquals(1, first.revision)
            assertEquals(2, second.revision)
            assertEquals(3, second.partsTotal)
        }

    @Test
    fun `a later page after a restart is folded with the stored pages`() =
        runBlocking {
            val before = CbIngest().prepare(listOf(event(1))).single()
            val stored = mapOf(before.eventId to before.pdus)

            // A new process: the assembler is empty.
            val after = CbIngest().prepare(listOf(event(2))) { id -> stored[id].orEmpty() }.single()

            assertEquals(before.eventId, after.eventId)
            // Higher than the stored revision 1, so the store replaces the row instead of counting a duplicate.
            assertEquals(2, after.revision)
            assertEquals(2, after.partsHave)
            assertEquals(2, after.pdus.size)
        }

    @Test
    fun `a page that is already stored stays a duplicate after a restart`() =
        runBlocking {
            val ingest = CbIngest()
            ingest.prepare(listOf(event(1)))
            val both = ingest.prepare(listOf(event(2))).single()
            val stored = mapOf(both.eventId to both.pdus)

            val again = CbIngest().prepare(listOf(event(2))) { id -> stored[id].orEmpty() }.single()

            // The same revision as the stored row: the store counts it as a duplicate and still acknowledges it.
            assertEquals(both.revision, again.revision)
        }

    @Test
    fun `a UMTS message with two pages inside is one complete part`() =
        runBlocking {
            // The corpus UMTS message with number_of_pages = 2 and its page block twice.
            val umts = hex(UMTS_HEAD + "02" + UMTS_PAGE + UMTS_PAGE)
            // VR01-F19: part counts that the unchanged event cannot give, so the asserts prove the fold ran.
            val event = event(1).copy(partsTotal = 0, partsHave = 0, pdus = listOf(PduPayload(1, umts, "cb_umts")))

            val folded = CbIngest().prepare(listOf(event)).single()

            // The decode happened: the row identity comes from the PDU (serial 0x1234, message id 0x1112, update 4).
            assertEquals("cb:1:4660:4370:4", folded.eventId)
            assertEquals(1, folded.pdus.size)
            // The payload reports one UMTS message as page 1 of 1; the assembler must not wait for a page 2.
            assertEquals(1, folded.partsTotal)
            assertEquals(1, folded.partsHave)
            assertEquals(1, folded.revision)
        }

    @Test
    fun `a UMTS message that does not decode is kept as the payload sent it`() =
        runBlocking {
            // Negative control for the test above: a cut-off header has no message id or serial.
            val broken = event(1).copy(partsTotal = 0, partsHave = 0, pdus = listOf(PduPayload(1, hex(UMTS_HEAD.take(4)), "cb_umts")))

            val kept = CbIngest().prepare(listOf(broken)).single()

            assertEquals("page-1", kept.eventId)
            assertEquals(0, kept.partsTotal)

            // VR01-A13: the same for a GSM page cut inside its 6-octet header (serial and the first id octet only).
            val gsm = event(1).copy(partsTotal = 0, partsHave = 0, pdus = listOf(PduPayload(1, hex(CORPUS_PAGE.take(6)), "cb")))
            assertEquals("page-1", CbIngest().prepare(listOf(gsm)).single().eventId)
        }

    @Test
    fun `events that are not cell broadcasts pass through`() =
        runBlocking {
            val normal = event(1).copy(classId = ClassId.SILENT.wire())
            assertEquals(listOf(normal), CbIngest().prepare(listOf(normal)) { error("no lookup for a non-CB event") })
        }

    private companion object {
        const val PAGE_BYTE = 5
        const val PAGES = 3
        const val CORPUS_PAGE =
            "123411120F23D3F2F8ED2683E0E173B9D168341A8D46A3D168341A8D46A3D168341A8D46A3D168341A8D46A3D168341A8D46A3D1" +
                "68341A8D46A3D168341A8D46A3D168341A8D46A3D168341A8D46A3D168341A8D46A3D100"

        /** Decoder corpus `umts_cmas`: message type, message id, serial, DCS. */
        const val UMTS_HEAD = "01111212340F"

        /** One UMTS page block of the same corpus PDU: 82 data bytes and the length byte (0x52). */
        const val UMTS_PAGE =
            "5079799E2697DDF474980D0AB3CB727AA3D168341A8D46A3D168341A8D46A3D168341A8D46A3D168341A8D46A3D168341A8D" +
                "46A3D168341A8D46A3D168341A8D46A3D168341A8D46A3D168341A8D46A3D10052"
    }
}
