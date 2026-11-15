package dev.nightjar.pduparse;

import static dev.nightjar.pduparse.AnomalyFixturesTest.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.nightjar.contract.AnomalyCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Facts and anomalies of the shared WBXML scanner (WAP-192). */
class WbxmlScannerTest {
    /** Corpus {@code wbxml/sl_example} after the header: sl with href attributes, no content. */
    private static final String SL_BODY = "850A0378797A00850370706169642F3132332F6162632E776D6C0001";
    /** String table with the formal public identifier {@code -//WAPFORUM//DTD SL 1.0//EN} (28 octets). */
    private static final String SL_FPI_TABLE = "1C2D2F2F574150464F52554D2F2F44544420534C20312E302F2F454E00";

    @Test
    void version10HasNoCharset() {
        WbxmlFacts f = WbxmlScanner.scan(hex("0006" + "00" + SL_BODY));
        assertEquals(0, f.version());
        assertEquals(WbxmlScanner.PUBLIC_ID_SL, f.publicId());
        assertEquals(-1, f.charset());
        assertEquals(0, f.stringTableLength());
        assertEquals(5, f.rootTag(), "sl");
        assertEquals(List.of(), f.anomalies());
    }

    @Test
    void stringPublicIdKeepsTheTagCheck() {
        WbxmlFacts clean = WbxmlScanner.scan(hex("0300006A" + SL_FPI_TABLE + SL_BODY));
        assertEquals(0, clean.publicId(), "0: the id is a string");
        assertEquals(0x6A, clean.charset());
        assertEquals(List.of(), clean.anomalies());
        WbxmlFacts unknownTag = WbxmlScanner.scan(hex("0300006A" + SL_FPI_TABLE + "06"));
        assertEquals(List.of(AnomalyCode.WBXML_TOK), unknownTag.anomalies());
    }

    @Test
    void publicIdIndexOutsideTheTableIsStr() {
        WbxmlFacts f = WbxmlScanner.scan(hex("0300056A00" + SL_BODY));
        assertEquals(List.of(AnomalyCode.WBXML_STR), f.anomalies());
    }

    @Test
    void unknownPublicIdUsesTheContentTypeOfThePush() {
        // Push: content type 0x2E (application/vnd.wap.sic); WBXML with public id 0x01 (unknown) and tag 0x09.
        byte[] push = hex("010601AE" + "03016A0009");
        WspFacts w = WspParser.parse(push);
        assertEquals("application/vnd.wap.sic", w.contentType());
        WbxmlFacts body = WbxmlScanner.scanPushBody(w, push, 0);
        assertNotNull(body);
        assertEquals(List.of(AnomalyCode.WBXML_TOK), body.anomalies(), "tag 0x09 is not an SI tag");
        assertEquals(List.of(), WbxmlScanner.scan(hex("03016A0009")).anomalies(), "no type: no tag check");
        assertEquals(WbxmlScanner.PUBLIC_ID_SI, WbxmlScanner.documentType("application/vnd.wap.sic"));
        assertEquals(WbxmlScanner.PUBLIC_ID_PROV, WbxmlScanner.documentType("application/vnd.wap.connectivity-wbxml"));
        assertEquals(0, WbxmlScanner.documentType("application/vnd.wap.wmlc"));
        assertEquals(0, WbxmlScanner.documentType(null));
    }

    @Test
    void globalTokensInAnUnknownDocumentType() {
        // String table "A"; LITERAL_C element, OPAQUE of 2, PI with one attribute, SWITCH_PAGE 1, STR_T 0, END.
        WbxmlFacts f = WbxmlScanner.scan(hex("03016A024100" + "4400" + "C302AABB" + "430501" + "0001" + "8300" + "01"));
        assertEquals(4, f.rootTag(), "LITERAL");
        assertEquals(1, f.elements());
        assertEquals(List.of(), f.anomalies());
        // In the attribute code space only LITERAL can appear.
        assertEquals(List.of(AnomalyCode.WBXML_TOK), WbxmlScanner.scan(hex("03016A024100" + "8400840001")).anomalies());
        // OPAQUE longer than the document.
        assertEquals(List.of(AnomalyCode.WBXML_TOK), WbxmlScanner.scan(hex("03016A00" + "45C3050001")).anomalies());
    }

    @Test
    void rangesAreTotal() {
        byte[] doc = hex("0006" + "00" + SL_BODY);
        assertEquals(List.of(AnomalyCode.WBXML_TOK), WbxmlScanner.scan(doc, -1, 4).anomalies());
        assertEquals(List.of(AnomalyCode.WBXML_TOK), WbxmlScanner.scan(doc, doc.length, 4).anomalies());
        assertEquals(List.of(AnomalyCode.WBXML_TOK), WbxmlScanner.scan(doc, 0, -1).anomalies());
        assertEquals(List.of(), WbxmlScanner.scan(doc, 0, Integer.MAX_VALUE).anomalies());
        assertEquals(List.of(AnomalyCode.WBXML_TOK), WbxmlScanner.scan(doc, 2, Integer.MAX_VALUE).anomalies());
        byte[] push = hex("010601AE" + "03016A0009");
        WbxmlFacts far = WbxmlScanner.scanPushBody(WspParser.parse(push), push, Integer.MAX_VALUE);
        assertNotNull(far);
        assertEquals(List.of(AnomalyCode.WBXML_TOK), far.anomalies(), "base + body offset overflows an int");
    }
}
