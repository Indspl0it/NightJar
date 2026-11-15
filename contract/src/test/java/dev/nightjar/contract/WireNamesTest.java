package dev.nightjar.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Wire names match the IPC contract exactly. */
class WireNamesTest {

    private static <E extends Enum<E> & WireEnum> List<String> wires(Class<E> type) {
        return Arrays.stream(type.getEnumConstants()).map(WireEnum::wire).toList();
    }

    @Test
    void IPC_01_enumWireNamesMatchContract() {
        assertEquals(List.of("IN", "OUT"), wires(Direction.class));
        assertEquals(List.of("CS", "IMS", "CB", "INJECTED", "MO_CS", "MO_IMS"), wires(Transport.class));
        assertEquals(List.of("INFO", "NOTABLE", "SUSPICIOUS", "HIGH"), wires(Severity.class));
        assertEquals(List.of("ALLOW", "NOTIFY", "QUARANTINE", "BLOCK"), wires(Verdict.class));
        assertEquals(List.of("LIVE", "NOT_FOUND", "FAILED", "DISABLED", "NOT_APPLICABLE"), wires(HookState.class));
        assertEquals(List.of("PROTECTED", "MONITOR_ONLY", "SAFE_MODE", "NOT_ACTIVE", "UNSUPPORTED"), wires(ProtectionState.class));
        assertEquals(
                List.of("capture-required", "capture-optional", "meaning", "firewall-core", "firewall-extra", "outbound"),
                wires(Tier.class));
    }

    @Test
    void severityOrdinalsAscend() {
        // The app raises a severity by ordinal (evidence floor): the order is part of the contract.
        assertTrue(Severity.INFO.ordinal() < Severity.NOTABLE.ordinal());
        assertTrue(Severity.NOTABLE.ordinal() < Severity.SUSPICIOUS.ordinal());
        assertTrue(Severity.SUSPICIOUS.ordinal() < Severity.HIGH.ordinal());
    }

    @Test
    void IPC_01_classIdsMatchDesign() {
        assertEquals(
                List.of("CL-NORMAL", "CL-SILENT", "CL-FLASH", "CL-REPLACE", "CL-MWI", "CL-VVM", "CL-MISSED-CALL",
                        "CL-CARRIER", "CL-RCS-PROV", "CL-APP-TOKEN", "CL-DATA-PORT", "CL-WAP-MMS", "CL-WAP-SISL",
                        "CL-WAP-CP", "CL-WAP-DM", "CL-WAP-SUPL", "CL-WAP-OTHER", "CL-SIM-DD", "CL-SIM-STORED",
                        "CL-STATUS", "CL-CB", "CL-OEM", "CL-INJECTED", "CL-MO-VISIBLE", "CL-MO-UNPERSISTED",
                        "CL-MO-DATA", "CL-MO-RAW", "CL-MO-STK", "CL-MO-VVM",
                        // Newer classes, appended so the existing ordinals stay.
                        "CL-RETURN-CALL", "CL-ME-DOWNLOAD", "CL-ME-DEPERSO", "CL-CLASS3", "CL-TELEMATIC",
                        "CL-EMPTY", "CL-BINARY", "CL-COMPRESSED", "CL-MULTIPART-INCOMPLETE", "CL-MALFORMED-SMS",
                        "CL-SMART", "CL-LOC-SUPL", "CL-WAP-CO", "CL-WAP-DS", "CL-WAP-EMN", "CL-WAP-DRM",
                        "CL-WAP-VCARD", "CL-WAP-IMPS", "CL-WAP-UNREADABLE", "CL-CB-SIM",
                        // appended.
                        "CL-UNREADABLE-SMS"),
                wires(ClassId.class));
    }

    @Test
    void CL_01_anomalyCodesMatchPlan() {
        assertEquals(
                List.of("AN-ADDR-TRUNC", "AN-ADDR-LEN", "AN-ADDR-TOA", "AN-PDU-SHORT", "AN-MTI", "AN-PID-RSV",
                        "AN-DCS-RSV", "AN-UDL-LONG", "AN-UDL-SHORT", "AN-UDH-LEN", "AN-IE-OVERRUN", "AN-IE-RSV",
                        "AN-IE-DUP", "AN-PORT-ZERO", "AN-CONCAT-BAD", "AN-CONCAT-DUP", "AN-WSP-TYPE", "AN-WSP-HLEN",
                        "AN-WSP-CTYPE", "AN-WSP-APPID", "AN-WBXML-VER", "AN-WBXML-STR", "AN-WBXML-TOK", "AN-OTA-HDR"),
                wires(AnomalyCode.class));
    }

    @Test
    void CL_01_anomalyPolicyMatchesOd4() {
        Set<AnomalyCode> hard = EnumSet.of(AnomalyCode.ADDR_TRUNC, AnomalyCode.ADDR_LEN, AnomalyCode.PDU_SHORT,
                AnomalyCode.UDL_LONG, AnomalyCode.UDH_LEN, AnomalyCode.IE_OVERRUN);
        Set<AnomalyCode> wspHard = EnumSet.of(AnomalyCode.WSP_TYPE, AnomalyCode.WSP_HLEN, AnomalyCode.WSP_CTYPE);
        for (AnomalyCode a : AnomalyCode.values()) {
            ClassId expected;
            if (hard.contains(a)) {
                expected = ClassId.MALFORMED_SMS;
            } else if (a == AnomalyCode.MTI) {
                expected = ClassId.UNREADABLE_SMS; // Wrong message type, AOSP still reads it
            } else if (wspHard.contains(a)) {
                expected = ClassId.WAP_UNREADABLE;
            } else if (a == AnomalyCode.OTA_HDR) {
                expected = ClassId.SIM_DD;
            } else {
                expected = null;
            }
            assertEquals(expected, a.forcedClass(), a.wire());
        }
        assertEquals("AN-UDH-LEN", AnomalyCode.UDH_LEN.forcedSubtype());
        assertEquals("AN-MTI", AnomalyCode.MTI.forcedSubtype());
        assertEquals("AN-WSP-TYPE", AnomalyCode.WSP_TYPE.forcedSubtype());
        assertEquals(Subtype.SimOta.OTHER, AnomalyCode.OTA_HDR.forcedSubtype());
        assertNull(AnomalyCode.PORT_ZERO.forcedSubtype());
        assertEquals(AnomalyCode.IE_DUP, Wire.parse(AnomalyCode.class, "AN-IE-DUP", null));
    }

    @Test
    void CL_01_subtypeCodesMatchPlan() {
        assertEquals(List.of("TYPE0", "TYPE0-PORT"), Subtype.known(ClassId.SILENT));
        assertEquals(List.of("CLASS0", "CLASS0-EMPTY"), Subtype.known(ClassId.FLASH));
        assertEquals(List.of("TYPE1", "TYPE2", "TYPE3", "TYPE4", "TYPE5", "TYPE6", "TYPE7"),
                Subtype.known(ClassId.REPLACE));
        assertEquals(
                List.of("SIM-OTA-SAT", "SIM-OTA-WIB", "SIM-OTA-NOSEC", "SIM-OTA-RFM", "SIM-OTA-RAM", "SIM-OTA-OTHER"),
                Subtype.known(ClassId.SIM_DD));
        assertEquals(List.of("CLASS2"), Subtype.known(ClassId.SIM_STORED));
        assertEquals(List.of("FAX", "TELEX", "PAGING", "VOICE", "ERMES", "X400", "EMAIL", "OTHER"),
                Subtype.known(ClassId.TELEMATIC));
        assertEquals(List.of("VOICEMAIL", "FAX", "EMAIL", "OTHER", "EXTENDED", "DISCARD-SILENT"),
                Subtype.known(ClassId.MWI));
        assertEquals(List.of("DELIVERED", "PENDING", "FAILED"), Subtype.known(ClassId.STATUS));
        assertEquals(
                List.of("UNKNOWN", "PORT0", "WAP-PUSH", "WAP-PUSH-SECURE", "WAP-CONNECTIONLESS-SESSION", "WAP-SESSION",
                        "WAP-CONNECTIONLESS-SESSION-SECURE", "WAP-SESSION-SECURE", "WAP-VCARD-VCALENDAR",
                        "WAP-WTA-CONNECTIONLESS-SESSION-SECURE", "WAP-WTA-SESSION-SECURE", "SERVICE-CARD",
                        "INTERNET-ACCESS-CONFIG", "DYNAMIC-MENU-CONTROL", "MESSAGE-ACCESS", "EMAIL-NOTIFICATION",
                        "SYNCML-OTA-CONFIG", "WAP-OTA-CONFIG"),
                Subtype.known(ClassId.DATA_PORT));
        assertEquals(List.of("RINGTONE", "OPLOGO", "CLILOGO", "PICTURE", "VCARD", "VCAL"),
                Subtype.known(ClassId.SMART));
        assertEquals(List.of("SMS"), Subtype.known(ClassId.LOC_SUPL));
        assertEquals(List.of("NOTIFY", "DELIVERY-REPORT", "READ-REPORT"), Subtype.known(ClassId.WAP_MMS));
        assertEquals(List.of("SI", "SI-DELETE", "SL"), Subtype.known(ClassId.WAP_SISL));
        assertEquals(List.of("NETWPIN", "USERPIN", "USERNETWPIN", "USERPINMAC", "NOSEC"),
                Subtype.known(ClassId.WAP_CP));
        assertEquals(List.of("NOTIFY", "BOOTSTRAP"), Subtype.known(ClassId.WAP_DM));
        assertEquals(List.of("NOTIFY", "VERIFY", "SILENT"), Subtype.known(ClassId.WAP_SUPL));
        assertEquals(List.of("VCARD", "VCAL"), Subtype.known(ClassId.WAP_VCARD));
        assertEquals(
                List.of("PRESIDENTIAL", "EXTREME", "SEVERE", "AMBER", "TEST", "EXERCISE", "PUBLIC-SAFETY", "OPERATOR",
                        "ETWS-EQ", "ETWS-TSUNAMI", "ETWS-BOTH", "ETWS-TEST", "ETWS-OTHER", "AREA-INFO", "CHANNEL"),
                Subtype.known(ClassId.CB));
        assertEquals(List.of("SEND-SMS"), Subtype.known(ClassId.MO_STK));
        assertEquals(List.of(), Subtype.known(null));
    }

    @Test
    void CL_01_subtypesAreUniqueAndFreeFormClassesHaveNoFixedCodes() {
        for (ClassId c : ClassId.values()) {
            List<String> known = Subtype.known(c);
            assertEquals(known.size(), known.stream().distinct().count(), c.wire());
            if (Subtype.isFreeForm(c)) assertTrue(known.isEmpty(), c.wire());
        }
        for (AnomalyCode a : AnomalyCode.values()) {
            if (a.forcedClass() == ClassId.MALFORMED_SMS || a.forcedClass() == ClassId.UNREADABLE_SMS
                    || a.forcedClass() == ClassId.WAP_UNREADABLE) {
                assertTrue(Subtype.isFreeForm(a.forcedClass()), a.wire());
            } else if (a.forcedClass() != null) {
                assertTrue(Subtype.known(a.forcedClass()).contains(a.forcedSubtype()), a.wire());
            }
        }
        assertFalse(Subtype.isFreeForm(null));
    }

    @Test
    void CL_01_eventKeysAndVersion() {
        assertEquals(2, Ipc.VERSION);
        assertEquals("subtype", Ipc.EventKey.SUBTYPE);
        assertEquals("extraSubtypes", Ipc.EventKey.EXTRA_SUBTYPES);
        assertEquals("anomalies", Ipc.EventKey.ANOMALIES);
    }

    @Test
    void CL_08_simOtaHeaderKeys() {
        assertEquals("simOtaHeader", Ipc.EventKey.SIM_OTA_HEADER);
        assertEquals(
                List.of("cpl", "chl", "spi", "kic", "kid", "tar", "cntr"),
                List.of(Ipc.SimOtaKey.CPL, Ipc.SimOtaKey.CHL, Ipc.SimOtaKey.SPI, Ipc.SimOtaKey.KIC,
                        Ipc.SimOtaKey.KID, Ipc.SimOtaKey.TAR, Ipc.SimOtaKey.CNTR));
    }

    @Test
    void IPC_01_fatesMatchDesign() {
        assertEquals(
                List.of("FT-SHOWN", "FT-APP", "FT-NO-RECEIVER", "FT-VVM", "FT-CARRIER", "FT-MISSED-CALL", "FT-SYSTEM",
                        "FT-SIM", "FT-OEM-DROPPED", "FT-OEM-REDIRECTED", "FT-BLOCKLIST", "FT-BLOCKED", "FT-QUARANTINED",
                        "FT-RELEASED", "FT-NACK-STORAGE", "FT-INCOMPLETE", "FT-PENDING-UNLOCK", "FT-SENT",
                        "FT-SEND-FAILED", "FT-REJECTED", "FT-ACCEPTED", "FT-NOT-DISPATCHED", "FT-PHONE-STOPPED",
                        "FT-UNLOCK-EXPIRED", "FT-NOT-SENT", "FT-SEND-UNCONFIRMED", "FT-UNKNOWN"),
                wires(Fate.class));
    }

    @Test
    void IPC_01_wireNamesAreUniqueAndParse() {
        for (Class<?> c : List.of(ClassId.class, Fate.class, TraceStage.class, Verdict.class, AnomalyCode.class)) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            List<String> w = wires((Class) c);
            assertEquals(w.size(), w.stream().distinct().count(), c.getSimpleName());
        }
        assertEquals(Fate.PENDING_UNLOCK, Wire.parse(Fate.class, "FT-PENDING-UNLOCK", Fate.UNKNOWN));
        assertEquals(Fate.UNKNOWN, Wire.parse(Fate.class, "FT-NOPE", Fate.UNKNOWN));
        assertEquals(Fate.UNKNOWN, Wire.parse(Fate.class, null, Fate.UNKNOWN));
    }

    @Test
    void UX_B02_ackResultsSplitIntoAcceptedRejectedAndStorageFull() {
        assertTrue(AckResult.accepted(AckResult.HANDLED));
        assertTrue(AckResult.accepted(AckResult.OK));
        assertFalse(AckResult.rejected(AckResult.HANDLED));
        assertFalse(AckResult.rejected(AckResult.OK));
        // Full storage has its own fate (FT-NACK-STORAGE), so it is neither.
        assertFalse(AckResult.accepted(AckResult.OUT_OF_MEMORY));
        assertFalse(AckResult.rejected(AckResult.OUT_OF_MEMORY));
        for (int code = AckResult.GENERIC_ERROR; code <= AckResult.INVALID_URI; code++) {
            if (code == AckResult.OUT_OF_MEMORY) continue;
            assertTrue(AckResult.rejected(code), "code " + code);
            assertFalse(AckResult.accepted(code), "code " + code);
        }
        assertFalse(AckResult.rejected(0));
        assertFalse(AckResult.rejected(12));
    }

    @Test
    void IPC_05_controlDescriptorCarriesTheVersion() {
        assertEquals("dev.nightjar.IPayloadControl/1", Control.DESCRIPTOR);
    }

    @Test
    void IPC_05_releaseResultCodesAreFixedAndUnique() {
        // Wire values: the app maps them to text (ReleaseCodes.kt), so a value never changes once released.
        List<Integer> codes = List.of(Control.ReleaseResult.HANDLED, Control.ReleaseResult.GENERIC_FAILURE,
                Control.ReleaseResult.OUT_OF_MEMORY, Control.ReleaseResult.INVALID_ARGUMENTS,
                Control.ReleaseResult.NOT_RELEASABLE, Control.ReleaseResult.TIMEOUT,
                Control.ReleaseResult.NO_DISPATCHER, Control.ReleaseResult.CALLER_REJECTED,
                Control.ReleaseResult.NOT_SUPPORTED);
        assertEquals(List.of(1, 2, 3, -10, -11, -12, -13, -14, -15), codes);
        assertEquals(codes.size(), Set.copyOf(codes).size());
    }
}
