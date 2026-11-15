package dev.nightjar.payload.inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Control;
import dev.nightjar.payload.env.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Release ordering (05), the non-releasable guard and the self-test engine. */
class ReleaserTest {

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

    /** Records every injected PDU and answers with a scripted result sequence. */
    static final class FakeInjector implements SmsInjector {
        final List<byte[]> injected = new ArrayList<>();
        final List<Integer> results = new ArrayList<>();
        final List<Integer> subs = new ArrayList<>();
        int defaultResult = Control.ReleaseResult.HANDLED;
        int cursor;

        @Override
        public int inject(byte[] radioPdu, String format, int subId) {
            injected.add(radioPdu);
            subs.add(subId);
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
    void QUA_02_tokensAreRegisteredBeforeTheFirstInjection() {
        FakeClock clock = new FakeClock();
        TokenRegistry tokens = new TokenRegistry(clock);
        FakeInjector injector = new FakeInjector();
        Releaser releaser = new Releaser(tokens, injector, null);
        byte[] p1 = part(1);
        byte[] p2 = part(2);

        assertEquals(Control.ReleaseResult.HANDLED,
                releaser.release("evt", ClassId.WAP_CP, new byte[][] {p1, p2}, "3gpp", 0));
        assertEquals(2, injector.injected.size());
        // Both keys were registered for both parts, so a fast pipeline still matches.
        assertNotNull(tokens.consume(Releaser.keys(p1)[0]));
        assertNotNull(tokens.consume(Releaser.keys(p2)[0]));
    }

    @Test
    void QUA_05_partsAreInjectedInOrder() {
        FakeInjector injector = new FakeInjector();
        Releaser releaser = new Releaser(new TokenRegistry(new FakeClock()), injector, null);
        byte[] p1 = part(1);
        byte[] p2 = part(2);
        byte[] p3 = part(3);
        releaser.release("e", ClassId.WAP_CP, new byte[][] {p1, p2, p3}, "3gpp", 1);
        assertEquals(3, injector.injected.size());
        assertEquals(p1, injector.injected.get(0));
        assertEquals(p2, injector.injected.get(1));
        assertEquals(p3, injector.injected.get(2));
    }

    @Test
    void QUA_04_theFirstFailureIsReturnedAndStopsTheSequence() {
        FakeInjector injector = new FakeInjector();
        injector.results.add(Control.ReleaseResult.HANDLED);
        injector.results.add(Control.ReleaseResult.GENERIC_FAILURE);
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        Releaser releaser = new Releaser(tokens, injector, null);
        byte[] p1 = part(1);
        byte[] p2 = part(2);
        byte[] p3 = part(3);

        assertEquals(Control.ReleaseResult.GENERIC_FAILURE,
                releaser.release("e", ClassId.WAP_CP, new byte[][] {p1, p2, p3}, "3gpp", 0));
        assertEquals(2, injector.injected.size());
        // The failed part and the untouched part lost their tokens; the injected part kept its.
        assertNotNull(tokens.consume(Releaser.keys(p1)[0]));
        assertNull(tokens.consume(Releaser.keys(p2)[0]));
        assertNull(tokens.consume(Releaser.keys(p3)[0]));
    }

    @Test
    void QUA_04_timeoutIsPropagated() {
        FakeInjector injector = new FakeInjector();
        injector.defaultResult = Control.ReleaseResult.TIMEOUT;
        Releaser releaser = new Releaser(new TokenRegistry(new FakeClock()), injector, null);
        assertEquals(Control.ReleaseResult.TIMEOUT,
                releaser.release("e", ClassId.FLASH, new byte[][] {part(1)}, "3gpp", 0));
    }

    @Test
    void QUA_07_nonReleasableClassesAreRejected() {
        FakeInjector injector = new FakeInjector();
        Releaser releaser = new Releaser(new TokenRegistry(new FakeClock()), injector, null);
        assertEquals(Control.ReleaseResult.NOT_RELEASABLE,
                releaser.release("e", ClassId.SIM_DD, new byte[][] {part(1)}, "3gpp", 0));
        assertEquals(Control.ReleaseResult.NOT_RELEASABLE,
                releaser.release("e", ClassId.CB, new byte[][] {part(1)}, "3gpp", 0));
        assertEquals(Control.ReleaseResult.NOT_RELEASABLE,
                releaser.release("e", ClassId.FLASH, new byte[][] {part(1)}, "cb", 0));
        assertTrue(injector.injected.isEmpty());
    }

    @Test
    void UX2_FW_02_aHeldUnreadableSmsIsReinjectedWithoutAParse() {
        // Synthetic scenarios.json anomaly-mti: TP-MTI 3, the parse is invalid. The release path only hashes the PDU
        // (keys) and skips the WAP keys of a PDU that does not parse, so nothing throws and the raw PDU goes out.
        byte[] mti3 = hex("059151551000070891515510240000621150214365220ED4F29C0E6A97E7F3F0B90C8A01");
        byte[][] pdus = {mti3};
        ClassId c = dev.nightjar.payload.firewall.ReleaseEligibility.classify(pdus);
        assertEquals(ClassId.UNREADABLE_SMS, c);
        assertNull(Releaser.payloadKeys(mti3));
        FakeInjector injector = new FakeInjector();
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        Releaser releaser = new Releaser(tokens, injector, null);
        assertEquals(Control.ReleaseResult.HANDLED, releaser.release("e", c, pdus, "3gpp", 0));
        assertEquals(1, injector.injected.size());
        assertEquals(mti3, injector.injected.get(0));
        assertNotNull(tokens.consume(Releaser.keys(mti3)[0]));
        // Known damage is not re-injected.
        byte[][] cut = {hex("059151551000040C915155")};
        assertEquals(Control.ReleaseResult.NOT_RELEASABLE,
                releaser.release("e", dev.nightjar.payload.firewall.ReleaseEligibility.classify(cut), cut, "3gpp", 0));
        assertEquals(1, injector.injected.size());
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    @Test
    void QUA_02_invalidArgumentsAreRejectedBeforeAnyToken() {
        FakeInjector injector = new FakeInjector();
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        Releaser releaser = new Releaser(tokens, injector, null);
        assertEquals(Control.ReleaseResult.INVALID_ARGUMENTS,
                releaser.release(null, ClassId.FLASH, new byte[][] {part(1)}, "3gpp", 0));
        assertEquals(Control.ReleaseResult.INVALID_ARGUMENTS,
                releaser.release("e", ClassId.FLASH, new byte[0][], "3gpp", 0));
        assertEquals(Control.ReleaseResult.INVALID_ARGUMENTS,
                releaser.release("e", ClassId.FLASH, new byte[][] {part(1)}, "3gpp", -2));
        assertEquals(Control.ReleaseResult.INVALID_ARGUMENTS,
                releaser.release("e", ClassId.FLASH, new byte[][] {null}, "3gpp", 0));
        assertEquals(0, tokens.size());
        assertTrue(injector.injected.isEmpty());
    }

    @Test
    void TST_02_selfTestRefusesWhileLocked() {
        SelfTestRunner runner = new SelfTestRunner(new TokenRegistry(new FakeClock()), new FakeInjector(),
                SelfTestRunner.HookStates.ALL, () -> false, () -> 0, new FakeClock(), null);
        assertEquals(Control.SelfTestError.PREFIX + Control.SelfTestError.LOCKED, runner.run("[\"FLASH\"]"));
    }

    @Test
    void TST_02_selfTestInjectsEveryRequestedClassAndRegistersTokens() {
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        FakeInjector injector = new FakeInjector();
        SelfTestRunner runner = new SelfTestRunner(tokens, injector, SelfTestRunner.HookStates.ALL, () -> true,
                () -> 0, new FakeClock(), null);

        String runId = runner.run("[\"FLASH\",\"WAP_SI\",\"MWI\"]");
        assertFalse(runId.startsWith(Control.SelfTestError.PREFIX));
        assertEquals(3, injector.injected.size());
        assertEquals(SelfTestRunner.INJECTED, runner.lastStatus().get("FLASH"));
        assertEquals(SelfTestRunner.INJECTED, runner.lastStatus().get("MWI"));
        assertTrue(tokens.size() > 0);
        assertFalse(runner.running());
    }

    @Test
    void TST_02_missingHookSkipsTheCanaryInsteadOfInjectingIt() {
        FakeInjector injector = new FakeInjector();
        SelfTestRunner runner = new SelfTestRunner(new TokenRegistry(new FakeClock()), injector,
                hook -> !hook.equals("HK-14"), () -> true, () -> 0, new FakeClock(), null);

        runner.run("[\"WAP_SI\",\"FLASH\"]");
        assertEquals(SelfTestRunner.SKIPPED + "HK-14", runner.lastStatus().get("WAP_SI"));
        assertEquals(SelfTestRunner.INJECTED, runner.lastStatus().get("FLASH"));
        assertEquals(1, injector.injected.size());
    }

    @Test
    void TST_02_invalidClassListIsRejected() {
        SelfTestRunner runner = new SelfTestRunner(new TokenRegistry(new FakeClock()), new FakeInjector(),
                SelfTestRunner.HookStates.ALL, () -> true, () -> 0, new FakeClock(), null);
        assertEquals(Control.SelfTestError.PREFIX + Control.SelfTestError.INVALID, runner.run("[]"));
        assertEquals(Control.SelfTestError.PREFIX + Control.SelfTestError.INVALID, runner.run("[\"NOPE\"]"));
        assertEquals(Control.SelfTestError.PREFIX + Control.SelfTestError.INVALID, runner.run("not json"));
        assertEquals(Control.SelfTestError.PREFIX + Control.SelfTestError.INVALID, runner.run(null));
        assertEquals(Control.SelfTestError.PREFIX + Control.SelfTestError.INVALID,
                runner.run("{\"classes\":[\"FLASH\",5]}"));
    }

    @Test
    void TST_02_objectFormIsAccepted() {
        FakeInjector injector = new FakeInjector();
        SelfTestRunner runner = new SelfTestRunner(new TokenRegistry(new FakeClock()), injector,
                SelfTestRunner.HookStates.ALL, () -> true, () -> 0, new FakeClock(), null);
        String runId = runner.run("{\"classes\":[\"SIM_DD\",\"FLASH\"]}");
        assertFalse(runId.startsWith(Control.SelfTestError.PREFIX));
        assertEquals(2, injector.injected.size());
    }

    @Test
    void TST_02_injectionFailureIsReportedAndTheTokenDropped() {
        FakeInjector injector = new FakeInjector();
        injector.defaultResult = Control.ReleaseResult.GENERIC_FAILURE;
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        SelfTestRunner runner = new SelfTestRunner(tokens, injector, SelfTestRunner.HookStates.ALL, () -> true,
                () -> 0, new FakeClock(), null);
        runner.run("[\"FLASH\"]");
        assertEquals(SelfTestRunner.FAILED + Control.ReleaseResult.GENERIC_FAILURE,
                runner.lastStatus().get("FLASH"));
        assertEquals(0, tokens.size());
    }

    @Test
    void TST_02_cancelStopsFurtherInjections() {
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        List<byte[]> injected = new ArrayList<>();
        SelfTestRunner[] holder = new SelfTestRunner[1];
        SmsInjector injector = (pdu, format, subId) -> {
            injected.add(pdu);
            holder[0].cancel(holder[0].currentRunId());
            return Control.ReleaseResult.HANDLED;
        };
        holder[0] = new SelfTestRunner(tokens, injector, SelfTestRunner.HookStates.ALL, () -> true, () -> 0,
                new FakeClock(), null);
        holder[0].run("[\"FLASH\",\"REPLACE\",\"WAP_CP\"]");
        assertEquals(1, injected.size());
    }

    @Test
    void TST_02_aSecondRunIsBusyWhileTheFirstIsInjecting() {
        SelfTestRunner[] holder = new SelfTestRunner[1];
        List<String> answers = new ArrayList<>();
        SmsInjector injector = (pdu, format, subId) -> {
            answers.add(holder[0].run("[\"FLASH\"]"));
            return Control.ReleaseResult.HANDLED;
        };
        holder[0] = new SelfTestRunner(new TokenRegistry(new FakeClock()), injector,
                SelfTestRunner.HookStates.ALL, () -> true, () -> 0, new FakeClock(), null);
        holder[0].run("[\"FLASH\",\"REPLACE\"]");
        assertEquals(Control.SelfTestError.PREFIX + Control.SelfTestError.BUSY, answers.get(0));
    }

    @Test
    void TST_04_statusJsonIsContentFree() {
        SelfTestRunner runner = new SelfTestRunner(new TokenRegistry(new FakeClock()), new FakeInjector(),
                SelfTestRunner.HookStates.ALL, () -> true, () -> 0, new FakeClock(), null);
        runner.run("[\"FLASH\"]");
        String json = runner.lastStatusJson();
        assertTrue(json.contains("\"FLASH\":\"INJECTED\""));
        assertEquals(List.of(ClassId.FLASH), runner.lastClasses());
    }

    @Test
    void aFailedWapReleaseDropsThePayloadKeysToo() {
        TokenRegistry tokens = new TokenRegistry(new ReplayerTest.FakeClock());
        ReplayerTest.FakeInjector injector = new ReplayerTest.FakeInjector();
        injector.defaultResult = Control.ReleaseResult.GENERIC_FAILURE;
        Releaser releaser = new Releaser(tokens, injector, null);
        byte[] push = CanaryFactory.build(CanaryFactory.Canary.WAP_CP).radioPdu();
        String[] wap = Releaser.payloadKeys(push);
        assertNotNull(wap);

        assertEquals(Control.ReleaseResult.GENERIC_FAILURE,
                releaser.release("evt-w", ClassId.WAP_CP, new byte[][] {push}, "3gpp", 0));
        assertNull(tokens.consume(wap[0]), "no RELEASE token may stay on the WAP payload");
        if (wap[1] != null) assertNull(tokens.consume(wap[1]));
    }

    /** subId -1 is the default SMS subscription; with no default the release is rejected before a token. */
    @Test
    void subscriptionMinusOneResolvesToTheDefault() {
        FakeInjector injector = new FakeInjector();
        TokenRegistry tokens = new TokenRegistry(new FakeClock());
        Releaser releaser = new Releaser(tokens, injector, null);
        releaser.setDefaultSub(() -> 3);
        assertEquals(Control.ReleaseResult.HANDLED,
                releaser.release("e", ClassId.FLASH, new byte[][] {part(1)}, "3gpp", -1));
        assertEquals(List.of(3), injector.subs);

        FakeInjector none = new FakeInjector();
        TokenRegistry noTokens = new TokenRegistry(new FakeClock());
        Releaser noDefault = new Releaser(noTokens, none, null);
        noDefault.setDefaultSub(() -> -1);
        assertEquals(Control.ReleaseResult.INVALID_ARGUMENTS,
                noDefault.release("e", ClassId.FLASH, new byte[][] {part(1)}, "3gpp", -1));
        assertEquals(0, noTokens.size());
        assertTrue(none.injected.isEmpty());
    }
}
