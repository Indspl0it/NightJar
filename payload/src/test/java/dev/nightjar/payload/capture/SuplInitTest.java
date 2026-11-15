package dev.nightjar.payload.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.nightjar.contract.Subtype;
import org.junit.jupiter.api.Test;

/**
 * SUPL INIT notification type per X.691 unaligned PER and OMA-TS-ULP. The vectors are the shared ones of
 * the payload and the decoder (synthetic data: TEST-NET addresses and example.org).
 */
class SuplInitTest {
    private static final String V1_SILENT = "0011020000400000004600001008201000";
    private static final String V2_NOTIFY = "002F020000C001D58C66C80E000000028555ECD96B8D40E9BD80D000142AAF66CB5C6A07"
            + "4DEC008101820283038400";
    private static final String V3_VERIFY = "0011020000400000004600001008201008";
    private static final String V4_VERIFY = "001102000040000000460000100820900C";
    private static final String V5_PRIVACY_OVERRIDE = "0011020000400000004600001008201010";
    private static final String V6_NO_NOTIFICATION = "00100200004000000046000010080010";
    private static final String V7_SUPL_END = "000F02000040000000460000100A80";

    @Test
    void notificationTypesOfTheSharedVectors() {
        assertEquals(0, type(V1_SILENT));
        assertEquals(1, type(V2_NOTIFY));
        assertEquals(2, type(V3_VERIFY));
        assertEquals(3, type(V4_VERIFY));
        assertEquals(4, type(V5_PRIVACY_OVERRIDE));
        assertEquals(WapContentType.SUPL_NO_NOTIFICATION, type(V6_NO_NOTIFICATION));
        assertEquals(-1, type(V7_SUPL_END));
    }

    @Test
    void subtypesOfTheSharedVectors() {
        assertEquals(Subtype.WapSupl.SILENT, subtype(V1_SILENT));
        assertEquals(Subtype.WapSupl.NOTIFY, subtype(V2_NOTIFY));
        assertEquals(Subtype.WapSupl.VERIFY, subtype(V3_VERIFY));
        assertEquals(Subtype.WapSupl.VERIFY, subtype(V4_VERIFY));
        assertEquals(Subtype.WapSupl.SILENT, subtype(V5_PRIVACY_OVERRIDE));
        assertEquals(Subtype.WapSupl.SILENT, subtype(V6_NO_NOTIFICATION));
        assertEquals(null, subtype(V7_SUPL_END));
    }

    @Test
    void everyTruncationIsUnreadable() {
        byte[] full = hex(V3_VERIFY);
        for (int len = 0; len < full.length; len++) {
            assertEquals(-1, WapContentType.suplNotificationType(full, 0, len), "length " + len);
        }
    }

    @Test
    void anOffsetInsideALargerBufferIsHonoured() {
        byte[] body = hex(V2_NOTIFY);
        byte[] buf = new byte[body.length + 5];
        System.arraycopy(body, 0, buf, 3, body.length);
        assertEquals(1, WapContentType.suplNotificationType(buf, 3, body.length));
    }

    private static int type(String hex) {
        byte[] d = hex(hex);
        return WapContentType.suplNotificationType(d, 0, d.length);
    }

    private static String subtype(String hex) {
        return WapContentType.suplSubtype(type(hex));
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
