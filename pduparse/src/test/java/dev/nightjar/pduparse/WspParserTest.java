package dev.nightjar.pduparse;

import static dev.nightjar.pduparse.AnomalyFixturesTest.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.AnomalyCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Facts and anomalies of the shared WSP push parser. */
class WspParserTest {
    /** Corpus {@code wsp/push_oma_cp}: content type 0x36 (short form), application id 0x0A, WBXML body at 6. */
    private static final String OMA_CP = "010603B6AF8A020B6A0045C65501870706034D792041504E000187080603696E7465726E6574"
            + "000101C652018720060331302E302E302E3100018723060338303830000101C65101871C0603687474703A2F2F6578616D706C"
            + "652E636F6D2F00010101";
    /** Content-general-form with SEC 0x81 (USERPIN) inside the value. */
    private static final String USERPIN = "01060603B69181AF8A020B6A0045C6550187070603546573742041504E00010101";
    /** Corpus {@code wsp/push_supl} with the application id as text whose NUL is the first body octet. */
    private static final String APPID_TEXT_CUT = "01061D03020312AF782D6F6D612D6170706C69636174696F6E3A756C702E75610001"
            + "020304";
    /** Content-general-form: MAC (a text value), then SEC 0x81 (USERPIN). */
    private static final String MAC_THEN_SEC = "01060B08B692414243009181AF8A020B6A0045C6550187070603546573742041504E00"
            + "010101";
    /** Content-general-form: charset utf-8, then SEC 0x80 (NETWPIN). */
    private static final String CHARSET_THEN_SEC = "01060805B681EA9180AF8A020B6A0045C6550187070603546573742041504E0001"
            + "0101";
    /** X-Wap-Application-Id as a Long-integer field name: short-length 1, value 0x2F. */
    private static final String LONG_INTEGER_APPID_NAME = "010604B6012F8A020B6A0045C6550187070603546573742041504E000101"
            + "01";
    /** 70 Accept headers (0x80 0x80) before X-Wap-Application-Id; HeadersLen 143 as a two-octet uintvar. */
    private static final String FILLER_HEADERS = "0106810FB68080808080808080808080808080808080808080808080808080808080"
            + "808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080"
            + "808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080"
            + "808080808080808080AF8A020B6A0045C6550187070603546573742041504E00010101";

    @Test
    void shortFormFacts() {
        byte[] b = hex(OMA_CP);
        WspFacts f = WspParser.parse(b);
        assertTrue(f.valid(), f.error());
        assertEquals(1, f.transactionId());
        assertEquals(WspParser.PDU_TYPE_PUSH, f.pduType());
        assertEquals(3, f.headerLength());
        assertEquals(0x36, f.contentTypeCode());
        assertNull(f.contentTypeText());
        assertEquals("application/vnd.wap.connectivity-wbxml", f.contentType());
        assertEquals(0x0A, f.applicationIdCode());
        assertEquals("0xa", f.applicationId(), "the former WspMini text form");
        assertEquals(-1, f.secParam());
        assertFalse(f.macParam());
        assertEquals(6, f.bodyOffset());
        assertEquals(b.length - 6, f.bodyLength());
        assertEquals(List.of(), f.anomalies());
        WbxmlFacts body = WbxmlScanner.scanPushBody(f, b, 0);
        assertNotNull(body);
        assertEquals(List.of(), body.anomalies());
    }

    @Test
    void generalFormRecordsTheSecParameter() {
        WspFacts f = WspParser.parse(hex(USERPIN));
        assertTrue(f.valid(), f.error());
        assertEquals(0x36, f.contentTypeCode());
        assertEquals(1, f.secParam());
        assertEquals(9, f.bodyOffset());
        assertEquals(List.of(), f.anomalies());
    }

    @Test
    void offsetEntryPointReadsTheSlice() {
        byte[] padded = hex("FFFF" + USERPIN);
        WspFacts f = WspParser.parse(padded, 2, padded.length - 2);
        assertEquals(1, f.secParam());
        assertEquals(9, f.bodyOffset(), "offsets are relative to the slice");
    }

    @Test
    void unreadablePushes() {
        assertEquals(List.of(AnomalyCode.WSP_TYPE), WspParser.parse(hex("010503B6AF8A")).anomalies());
        assertEquals(List.of(AnomalyCode.WSP_HLEN), WspParser.parse(hex("0106")).anomalies());
        assertEquals(List.of(AnomalyCode.WSP_HLEN), WspParser.parse(hex("0106FFB6")).anomalies());
        WspFacts cut = WspParser.parse(hex("0106"));
        assertFalse(cut.valid());
        assertEquals("short", cut.error());
        assertNull(WbxmlScanner.scanPushBody(WspParser.parse(hex("010503B6AF8A")), hex("010503B6AF8A"), 0));
    }

    @Test
    void applicationIdWithNoValueIsSoft() {
        WspFacts f = WspParser.parse(hex("010602AEAF"));
        assertEquals(List.of(AnomalyCode.WSP_APPID), f.anomalies());
        assertNull(Anomalies.forcing(f.anomalyMask()), "AN-WSP-APPID forces no class");
    }

    @Test
    void normalizeDropsParametersAndCase() {
        assertEquals("application/vnd.wap.sic", WspParser.normalize(" Application/VND.WAP.SIC ; charset=utf-8"));
        assertNull(WspParser.normalize(null));
    }

    @Test
    void tablesGiveNullForUnknownCodes() {
        assertEquals("application/vnd.wap.connectivity-wbxml", WspTables.contentTypeName(0x36));
        assertNull(WspTables.contentTypeName(0x7FFF));
        assertEquals("x-wap-application:wml.ua", WspTables.applicationIdName(0x02));
        assertNull(WspTables.applicationIdName(-1));
    }

    @Test
    void cutTextApplicationIdKeepsTheContentType() {
        WspFacts f = WspParser.parse(hex(APPID_TEXT_CUT));
        assertTrue(f.valid(), "the content type was read");
        assertEquals("applicationId", f.error());
        assertEquals(0x0312, f.contentTypeCode());
        assertNull(f.applicationId(), "the text is not read into the body");
        assertEquals(List.of(AnomalyCode.WSP_APPID), f.anomalies());
    }

    @Test
    void textApplicationId() {
        WspFacts f = WspParser.parse(hex(APPID_TEXT_CUT.replace("01061D", "01061E")));
        assertTrue(f.valid(), f.error());
        assertEquals("x-oma-application:ulp.ua", f.applicationId());
        assertEquals(-1, f.applicationIdCode());
        assertEquals(List.of(), f.anomalies());
    }

    @Test
    void secIsFoundAfterOtherParameters() {
        WspFacts mac = WspParser.parse(hex(MAC_THEN_SEC));
        assertTrue(mac.macParam());
        assertEquals(1, mac.secParam());
        assertEquals(0x0A, mac.applicationIdCode());
        assertEquals(List.of(), mac.anomalies());
        WspFacts charset = WspParser.parse(hex(CHARSET_THEN_SEC));
        assertFalse(charset.macParam());
        assertEquals(0, charset.secParam(), "SEC 0x80: NETWPIN");
        assertEquals(List.of(), charset.anomalies());
    }

    @Test
    void longIntegerFieldNameIsTheApplicationId() {
        WspFacts f = WspParser.parse(hex(LONG_INTEGER_APPID_NAME));
        assertEquals(0x0A, f.applicationIdCode());
        assertEquals(7, f.bodyOffset());
        assertEquals(List.of(), f.anomalies());
    }

    @Test
    void fillerHeadersDoNotHideTheApplicationId() {
        byte[] b = hex(FILLER_HEADERS);
        WspFacts f = WspParser.parse(b);
        assertEquals(143, f.headerLength());
        assertEquals(0x0A, f.applicationIdCode());
        assertEquals(4 + 143, f.bodyOffset());
        assertEquals(List.of(), f.anomalies());
        WbxmlFacts body = WbxmlScanner.scanPushBody(f, b, 0);
        assertNotNull(body);
        assertEquals(List.of(), body.anomalies());
    }

    @Test
    void badRangeIsHeaderLength() {
        byte[] b = hex(USERPIN);
        for (int[] range : new int[][] {{-1, 4}, {b.length + 1, 1}, {0, -1}, {Integer.MIN_VALUE, Integer.MAX_VALUE}}) {
            WspFacts f = WspParser.parse(b, range[0], range[1]);
            assertFalse(f.valid());
            assertEquals("range", f.error());
            assertEquals(List.of(AnomalyCode.WSP_HLEN), f.anomalies());
        }
        assertEquals("range", WspParser.parse(null, 0, 1).error());
        WspFacts all = WspParser.parse(b, 0, Integer.MAX_VALUE);
        assertEquals(1, all.secParam(), "a length past the end reads to the end");
        WspFacts sum = WspParser.parse(b, 2, Integer.MAX_VALUE);
        assertEquals(List.of(AnomalyCode.WSP_TYPE), sum.anomalies(), "off + len overflows an int");
    }

    @Test
    void addedWellKnownCodesAndVcard() {
        assertEquals("application/vnd.syncml.ds.notification", WspTables.contentTypeName(0x4E));
        assertEquals("application/vnd.docomo.ub", WspTables.contentTypeName(0x0311));
        assertNull(WspTables.contentTypeName(0x0319), "spelling not confirmed");
        WspFacts vcard = WspParser.parse(hex("01060403020309" + "00"));
        assertEquals(0x0309, vcard.contentTypeCode());
        assertEquals("text/x-vcard", vcard.contentType());
    }
}
