package dev.nightjar.pduparse;

import static dev.nightjar.pduparse.AnomalyFixturesTest.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.AnomalyCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Header facts of the shared OTA packet parser (TS 31.115, ETSI TS 102 225). */
class OtaParserTest {
    /** Response packet: RPL 11, RHL 10, TAR B0 00 10, CNTR 1, PCNTR 0, status 0x0A. */
    private static final String RESPONSE = "000B0AB000100000000001000A";
    /** First segment of a command packet: CPL 0x40 (the whole packet), CHL 0x15, CC, TAR B0 00 00; 28 octets here. */
    private static final String FIRST_SEGMENT = "00401502000000B00000000000000001" + "000000000000000000000000";

    @Test
    void responsePacketFacts() {
        byte[] b = hex(RESPONSE);
        OtaFacts f = OtaParser.parse(b, 0, b.length, true, false);
        assertTrue(f.response());
        assertEquals(-1, f.spi(), "a response has no SPI");
        assertFalse(f.ciphered());
        assertEquals(0xB00010, f.tar());
        assertEquals(1L, f.counter());
        assertEquals(0, f.paddingCounter());
        assertEquals(0x0A, f.status());
        assertTrue(f.headerComplete());
        assertEquals(List.of(), f.anomalies());
    }

    @Test
    void multipartPacketLengthCountsTheWholePacket() {
        byte[] b = hex(FIRST_SEGMENT);
        assertEquals(28, b.length);
        OtaFacts first = OtaParser.parse(b, 0, b.length, false, true);
        assertEquals(0x40, first.packetLength());
        assertEquals(0xB00000, first.tar());
        assertFalse(first.satPush() || first.wibCommand(), "the data shape is read only for a whole packet");
        assertEquals(List.of(), first.anomalies());
        // The same octets as a whole packet: CPL does not match the data.
        assertEquals(List.of(AnomalyCode.OTA_HDR), OtaParser.parse(b, 0, b.length, false, false).anomalies());
        // A CPL below the data present is wrong also in a segment.
        b[1] = 0x10;
        assertEquals(List.of(AnomalyCode.OTA_HDR), OtaParser.parse(b, 0, b.length, false, true).anomalies());
    }

    @Test
    void rangesAreTotal() {
        byte[] b = hex(RESPONSE);
        for (int[] range : new int[][] {{-1, 4}, {b.length + 1, 1}, {0, -1}, {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            OtaFacts f = OtaParser.parse(b, range[0], range[1], true, false);
            assertEquals(List.of(AnomalyCode.OTA_HDR), f.anomalies());
            assertEquals(-1, f.tar());
        }
        assertEquals(List.of(), OtaParser.parse(b, 0, Integer.MAX_VALUE, true, false).anomalies());
    }
}
