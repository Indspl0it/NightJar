package dev.nightjar.payload.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The wiring poll never gives up: fast at first, then backing off to a bounded interval; a failed wiring is retried. */
class WiringBackoffTest {

    @Test
    void pollsFastForTheFirstMinute() {
        assertEquals(200L, PayloadRuntime.wiringDelayMs(1));
        assertEquals(200L, PayloadRuntime.wiringDelayMs(300));
    }

    @Test
    void backsOffMonotonicallyToTheCap() {
        long previous = PayloadRuntime.wiringDelayMs(300);
        for (int attempts = 301; attempts < 400; attempts++) {
            long delay = PayloadRuntime.wiringDelayMs(attempts);
            assertTrue(delay >= 200L, "never below the fast poll");
            assertTrue(delay <= 30_000L, "bounded");
            assertTrue(delay >= previous || attempts == 301, "non-decreasing");
            previous = delay;
        }
        assertEquals(30_000L, PayloadRuntime.wiringDelayMs(Integer.MAX_VALUE / 2));
    }

    /** A failed wiring attempt is tried again, 1 s first, doubling to the cap. */
    @Test
    void aFailedWiringIsTriedAgainWithABoundedBackoff() {
        assertEquals(1_000L, PayloadRuntime.wiringRetryDelayMs(1));
        assertEquals(2_000L, PayloadRuntime.wiringRetryDelayMs(2));
        assertEquals(16_000L, PayloadRuntime.wiringRetryDelayMs(5));
        assertEquals(30_000L, PayloadRuntime.wiringRetryDelayMs(6));
        assertEquals(30_000L, PayloadRuntime.wiringRetryDelayMs(Integer.MAX_VALUE));
        assertEquals(1_000L, PayloadRuntime.wiringRetryDelayMs(0));
    }
}
