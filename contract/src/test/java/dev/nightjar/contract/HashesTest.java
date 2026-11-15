package dev.nightjar.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Correlation keys. */
class HashesTest {

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    // Synthetic SMS-DELIVER from +1 555 0100 123 (fictional range), 4 GSM 7-bit chars, SMSC +1 555 0100 000.
    private static final String SMSC = "07915155100000F0";
    private static final String TPDU = "040B915155100021F300002080629173140804F4F29C0E";

    @Test
    void CAP_01_tpduKeyIsFirst16BytesOfSha256() {
        // sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertEquals("ba7816bf8f01cfea414140de5dae2223", Hashes.tpduKey("abc".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> Hashes.tpduKey(null));
    }

    @Test
    void CAP_01_radioAndImsFormsCorrelate() {
        byte[] radio = hex(SMSC + TPDU);
        byte[] bare = hex(TPDU);
        assertArrayEquals(bare, Hashes.stripSmsc(radio));
        assertEquals(Hashes.tpduKey(bare), Hashes.tpduKey(Hashes.stripSmsc(radio)));
    }

    @Test
    void CAP_01_emptySmscOctetIsStripped() {
        assertArrayEquals(hex(TPDU), Hashes.stripSmsc(hex("00" + TPDU)));
    }

    @Test
    void CAP_01_implausibleSmscLengthLeavesPduUnchanged() {
        byte[] p = hex("F0" + TPDU);
        assertSame(p, Hashes.stripSmsc(p));
        byte[] tiny = hex("05");
        assertSame(tiny, Hashes.stripSmsc(tiny));
        byte[] tooShort = hex("0791");
        assertSame(tooShort, Hashes.stripSmsc(tooShort));
    }
}
