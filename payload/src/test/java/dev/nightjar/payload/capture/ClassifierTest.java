package dev.nightjar.payload.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.AnomalyCode;
import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Direction;
import dev.nightjar.contract.Fate;
import dev.nightjar.contract.Subtype;
import dev.nightjar.payload.inject.CanaryFactory;
import dev.nightjar.payload.testing.Pdu;
import dev.nightjar.pduparse.Anomalies;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Provisional classification, final classification, WAP parsing and sealing. */
class ClassifierTest {

    // ------------------------------------------------------------------ provisional

    @Test
    void tableDrivenProvisionalClasses() {
        assertEquals(ClassId.SILENT,
                ProvisionalClassifier.classify(PduFacts.parseRadio(Pdu.deliver().pid(0x40).build()),
                        false, false, null));
        assertEquals(ClassId.SIM_DD,
                ProvisionalClassifier.classify(PduFacts.parseRadio(Pdu.deliver().pid(0x7F).dcs(0xF2).build()),
                        false, false, null));
        assertEquals(ClassId.MWI,
                ProvisionalClassifier.classify(PduFacts.parseRadio(Pdu.deliver().dcs(0xD0).build()),
                        false, false, null));
        assertEquals(ClassId.REPLACE,
                ProvisionalClassifier.classify(PduFacts.parseRadio(Pdu.deliver().pid(0x41).build()),
                        false, false, null));
        assertEquals(ClassId.STATUS,
                ProvisionalClassifier.classify(PduFacts.parseRadio(Pdu.deliver().statusReport().build()),
                        false, false, null));
        assertEquals(ClassId.DATA_PORT,
                ProvisionalClassifier.classify(PduFacts.parseRadio(Pdu.deliver().udh(1, 1, 1, 16000).build()),
                        false, false, null));
        assertEquals(ClassId.FLASH,
                ProvisionalClassifier.classify(PduFacts.parseRadio(Pdu.deliver().dcs(0x10).build()),
                        false, false, null));
        assertEquals(ClassId.NORMAL,
                ProvisionalClassifier.classify(PduFacts.parseRadio(Pdu.deliver().build()), false, false,
                        null));
    }

    @Test
    void precedencePutsInjectionFirstAndThePidBeforeWap() {
        byte[] pdu = Pdu.deliver().pid(0x40).build();
        assertEquals(ClassId.INJECTED,
                ProvisionalClassifier.classify(PduFacts.parseRadio(pdu), false, true, null));
        // Rule 3 (PID) comes before rule 7 (WAP push): the WAP class is an extra subtype.
        ProvisionalClassifier.Result r = result(pdu, false, false, WapContentType.CT_WBXML);
        assertEquals(ClassId.SILENT, r.classId());
        assertEquals(Subtype.Silent.TYPE0, r.subtype());
        assertEquals(List.of(ClassId.WAP_CP.wire()), r.extras(), "no SEC parameter without the push body");
    }

    @Test
    void aParseFailureIsMalformedAndNeverNormal() {
        byte[] good = Pdu.deliver().build();
        byte[] cut = Arrays.copyOf(good, good.length - 1); // TP-UDL now asks for one octet more than there is
        PduFacts facts = PduFacts.parseRadio(cut);
        int mask = facts.facts().anomalyMask();
        assertTrue(Anomalies.has(mask, AnomalyCode.UDL_LONG));
        ProvisionalClassifier.Result r = ProvisionalClassifier.result(facts, false, false, null);
        assertEquals(ClassId.MALFORMED_SMS, r.classId());
        assertEquals(Anomalies.forcing(mask).forcedSubtype(), r.subtype());
        for (byte[] junk : new byte[][] {{1}, {0x00, 0x04}}) {
            ClassId c = ProvisionalClassifier.classify(PduFacts.parseRadio(junk), false, false, null);
            assertEquals(ClassId.MALFORMED_SMS, c, Arrays.toString(junk));
            c = ProvisionalClassifier.classify(PduFacts.parse(junk), false, false, null);
            assertEquals(ClassId.MALFORMED_SMS, c, Arrays.toString(junk));
        }
        // TP-MTI 3 with no known damage is CL-UNREADABLE-SMS, also never CL-NORMAL.
        byte[] wrongType = {(byte) 0xFF, (byte) 0xFF, 1, 2, 3};
        assertEquals(ClassId.UNREADABLE_SMS, ProvisionalClassifier.classify(PduFacts.parseRadio(wrongType), false,
                false, null));
        assertEquals(ClassId.UNREADABLE_SMS, ProvisionalClassifier.classify(PduFacts.parse(wrongType), false, false,
                null));
        // The source rule still runs: an injected malformed PDU keeps CL-INJECTED as an extra.
        ProvisionalClassifier.Result injected = ProvisionalClassifier.result(facts, false, true, null);
        assertEquals(ClassId.MALFORMED_SMS, injected.classId());
        assertEquals(List.of(ClassId.INJECTED.wire()), injected.extras());
    }

    @Test
    void UX2_FW_02_theWrongMessageTypeIsUnreadableAndKnownDamageWins() {
        // Synthetic scenarios.json anomaly-mti: TP-MTI 3.
        byte[] mti3 = hex("059151551000070891515510240000621150214365220ED4F29C0E6A97E7F3F0B90C8A01");
        ProvisionalClassifier.Result r = ProvisionalClassifier.result(PduFacts.parseRadio(mti3), false, false, null);
        assertEquals(ClassId.UNREADABLE_SMS, r.classId());
        assertEquals(AnomalyCode.MTI.wire(), r.subtype());
        assertEquals(ProvisionalClassifier.RULE_ANOMALY, r.rule());
        // An injected PDU keeps CL-INJECTED as an extra, as for CL-MALFORMED-SMS.
        ProvisionalClassifier.Result injected =
                ProvisionalClassifier.result(PduFacts.parseRadio(mti3), false, true, null);
        assertEquals(ClassId.UNREADABLE_SMS, injected.classId());
        assertEquals(List.of(ClassId.INJECTED.wire()), injected.extras());

        // Precedence: known damage is the stricter class, also next to AN-MTI and after it in contract order.
        assertEquals(ClassId.MALFORMED_SMS, ProvisionalClassifier.parseFailureClass(false,
                Anomalies.of(AnomalyCode.MTI, AnomalyCode.ADDR_TRUNC)));
        assertEquals(ClassId.MALFORMED_SMS, ProvisionalClassifier.parseFailureClass(false,
                Anomalies.of(AnomalyCode.MTI, AnomalyCode.UDL_LONG)));
        assertEquals(ClassId.UNREADABLE_SMS, ProvisionalClassifier.parseFailureClass(false,
                Anomalies.of(AnomalyCode.MTI)));
        // A parser error has no anomaly code (TpduParser.error): CL-UNREADABLE-SMS, which a release can re-inject.
        assertEquals(ClassId.UNREADABLE_SMS, ProvisionalClassifier.parseFailureClass(false, Anomalies.NONE));
        assertEquals(ClassId.UNREADABLE_SMS, ProvisionalClassifier.parseFailureClass(false,
                Anomalies.of(AnomalyCode.ADDR_TOA)));
        assertEquals(ClassId.MALFORMED_SMS, ProvisionalClassifier.parseFailureClass(true,
                Anomalies.of(AnomalyCode.UDL_LONG)));
        assertNull(ProvisionalClassifier.parseFailureClass(true, Anomalies.NONE));
        assertNull(ProvisionalClassifier.parseFailureClass(true, Anomalies.of(AnomalyCode.PID_RSV)));
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @Test
    void laterMatchesBecomeExtraSubtypes() {
        // Type 0 with class 0 and a data port: PID, then DCS, then port.
        byte[] pdu = Pdu.deliver().pid(0x40).dcs(0x14)
                .udhBytes(new byte[] {6, 0x05, 4, 0x3E, (byte) 0x81, 0, 0})
                .ud(new byte[] {1, 2})
                .build();
        ProvisionalClassifier.Result r = result(pdu, false, false, null);
        assertEquals(ClassId.SILENT, r.classId());
        assertEquals(Subtype.Silent.TYPE0_PORT, r.subtype());
        assertEquals(List.of(ClassId.FLASH.wire() + ":" + Subtype.Flash.CLASS0,
                ClassId.DATA_PORT.wire() + ":" + Subtype.DataPort.UNKNOWN), r.extras());
        // Read from the SIM: CL-SIM-STORED joins the extras after the flash class.
        ProvisionalClassifier.Result sim = result(pdu, true, false, null);
        assertEquals(List.of(ClassId.FLASH.wire() + ":" + Subtype.Flash.CLASS0,
                ClassId.SIM_STORED.wire() + ":" + Subtype.SimStored.CLASS2,
                ClassId.DATA_PORT.wire() + ":" + Subtype.DataPort.UNKNOWN), sim.extras());
    }

    @Test
    void extraCodeFormat() {
        assertEquals(Subtype.SimOta.NOSEC,
                ProvisionalClassifier.extraCode(ClassId.SIM_DD, ClassId.SIM_DD, Subtype.SimOta.NOSEC));
        assertEquals(ClassId.FLASH.wire() + ":" + Subtype.Flash.CLASS0,
                ProvisionalClassifier.extraCode(ClassId.ME_DOWNLOAD, ClassId.FLASH, Subtype.Flash.CLASS0));
        assertEquals(ClassId.BINARY.wire(),
                ProvisionalClassifier.extraCode(ClassId.MALFORMED_SMS, ClassId.BINARY, null));
        assertNull(ProvisionalClassifier.extraCode(ClassId.SIM_DD, ClassId.SIM_DD, null));
        assertNull(ProvisionalClassifier.extraCode(ClassId.SIM_DD, null, "X"));
    }

    @Test
    void subtypesOfThePidAndDcsRules() {
        assertResult(ClassId.REPLACE, Subtype.Replace.TYPE7, Pdu.deliver().pid(0x47));
        assertResult(ClassId.RETURN_CALL, null, Pdu.deliver().pid(0x5F));
        assertResult(ClassId.ME_DOWNLOAD, null, Pdu.deliver().pid(0x7D));
        assertResult(ClassId.ME_DEPERSO, null, Pdu.deliver().pid(0x7E));
        assertResult(ClassId.SIM_DD, Subtype.SimOta.OTHER, Pdu.deliver().pid(0x7F).dcs(0xF6));
        assertResult(ClassId.TELEMATIC, Subtype.Telematic.FAX, Pdu.deliver().pid(0x22));
        assertResult(ClassId.TELEMATIC, Subtype.Telematic.EMAIL, Pdu.deliver().pid(0x32));
        assertResult(ClassId.TELEMATIC, Subtype.Telematic.OTHER, Pdu.deliver().pid(0x3F));
        assertResult(ClassId.FLASH, Subtype.Flash.CLASS0, Pdu.deliver().dcs(0x10));
        assertResult(ClassId.FLASH, Subtype.Flash.CLASS0_EMPTY, Pdu.deliver().dcs(0x10).ud(new byte[0]));
        assertResult(ClassId.SIM_STORED, Subtype.SimStored.CLASS2, Pdu.deliver().dcs(0x12));
        assertResult(ClassId.CLASS3, null, Pdu.deliver().dcs(0x13));
        assertResult(ClassId.COMPRESSED, null, Pdu.deliver().dcs(0x20).ud(new byte[] {1, 2, 3}));
        assertResult(ClassId.MWI, Subtype.Mwi.VOICEMAIL, Pdu.deliver().dcs(0xD0));
        assertResult(ClassId.MWI, Subtype.Mwi.FAX, Pdu.deliver().dcs(0xD9));
        assertResult(ClassId.MWI, Subtype.Mwi.EMAIL, Pdu.deliver().dcs(0xE2).ud(new byte[] {0, 0x41}));
        assertResult(ClassId.MWI, Subtype.Mwi.OTHER, Pdu.deliver().dcs(0xDB));
        assertResult(ClassId.EMPTY, null, Pdu.deliver().ud(new byte[0]));
        assertResult(ClassId.BINARY, null, Pdu.deliver().dcs(0x04).ud(new byte[] {1, 2, 3}));
    }

    @Test
    void discardSilentNeedsAnInactiveEmptyDiscardIndication() {
        assertResult(ClassId.MWI, Subtype.Mwi.DISCARD_SILENT, Pdu.deliver().dcs(0xC0).ud(new byte[0]));
        // Active, or with text, or in a store group: an ordinary indication.
        assertResult(ClassId.MWI, Subtype.Mwi.VOICEMAIL, Pdu.deliver().dcs(0xC8).ud(new byte[0]));
        assertResult(ClassId.MWI, Subtype.Mwi.VOICEMAIL, Pdu.deliver().dcs(0xC0));
        assertResult(ClassId.MWI, Subtype.Mwi.VOICEMAIL, Pdu.deliver().dcs(0xD0).ud(new byte[0]));
    }

    @Test
    void udhElementRulesGiveMwiSubtypes() {
        // IE 0x01 special SMS indication: type 1 (fax), count 1.
        assertResult(ClassId.MWI, Subtype.Mwi.FAX, Pdu.deliver().udhBytes(new byte[] {4, 0x01, 2, 0x01, 1}));
        // IE 0x23 enhanced voicemail information.
        assertResult(ClassId.MWI, Subtype.Mwi.EXTENDED, Pdu.deliver().udhBytes(new byte[] {3, 0x23, 1, 0}));
    }

    @Test
    void statusReportSubtypesFollowTheStatusRanges() {
        assertResult(ClassId.STATUS, Subtype.Status.DELIVERED, Pdu.deliver().statusReport().status(0x00));
        assertResult(ClassId.STATUS, Subtype.Status.DELIVERED, Pdu.deliver().statusReport().status(0x1F));
        assertResult(ClassId.STATUS, Subtype.Status.PENDING, Pdu.deliver().statusReport().status(0x20));
        assertResult(ClassId.STATUS, Subtype.Status.FAILED, Pdu.deliver().statusReport().status(0x45));
        assertResult(ClassId.STATUS, Subtype.Status.FAILED, Pdu.deliver().statusReport().status(0x7F));
        assertResult(ClassId.STATUS, null, Pdu.deliver().statusReport().status(0x80));
        assertEquals(Subtype.Status.FAILED, ProvisionalClassifier.statusSubtype(0x60));
        assertNull(ProvisionalClassifier.statusSubtype(-1));
    }

    @Test
    void portRulesUsePortNames() {
        assertResult(ClassId.SMART, Subtype.Smart.VCARD, Pdu.deliver().dcs(0x04).udh(1, 1, 1, 9204));
        assertResult(ClassId.DATA_PORT, Subtype.DataPort.WAP_VCARD_VCALENDAR,
                Pdu.deliver().dcs(0x04).udh(1, 1, 1, 9206));
        assertResult(ClassId.LOC_SUPL, Subtype.LocSupl.SMS, Pdu.deliver().dcs(0x04).udh(1, 1, 1, 7275));
        assertResult(ClassId.DATA_PORT, Subtype.DataPort.UNKNOWN, Pdu.deliver().dcs(0x04).udh(1, 1, 1, 16000));
        // A later segment of a multipart push: port 2948, but no WSP header to read before reassembly.
        assertResult(ClassId.DATA_PORT, Subtype.DataPort.WAP_PUSH, Pdu.deliver().dcs(0x04).udh(1, 2, 2, 2948));
    }

    @Test
    void wapPushSubtypesComeFromTheBody() {
        // WSP push: TID 1, PDU type 6, header length 1, content type 0x3E (mms-message), then the MMS header.
        byte[] notify = {0x01, 0x06, 0x01, (byte) 0xBE, (byte) 0x8C, (byte) 0x82, (byte) 0x98, 0x41, 0x00};
        assertResult(ClassId.WAP_MMS, Subtype.WapMms.NOTIFY, wapPush(notify));
        byte[] delivery = {0x01, 0x06, 0x01, (byte) 0xBE, (byte) 0x8C, (byte) 0x86, (byte) 0x98, 0x41, 0x00};
        assertResult(ClassId.WAP_MMS, Subtype.WapMms.DELIVERY_REPORT, wapPush(delivery));
        // Content type 0x30 (slc): the content type alone gives SL.
        byte[] sl = {0x01, 0x06, 0x01, (byte) 0xB0, 0x02, 0x06, 0x6A, 0x00, (byte) 0x85, 0x01};
        assertResult(ClassId.WAP_SISL, Subtype.WapSisl.SL, wapPush(sl));
        // Content type 0x32 (coc): cache operation is its own class.
        byte[] co = {0x01, 0x06, 0x01, (byte) 0xB2, 0x02, 0x07, 0x6A, 0x00, 0x05, 0x01};
        assertResult(ClassId.WAP_CO, null, wapPush(co));
    }

    @Test
    void anUnreadableApplicationIdDoesNotMakeThePushUnreadable() {
        // With the routed ProvisionalClassifier finding: content type slc is read, then the X-Wap-Application-Id
        // value (0xAF, length quote 0x1F without its length) is cut. Only the content type decides WAP-UNREADABLE.
        byte[] sl = {0x01, 0x06, 0x03, (byte) 0xB0, (byte) 0xAF, 0x1F, 0x02, 0x06, 0x6A, 0x00, (byte) 0x85, 0x01};
        assertResult(ClassId.WAP_SISL, Subtype.WapSisl.SL, wapPush(sl));
    }

    @Test
    void frameworkUsimDataDownloadIsSimOta() {
        // Class 2 with PID 0x7C goes to the UICC too (SmsMessage.isUsimDataDownload).
        assertResult(ClassId.SIM_DD, Subtype.SimOta.OTHER, Pdu.deliver().pid(0x7C).dcs(0xF6));
        assertResult(ClassId.SIM_STORED, Subtype.SimStored.CLASS2, Pdu.deliver().dcs(0xF6));
    }

    @Test
    void malformedWithoutAHardAnomalyStillNamesAnAnomaly() {
        assertEquals(AnomalyCode.UDH_LEN.wire(),
                ProvisionalClassifier.malformedSubtype(Anomalies.of(AnomalyCode.DCS_RSV, AnomalyCode.UDH_LEN)));
        assertEquals(AnomalyCode.DCS_RSV.wire(),
                ProvisionalClassifier.malformedSubtype(Anomalies.of(AnomalyCode.DCS_RSV)));
        assertNull(ProvisionalClassifier.malformedSubtype(Anomalies.NONE));
    }

    @Test
    void contentTypeOnlyPushesWithoutAPdu() {
        ProvisionalClassifier.Result r = ProvisionalClassifier.result(null, false, false, WapContentType.CT_SLC);
        assertEquals(ClassId.WAP_SISL, r.classId());
        assertEquals(Subtype.WapSisl.SL, r.subtype());
        r = ProvisionalClassifier.result(null, false, false, WapContentType.CT_UNKNOWN);
        assertEquals(ClassId.WAP_UNREADABLE, r.classId());
        assertEquals(AnomalyCode.WSP_CTYPE.forcedSubtype(), r.subtype());
        r = ProvisionalClassifier.result(null, false, false, WapContentType.CT_WML);
        assertEquals(ClassId.WAP_OTHER, r.classId(), "WML is WAP-OTHER");
        assertEquals(WapContentType.CT_WML, r.subtype());
    }

    @Test
    void everyCanaryKeepsItsClass() {
        byte[] nonce = {0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF};
        for (CanaryFactory.Canary canary : CanaryFactory.Canary.values()) {
            PduFacts facts = PduFacts.parseRadio(CanaryFactory.build(canary, nonce).radioPdu());
            assertEquals(canary.classId(), ProvisionalClassifier.classify(facts, false, false, null), canary.name());
        }
    }

    private static ProvisionalClassifier.Result result(byte[] pdu, boolean fromSim, boolean injected, String ct) {
        return ProvisionalClassifier.result(PduFacts.parseRadio(pdu), fromSim, injected, ct);
    }

    private static void assertResult(ClassId c, String subtype, Pdu.Builder b) {
        assertResult(c, subtype, b.build());
    }

    private static void assertResult(ClassId c, String subtype, byte[] pdu) {
        ProvisionalClassifier.Result r = result(pdu, false, false, null);
        String what = c + " " + subtype + " extras=" + r.extras();
        assertEquals(c, r.classId(), what);
        assertEquals(subtype, r.subtype(), what);
    }

    /** An 8-bit SMS-DELIVER to port 2948 that carries one whole WSP push. */
    private static byte[] wapPush(byte[] push) {
        return Pdu.deliver().dcs(0x04)
                .udhBytes(new byte[] {6, 0x05, 4, 0x0B, (byte) 0x84, 0x23, (byte) 0xF0})
                .ud(push)
                .build();
    }

    @Test
    void simStoredWinsOverNormalButNotOverTypeZero() {
        byte[] normal = Pdu.deliver().build();
        assertEquals(ClassId.SIM_STORED,
                ProvisionalClassifier.classify(PduFacts.parseRadio(normal), true, false, null));
        byte[] silent = Pdu.deliver().pid(0x40).build();
        assertEquals(ClassId.SILENT,
                ProvisionalClassifier.classify(PduFacts.parseRadio(silent), true, false, null));
    }

    @Test
    void nullFactsFallBackToNormal() {
        assertEquals(ClassId.NORMAL, ProvisionalClassifier.classify(null, false, false, null));
    }

    @Test
    void wapContentTypeMapping() {
        assertEquals(ClassId.WAP_CP, WapContentType.classify("application/vnd.wap.connectivity-wbxml"));
        assertEquals(ClassId.WAP_DM, WapContentType.classify("application/vnd.syncml.notification"));
        assertEquals(ClassId.WAP_DM, WapContentType.classify("application/vnd.syncml.notification.dm"));
        assertEquals(ClassId.WAP_SUPL, WapContentType.classify("application/vnd.omaloc-supl-init"));
        assertEquals(ClassId.WAP_SISL, WapContentType.classify("application/vnd.wap.sic"));
        assertEquals(ClassId.WAP_SISL, WapContentType.classify("application/vnd.wap.slc"));
        assertEquals(ClassId.WAP_SISL, WapContentType.classify("text/vnd.wap.si"));
        assertEquals(ClassId.WAP_SISL, WapContentType.classify("text/vnd.wap.sl"));
        // The framework spells 0x32 "application/vnd.wap.coc" and 0x31 "text/vnd.wap.co": cache operation.
        assertEquals(ClassId.WAP_CO, WapContentType.classify("application/vnd.wap.coc"));
        assertEquals(ClassId.WAP_CO, WapContentType.classify("text/vnd.wap.co"));
        assertEquals(ClassId.WAP_CP, WapContentType.classify("text/vnd.wap.connectivity-xml"));
        assertEquals(ClassId.WAP_DM, WapContentType.classify("application/vnd.syncml.dm+wbxml"));
        assertEquals(ClassId.WAP_DS, WapContentType.classify("application/vnd.syncml.ds.notification"));
        assertEquals(ClassId.WAP_EMN, WapContentType.classify("application/vnd.wap.emn+wbxml"));
        assertEquals(ClassId.WAP_DRM, WapContentType.classify("application/vnd.oma.drm.rights+wbxml"));
        assertEquals(ClassId.WAP_VCARD, WapContentType.classify("text/x-vCard"));
        assertEquals(ClassId.WAP_VCARD, WapContentType.classify("text/x-vcalendar"));
        assertEquals(ClassId.WAP_IMPS, WapContentType.classify("application/vnd.wv.csp.cir"));
        // WML is not a family of its own.
        assertEquals(ClassId.WAP_OTHER, WapContentType.classify("text/vnd.wap.wml"));
        assertEquals(ClassId.WAP_OTHER, WapContentType.classify("application/vnd.wap.wmlc"));
        assertEquals(ClassId.WAP_MMS, WapContentType.classify("application/vnd.wap.mms-message"));
        assertEquals(ClassId.WAP_OTHER, WapContentType.classify("application/octet-stream"));
        assertEquals(ClassId.WAP_OTHER, WapContentType.classify("application/vnd.wap.co"));
        assertEquals(ClassId.WAP_OTHER, WapContentType.classify(null));
    }

    @Test
    void wapSubtypeTables() {
        assertEquals(Subtype.WapDm.BOOTSTRAP,
                WapContentType.subtype(ClassId.WAP_DM, WapContentType.CT_SYNCML_DM_WBXML, null, null, -1));
        assertEquals(Subtype.WapDm.NOTIFY,
                WapContentType.subtype(ClassId.WAP_DM, WapContentType.CT_SYNCML, null, null, -1));
        assertEquals(Subtype.WapVcard.VCAL,
                WapContentType.subtype(ClassId.WAP_VCARD, WapContentType.CT_VCALENDAR, null, null, -1));
        assertEquals(Subtype.WapVcard.VCARD,
                WapContentType.subtype(ClassId.WAP_VCARD, WapContentType.CT_VCARD, null, null, -1));
        assertEquals("application/octet-stream",
                WapContentType.subtype(ClassId.WAP_OTHER, "application/octet-stream", null, null, -1));
        // No body: the body rules give no subtype and never throw.
        assertNull(WapContentType.subtype(ClassId.WAP_MMS, WapContentType.CT_MMS, null, null, -1));
        assertNull(WapContentType.subtype(ClassId.WAP_SUPL, WapContentType.CT_SUPL, null, new byte[0], 0));
        assertNull(WapContentType.subtype(ClassId.WAP_CP, WapContentType.CT_WBXML, null, null, -1));
        // SUPL notification types.
        assertEquals(Subtype.WapSupl.SILENT, WapContentType.suplSubtype(0));
        assertEquals(Subtype.WapSupl.NOTIFY, WapContentType.suplSubtype(1));
        assertEquals(Subtype.WapSupl.VERIFY, WapContentType.suplSubtype(2));
        assertEquals(Subtype.WapSupl.VERIFY, WapContentType.suplSubtype(3));
        assertEquals(Subtype.WapSupl.SILENT, WapContentType.suplSubtype(4));
        assertEquals(Subtype.WapSupl.SILENT, WapContentType.suplSubtype(WapContentType.SUPL_NO_NOTIFICATION));
    }

    @Test
    void wellKnownCodesMatchTheFrameworkTable() {
        assertEquals("application/vnd.wap.sic", WspMini.wellKnownName(0x2E));
        assertEquals("application/vnd.wap.slc", WspMini.wellKnownName(0x30));
        assertEquals("application/vnd.wap.coc", WspMini.wellKnownName(0x32));
        assertEquals("application/vnd.wap.connectivity-wbxml", WspMini.wellKnownName(0x36));
        assertEquals("application/vnd.wap.mms-message", WspMini.wellKnownName(0x3E));
        assertEquals("application/vnd.syncml.notification", WspMini.wellKnownName(0x44));
        assertEquals("application/vnd.omaloc-supl-init", WspMini.wellKnownName(0x0312));
        // The full OMNA table of the shared parser; 0x17 is assigned there.
        assertEquals("application/vnd.wap.uaprof", WspMini.wellKnownName(0x17));
        assertEquals("text/x-vcard", WspMini.wellKnownName(0x07));
        // The shared table names 0x4C-0x52 and 0x0311 too (framework WspTypeDecoder); 0x0319 is still unassigned.
        assertEquals("application/vnd.wv.csp+xml", WspMini.wellKnownName(0x4C));
        assertEquals("application/vnd.docomo.ub", WspMini.wellKnownName(0x0311));
        assertNull(WspMini.wellKnownName(0x0319));
    }

    // ------------------------------------------------------------------ final

    @Test
    void consumerFactsOverrideTheProvisionalClass() {
        Event e = new Event();
        e.provisionalClass = ClassId.NORMAL;
        assertEquals(ClassId.NORMAL, FinalClassifier.classify(e));
        e.vvmConsumed = true;
        assertEquals(ClassId.VVM, FinalClassifier.classify(e));
        e.vvmConsumed = false;
        e.missedCallConsumed = true;
        assertEquals(ClassId.MISSED_CALL, FinalClassifier.classify(e));
        e.missedCallConsumed = false;
        e.carrierConsumed = true;
        e.consumerPackage = "com.example.carrier";
        assertEquals(ClassId.CARRIER, FinalClassifier.classify(e));
        e.consumerPackage = FinalClassifier.RCS_PACKAGE;
        assertEquals(ClassId.RCS_PROV, FinalClassifier.classify(e));
        e.carrierConsumed = false;
        e.appTokenConsumed = true;
        assertEquals(ClassId.APP_TOKEN, FinalClassifier.classify(e));
        e.appTokenConsumed = false;
        e.oemConsumed = true;
        assertEquals(ClassId.OEM, FinalClassifier.classify(e));
    }

    @Test
    void consumerClassesAreExtrasWhenAnEarlierPduRuleMatched() {
        // Rules 1 to 5 and 7 win over a consumer class.
        Event e = new Event();
        e.provisional = new ProvisionalClassifier.Result(ClassId.SILENT, Subtype.Silent.TYPE0, List.of(),
                ProvisionalClassifier.RULE_PID);
        e.provisionalClass = ClassId.SILENT;
        e.vvmConsumed = true;
        FinalClassifier.apply(e);
        assertEquals(ClassId.SILENT, e.finalClass);
        assertEquals(Subtype.Silent.TYPE0, e.subtype);
        assertEquals(List.of(ClassId.VVM.wire()), e.extraSubtypes);

        Event wap = new Event();
        wap.provisional = new ProvisionalClassifier.Result(ClassId.WAP_MMS, Subtype.WapMms.NOTIFY, List.of(),
                ProvisionalClassifier.RULE_WAP);
        wap.provisionalClass = ClassId.WAP_MMS;
        wap.carrierConsumed = true;
        FinalClassifier.apply(wap);
        assertEquals(ClassId.WAP_MMS, wap.finalClass);
        assertEquals(List.of(ClassId.CARRIER.wire()), wap.extraSubtypes);

        // A result without a rule number (or only a predicted class) keeps the PDU class.
        Event unknownRule = new Event();
        unknownRule.provisional = new ProvisionalClassifier.Result(ClassId.DATA_PORT, Subtype.DataPort.UNKNOWN,
                List.of());
        unknownRule.provisionalClass = ClassId.DATA_PORT;
        unknownRule.vvmConsumed = true;
        assertEquals(ClassId.DATA_PORT, FinalClassifier.classify(unknownRule));
        Event predicted = new Event();
        predicted.predictedClass = ClassId.DATA_PORT;
        predicted.vvmConsumed = true;
        assertEquals(ClassId.DATA_PORT, FinalClassifier.classify(predicted));

        Event normal = new Event();
        normal.provisional = ProvisionalClassifier.Result.NORMAL;
        normal.provisionalClass = ClassId.NORMAL;
        normal.missedCallConsumed = true;
        FinalClassifier.apply(normal);
        assertEquals(ClassId.MISSED_CALL, normal.finalClass);
        assertNull(normal.subtype);
        assertTrue(normal.extraSubtypes.isEmpty());
    }

    @Test
    void consumerWinsOverAPortMatch() {
        // A carrier voicemail SMS on a data port is CL-VVM; the port match is the first extra.
        byte[] pdu = Pdu.deliver().dcs(0x04)
                .udhBytes(new byte[] {6, 0x05, 4, 0x3E, (byte) 0x81, 0, 0})
                .ud(new byte[] {1, 2})
                .build();
        Event e = new Event();
        e.provisional = result(pdu, false, false, null);
        e.provisionalClass = e.provisional.classId();
        assertEquals(ClassId.DATA_PORT, e.provisionalClass);
        assertEquals(ProvisionalClassifier.RULE_PORT, e.provisional.rule());
        e.vvmConsumed = true;
        FinalClassifier.apply(e);
        assertEquals(ClassId.VVM, e.finalClass);
        assertNull(e.subtype);
        assertEquals(List.of(ClassId.DATA_PORT.wire() + ":" + Subtype.DataPort.UNKNOWN), e.extraSubtypes);
    }

    @Test
    void consumerWinsOverAContentMatch() {
        Event e = new Event();
        e.provisional = result(Pdu.deliver().dcs(0x04).ud(new byte[] {1, 2, 3}).build(), false, false, null);
        e.provisionalClass = e.provisional.classId();
        assertEquals(ClassId.BINARY, e.provisionalClass);
        assertEquals(ProvisionalClassifier.RULE_CONTENT, e.provisional.rule());
        e.appTokenConsumed = true;
        FinalClassifier.apply(e);
        assertEquals(ClassId.APP_TOKEN, e.finalClass);
        assertEquals(List.of(ClassId.BINARY.wire()), e.extraSubtypes);
    }

    @Test
    void theResultNamesTheRuleThatGaveTheClass() {
        assertEquals(ProvisionalClassifier.RULE_PID, result(Pdu.deliver().pid(0x40).build(), false, false, null).rule());
        assertEquals(ProvisionalClassifier.RULE_DCS, result(Pdu.deliver().dcs(0x10).build(), false, false, null).rule());
        assertEquals(ProvisionalClassifier.RULE_UDH,
                result(Pdu.deliver().udhBytes(new byte[] {3, 0x23, 1, 0}).build(), false, false, null).rule());
        assertEquals(ProvisionalClassifier.RULE_SOURCE, result(Pdu.deliver().build(), false, true, null).rule());
        assertEquals(ProvisionalClassifier.RULE_ANOMALY, result(new byte[] {1}, false, false, null).rule());
        assertEquals(ProvisionalClassifier.RULE_NORMAL, result(Pdu.deliver().build(), false, false, null).rule());
        assertEquals(ProvisionalClassifier.RULE_CONTENT,
                result(Pdu.deliver().ud(new byte[0]).build(), false, false, null).rule());
        assertTrue(ProvisionalClassifier.Result.NORMAL.consumerMayWin());
        assertFalse(result(Pdu.deliver().pid(0x40).build(), false, false, null).consumerMayWin());
    }

    @Test
    void extrasForWritesTheCodesAgainForTheNewClass() {
        ProvisionalClassifier.Result r = new ProvisionalClassifier.Result(ClassId.DATA_PORT,
                Subtype.DataPort.UNKNOWN, List.of(Subtype.DataPort.PORT0, "CL-BINARY", "CL-VVM", "CL-VVM:X"),
                ProvisionalClassifier.RULE_PORT);
        assertEquals(List.of("CL-DATA-PORT:UNKNOWN", "CL-DATA-PORT:PORT0", "CL-BINARY", "X"),
                ProvisionalClassifier.extrasFor(ClassId.VVM, r));
        ProvisionalClassifier.Result empty = new ProvisionalClassifier.Result(ClassId.EMPTY, null, List.of(),
                ProvisionalClassifier.RULE_CONTENT);
        assertEquals(List.of("CL-EMPTY"), ProvisionalClassifier.extrasFor(ClassId.CARRIER, empty));
    }

    @Test
    void injectedEventsNameTheCallingPackage() {
        Event e = new Event();
        e.provisional = new ProvisionalClassifier.Result(ClassId.INJECTED, null, List.of());
        e.provisionalClass = ClassId.INJECTED;
        e.callingPackage = "com.example.injector";
        FinalClassifier.apply(e);
        assertEquals(ClassId.INJECTED, e.finalClass);
        assertEquals("com.example.injector", e.subtype);
    }

    @Test
    void outboundSubtypesWithoutApply() {
        Event e = new Event();
        e.direction = Direction.OUT;
        e.finalClass = ClassId.MO_STK;
        assertEquals(Subtype.MoStk.SEND_SMS, FinalClassifier.subtype(e));
        e.finalClass = ClassId.MO_DATA;
        e.destPort = 16001;
        assertEquals("16001", FinalClassifier.subtype(e));
        e.finalClass = ClassId.MO_VISIBLE;
        e.callingPackage = "com.example.sender";
        assertEquals("com.example.sender", FinalClassifier.subtype(e));
    }

    @Test
    void cbEventsAreAlwaysCb() {
        Event e = new Event();
        e.cb = new Event.Cb(1, 4370, 1, 1, true);
        assertEquals(ClassId.CB, FinalClassifier.classify(e));
    }

    // ------------------------------------------------------------------ WSP

    @Test
    void wspParsesAnOmaCpPush() {
        // A real OMA CP push as the framework reads it: TID 0x2B, Push, header length 3, content
        // type short-integer 0x36 (application/vnd.wap.connectivity-wbxml), X-Wap-Application-Id
        // short-integer 0x02, then the WBXML body.
        byte[] pdu = {0x2B, 0x06, 0x03, (byte) 0xB6, (byte) 0xAF, (byte) 0x82, 0x03, 0x01, 0x6A};
        WspMini.Result r = WspMini.parse(pdu);
        assertTrue(r.valid(), r.error());
        assertEquals(0x2B, r.transactionId());
        assertEquals(WspMini.PDU_TYPE_PUSH, r.pduType());
        assertEquals(WapContentType.CT_WBXML, r.contentType());
        assertEquals(0x36, r.contentTypeCode());
        assertEquals("0x2", r.applicationId());
        assertEquals(ClassId.WAP_CP, WapContentType.classify(r.contentType()));
    }

    @Test
    void wspParsesAnOmaSiPush() {
        // Real SI push shape: content type 0x2E (application/vnd.wap.sic), no other header, then
        // the WBXML body.
        byte[] pdu = {0x01, 0x06, 0x01, (byte) 0xAE, 0x02, 0x05, 0x6A, 0x00, 0x45, (byte) 0xC6,
                (byte) 0x0C, 0x03};
        WspMini.Result r = WspMini.parse(pdu);
        assertTrue(r.valid(), r.error());
        assertEquals(1, r.transactionId());
        assertEquals(WapContentType.CT_SIC, r.contentType());
        assertEquals(ClassId.WAP_SISL, WapContentType.classify(r.contentType()));
    }

    @Test
    void wspParsesTextContentType() {
        byte[] ct = "application/vnd.omaloc-supl-init".getBytes();
        byte[] pdu = new byte[3 + ct.length + 1 + 2];
        pdu[0] = 0x02;
        pdu[1] = 0x06;
        pdu[2] = (byte) (ct.length + 1);
        System.arraycopy(ct, 0, pdu, 3, ct.length);
        // pdu[3 + ct.length] stays 0: the NUL terminator.
        WspMini.Result r = WspMini.parse(pdu);
        assertTrue(r.valid(), r.error());
        assertEquals(WapContentType.CT_SUPL, r.contentType());
        assertEquals(ClassId.WAP_SUPL, WapContentType.classify(r.contentType()));
    }

    @Test
    void wspParsesAValueLengthWrappedContentType() {
        // Content-general-form: value-length 0x05 then the media type plus two parameters.
        byte[] pdu = {0x01, 0x06, 0x06, 0x05, (byte) 0xB6, (byte) 0x91, 0x02, (byte) 0x84};
        WspMini.Result r = WspMini.parse(pdu);
        assertTrue(r.valid(), r.error());
        assertEquals(WapContentType.CT_WBXML, r.contentType());
        assertEquals(ClassId.WAP_CP, WapContentType.classify(r.contentType()));
    }

    @Test
    void wspParsesAMultibyteHeaderLength() {
        // A header longer than 127 octets needs a two-octet uintvar (0x80|hi, lo): 0x81 0x48 = 200.
        int body = 200;
        byte[] pdu = new byte[3 + 2 + body];
        pdu[0] = 0x01;
        pdu[1] = 0x06;
        pdu[2] = (byte) 0x81;
        pdu[3] = 0x48;
        pdu[4] = (byte) 0xAE;
        WspMini.Result r = WspMini.parse(pdu);
        assertTrue(r.valid(), r.error());
        assertEquals(body, r.headerLength());
        assertEquals(WapContentType.CT_SIC, r.contentType());
    }

    @Test
    void wspIsTotal() {
        assertFalse(WspMini.parse(null).valid());
        assertFalse(WspMini.parse(new byte[0]).valid());
        assertFalse(WspMini.parse(new byte[] {1, 2}).valid());
        // Header length sits before the content type; 0x2C there is not a field name any more.
        assertFalse(WspMini.parse(new byte[] {0x01, 0x06, (byte) 0xFF, 0x2C, 0x00}).valid());
        // A truncated uintvar leaves no content type.
        assertFalse(WspMini.parse(new byte[] {0x01, 0x06, (byte) 0x86}).valid());
    }

    @Test
    void wspReadsTheApplicationId() {
        byte[] appId = "x-wap-application:mms.ua".getBytes();
        byte[] pdu = new byte[3 + 1 + 2 + appId.length + 1];
        pdu[0] = 0x03;
        pdu[1] = 0x06;
        pdu[2] = (byte) (2 + appId.length + 1);
        pdu[3] = (byte) 0xBE; // content type 0x3E: mms-message
        pdu[4] = (byte) 0xAF; // X-Wap-Application-Id
        System.arraycopy(appId, 0, pdu, 5, appId.length);
        WspMini.Result r = WspMini.parse(pdu);
        assertTrue(r.valid(), r.error());
        assertEquals("x-wap-application:mms.ua", r.applicationId());
        assertEquals(WapContentType.CT_MMS, r.contentType());
    }

    // ------------------------------------------------------------------ sealer

    @Test
    void sealerTimesOutAtThirtySeconds() {
        Sealer sealer = new Sealer(() -> true);
        Event e = new Event();
        e.createdAtNs = 0;
        e.lastTNs = 0;
        e.provisionalClass = ClassId.NORMAL;
        assertNull(sealer.decide(e, 29_999_999_999L));
        // No dispatch result means Android did not dispatch it.
        assertEquals(Fate.NOT_DISPATCHED, sealer.decide(e, 30_000_000_000L));
        assertTrue(sealer.isTimeout(e, 30_000_000_000L));
        e.ackResult = 1;
        assertNull(sealer.decide(e, 29_999_999_999L), "an accepted message waits for its consumer");
        assertEquals(Fate.ACCEPTED, sealer.decide(e, 30_000_000_000L));
        e.ackResult = -1;
        assertEquals(Fate.ACCEPTED, sealer.decide(e, 30_000_000_000L));
        e.ackResult = 0;
        assertEquals(Fate.REJECTED, sealer.decide(e, 30_000_000_000L), "a result that is not a known acceptance");
        e.ackResult = null;
        e.flags.replay = true;
        assertEquals(Fate.ACCEPTED, sealer.decide(e, 30_000_000_000L), "the unlock replay is Android's dispatch");
        assertTrue(Sealer.isTimeoutFate(Fate.ACCEPTED));
        assertTrue(Sealer.isTimeoutFate(Fate.NOT_DISPATCHED));
        assertFalse(Sealer.isTimeoutFate(Fate.REJECTED));
    }

    @Test
    void sealerRejectsARefusedDispatchAtOnce() {
        Sealer sealer = new Sealer(() -> true);
        for (int code : new int[] {2, 4, 5, 6, 7, 8, 9, 10, 11}) {
            Event e = new Event();
            e.createdAtNs = 0;
            e.lastTNs = 0;
            e.provisionalClass = ClassId.NORMAL;
            e.ackResult = code;
            assertEquals(Fate.REJECTED, sealer.decide(e, 0), "code " + code);
        }
        Event full = new Event();
        full.ackResult = 3;
        assertEquals(Fate.NACK_STORAGE, sealer.decide(full, 0));
        Event blocked = new Event();
        blocked.ackResult = 5;
        blocked.verdict = dev.nightjar.contract.Verdict.BLOCK;
        assertEquals(Fate.BLOCKED, sealer.decide(blocked, 0), "a NightJar verdict decides first");
    }

    @Test
    void aDispatchedCanaryWithoutAVerdictIsShownAfterTheGrace() {
        // Event.verdict is ALLOW by default, so the rule must test whether a verdict was reported.
        Sealer sealer = new Sealer(() -> true);
        Event e = new Event();
        e.createdAtNs = 0;
        e.lastTNs = 0;
        e.provisionalClass = ClassId.NORMAL;
        e.flags.selfTest = true;
        e.source = 0;
        e.ackResult = 1;
        assertNull(sealer.decide(e, Sealer.CANARY_PASSED_NS - 1));
        assertEquals(Fate.SHOWN, sealer.decide(e, Sealer.CANARY_PASSED_NS));
        e.verdictSeen = true;
        assertNull(sealer.decide(e, Sealer.CANARY_PASSED_NS), "only a canary that met no filter counts as shown");
    }

    @Test
    void pendingUnlockOnlyForMessagesThatWaitInTheRawTable() {
        Sealer locked = new Sealer(() -> false);
        Event withPort = new Event();
        withPort.destPort = 2948;
        assertEquals(Fate.PENDING_UNLOCK, locked.decide(withPort, 5_000_000_000L));
        Event normal = new Event();
        normal.provisionalClass = ClassId.NORMAL;
        assertEquals(Fate.PENDING_UNLOCK, locked.decide(normal, 5_000_000_000L),
                "a normal SMS with no dispatch waits in the raw table");
        normal.ackResult = 1;
        assertNull(locked.decide(normal, 5_000_000_000L), "a dispatched SMS is not pending");
        Sealer unlocked = new Sealer(() -> true);
        assertNull(unlocked.decide(withPort, 5_000_000_000L));
    }

    @Test
    void newClassesOnTheNormalDeliveryPathWaitForTheUnlock() {
        Sealer locked = new Sealer(() -> false);
        for (ClassId c : new ClassId[] {ClassId.EMPTY, ClassId.BINARY, ClassId.CLASS3, ClassId.TELEMATIC,
            ClassId.RETURN_CALL, ClassId.COMPRESSED, ClassId.ME_DOWNLOAD, ClassId.ME_DEPERSO}) {
            Event e = new Event();
            e.provisionalClass = c;
            assertEquals(Fate.PENDING_UNLOCK, locked.decide(e, 5_000_000_000L), c.wire());
        }
        // The framework cannot read a malformed PDU, so it never waits in the raw table.
        Event malformed = new Event();
        malformed.provisionalClass = ClassId.MALFORMED_SMS;
        assertNull(locked.decide(malformed, 5_000_000_000L));
        assertEquals(Fate.NOT_DISPATCHED, locked.decide(malformed, Sealer.TIMEOUT_NS));
        assertFalse(Sealer.normalDeliveryPath(ClassId.SILENT));
        assertFalse(Sealer.normalDeliveryPath(null));
        // AOSP reads TP-MTI 3 as an SMS-DELIVER, so CL-UNREADABLE-SMS waits like a normal SMS.
        assertTrue(Sealer.normalDeliveryPath(ClassId.UNREADABLE_SMS));
    }

    @Test
    void explicitFatesWinOverTheTimeout() {
        Sealer sealer = new Sealer(() -> true);
        Event e = new Event();
        e.fate = Fate.SHOWN;
        assertEquals(Fate.SHOWN, sealer.decide(e, 60_000_000_000L));
        Event sealed = new Event();
        sealed.sealed = true;
        assertNull(sealer.decide(sealed, 60_000_000_000L));
    }

    @Test
    void mwiDontStoreSealsSystemButMwiStoreWaits() {
        Sealer sealer = new Sealer(() -> true);
        Event dontStore = new Event();
        dontStore.branch = "mwi";
        dontStore.facts = PduFacts.parseRadio(Pdu.deliver().dcs(0xC8).build());
        // The dispatch observation has not arrived yet: the seal waits for it, so a canary keeps its flags.
        assertNull(sealer.decide(dontStore, 0));
        dontStore.source = 0;
        assertEquals(Fate.SYSTEM, sealer.decide(dontStore, 0));
        Event store = new Event();
        store.branch = "mwi";
        store.facts = PduFacts.parseRadio(Pdu.deliver().dcs(0xD0).build());
        assertNull(sealer.decide(store, 0));
    }

    @Test
    void wapResultsMapToFates() {
        Sealer sealer = new Sealer(() -> true);
        Event e = new Event();
        e.wapHandled = true;
        e.wapResult = -1;
        assertEquals(Fate.APP, sealer.decide(e, 0));
        // Android's RESULT_SMS_HANDLED means a WAP push manager app consumed the push.
        e.wapResult = 1;
        assertEquals(Fate.APP, sealer.decide(e, 0));
        e.wapBy = "nightjar";
        assertEquals(Fate.BLOCKED, sealer.decide(e, 0));
        e.wapBy = "crash";
        assertEquals(Fate.BLOCKED, sealer.decide(e, 0));
        // Any other result (2, the WSP header did not decode) makes Android delete the push after the ACK.
        e.wapBy = null;
        e.wapResult = 2;
        assertEquals(Fate.SYSTEM, sealer.decide(e, 0));
    }

    @Test
    void blocklistAndOemFates() {
        Sealer sealer = new Sealer(() -> true);
        Event blocked = new Event();
        blocked.blockedByAndroid = true;
        assertEquals(Fate.BLOCKLIST, sealer.decide(blocked, 0));
        Event oem = new Event();
        oem.oemConsumed = true;
        assertEquals(Fate.OEM_DROPPED, sealer.decide(oem, 0));
        Event redirected = new Event();
        redirected.oemRedirected = true;
        assertEquals(Fate.OEM_REDIRECTED, sealer.decide(redirected, 0));
    }
}
