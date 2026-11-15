package dev.nightjar.payload.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.payload.testing.Pdu;
import org.junit.jupiter.api.Test;

/** The TPDU parser (facts). */
class PduFactsTest {

    @Test
    void parsesPlainDeliver() {
        byte[] pdu = Pdu.deliver().ud("hello".getBytes()).build();
        PduFacts f = PduFacts.parseRadio(pdu);
        assertTrue(f.valid(), f.parseError());
        assertEquals("+919870000210", f.originator());
        assertEquals("+919000000000", f.smsc());
        assertEquals(0, f.protocolId());
        assertEquals(-1, f.dcsClass());
        assertFalse(f.statusReport());
        assertFalse(f.udhi());
        assertEquals(-1, f.destPort());
        assertEquals(-1, f.concatRef());
        assertTrue(f.timestampMs() > 0);
    }

    @Test
    void builtPdusHaveNoAnomalies() {
        // The test builder writes a TP-UDL that matches the user data, so no test depends on a length anomaly.
        int[] dcsValues = {0x00, 0x04, 0x08, 0x10, 0x11, 0x12, 0x13, 0x14, 0x18, 0xC0, 0xC8, 0xD0, 0xD8, 0xE0, 0xF0,
            0xF1, 0xF4, 0xF6};
        byte[][] bodies = {null, new byte[0], "hello world, 23 chars!!".getBytes()};
        for (int dcs : dcsValues) {
            for (boolean udh : new boolean[] {false, true}) {
                for (byte[] ud : bodies) {
                    Pdu.Builder b = Pdu.deliver().dcs(dcs).ud(ud);
                    if (udh) b.udh(1, 2, 1, 16000);
                    PduFacts f = PduFacts.parseRadio(b.build());
                    String what = "dcs=0x" + Integer.toHexString(dcs) + " udh=" + udh + " ud="
                            + (ud == null ? "default" : ud.length);
                    assertTrue(f.valid(), what);
                    assertTrue(f.anomalies().isEmpty(), what + " " + f.anomalies());
                }
            }
        }
    }

    @Test
    void detectsTypeZero() {
        PduFacts f = PduFacts.parseRadio(Pdu.deliver().pid(0x40).build());
        assertTrue(f.typeZero());
    }

    @Test
    void detectsUsimDataDownload() {
        PduFacts f = PduFacts.parseRadio(Pdu.deliver().pid(0x7F).dcs(0xF2).build());
        assertTrue(f.usimDataDownload(), "PID 0x7F with class 2");
        assertEquals(2, f.dcsClass());
    }

    @Test
    void detectsMwiSetAndDontStore() {
        PduFacts set = PduFacts.parseRadio(Pdu.deliver().dcs(0xD8).build());
        assertTrue(set.mwiSet(), "DCS 0xD8: voicemail, indication active");
        assertFalse(set.mwiClear());
        assertFalse(set.mwiDontStore());
        PduFacts clear = PduFacts.parseRadio(Pdu.deliver().dcs(0xD0).build());
        assertTrue(clear.mwiClear(), "DCS 0xD0: voicemail, indication inactive");
        assertFalse(clear.mwiSet());
        PduFacts dontStore = PduFacts.parseRadio(Pdu.deliver().dcs(0xC8).build());
        assertTrue(dontStore.mwiDontStore());
        assertTrue(dontStore.mwiSet());
        // Fax and email carry a DCS MWI group but are not MWI messages (bits 1..0 != 0).
        PduFacts fax = PduFacts.parseRadio(Pdu.deliver().dcs(0xD1).build());
        assertFalse(fax.mwiSet());
        assertFalse(fax.mwiClear());
    }

    @Test
    void detectsReplace() {
        assertTrue(PduFacts.parseRadio(Pdu.deliver().pid(0x45).build()).replace());
        assertFalse(PduFacts.parseRadio(Pdu.deliver().pid(0x48).build()).replace());
    }

    @Test
    void readsConcatAndDistinctPorts() {
        PduFacts f = PduFacts.parseRadio(Pdu.deliver().udh(7, 3, 2, 2948, 9200).build());
        assertTrue(f.udhi());
        assertEquals(7, f.concatRef());
        assertEquals(3, f.concatTotal());
        assertEquals(2, f.concatSeq());
        assertEquals(2948, f.destPort(), "IE 5: the first pair is the destination port");
    }

    @Test
    void readsEightBitPorts() {
        PduFacts f = PduFacts.parseRadio(Pdu.deliver().udh8(1, 1, 1, 0x0B, 0x84).build());
        assertEquals(0x0B, f.destPort(), "IE 4: dest then orig, one octet each");
    }

    @Test
    void rejectsOutOfRangeConcatIes() {
        // seq 0, total 0 and seq > total are ignored, as SmsHeader.fromByteArray does.
        assertEquals(-1, PduFacts.parseRadio(Pdu.deliver().udh(5, 3, 0, -1).build()).concatRef());
        assertEquals(-1, PduFacts.parseRadio(Pdu.deliver().udh(5, 0, 1, -1).build()).concatRef());
        assertEquals(-1, PduFacts.parseRadio(Pdu.deliver().udh(5, 2, 3, -1).build()).concatRef());
    }

    @Test
    void parsesStatusReportWithAndWithoutUserData() {
        PduFacts full = PduFacts.parseRadio(Pdu.deliver().statusReport().build());
        assertTrue(full.valid(), full.parseError());
        assertTrue(full.statusReport());
        assertEquals("", full.originator(), "TP-RA is the recipient, not the originator");

        PduFacts bare = PduFacts.parseRadio(Pdu.deliver().statusReportNoPdu().build());
        assertTrue(bare.valid(), bare.parseError());
        assertTrue(bare.statusReport());
        assertEquals(0, bare.userData().length);
    }

    @Test
    void statusReportWithoutOptionalParametersStillParses() {
        // [smsc][0x02][mr][ra=0][scts][dt][st]: no TP-PI and no TP-UD.
        byte[] pdu = {(byte) 0x07, (byte) 0x91, 0x00, 0x00, 0x00, 0x00, 0x00, (byte) 0xF0,
            0x02, 0x01, 0x00, (byte) 0x91,
            0x62, 0x11, 0x50, 0x00, 0x00, 0x00, 0x00,
            0x62, 0x11, 0x50, 0x00, 0x00, 0x00, 0x00,
            0x00};
        PduFacts f = PduFacts.parseRadio(pdu);
        assertTrue(f.valid(), f.parseError());
        assertTrue(f.statusReport());
    }

    @Test
    void sctsHonoursTheOffsetSign() {
        // 2026-11-05 12:00:00 at UTC-5: 20 quarter-hours as swapped BCD 0x02, sign bit 3 set.
        byte[] t = {0x62, 0x11, 0x50, 0x21, 0x00, 0x00, 0x0A};
        long ms = PduFacts.decodeScts(t, 0);
        assertEquals(java.time.Instant.parse("2026-11-05T17:00:00Z").toEpochMilli(), ms,
                "12:00 at UTC-5 is 17:00 UTC");
        byte[] plus = {0x62, 0x11, 0x50, 0x21, 0x00, 0x00, 0x02};
        assertEquals(java.time.Instant.parse("2026-11-05T07:00:00Z").toEpochMilli(),
                PduFacts.decodeScts(plus, 0), "12:00 at UTC+5 is 07:00 UTC");
    }

    @Test
    void gsm7NulIsAnAtSignNotAStringEnd() {
        // "A@B" as packed septets 0x41, 0x00, 0x42; the length octet counts semi-octets, so three
        // characters are 6.
        byte[] t = {0x41, (byte) 0x80, 0x10};
        assertEquals("A@B", PduFacts.decodeAddress(t, 0, 6, 0xD0));
    }

    @Test
    void parsesBareTpduForIms() {
        byte[] radio = Pdu.deliver().build();
        PduFacts f = PduFacts.parse(Pdu.bare(radio));
        assertTrue(f.valid(), f.parseError());
        assertEquals("+919870000210", f.originator());
        assertEquals(null, f.smsc());
    }

    @Test
    void neverThrowsOnGarbage() {
        assertFalse(PduFacts.parse(new byte[0]).valid());
        assertFalse(PduFacts.parse(new byte[] {1}).valid());
        assertFalse(PduFacts.parseRadio(new byte[] {(byte) 0xFF, (byte) 0xFF, 1, 2, 3}).valid());
        assertNotNull(PduFacts.parse(null).parseError());
    }

    @Test
    void alphanumericOriginatorIsDecodedExactly() {
        // Appendix F canaries use an alphanumeric originator, so the 7-bit packed TP-OA must round
        // trip byte for byte and must not leak the padding septets.
        for (String oa : new String[] {"NJSELFTEST", "NJSELFTEST2", "VERIZON", "MyBank", "AB"}) {
            PduFacts f = PduFacts.parseRadio(Pdu.deliver().originator(oa).build());
            assertTrue(f.valid(), f.parseError());
            assertEquals(oa, f.originator(), "originator " + oa);
        }
    }

    @Test
    void alphanumericAddressLengthUsesSemiOctets() {
        byte[] pdu = Pdu.deliver().originator("NJSELFTEST").build();
        // [smsc (1+len bytes)][fo][len=18][toa=0xD0][9 octets]…
        int oa = 1 + (pdu[0] & 0xFF) + 1;
        assertEquals(18, pdu[oa] & 0xFF, "10 characters occupy 18 semi-octets");
        assertEquals(0xD0, pdu[oa + 1] & 0xFF);
        assertEquals(9, PduFacts.addressOctets(18, 0xD0), "ceil(10*7/8) octets");
        assertEquals(8, PduFacts.addressOctets(15, 0x91), "ceil(15/2) octets for a numeric address");
    }

    @Test
    void tpduKeyIsStableAcrossSmscForms() {
        byte[] radio = Pdu.deliver().build();
        assertEquals(PduFacts.parseRadio(radio).tpduKey(), PduFacts.parse(Pdu.bare(radio)).tpduKey());
    }

    @Test
    void adapterPassesTheSharedParserFactsThrough() {
        // PduFacts is an adapter over:pduparse; the anomaly list is the shared parser's list.
        PduFacts f = PduFacts.parseRadio(Pdu.deliver().pid(0x7F).dcs(0xF6).build());
        assertNotNull(f.facts());
        assertEquals(f.facts().anomalies(), f.anomalies());
        assertEquals(f.facts().protocolId(), f.protocolId());
        assertEquals(f.facts().originator(), f.originator());
        assertNotNull(PduFacts.parseRadio(null).anomalies());
    }
}
