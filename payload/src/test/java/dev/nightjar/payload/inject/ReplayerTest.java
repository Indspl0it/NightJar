package dev.nightjar.payload.inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Control;
import dev.nightjar.payload.capture.InjectionTokens;
import dev.nightjar.payload.env.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A replay is injected locally, marked and linked to the original. */
class ReplayerTest {

    static final class FakeClock implements Clock {
        long ns = 1_000_000L;

        @Override
        public long elapsedNs() {
            return ns;
        }

        @Override
        public long wallMs() {
            return 1_762_330_000_000L;
        }
    }

    static class FakeInjector implements SmsInjector {
        final List<byte[]> injected = new ArrayList<>();
        final List<Integer> results = new ArrayList<>();
        int defaultResult = Control.ReleaseResult.HANDLED;
        int cursor;

        @Override
        public int inject(byte[] radioPdu, String format, int subId) {
            injected.add(radioPdu);
            int result = cursor < results.size() ? results.get(cursor) : defaultResult;
            cursor++;
            return result;
        }
    }

    private static byte[] part(int marker) {
        return new byte[] {0x04, 0x04, 0x0B, (byte) 0x91, 0x19, 0x09, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            (byte) marker, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    }

    @Test
    void replayInjectsEveryPartInOrderAndLinksToTheOriginal() {
        FakeClock clock = new FakeClock();
        TokenRegistry tokens = new TokenRegistry(clock);
        FakeInjector injector = new FakeInjector();
        Replayer replayer = new Replayer(tokens, injector, null);
        byte[] p1 = part(1);
        byte[] p2 = part(2);

        assertEquals(Control.ReleaseResult.HANDLED,
                replayer.replay("evt-9", ClassId.WAP_CP, new byte[][] {p1, p2}, "3gpp", 1));
        assertEquals(2, injector.injected.size());
        assertTrue(java.util.Arrays.equals(p1, injector.injected.get(0)));
        assertTrue(java.util.Arrays.equals(p2, injector.injected.get(1)));

        // The correlator consumes the token and marks flags.replay + flags.releaseOf.
        InjectionTokens.Match first = tokens.consume(Releaser.keys(p1)[0]);
        assertNotNull(first);
        assertEquals(InjectionTokens.Kind.REPLAY, first.kind());
        assertEquals("evt-9", first.releaseOf());
        assertNull(first.runId());
        assertNotNull(tokens.consume(Releaser.keys(p2)[0]));
    }

    @Test
    void aReplayTokenIsNotAReleaseToken() {
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        Replayer replayer = new Replayer(tokens, new FakeInjector(), null);
        byte[] p = part(3);

        assertEquals(Control.ReleaseResult.HANDLED,
                replayer.replay("evt-a", ClassId.FLASH, new byte[][] {p}, "3gpp", 0));
        InjectionTokens.Match match = tokens.consume(Releaser.keys(p)[0]);
        assertEquals(InjectionTokens.Kind.REPLAY, match.kind());
        // A replay is consumed once, so the second lookup finds nothing (no double classification).
        assertNull(tokens.consume(Releaser.keys(p)[0]));
    }

    @Test
    void invalidArgumentsAreRejectedBeforeAnyInjection() {
        FakeInjector injector = new FakeInjector();
        Replayer replayer = new Replayer(new TokenRegistry(new FakeClock()), injector, null);

        assertEquals(Control.ReleaseResult.INVALID_ARGUMENTS,
                replayer.replay("", ClassId.NORMAL, new byte[][] {part(1)}, "3gpp", 0));
        assertEquals(Control.ReleaseResult.INVALID_ARGUMENTS,
                replayer.replay("e", ClassId.NORMAL, new byte[0][], "3gpp", 0));
        assertEquals(Control.ReleaseResult.INVALID_ARGUMENTS,
                replayer.replay("e", ClassId.NORMAL, new byte[][] {new byte[0]}, "3gpp", 0));
        assertEquals(Control.ReleaseResult.INVALID_ARGUMENTS,
                replayer.replay("e", ClassId.NORMAL, new byte[][] {part(1)}, "3gpp", -1));
        assertEquals(0, injector.injected.size());
    }

    @Test
    void nonReplayableClassesAndFormatsAreRefused() {
        FakeInjector injector = new FakeInjector();
        Replayer replayer = new Replayer(new TokenRegistry(new FakeClock()), injector, null);

        // A cell broadcast cannot be reproduced by an injection.
        assertEquals(Control.ReleaseResult.NOT_RELEASABLE,
                replayer.replay("e", ClassId.CB, new byte[][] {part(1)}, "3gpp", 0));
        // Outbound classes are never replayed.
        assertEquals(Control.ReleaseResult.NOT_RELEASABLE,
                replayer.replay("e", ClassId.MO_DATA, new byte[][] {part(1)}, "3gpp", 0));
        // Only 3GPP TPDUs can be re-injected.
        assertEquals(Control.ReleaseResult.NOT_RELEASABLE,
                replayer.replay("e", ClassId.NORMAL, new byte[][] {part(1)}, "3gpp2", 0));
        assertEquals(0, injector.injected.size());
    }

    @Test
    void aFailedPartStopsTheSequenceAndDropsTheUnusedTokens() {
        FakeClock clock = new FakeClock();
        TokenRegistry tokens = new TokenRegistry(clock);
        FakeInjector injector = new FakeInjector();
        injector.results.add(Control.ReleaseResult.HANDLED);
        injector.results.add(Control.ReleaseResult.GENERIC_FAILURE);
        Replayer replayer = new Replayer(tokens, injector, null);
        byte[] p1 = part(4);
        byte[] p2 = part(5);
        byte[] p3 = part(6);

        assertEquals(Control.ReleaseResult.GENERIC_FAILURE,
                replayer.replay("evt-b", ClassId.NORMAL, new byte[][] {p1, p2, p3}, "3gpp", 0));
        assertEquals(2, injector.injected.size());
        // The part that did enter the pipeline keeps its token; the ones that did not are discarded.
        assertNotNull(tokens.consume(Releaser.keys(p1)[0]));
        assertNull(tokens.consume(Releaser.keys(p2)[0]));
        assertNull(tokens.consume(Releaser.keys(p3)[0]));
    }

    @Test
    void anInjectorThatThrowsBecomesAGenericFailure() {
        FakeInjector injector =
                new FakeInjector() {
                    @Override
                    public int inject(byte[] radioPdu, String format, int subId) {
                        throw new IllegalStateException("no dispatcher yet");
                    }
                };
        Replayer replayer = new Replayer(new TokenRegistry(new FakeClock()), injector, null);

        assertEquals(Control.ReleaseResult.GENERIC_FAILURE,
                replayer.replay("e", ClassId.NORMAL, new byte[][] {part(1)}, "3gpp", 0));
    }

    @Test
    void moreThanThirtyTwoPartsIsRejected() {
        Replayer replayer = new Replayer(new TokenRegistry(new FakeClock()), new FakeInjector(), null);
        byte[][] parts = new byte[Replayer.MAX_PARTS + 1][];
        for (int i = 0; i < parts.length; i++) parts[i] = part(i);

        assertEquals(Control.ReleaseResult.INVALID_ARGUMENTS,
                replayer.replay("e", ClassId.NORMAL, parts, "3gpp", 0));
    }

    @Test
    void theSentResultCodeIsGenericFailure() {
        // Names RESULT_ERROR_GENERIC_FAILURE explicitly; the constant is checked so a refactor cannot drift.
        assertEquals(1, SentFailure.RESULT_ERROR_GENERIC_FAILURE);
        assertFalse(SentFailure.fail(null));
    }

    @Test
    void aReplayedWapPushRegistersItsPayloadKeysForHk14() {
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        Replayer replayer = new Replayer(tokens, new FakeInjector(), null);
        byte[] push = CanaryFactory.build(CanaryFactory.Canary.WAP_CP).radioPdu();
        String[] wap = Releaser.payloadKeys(push);
        assertNotNull(wap);

        assertEquals(Control.ReleaseResult.HANDLED,
                replayer.replay("evt-w", ClassId.WAP_CP, new byte[][] {push}, "3gpp", 0));
        InjectionTokens.Match m = tokens.consume(wap[0]);
        assertNotNull(m, "HK-14 must find the replay token over the WAP payload");
        assertEquals(InjectionTokens.Kind.REPLAY, m.kind());
    }

    @Test
    void aFailedWapReplayDropsThePayloadKeysButATimeoutKeepsThem() {
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        FakeInjector injector = new FakeInjector();
        Replayer replayer = new Replayer(tokens, injector, null);
        byte[] push = CanaryFactory.build(CanaryFactory.Canary.WAP_CP).radioPdu();
        String[] wap = Releaser.payloadKeys(push);

        injector.defaultResult = Control.ReleaseResult.GENERIC_FAILURE;
        replayer.replay("evt-f", ClassId.WAP_CP, new byte[][] {push}, "3gpp", 0);
        assertNull(tokens.consume(wap[0]));
        assertNull(tokens.consume(Releaser.keys(push)[0]));

        injector.defaultResult = Control.ReleaseResult.TIMEOUT;
        replayer.replay("evt-t", ClassId.WAP_CP, new byte[][] {push}, "3gpp", 0);
        assertNotNull(tokens.consume(wap[0]), "P-36s: the injection can still land after a timeout");
    }
}
