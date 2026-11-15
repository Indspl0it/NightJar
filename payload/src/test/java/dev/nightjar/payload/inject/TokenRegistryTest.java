package dev.nightjar.payload.inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.payload.capture.InjectionTokens;
import dev.nightjar.payload.env.Clock;
import org.junit.jupiter.api.Test;

/** One-time tokens and their expiry. */
class TokenRegistryTest {

    static final class FakeClock implements Clock {
        long ns;

        @Override
        public long elapsedNs() {
            return ns;
        }

        @Override
        public long wallMs() {
            return 1_762_330_000_000L + ns / 1_000_000L;
        }
    }

    @Test
    void QUA_02_releaseTokenIsConsumedOnce() {
        FakeClock clock = new FakeClock();
        TokenRegistry tokens = new TokenRegistry(clock);
        tokens.registerRelease("k1", "k1", "event-1");

        assertEquals(InjectionTokens.Kind.RELEASE, tokens.peek("k1", null));
        InjectionTokens.Match match = tokens.consume("k1");
        assertNotNull(match);
        assertEquals(InjectionTokens.Kind.RELEASE, match.kind());
        assertEquals("event-1", match.releaseOf());
        // The correlator's match is one-time, but the token stays readable until its TTL so
        // the head filter (which runs after the worker drained the observation) still sees it.
        assertNull(tokens.consume("k1"));
        assertEquals(InjectionTokens.Kind.RELEASE, tokens.peek("k1", null));
        tokens.discard("k1", null);
        assertNull(tokens.peek("k1", null));
    }

    @Test
    void QUA_02_bothKeySpellingsAreRegistered() {
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        tokens.registerRelease("stripped", "raw", "e");
        assertEquals(InjectionTokens.Kind.RELEASE, tokens.peek("raw", null));
        assertNotNull(tokens.consume("raw"));
        assertNull(tokens.consume("stripped"));
    }

    @Test
    void QUA_02_releaseTokenExpiresAfterSixtySeconds() {
        FakeClock clock = new FakeClock();
        TokenRegistry tokens = new TokenRegistry(clock);
        tokens.registerRelease("k", null, "e");
        clock.ns = TokenRegistry.RELEASE_TTL_NS - 1;
        assertEquals(InjectionTokens.Kind.RELEASE, tokens.peek("k", null));
        clock.ns = TokenRegistry.RELEASE_TTL_NS + 1;
        assertNull(tokens.peek("k", null));
        assertNull(tokens.consume("k"));
    }

    @Test
    void TST_02_canaryTokenCarriesRunAndClass() {
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        tokens.registerCanary("k", null, "run-9", "CL-FLASH");
        assertTrue(tokens.hasPendingCanary());
        InjectionTokens.Match match = tokens.consume("k");
        assertNotNull(match);
        assertEquals(InjectionTokens.Kind.CANARY, match.kind());
        assertEquals("run-9", match.runId());
        assertEquals("CL-FLASH", match.canaryClass());
        assertFalse(tokens.hasPendingCanary());
    }

    @Test
    void TST_02_discardDropsAnUnusedToken() {
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        tokens.registerCanary("k", null, "run", "CL-SIM-DD");
        tokens.discard("k", null);
        assertNull(tokens.consume("k"));
        assertFalse(tokens.hasPendingCanary());
    }

    @Test
    void UX2_FW_06_aReleaseOfAClassStaysPendingUntilItsTtl() {
        FakeClock clock = new FakeClock();
        TokenRegistry tokens = new TokenRegistry(clock);
        assertFalse(tokens.hasPendingRelease("CL-MWI"));
        tokens.registerRelease("k", null, "e", "CL-MWI");
        assertTrue(tokens.hasPendingRelease("CL-MWI"));
        assertFalse(tokens.hasPendingRelease("CL-NORMAL"));
        assertFalse(tokens.hasPendingCanary("CL-MWI"), "a release is not a canary");
        assertNotNull(tokens.consume("k"));
        assertTrue(tokens.hasPendingRelease("CL-MWI"), "the correlator can consume before HK-23 runs");
        clock.ns = TokenRegistry.RELEASE_TTL_NS + 1;
        assertFalse(tokens.hasPendingRelease("CL-MWI"));
        assertFalse(tokens.hasPendingRelease(null));
    }

    @Test
    void UX2_FW_06_aReleaseWithoutAClassIsNeverPendingForAClass() {
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        tokens.registerRelease("k", null, "e");
        assertFalse(tokens.hasPendingRelease("CL-MWI"));
        assertEquals(InjectionTokens.Kind.RELEASE, tokens.peek("k", null));
    }

    @Test
    void TD_14_unknownKeysAreNotOurs() {
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        assertNull(tokens.peek(null, null));
        assertNull(tokens.consume(null));
        assertNull(tokens.peek("other", "other2"));
    }

    @Test
    void TD_14_clearDropsEverything() {
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        tokens.registerRelease("a", null, "e1");
        tokens.registerDebug("b", null);
        assertEquals(2, tokens.size());
        tokens.clear();
        assertEquals(0, tokens.size());
        assertNull(tokens.consume("a"));
    }

    @Test
    void TD_14_peekDoesNotConsume() {
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        tokens.registerCanary("k", null, "run", "CL-WAP-CP");
        assertEquals(InjectionTokens.Kind.CANARY, tokens.peek("k", null));
        assertEquals(InjectionTokens.Kind.CANARY, tokens.peek("k", null));
        assertNotNull(tokens.consume("k"));
    }
}
