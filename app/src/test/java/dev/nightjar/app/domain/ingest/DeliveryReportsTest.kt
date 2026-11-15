package dev.nightjar.app.domain.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Builds a synthetic SMS-STATUS-REPORT for tests (TS 23.040 section 9.2.2.3). The numbers are not real. */
internal object StatusReportPdus {
    private const val TIMESTAMP_OCTETS = 7

    fun build(
        reference: Int,
        recipient: String,
        status: Int,
        withSmsc: Boolean = true,
    ): ByteArray {
        val out = ArrayList<Byte>()
        // A zero-length SMSC means "use the default".
        if (withSmsc) out += 0x00
        // MTI 2 (status report) with TP-SRQ set.
        out += 0x06
        out += reference.toByte()
        out += recipient.length.toByte()
        // Type of address: international, ISDN.
        out += 0x91.toByte()
        val padded = if (recipient.length % 2 == 0) recipient else recipient + "F"
        padded.chunked(2).forEach { pair ->
            out += ((Character.digit(pair[1], 16) shl 4) or Character.digit(pair[0], 16)).toByte()
        }
        // TP-SCTS and TP-DT.
        repeat(TIMESTAMP_OCTETS * 2) { out += 0x00 }
        out += status.toByte()
        return out.toByteArray()
    }
}

class DeliveryReportsTest {
    @Test
    fun `the parser reads TP-MR, TP-RA and TP-ST with an SMSC prefix`() {
        val facts = StatusReportFacts.read(StatusReportPdus.build(200, "15559000000002", 0x00), withSmsc = true)!!
        assertEquals(200, facts.messageReference)
        // TOA 0x91 is international, so the shared address decoder adds the plus sign.
        assertEquals("+15559000000002", facts.recipient)
        assertEquals(0, facts.status)
    }

    @Test
    fun `the parser reads a bare TPDU report with an odd digit count`() {
        val facts = StatusReportFacts.read(StatusReportPdus.build(7, "9000000", 0x20, withSmsc = false), withSmsc = false)!!
        assertEquals(7, facts.messageReference)
        assertEquals("+9000000", facts.recipient)
        assertEquals(DeliveryStatus.PENDING, facts.deliveryStatus)
    }

    @Test
    fun `the parser returns null for a truncated or a non-report PDU`() {
        val full = StatusReportPdus.build(1, "15559000000002", 0)
        assertNull(StatusReportFacts.read(full.copyOf(full.size - 1), withSmsc = true))
        assertNull(StatusReportFacts.read(byteArrayOf(), withSmsc = true))
        assertNull(StatusReportFacts.read(byteArrayOf(0x00, 0x04, 0x01), withSmsc = true))
        assertNull(StatusReportFacts.read(byteArrayOf(0x04, 0x01), withSmsc = false))
    }

    @Test
    fun `TP-ST maps to delivered, pending or failed`() {
        assertEquals(DeliveryStatus.DELIVERED, DeliveryStatus.fromTpSt(0x00))
        assertEquals(DeliveryStatus.DELIVERED, DeliveryStatus.fromTpSt(0x1F))
        assertEquals(DeliveryStatus.PENDING, DeliveryStatus.fromTpSt(0x20))
        assertEquals(DeliveryStatus.PENDING, DeliveryStatus.fromTpSt(0x3F))
        assertEquals(DeliveryStatus.FAILED, DeliveryStatus.fromTpSt(0x40))
        assertEquals(DeliveryStatus.FAILED, DeliveryStatus.fromTpSt(0x7F))
        assertNull(DeliveryStatus.fromTpSt(0x80))
    }

    @Test
    fun `numbers match when equal or when one is the tail of the other`() {
        assertTrue(DeliveryReportLinker.sameNumber("15559000000002", "15559000000002"))
        assertTrue(DeliveryReportLinker.sameNumber("9000000002", "15559000000002"))
        assertFalse(DeliveryReportLinker.sameNumber("15559000000002", "15559000000003"))
        assertFalse(DeliveryReportLinker.sameNumber("12345", "9912345"))
        assertFalse(DeliveryReportLinker.sameNumber(null, "15559000000002"))
    }
}
