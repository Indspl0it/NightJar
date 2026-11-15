package dev.nightjar.payload.firewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.payload.testing.Pdu;
import org.junit.jupiter.api.Test;

/** Release eligibility comes from the released PDUs, not from the event ring. */
class ReleaseEligibilityTest {

    @Test
    void theClassComesFromPartOneInRadioOrBareForm() {
        byte[] radio = Pdu.deliver().build();
        assertEquals(ClassId.NORMAL, ReleaseEligibility.classify(new byte[][] {radio}));
        assertEquals(ClassId.NORMAL, ReleaseEligibility.classify(new byte[][] {Pdu.bare(radio)}));
        assertEquals(ClassId.FLASH, ReleaseEligibility.classify(new byte[][] {Pdu.deliver().dcs(0x10).build()}));
    }

    @Test
    void aSilentMessageIsClassifiedAndNotReleasable() {
        ClassId c = ReleaseEligibility.classify(new byte[][] {Pdu.deliver().pid(0x40).build()});
        assertEquals(ClassId.SILENT, c);
        assertFalse(ReleaseEligibility.releasable(c));
    }

    @Test
    void anUnreadablePartGivesNoClass() {
        assertNull(ReleaseEligibility.classify(null));
        assertNull(ReleaseEligibility.classify(new byte[0][]));
        assertNull(ReleaseEligibility.classify(new byte[][] {null}));
        // {0x00, 0x04}: the radio read leaves one TPDU octet (AN-PDU-SHORT). Known damage is never re-injected.
        assertNull(ReleaseEligibility.classify(new byte[][] {{0x00, 0x04}}));
        assertFalse(ReleaseEligibility.releasable(ReleaseEligibility.classify(new byte[][] {{0x00, 0x04}})));
        // Synthetic scenarios.json anomaly-addr-trunc.
        assertNull(ReleaseEligibility.classify(new byte[][] {hex("059151551000040C915155")}));
    }

    @Test
    void UX2_FW_02_aHeldUnreadableSmsCanBeReleased() {
        // Synthetic scenarios.json anomaly-mti: TP-MTI 3. The parse is invalid, but AOSP reads it as an SMS-DELIVER.
        byte[] mti3 = hex("059151551000070891515510240000621150214365220ED4F29C0E6A97E7F3F0B90C8A01");
        ClassId c = ReleaseEligibility.classify(new byte[][] {mti3});
        assertEquals(ClassId.UNREADABLE_SMS, c);
        assertTrue(ReleaseEligibility.releasable(c));
        // {0x01, 0x02}: TP-MTI 1 and no known damage.
        assertEquals(ClassId.UNREADABLE_SMS, ReleaseEligibility.classify(new byte[][] {{0x01, 0x02}}));
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    /** The payload answer is the shared:rules table for every class. */
    @Test
    void releasableAgreesWithTheSharedTableForEveryClass() {
        for (dev.nightjar.contract.ClassId c : dev.nightjar.contract.ClassId.values()) {
            org.junit.jupiter.api.Assertions.assertEquals(dev.nightjar.rules.EnforcementMatrix.releasable(c),
                    ReleaseEligibility.releasable(c), c.name());
        }
        org.junit.jupiter.api.Assertions.assertFalse(ReleaseEligibility.releasable(null));
        org.junit.jupiter.api.Assertions.assertFalse(
                ReleaseEligibility.releasable(dev.nightjar.contract.ClassId.MALFORMED_SMS));
        org.junit.jupiter.api.Assertions.assertTrue(
                ReleaseEligibility.releasable(dev.nightjar.contract.ClassId.ME_DOWNLOAD));
    }
}
