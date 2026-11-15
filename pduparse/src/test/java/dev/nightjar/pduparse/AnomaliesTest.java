package dev.nightjar.pduparse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.AnomalyCode;
import dev.nightjar.contract.ClassId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The anomaly bit set and the precedence of the codes. */
class AnomaliesTest {
    @Test
    void everyCodeFitsTheIntMask() {
        assertTrue(AnomalyCode.values().length <= Integer.SIZE, "the mask is an int over the ordinals");
        int all = Anomalies.NONE;
        for (AnomalyCode c : AnomalyCode.values()) all = Anomalies.with(all, c);
        assertEquals(List.of(AnomalyCode.values()), Anomalies.list(all));
    }

    @Test
    void listIsInContractOrder() {
        int mask = Anomalies.of(AnomalyCode.OTA_HDR, AnomalyCode.ADDR_TOA, AnomalyCode.WSP_HLEN);
        assertEquals(List.of(AnomalyCode.ADDR_TOA, AnomalyCode.WSP_HLEN, AnomalyCode.OTA_HDR), Anomalies.list(mask));
        assertEquals("AN-ADDR-TOA,AN-WSP-HLEN,AN-OTA-HDR", Anomalies.names(mask));
        assertEquals("", Anomalies.names(Anomalies.NONE));
    }

    @Test
    void forcingFollowsOd4() {
        assertNull(Anomalies.forcing(Anomalies.of(AnomalyCode.ADDR_TOA, AnomalyCode.IE_RSV, AnomalyCode.WBXML_TOK)));
        assertEquals(AnomalyCode.UDL_LONG,
                Anomalies.forcing(Anomalies.of(AnomalyCode.OTA_HDR, AnomalyCode.WSP_TYPE, AnomalyCode.UDL_LONG)));
        assertEquals(AnomalyCode.WSP_CTYPE, Anomalies.forcing(Anomalies.of(AnomalyCode.OTA_HDR, AnomalyCode.WSP_CTYPE)));
        assertEquals(ClassId.SIM_DD, Anomalies.forcing(Anomalies.of(AnomalyCode.OTA_HDR)).forcedClass());
    }

    @Test
    void knownDamageWinsOverTheWrongMessageType() {
        // AN-MTI alone gives CL-UNREADABLE-SMS. With a CL-MALFORMED-SMS code, the stricter class wins, also
        // when that code comes after AN-MTI in contract order.
        assertEquals(AnomalyCode.MTI, Anomalies.forcing(Anomalies.of(AnomalyCode.MTI)));
        assertEquals(ClassId.UNREADABLE_SMS, AnomalyCode.MTI.forcedClass());
        assertEquals(AnomalyCode.ADDR_TRUNC, Anomalies.forcing(Anomalies.of(AnomalyCode.MTI, AnomalyCode.ADDR_TRUNC)));
        assertEquals(AnomalyCode.UDL_LONG, Anomalies.forcing(Anomalies.of(AnomalyCode.MTI, AnomalyCode.UDL_LONG)));
        assertEquals(AnomalyCode.MTI, Anomalies.forcing(Anomalies.of(AnomalyCode.MTI, AnomalyCode.WSP_TYPE)));
        assertFalse(Anomalies.malformed(Anomalies.of(AnomalyCode.MTI)));
        assertTrue(Anomalies.malformed(Anomalies.of(AnomalyCode.MTI, AnomalyCode.PDU_SHORT)));
    }

    @Test
    void malformedIsOnlyTheMalformedSmsCodes() {
        assertTrue(Anomalies.malformed(Anomalies.of(AnomalyCode.IE_OVERRUN)));
        assertFalse(Anomalies.malformed(Anomalies.of(AnomalyCode.WSP_TYPE, AnomalyCode.OTA_HDR, AnomalyCode.UDL_SHORT)));
        assertFalse(Anomalies.malformed(Anomalies.NONE));
    }

    @Test
    void unparsedPushesAreUnreadable() {
        assertEquals(Anomalies.of(AnomalyCode.WSP_TYPE, AnomalyCode.WSP_HLEN, AnomalyCode.WSP_CTYPE), WspParser.UNREADABLE);
        for (AnomalyCode c : Anomalies.list(WspParser.UNREADABLE)) {
            assertEquals(ClassId.WAP_UNREADABLE, c.forcedClass(), c.wire());
        }
    }
}
