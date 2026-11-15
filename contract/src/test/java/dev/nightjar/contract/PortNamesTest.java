package dev.nightjar.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The port table of {@link PortNames}. */
class PortNamesTest {

    private static final int[] TABLE = {
        0, 2948, 2949, 9200, 9201, 9202, 9203, 9204, 9205, 9206, 9207, 5505, 5506, 5507, 5514, 7275,
        2805, 2923, 5502, 5503, 5508, 5511, 5512, 49996, 49999,
    };

    @Test
    void everyEntryHasASubtypeOfItsClass() {
        for (int port : TABLE) {
            ClassId c = PortNames.classOf(port);
            String s = PortNames.subtype(port);
            assertTrue(Subtype.known(c).contains(s), port + " -> " + c + " " + s);
            assertTrue(PortNames.known(port), String.valueOf(port));
        }
    }

    @Test
    void wapPortsAreDataPortsWithANameCode() {
        assertEquals(ClassId.DATA_PORT, PortNames.classOf(2948));
        assertEquals(Subtype.DataPort.WAP_PUSH, PortNames.subtype(PortNames.WAP_PUSH));
        assertEquals(Subtype.DataPort.WAP_PUSH_SECURE, PortNames.subtype(2949));
        assertEquals(Subtype.DataPort.WAP_CONNECTIONLESS_SESSION, PortNames.subtype(9200));
        assertEquals(Subtype.DataPort.WAP_SESSION, PortNames.subtype(9201));
        assertEquals(Subtype.DataPort.WAP_CONNECTIONLESS_SESSION_SECURE, PortNames.subtype(9202));
        assertEquals(Subtype.DataPort.WAP_SESSION_SECURE, PortNames.subtype(9203));
    }

    @Test
    void od1SmartMessagingOwnsTheVcardPortsAndTheSecurePortsStayDataPorts() {
        assertEquals(ClassId.SMART, PortNames.classOf(9204));
        assertEquals(Subtype.Smart.VCARD, PortNames.subtype(9204));
        assertEquals(ClassId.SMART, PortNames.classOf(9205));
        assertEquals(Subtype.Smart.VCAL, PortNames.subtype(9205));
        assertEquals(ClassId.DATA_PORT, PortNames.classOf(9206));
        assertEquals(Subtype.DataPort.WAP_VCARD_VCALENDAR, PortNames.subtype(9206));
        assertEquals(ClassId.DATA_PORT, PortNames.classOf(9207));
        assertEquals(Subtype.DataPort.WAP_VCARD_VCALENDAR, PortNames.subtype(9207));
    }

    @Test
    void smartMessagingAndSuplPorts() {
        assertEquals(Subtype.Smart.RINGTONE, PortNames.subtype(5505));
        assertEquals(Subtype.Smart.OPLOGO, PortNames.subtype(5506));
        assertEquals(Subtype.Smart.CLILOGO, PortNames.subtype(5507));
        assertEquals(Subtype.Smart.PICTURE, PortNames.subtype(5514));
        assertEquals(ClassId.LOC_SUPL, PortNames.classOf(7275));
        assertEquals(Subtype.LocSupl.SMS, PortNames.subtype(7275));
    }

    @Test
    void jsr120RestrictedPortsAreNamedDataPorts() {
        assertEquals(Subtype.DataPort.WAP_WTA_CONNECTIONLESS_SESSION_SECURE, PortNames.subtype(2805));
        assertEquals(Subtype.DataPort.WAP_WTA_SESSION_SECURE, PortNames.subtype(2923));
        assertEquals(Subtype.DataPort.SERVICE_CARD, PortNames.subtype(5502));
        assertEquals(Subtype.DataPort.INTERNET_ACCESS_CONFIG, PortNames.subtype(5503));
        assertEquals(Subtype.DataPort.DYNAMIC_MENU_CONTROL, PortNames.subtype(5508));
        assertEquals(Subtype.DataPort.MESSAGE_ACCESS, PortNames.subtype(5511));
        assertEquals(Subtype.DataPort.EMAIL_NOTIFICATION, PortNames.subtype(5512));
        assertEquals(Subtype.DataPort.SYNCML_OTA_CONFIG, PortNames.subtype(49996));
        assertEquals(Subtype.DataPort.WAP_OTA_CONFIG, PortNames.subtype(49999));
        for (int port : new int[] {2805, 2923, 5502, 5503, 5508, 5511, 5512, 49996, 49999}) {
            assertEquals(ClassId.DATA_PORT, PortNames.classOf(port), String.valueOf(port));
        }
    }

    @Test
    void everyDataPortCodeHasAPort() {
        // No dead code in the contract: each DATA-PORT subtype is reachable from the table.
        java.util.Set<String> reached = new java.util.HashSet<>();
        for (int port = 0; port <= 0xFFFF; port++) {
            if (PortNames.classOf(port) == ClassId.DATA_PORT) reached.add(PortNames.subtype(port));
        }
        assertEquals(new java.util.HashSet<>(Subtype.known(ClassId.DATA_PORT)), reached);
    }

    @Test
    void portZeroUnknownPortsAndNoPort() {
        assertEquals(ClassId.DATA_PORT, PortNames.classOf(0));
        assertEquals(Subtype.DataPort.PORT0, PortNames.subtype(0));
        assertEquals(ClassId.DATA_PORT, PortNames.classOf(16001));
        assertEquals(Subtype.DataPort.UNKNOWN, PortNames.subtype(16001));
        assertFalse(PortNames.known(16001));
        assertNull(PortNames.classOf(-1));
        assertNull(PortNames.subtype(-1));
        assertFalse(PortNames.known(-1));
    }
}
