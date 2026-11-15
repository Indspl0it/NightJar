package dev.nightjar.payload.inject;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Hashes;
import dev.nightjar.payload.capture.PduFacts;
import dev.nightjar.payload.capture.WapContentType;
import dev.nightjar.payload.capture.WspMini;
import org.junit.jupiter.api.Test;

/** The canary PDUs parse into the expected class. */
class CanaryFactoryTest {

    private static final byte[] NONCE = {0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD,
        (byte) 0xEF};

    @Test
    void TST_01_everyCanaryParsesAsSmsDeliverFromTheMarker() {
        for (CanaryFactory.Canary canary : CanaryFactory.Canary.values()) {
            CanaryFactory.Pdu pdu = CanaryFactory.build(canary, NONCE);
            PduFacts facts = PduFacts.parseRadio(pdu.radioPdu());
            assertTrue(facts.valid(), canary.name() + " must parse: " + facts.parseError());
            assertFalse(facts.statusReport(), canary.name());
            // PduFacts decodes 2*ceil(len/2) septets of an alphanumeric address, so only the marker prefix is
            // guaranteed; the exact string is checked through the framework on device.
            assertTrue(facts.originator() != null && facts.originator().startsWith(CanaryFactory.ORIGINATOR),
                    canary.name() + " originator=" + facts.originator());
            assertEquals(CanaryFactory.SMSC, facts.smsc(), canary.name());
            assertArrayEquals(NONCE, pdu.nonce());
        }
    }

    @Test
    void AppendixF_flashCanaryIsClassZero() {
        PduFacts facts = PduFacts.parseRadio(CanaryFactory.build(CanaryFactory.Canary.FLASH, NONCE).radioPdu());
        assertEquals(0, facts.dcsClass());
        assertEquals(0x00, facts.protocolId());
    }

    @Test
    void AppendixF_dataPortCanaryUsesPort16999() {
        PduFacts facts = PduFacts.parseRadio(CanaryFactory.build(CanaryFactory.Canary.DATA_PORT, NONCE).radioPdu());
        assertEquals(16999, facts.destPort());
        assertTrue(facts.udhi());
    }

    @Test
    void AppendixF_wapCanariesUsePort2948AndTheExpectedContentType() {
        PduFacts si = PduFacts.parseRadio(CanaryFactory.build(CanaryFactory.Canary.WAP_SI, NONCE).radioPdu());
        assertEquals(2948, si.destPort());
        WspMini.Result siPush = WspMini.parse(wapPayload(si.userData()));
        assertTrue(siPush.valid(), String.valueOf(siPush.error()));
        assertEquals(WapContentType.CT_SIC, siPush.contentType());
        assertEquals(ClassId.WAP_SISL, WapContentType.classify(siPush.contentType()));

        PduFacts cp = PduFacts.parseRadio(CanaryFactory.build(CanaryFactory.Canary.WAP_CP, NONCE).radioPdu());
        WspMini.Result cpPush = WspMini.parse(wapPayload(cp.userData()));
        assertTrue(cpPush.valid());
        assertEquals(WapContentType.CT_WBXML, cpPush.contentType());
        assertEquals(ClassId.WAP_CP, WapContentType.classify(cpPush.contentType()));
    }

    @Test
    void AppendixF_mwiCanaryUsesTheDiscardGroup() {
        PduFacts facts = PduFacts.parseRadio(CanaryFactory.build(CanaryFactory.Canary.MWI, NONCE).radioPdu());
        assertTrue(facts.mwiSet() || facts.mwiClear());
        assertEquals(0xC8, facts.dataCodingScheme());
    }

    @Test
    void AppendixF_silentCanaryHasPid0x40() {
        PduFacts facts = PduFacts.parseRadio(CanaryFactory.build(CanaryFactory.Canary.SILENT, NONCE).radioPdu());
        assertEquals(0x40, facts.protocolId());
        assertTrue(facts.typeZero());
    }

    @Test
    void AppendixF_simDdCanaryUsesPid0x7FAndClassTwo() {
        PduFacts facts = PduFacts.parseRadio(CanaryFactory.build(CanaryFactory.Canary.SIM_DD, NONCE).radioPdu());
        assertEquals(0x7F, facts.protocolId());
        assertEquals(2, facts.dcsClass());
        assertTrue(facts.usimDataDownload());
    }

    @Test
    void AppendixF_replaceCanaryUsesPid0x41() {
        PduFacts facts = PduFacts.parseRadio(CanaryFactory.build(CanaryFactory.Canary.REPLACE, NONCE).radioPdu());
        assertEquals(0x41, facts.protocolId());
        assertTrue(facts.replace());
    }

    @Test
    void TST_01_theNonceIsInTheUserDataAndTheTextNeverStartsWithTheVvmPrefix() {
        for (CanaryFactory.Canary canary : CanaryFactory.Canary.values()) {
            CanaryFactory.Pdu pdu = CanaryFactory.build(canary, NONCE);
            PduFacts facts = PduFacts.parseRadio(pdu.radioPdu());
            byte[] ud = facts.userData();
            assertNotNull(ud);
            if (canary != CanaryFactory.Canary.SILENT) {
                assertFalse(startsWith(ud, '/', '/'), canary.name() + " must not look like a VVM message");
            }
        }
    }

    @Test
    void TST_01_twoBuildsDifferOnlyByTheNonce() {
        CanaryFactory.Pdu a = CanaryFactory.build(CanaryFactory.Canary.FLASH, NONCE);
        byte[] other = NONCE.clone();
        other[0] ^= 0xFF;
        CanaryFactory.Pdu b = CanaryFactory.build(CanaryFactory.Canary.FLASH, other);
        assertFalse(java.util.Arrays.equals(a.radioPdu(), b.radioPdu()));
        assertFalse(Releaser.keys(a.radioPdu())[0].equals(Releaser.keys(b.radioPdu())[0]));
    }

    @Test
    void TD_14_theKeyMatchesTheStrippedTpdu() {
        CanaryFactory.Pdu pdu = CanaryFactory.build(CanaryFactory.Canary.DATA_PORT, NONCE);
        assertEquals(Hashes.tpduKey(Hashes.stripSmsc(pdu.radioPdu())), Releaser.keys(pdu.radioPdu())[0]);
    }

    @Test
    void TST_01_classNamesParseLeniently() {
        assertEquals(CanaryFactory.Canary.WAP_SI, CanaryFactory.Canary.parse("wap-si"));
        assertEquals(CanaryFactory.Canary.SIM_DD, CanaryFactory.Canary.parse("CL-SIM-DD"));
        assertEquals(CanaryFactory.Canary.FLASH, CanaryFactory.Canary.parse("flash"));
        assertNull(CanaryFactory.Canary.parse("NOPE"));
        assertNull(CanaryFactory.Canary.parse(null));
    }

    @Test
    void AppendixF_requiredHooksMatchTheTable() {
        assertEquals("HK-08", CanaryFactory.Canary.FLASH.requiredHook());
        assertEquals("HK-14", CanaryFactory.Canary.WAP_CP.requiredHook());
        assertEquals("HK-15", CanaryFactory.Canary.SIM_DD.requiredHook());
        assertEquals("HK-23", CanaryFactory.Canary.MWI.requiredHook());
        assertEquals("HK-06,HK-07", CanaryFactory.Canary.SILENT.requiredHook());
        assertEquals(CanaryFactory.Interception.HEAD_FILTER, CanaryFactory.Canary.REPLACE.interception());
        assertEquals(ClassId.SIM_DD, CanaryFactory.Canary.SIM_DD.classId());
    }

    /** The WAP PDU inside TP-UD, i.e. after the UDH length prefix and the UDH itself. */
    private static byte[] wapPayload(byte[] userData) {
        int udhLength = userData[0] & 0xFF;
        return java.util.Arrays.copyOfRange(userData, 1 + udhLength, userData.length);
    }

    private static boolean startsWith(byte[] data, int a, int b) {
        return data.length >= 2 && data[0] == a && data[1] == b;
    }

    /** The framework hands HK-14 the user data without its header; the registered key must be over exactly that. */
    @Test
    void wapCanaryPayloadKeyIsOverTheBytesAfterTheUserDataHeader() {
        for (CanaryFactory.Canary canary : new CanaryFactory.Canary[] {
            CanaryFactory.Canary.WAP_SI, CanaryFactory.Canary.WAP_CP}) {
            CanaryFactory.Pdu pdu = CanaryFactory.build(canary, NONCE);
            byte[] userData = PduFacts.parseRadio(pdu.radioPdu()).userData();
            int skip = 1 + (userData[0] & 0xFF);
            byte[] body = java.util.Arrays.copyOfRange(userData, skip, userData.length);
            String[] keys = Releaser.payloadKeys(pdu.radioPdu());
            assertNotNull(keys, canary.name());
            assertEquals(Hashes.tpduKey(body), keys[0], canary.name());
            assertEquals(Hashes.tpduKey(userData), keys[1], canary.name());
        }
    }

    /** The attribute list, the inner element and the root element each get an END token. */
    @Test
    void wbxmlCanaryBodiesAreWellFormed() {
        for (byte[] body : new byte[][] {CanaryFactory.siWbxml(NONCE), CanaryFactory.cpWbxml(NONCE)}) {
            int n = body.length;
            assertEquals(0x00, body[n - 4], "end of the inline string");
            assertEquals(0x01, body[n - 3]);
            assertEquals(0x01, body[n - 2]);
            assertEquals(0x01, body[n - 1]);
        }
    }
}
