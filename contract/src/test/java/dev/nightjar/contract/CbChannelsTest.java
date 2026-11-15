package dev.nightjar.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The emergency range is the whole framework PWS range 0x1100-0x18FF. */
class CbChannelsTest {
    @Test
    void G_01_emergencyRangeIsThePwsRange() {
        assertEquals(4352, CbChannels.PWS_FIRST);
        assertEquals(6399, CbChannels.PWS_LAST);
        assertFalse(CbChannels.isEmergency(4351));
        assertTrue(CbChannels.isEmergency(4352));
        // Between the ETWS and the CMAS ids: reserved for future warning types, still PWS.
        assertTrue(CbChannels.isEmergency(4360));
        assertTrue(CbChannels.isEmergency(4369));
        // Above the CMAS ids, up to the end of the PWS range.
        assertTrue(CbChannels.isEmergency(4400));
        assertTrue(CbChannels.isEmergency(6399));
        assertFalse(CbChannels.isEmergency(6400));
        assertFalse(CbChannels.isEmergency(0));
        assertFalse(CbChannels.isEmergency(-1));
    }

    @Test
    void G_01_assignedRangesLieInsideThePwsRange() {
        for (int id = CbChannels.ETWS_FIRST; id <= CbChannels.ETWS_LAST; id++) assertTrue(CbChannels.isEmergency(id));
        for (int id = CbChannels.CMAS_FIRST; id <= CbChannels.CMAS_LAST; id++) assertTrue(CbChannels.isEmergency(id));
    }
}
