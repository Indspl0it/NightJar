package dev.nightjar.pduparse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** AN-CONCAT-DUP bookkeeping. The sender strings are synthetic. */
class ConcatTrackerTest {
    private static final String SENDER = "+15550142";

    @Test
    void secondCopyOfAPartIsADuplicate() {
        ConcatTracker t = new ConcatTracker();
        assertFalse(t.seen(SENDER, 7, 3, 1, 0));
        assertFalse(t.seen(SENDER, 7, 3, 2, 10));
        assertTrue(t.seen(SENDER, 7, 3, 1, 20));
        assertEquals(1, t.size());
    }

    @Test
    void groupKeyIsSenderReferenceAndTotal() {
        ConcatTracker t = new ConcatTracker();
        assertFalse(t.seen(SENDER, 7, 3, 1, 0));
        assertFalse(t.seen("+15550143", 7, 3, 1, 0));
        assertFalse(t.seen(SENDER, 8, 3, 1, 0));
        assertFalse(t.seen(SENDER, 7, 2, 1, 0));
        assertFalse(t.seen(null, 7, 3, 1, 0));
        assertEquals(5, t.size());
    }

    @Test
    void groupExpiresAfterTheWindow() {
        ConcatTracker t = new ConcatTracker(10, 1_000);
        assertFalse(t.seen(SENDER, 7, 2, 1, 0));
        assertTrue(t.seen(SENDER, 7, 2, 1, 1_000), "inside the window");
        assertFalse(t.seen(SENDER, 7, 2, 1, 1_001), "after the window the group starts again");
    }

    @Test
    void oldestGroupIsEvictedFirst() {
        ConcatTracker t = new ConcatTracker(2, ConcatTracker.DEFAULT_WINDOW_MS);
        t.seen(SENDER, 1, 2, 1, 0);
        t.seen(SENDER, 2, 2, 1, 0);
        t.seen(SENDER, 3, 2, 1, 0);
        assertEquals(2, t.size());
        assertFalse(t.seen(SENDER, 1, 2, 1, 0), "group 1 was evicted");
        assertTrue(t.seen(SENDER, 3, 2, 1, 0));
    }

    @Test
    void invalidSequenceNumbersAreIgnored() {
        ConcatTracker t = new ConcatTracker();
        assertFalse(t.seen(SENDER, 7, 3, 0, 0));
        assertFalse(t.seen(SENDER, 7, 3, 0, 0));
        assertFalse(t.seen(SENDER, 7, 3, 256, 0));
        assertFalse(t.seen(SENDER, 7, 0, 1, 0));
        assertEquals(0, t.size());
        t.seen(SENDER, 7, 3, 255, 0);
        assertTrue(t.seen(SENDER, 7, 3, 255, 0), "sequence 255 is the last bit of the set");
    }

    @Test
    void singlePartMessagesAreNotTracked() {
        ConcatTracker t = new ConcatTracker();
        TpduFacts single = TpduParser.parseRadio(AnomalyFixturesTest.hex(
                "059151551000040891515510240000621150214365220ED4F29C0E6A97E7F3F0B90C8A01"));
        assertEquals(Anomalies.NONE, t.check(single, 0));
        assertEquals(Anomalies.NONE, t.check(single, 0));
        assertEquals(0, t.size());
        assertEquals(Anomalies.NONE, t.check(null, 0));
    }
}
