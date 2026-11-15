package dev.nightjar.pduparse;

import static dev.nightjar.pduparse.AnomalyFixturesTest.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.AnomalyCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Facts of the shared UDH parser follow the framework's reading (AOSP {@code SmsHeader.fromByteArray}); the
 * anomalies report where the IEDL reading differs.
 */
class UdhParserTest {

    @Test
    void wrongShiftLengthHidesAPortFromTheIedlReading() {
        // IE 0x24 with IEDL 7: the framework reads 1 octet and then finds the port IE inside the 7 octets.
        UdhFacts f = UdhParser.parse(hex("0924070005040B8423F0"));
        assertTrue(f.complete());
        assertEquals(0, f.singleShift());
        assertEquals(TpduParser.WAP_PUSH_PORT, f.destPort());
        assertEquals(0x23F0, f.origPort());
        assertFalse(f.port8Bit());
        assertEquals(0, f.portIeCount(), "the IEDL reading sees no port IE");
        assertEquals(List.of(AnomalyCode.IE_DUP), f.anomalies());
    }

    @Test
    void wrongPortLengthReadsPastTheHeader() {
        UdhFacts f = UdhParser.parse(hex("0505030B8423"));
        assertEquals(TpduParser.WAP_PUSH_PORT, f.destPort());
        assertEquals(-1, f.origPort(), "the framework reads -1 past the end");
        assertEquals(List.of(AnomalyCode.IE_OVERRUN, AnomalyCode.IE_DUP), f.anomalies());
    }

    @Test
    void twoConcatenationElementsAreDuplicatesAndTheLastValidOneWins() {
        UdhFacts f = UdhParser.parse(hex("0B0003010201080400020202"));
        assertEquals(2, f.concatRef());
        assertEquals(2, f.concatTotal());
        assertEquals(2, f.concatSeq());
        assertTrue(f.concat16Bit());
        assertEquals(List.of(AnomalyCode.IE_DUP), f.anomalies());
    }

    @Test
    void invalidLaterConcatenationElementIsIgnored() {
        UdhFacts f = UdhParser.parse(hex("0A00030102010003050001"));
        assertEquals(1, f.concatRef());
        assertEquals(1, f.concatSeq());
        assertFalse(f.concat16Bit());
        assertEquals(List.of(AnomalyCode.IE_DUP, AnomalyCode.CONCAT_BAD), f.anomalies());
    }

    @Test
    void specialSmsAndShifts() {
        UdhFacts special = UdhParser.parse(hex("0401028005"));
        assertTrue(special.specialSms());
        assertEquals(0x80, special.specialSmsType());
        assertEquals(5, special.specialSmsCount());
        assertEquals(List.of(), special.anomalies());
        UdhFacts shifts = UdhParser.parse(hex("06240102250103"));
        assertEquals(2, shifts.singleShift());
        assertEquals(3, shifts.lockingShift());
        assertEquals(List.of(), shifts.anomalies());
    }

    @Test
    void commandPacketIdentifierWithDataIsAConflict() {
        // TS 31.115 IE 0x70 has no data.
        UdhFacts f = UdhParser.parse(hex("03700100"));
        assertTrue(f.toolkitCommand());
        assertEquals(List.of(AnomalyCode.IE_DUP), f.anomalies());
    }

    @Test
    void identifierWithoutLengthIsAnOverrun() {
        UdhFacts f = UdhParser.parse(hex("0170"));
        assertFalse(f.toolkitCommand(), "the framework throws on a length of -1");
        assertEquals(List.of(AnomalyCode.IE_OVERRUN), f.anomalies());
    }

    @Test
    void portZeroOnlyForAPortTheFrameworkReads() {
        assertEquals(List.of(AnomalyCode.PORT_ZERO), UdhParser.parse(hex("0605040000F000")).anomalies());
        // A port IE inside the data of a reserved IE: the framework skips it, so no port and no AN-PORT-ZERO.
        UdhFacts hidden = UdhParser.parse(hex("08E006050400000000"));
        assertEquals(-1, hidden.destPort());
        assertEquals(List.of(AnomalyCode.IE_RSV), hidden.anomalies());
    }

    @Test
    void offsetAndLengthAreRelativeAndTotal() {
        byte[] b = hex("FFFF0605040B8423F0");
        assertEquals(TpduParser.WAP_PUSH_PORT, UdhParser.parse(b, 2, 7).destPort());
        assertEquals(List.of(AnomalyCode.UDH_LEN), UdhParser.parse(b, 2, 3).anomalies(), "header cut by len");
        for (int[] range : new int[][] {{-1, 4}, {b.length, 1}, {0, 0}, {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            assertEquals(List.of(AnomalyCode.UDH_LEN), UdhParser.parse(b, range[0], range[1]).anomalies());
        }
        assertEquals(TpduParser.WAP_PUSH_PORT, UdhParser.parse(b, 2, Integer.MAX_VALUE).destPort());
    }
}
