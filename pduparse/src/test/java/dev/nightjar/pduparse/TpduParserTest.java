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

/** Facts of the shared TPDU parser. The PDUs are the synthetic ones of {@code testdata/scenarios.json}. */
class TpduParserTest {
    /** text-7bit: SMSC +15550100, OA +15550142, PID 0, DCS 0, 14 septets in 13 octets. */
    private static final String TEXT = "059151551000040891515510240000621150214365220ED4F29C0E6A97E7F3F0B90C8A01";
    private static final String STATUS_FAILED = "059151551000060108915155102462115021436522621150214365224000";
    private static final String CP_USERPIN = "05915155100044089151551024000462115021436522280605040B8423F001060603B691"
            + "81AF8A020B6A0045C6550187070603546573742041504E00010101";
    private static final String OTA_RFM = "059151551000440891515510247FF6621150214365221F027000001A1502000000B000000000"
            + "00000100000000000000000000000000";
    private static final String MULTIPART = "05915155100044089151551024000062115021436522150500032B0201A8E5391DD42ECFE7"
            + "E173191403";
    /** TP-PI 0x87 with one extension octet, then PID, DCS and 4 septets. */
    private static final String STATUS_PI_EXT = "05915155100006010891515510246211502143652262115021436522008700000004D4"
            + "F29C0E";
    /** TP-PI 0x04: user data without TP-DCS, so the default DCS 0x00 (GSM 7-bit) applies. */
    private static final String STATUS_UD_NO_DCS = "05915155100006010891515510246211502143652262115021436522000404D4F2"
            + "9C0E";
    private static final String UCS2 = "05915155100004089151551024000862115021436522080054006500730074";
    private static final String UDL_141 = "059151551000040891515510240004621150214365228D000000000000000000000000000000"
            + "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
            + "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
            + "000000000000000000000000000000000000000000000000";
    /** Port 2948 with DCS 0x00: a push of PDU type 0x40 after the UDH. */
    private static final String PUSH_7BIT_BAD_TYPE = "059151551000440891515510240000621150214365220E0605040B8423F00140"
            + "03AEAF84";
    /** Port 2948 with DCS 0x00 and a septet UDL of 21: the corpus MMS notification push after the UDH. */
    private static final String PUSH_7BIT_MMS = "05915155100044089151551024000062115021436522150605040B8423F0010603BEAF"
            + "848C829861800D";
    private static final String OTA_CIPHERED = "059151551000440891515510247FF66211502143652223027000001E1516211515B000"
            + "1000000000010000000000000000000000000000000000";
    private static final String OTA_FIRST_SEGMENT = "059151551000440891515510247FF662115021436522240700032C020170000040"
            + "1502000000B00000000000000001000000000000000000000000";
    private static final String OTA_LATER_SEGMENT = "059151551000440891515510247FF6621150214365221C0700032C020270000102"
            + "030405060708090A0B0C0D0E0F1011121314";
    /** SMSC length 0, then MTI 3; read from octet 0 it is a deliver whose UDL runs past the data. */
    private static final String RETRY_MALFORMED = "000791515510240000621150214365220ED4F2";

    private static byte[] with(String pdu, int index, int value) {
        byte[] b = hex(pdu);
        b[index] = (byte) value;
        return b;
    }

    @Test
    void deliverFacts() {
        TpduFacts f = TpduParser.parseRadio(hex(TEXT));
        assertTrue(f.valid(), f.parseError());
        assertEquals(0, f.mti());
        assertEquals("+15550142", f.originator());
        assertEquals(0x91, f.originatorToa());
        assertEquals(8, f.originatorLength());
        assertEquals("+15550100", f.smsc());
        assertEquals(0, f.protocolId());
        assertEquals(-1, f.dcsClass());
        assertEquals(Encoding.GSM7, f.encoding());
        assertEquals(14, f.udl());
        assertEquals(13, f.userData().length);
        assertNull(f.udh());
        assertEquals(-1, f.destPort());
        assertTrue(f.timestampMs() > 0);
        assertEquals(List.of(), f.anomalies());
    }

    @Test
    void statusReportFacts() {
        TpduFacts f = TpduParser.parseRadio(hex(STATUS_FAILED));
        assertTrue(f.valid(), f.parseError());
        assertTrue(f.statusReport());
        assertEquals(1, f.messageReference());
        assertEquals(0x40, f.status());
        assertEquals("", f.originator(), "TP-RA is the recipient, not the originator");
        assertEquals(List.of(), f.anomalies());
    }

    @Test
    void wapPushFacts() {
        TpduFacts f = TpduParser.parseRadio(hex(CP_USERPIN));
        assertTrue(f.valid(), f.parseError());
        assertEquals(TpduParser.WAP_PUSH_PORT, f.destPort());
        WspFacts push = f.wsp();
        assertNotNull(push);
        assertTrue(push.valid(), push.error());
        assertEquals(0x36, push.contentTypeCode());
        assertEquals("application/vnd.wap.connectivity-wbxml", push.contentType());
        assertEquals(1, push.secParam(), "SEC 0x81: USERPIN");
        assertEquals(0x0A, push.applicationIdCode());
        WbxmlFacts body = f.wbxml();
        assertNotNull(body);
        assertEquals(0x0B, body.publicId());
        assertEquals(5, body.rootTag(), "wap-provisioningdoc");
        assertEquals(List.of(), f.anomalies());
    }

    @Test
    void simOtaHeaderFacts() {
        TpduFacts f = TpduParser.parseRadio(hex(OTA_RFM));
        assertTrue(f.usimDataDownload());
        OtaFacts ota = f.ota();
        assertNotNull(ota);
        assertFalse(ota.response());
        assertEquals(0x1A, ota.packetLength());
        assertEquals(0x15, ota.headerLength());
        assertEquals(0x0200, ota.spi());
        assertEquals(0xB00000, ota.tar());
        assertEquals(1L, ota.counter());
        assertTrue(ota.headerComplete());
        assertEquals(List.of(), f.anomalies());
    }

    @Test
    void concatenationFactsAndBody() {
        TpduFacts f = TpduParser.parseRadio(hex(MULTIPART));
        assertEquals(0x2B, f.concatRef());
        assertEquals(2, f.concatTotal());
        assertEquals(1, f.concatSeq());
        assertEquals(6, f.bodyOffset(), "UDHL octet plus 5 header octets");
        assertNull(f.wsp(), "only a single-part push is parsed");
    }

    @Test
    void automaticDeletionGroupIsGeneralCoding() {
        // TS 23.038 01xx is the general coding group marked for automatic deletion.
        TpduFacts f = TpduParser.parseRadio(with(TEXT, 14, 0x40));
        assertEquals(Encoding.GSM7, f.encoding());
        assertEquals(13, f.userData().length);
        assertEquals(List.of(), f.anomalies());
    }

    @Test
    void compressedUserDataLengthCountsOctets() {
        // DCS 0x20: compressed GSM 7-bit, so TP-UDL 14 means 14 octets and only 13 are present.
        TpduFacts f = TpduParser.parseRadio(with(TEXT, 14, 0x20));
        assertTrue(f.compressed());
        assertEquals(List.of(AnomalyCode.UDL_LONG), f.anomalies());
    }

    @Test
    void groupFReservedBitKeepsTheAlphabetBit() {
        TpduFacts f = TpduParser.parseRadio(with(TEXT, 14, 0xF8));
        assertEquals(Encoding.GSM7, f.encoding(), "bit 2 clear: GSM 7-bit");
        assertEquals(0, f.dcsClass());
        assertEquals(List.of(AnomalyCode.DCS_RSV), f.anomalies());
    }

    @Test
    void messageWaitingGroupFacts() {
        TpduFacts f = TpduParser.parseRadio(with(TEXT, 14, 0xC9));
        assertTrue(f.messageWaitingGroup());
        assertTrue(f.mwiActive());
        assertEquals(1, f.mwiType(), "fax");
        assertTrue(f.mwiDontStore());
        assertFalse(f.mwiSet(), "only voicemail sets the MWI fact");
    }

    @Test
    void hardAnomalyKeepsTheFormerValidity() {
        // UDL past the data is malformed, but the header was readable, as PduFacts reported it.
        byte[] cut = hex(TEXT.substring(0, TEXT.length() - 6));
        TpduFacts f = TpduParser.parseRadio(cut);
        assertTrue(f.valid());
        assertTrue(Anomalies.malformed(f.anomalyMask()));
        assertEquals(AnomalyCode.UDL_LONG, Anomalies.forcing(f.anomalyMask()));
    }

    @Test
    void reservedMtiIsInvalid() {
        TpduFacts f = TpduParser.parseRadio(with(TEXT, 6, 0x07));
        assertFalse(f.valid());
        assertEquals(3, f.mti());
        assertEquals(List.of(AnomalyCode.MTI), f.anomalies());
    }

    @Test
    void addressOctetsAreHalfTheSemiOctets() {
        assertEquals(9, TpduParser.addressOctets(18, 0xD0));
        assertEquals(8, TpduParser.addressOctets(15, 0x91));
        assertEquals(0, TpduParser.addressOctets(0, 0x91));
    }

    @Test
    void statusReportReadsTheFirstPiOctetAndSkipsExtensions() {
        TpduFacts f = TpduParser.parseRadio(hex(STATUS_PI_EXT));
        assertTrue(f.valid(), f.parseError());
        assertTrue(f.statusReport());
        assertEquals(0, f.protocolId());
        assertEquals(0, f.dataCodingScheme());
        assertEquals(Encoding.GSM7, f.encoding());
        assertEquals(4, f.udl());
        assertEquals(4, f.userData().length);
        assertEquals(List.of(), f.anomalies());
    }

    @Test
    void statusReportUserDataWithoutDcsIsGsm7() {
        TpduFacts f = TpduParser.parseRadio(hex(STATUS_UD_NO_DCS));
        assertTrue(f.valid(), f.parseError());
        assertEquals(0, f.dataCodingScheme());
        assertEquals(Encoding.GSM7, f.encoding());
        assertEquals(4, f.udl());
        assertEquals(4, f.userData().length, "4 septets in 4 octets");
        assertEquals(List.of(), f.anomalies());
    }

    @Test
    void submitReportMtiIsAnAnomaly() {
        TpduFacts f = TpduParser.parseRadio(with(TEXT, 6, 0x05));
        assertFalse(f.valid());
        assertEquals(1, f.mti());
        assertEquals(List.of(AnomalyCode.MTI), f.anomalies());
    }

    @Test
    void bcdAddressHasTheExtendedDigits() {
        // TS 23.040 1010 to 1110 are '*', '#', 'a', 'b' and 'c'; 0xF is the filler.
        byte[] digits = {(byte) 0xBA, (byte) 0xDC, (byte) 0xFE};
        assertEquals("*#abc", TpduParser.decodeAddress(digits, 0, 5, 0x81));
        assertEquals("+*#abc", TpduParser.decodeAddress(digits, 0, 5, 0x91));
    }

    @Test
    void alphanumericAddressReadsTheExtensionTable() {
        // Septets ESC 0x65 (euro sign), then ESC ESC (a space, as the framework shows it).
        assertEquals("\u20AC", TpduParser.decodeAddress(new byte[] {(byte) 0x9B, 0x32}, 0, 4, 0xD0));
        assertEquals(" ", TpduParser.decodeAddress(new byte[] {(byte) 0x9B, 0x0D}, 0, 4, 0xD0));
        // Type of number 101 is alphanumeric also when bit 7 of the TOA is clear.
        assertEquals("\u20AC", TpduParser.decodeAddress(new byte[] {(byte) 0x9B, 0x32}, 0, 4, 0x50));
    }

    @Test
    void addressDecodingIsTotal() {
        byte[] b = {0x21, 0x43};
        assertEquals("", TpduParser.decodeAddress(b, 2, 4, 0x81));
        assertEquals("", TpduParser.decodeAddress(b, -1, 4, 0x81));
        assertEquals("", TpduParser.decodeAddress(null, 0, 4, 0x81));
        assertEquals("1234", TpduParser.decodeAddress(b, 0, Integer.MAX_VALUE, 0x81));
        assertEquals(0L, TpduParser.decodeScts(b, Integer.MAX_VALUE));
        assertEquals(0L, TpduParser.decodeScts(b, -1));
    }

    @Test
    void smscWithAnOddDigitCountAndWithoutDigits() {
        String tpdu = TEXT.substring(12);
        TpduFacts odd = TpduParser.parseRadio(hex("04915155F0" + tpdu));
        assertTrue(odd.valid(), odd.parseError());
        assertEquals("+15550", odd.smsc(), "the 0xF filler is not a digit");
        TpduFacts toaOnly = TpduParser.parseRadio(hex("0191" + tpdu));
        assertTrue(toaOnly.valid(), toaOnly.parseError());
        assertNull(toaOnly.smsc());
        assertEquals("+15550142", toaOnly.originator());
    }

    @Test
    void retryNeverHidesAMalformedTpdu() {
        TpduFacts f = TpduParser.parseRadio(hex(RETRY_MALFORMED));
        assertFalse(f.valid());
        assertEquals(3, f.mti());
        assertEquals(List.of(AnomalyCode.MTI), f.anomalies());
    }

    @Test
    void ucs2Facts() {
        TpduFacts f = TpduParser.parseRadio(hex(UCS2));
        assertEquals(Encoding.UCS2, f.encoding());
        assertEquals(8, f.udl());
        assertEquals(8, f.userData().length);
        assertEquals(List.of(), f.anomalies());
    }

    @Test
    void messageWaitingReservedBitAndVoicemail() {
        TpduFacts reserved = TpduParser.parseRadio(with(TEXT, 14, 0xC4));
        assertTrue(reserved.messageWaitingGroup());
        assertTrue(reserved.mwiClear(), "voicemail, indication not active");
        assertEquals(List.of(AnomalyCode.DCS_RSV), reserved.anomalies());
        TpduFacts set = TpduParser.parseRadio(with(TEXT, 14, 0xC8));
        assertTrue(set.mwiSet());
        assertFalse(set.mwiClear());
        assertEquals(List.of(), set.anomalies());
        TpduFacts ucs2 = TpduParser.parseRadio(with(TEXT, 14, 0xE4));
        assertEquals(Encoding.UCS2, ucs2.encoding());
        assertTrue(ucs2.anomalies().contains(AnomalyCode.DCS_RSV));
    }

    @Test
    void userDataLongerThan140OctetsIsUdlLong() {
        TpduFacts f = TpduParser.parseRadio(hex(UDL_141));
        assertTrue(f.valid(), f.parseError());
        assertEquals(141, f.udl());
        assertEquals(141, f.userData().length, "the data is still read");
        assertEquals(List.of(AnomalyCode.UDL_LONG), f.anomalies());
    }

    @Test
    void sevenBitPort2948GoesThroughThePushChecks() {
        TpduFacts bad = TpduParser.parseRadio(hex(PUSH_7BIT_BAD_TYPE));
        assertEquals(Encoding.GSM7, bad.encoding());
        assertEquals(TpduParser.WAP_PUSH_PORT, bad.destPort());
        assertNotNull(bad.wsp());
        assertEquals(List.of(AnomalyCode.WSP_TYPE), bad.anomalies());
        TpduFacts mms = TpduParser.parseRadio(hex(PUSH_7BIT_MMS));
        assertNotNull(mms.wsp());
        assertTrue(mms.wsp().valid(), mms.wsp().error());
        assertEquals(0x3E, mms.wsp().contentTypeCode());
        assertEquals(List.of(), mms.anomalies());
    }

    @Test
    void ansi136DataDownloadIsUsimDataDownload() {
        // TS 23.040 PID 0x7C (ANSI-136 R-DATA) in a class 2 message also goes to the SIM.
        TpduFacts f = TpduParser.parseRadio(with(OTA_RFM, 13, 0x7C));
        assertTrue(f.usimDataDownload());
        assertNotNull(f.ota());
        assertTrue(SimOtaSubtypes.isSimOta(f));
        assertEquals(List.of(), f.anomalies());
        byte[] classZero = with(OTA_RFM, 13, 0x7C);
        classZero[14] = (byte) 0xF4;
        assertFalse(TpduParser.parseRadio(classZero).usimDataDownload());
    }

    @Test
    void cipheredCounterIsNotReported() {
        OtaFacts ota = TpduParser.parseRadio(hex(OTA_CIPHERED)).ota();
        assertNotNull(ota);
        assertTrue(ota.ciphered());
        assertEquals(-1L, ota.counter(), "TS 102 225 §5.1: the counter is inside the ciphered part");
        assertEquals(-1, ota.paddingCounter());
        assertEquals(0xB00010, ota.tar(), "the TAR is never ciphered");
    }

    @Test
    void onlyTheFirstSegmentOfAConcatenatedPacketHasAHeader() {
        TpduFacts first = TpduParser.parseRadio(hex(OTA_FIRST_SEGMENT));
        assertEquals(1, first.concatSeq());
        assertNotNull(first.ota());
        assertEquals(0x40, first.ota().packetLength(), "CPL counts the whole packet, not this segment");
        assertEquals(0xB00000, first.ota().tar());
        assertEquals(List.of(), first.anomalies());
        TpduFacts later = TpduParser.parseRadio(hex(OTA_LATER_SEGMENT));
        assertEquals(2, later.concatSeq());
        assertNull(later.ota(), "a later segment carries secured data only");
        assertEquals(List.of(), later.anomalies());
    }
}
