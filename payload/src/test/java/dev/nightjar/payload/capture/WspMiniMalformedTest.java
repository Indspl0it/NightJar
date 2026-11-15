package dev.nightjar.payload.capture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WspMiniMalformedTest {
    @Test
    void overflowingHeaderLengthIsMalformed() {
        // TID, Push, headerLength uintvar 0x88 0x80 0x80 0x80 0x00 = 2^31, which is Integer.MIN_VALUE as an int.
        byte[] pdu = {0x01, 0x06, (byte) 0x88, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x00, 0x03, (byte) 0xBE, 0x00};
        assertFalse(WspMini.parse(pdu).valid());
    }

    @Test
    void ordinaryPushStaysValid() {
        // headerLength 3: content type 0xAE (application/vnd.wap.connectivity-wbxml) plus two header octets.
        byte[] pdu = {0x01, 0x06, 0x03, (byte) 0xAE, (byte) 0x81, 0x00, 0x01, 0x02};
        assertTrue(WspMini.parse(pdu).valid());
    }
}
