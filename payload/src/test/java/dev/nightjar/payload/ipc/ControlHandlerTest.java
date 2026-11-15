package dev.nightjar.payload.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.Control;
import dev.nightjar.payload.log.PLog;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code IPayloadControl} semantics, caller check and the oneway table. */
class ControlHandlerTest {

    static final class RecordingTarget implements ControlTarget {
        final List<String> calls = new ArrayList<>();
        int releaseResult = Control.ReleaseResult.HANDLED;
        int replayResult = Control.ReleaseResult.HANDLED;
        String runId = "run-1";
        boolean throwOnRelease;
        boolean throwOnReplay;

        @Override
        public void updateRules(String json) {
            calls.add("updateRules:" + json);
        }

        @Override
        public void updateSettings(String json) {
            calls.add("updateSettings:" + json);
        }

        @Override
        public void setSafeMode(boolean on) {
            calls.add("safeMode:" + on);
        }

        @Override
        public void enableFirewall(boolean on) {
            calls.add("firewall:" + on);
        }

        @Override
        public int release(String eventId, byte[][] pdus, String format, int subId) {
            if (throwOnRelease) throw new IllegalStateException("boom");
            calls.add("release:" + eventId + ":" + pdus.length + ":" + format + ":" + subId);
            return releaseResult;
        }

        @Override
        public int replay(String eventId, byte[][] pdus, String format, int subId) {
            if (throwOnReplay) throw new IllegalStateException("boom");
            calls.add("replay:" + eventId + ":" + pdus.length + ":" + format + ":" + subId);
            return replayResult;
        }

        @Override
        public String runSelfTest(String classesJson) {
            calls.add("selfTest:" + classesJson);
            return runId;
        }

        @Override
        public void cancelSelfTest(String runId) {
            calls.add("cancel:" + runId);
        }

        @Override
        public String hookHealthJson() {
            return "{\"hooks\":{}}";
        }

        @Override
        public String logJson(long sinceSeq) {
            return "[{\"seq\":" + (sinceSeq + 1) + "}]";
        }

        @Override
        public long pingMs() {
            return 42L;
        }
    }

    private static ControlHandler handler(RecordingTarget target) {
        return new ControlHandler(target, null);
    }

    @Test
    void IPC_05_updateRulesAndSettingsReachTheTarget() {
        RecordingTarget target = new RecordingTarget();
        ControlHandler h = handler(target);
        h.updateRules("{\"schema\":1}");
        h.updateSettings("{\"schema\":1}");
        h.setSafeMode(true);
        h.enableFirewall(false);
        assertEquals(List.of("updateRules:{\"schema\":1}", "updateSettings:{\"schema\":1}", "safeMode:true",
                "firewall:false"), target.calls);
    }

    @Test
    void IPC_05_emptyBodiesAreIgnored() {
        RecordingTarget target = new RecordingTarget();
        ControlHandler h = handler(target);
        h.updateRules(null);
        h.updateRules("");
        h.updateSettings(null);
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void IPC_05_releaseValidatesArgumentsBeforeInjection() {
        RecordingTarget target = new RecordingTarget();
        ControlHandler h = handler(target);
        assertEquals(Control.ReleaseResult.INVALID_ARGUMENTS, h.release(null, new byte[][] {{1}}, "3gpp", 0));
        assertEquals(Control.ReleaseResult.INVALID_ARGUMENTS, h.release("e", new byte[0][], "3gpp", 0));
        assertEquals(Control.ReleaseResult.INVALID_ARGUMENTS, h.release("e", new byte[][] {{1}}, "3gpp", -2));
        assertEquals(Control.ReleaseResult.INVALID_ARGUMENTS, h.release("e", new byte[][] {null}, "3gpp", 0));
        assertTrue(target.calls.isEmpty());
    }

    @Test
    void IPC_05_releasePassesThroughTheTargetResult() {
        RecordingTarget target = new RecordingTarget();
        target.releaseResult = Control.ReleaseResult.NOT_RELEASABLE;
        ControlHandler h = handler(target);
        assertEquals(Control.ReleaseResult.NOT_RELEASABLE, h.release("e", new byte[][] {{1}, {2}}, "3gpp", 3));
        assertEquals(List.of("release:e:2:3gpp:3"), target.calls);
    }

    @Test
    void IPC_05_releaseFailureIsReportedNotThrown() {
        RecordingTarget target = new RecordingTarget();
        target.throwOnRelease = true;
        ControlHandler h = handler(target);
        assertEquals(Control.ReleaseResult.GENERIC_FAILURE, h.release("e", new byte[][] {{1}}, "3gpp", 0));
    }

    @Test
    void IPC_05_selfTestReturnsTheRunIdOrAnErrorCode() {
        RecordingTarget target = new RecordingTarget();
        ControlHandler h = handler(target);
        assertEquals("run-1", h.runSelfTest("[\"FLASH\"]"));
        assertEquals(Control.SelfTestError.PREFIX + Control.SelfTestError.INVALID, h.runSelfTest(null));
        target.runId = "";
        assertEquals(Control.SelfTestError.PREFIX + Control.SelfTestError.INVALID, h.runSelfTest("[\"FLASH\"]"));
    }

    @Test
    void IPC_05_healthLogAndPingNeverThrow() {
        ControlHandler h = new ControlHandler(new RecordingTarget(), null);
        assertEquals("{\"hooks\":{}}", h.hookHealthJson());
        assertEquals("[{\"seq\":6}]", h.logJson(5));
        assertEquals(42L, h.pingMs());
    }

    @Test
    void IPC_05_failingTargetFallsBackToEmptyReplies() {
        ControlTarget failing = new ControlTarget() {
            @Override
            public void updateRules(String json) {}

            @Override
            public void updateSettings(String json) {}

            @Override
            public void setSafeMode(boolean on) {}

            @Override
            public void enableFirewall(boolean on) {}

            @Override
            public int release(String eventId, byte[][] pdus, String format, int subId) {
                throw new IllegalStateException();
            }

            @Override
            public int replay(String eventId, byte[][] pdus, String format, int subId) {
                throw new IllegalStateException();
            }

            @Override
            public String runSelfTest(String classesJson) {
                throw new IllegalStateException();
            }

            @Override
            public void cancelSelfTest(String runId) {}

            @Override
            public String hookHealthJson() {
                throw new IllegalStateException();
            }

            @Override
            public String logJson(long sinceSeq) {
                throw new IllegalStateException();
            }

            @Override
            public long pingMs() {
                throw new IllegalStateException();
            }
        };
        ControlHandler h = new ControlHandler(failing, null);
        assertEquals("{}", h.hookHealthJson());
        assertEquals("[]", h.logJson(0));
        assertEquals(0L, h.pingMs());
        assertEquals(Control.SelfTestError.PREFIX + Control.SelfTestError.INVALID, h.runSelfTest("[\"FLASH\"]"));
    }

    @Test
    void PG_06_callerGateRejectsEveryUidUntilTheHandshakeResolvesIt() {
        CallerGate gate = new CallerGate(new PLog(new FixedClock(), null));
        assertFalse(gate.allowed(1001));
        gate.setAppUid(10_123);
        assertTrue(gate.allowed(10_123));
        assertFalse(gate.allowed(1001));
        gate.reset();
        assertFalse(gate.allowed(10_123));
    }

    @Test
    void C10_onewayTableMatchesTheContract() {
        assertTrue(ControlHandler.isOneway(Control.Code.UPDATE_RULES));
        assertTrue(ControlHandler.isOneway(Control.Code.UPDATE_SETTINGS));
        assertTrue(ControlHandler.isOneway(Control.Code.SET_SAFE_MODE));
        assertTrue(ControlHandler.isOneway(Control.Code.ENABLE_FIREWALL));
        assertTrue(ControlHandler.isOneway(Control.Code.CANCEL_SELF_TEST));
        assertFalse(ControlHandler.isOneway(Control.Code.RELEASE));
        assertFalse(ControlHandler.isOneway(Control.Code.RUN_SELF_TEST));
        assertFalse(ControlHandler.isOneway(Control.Code.GET_HOOK_HEALTH));
        assertFalse(ControlHandler.isOneway(Control.Code.GET_LOG));
        assertFalse(ControlHandler.isOneway(Control.Code.PING));
    }

    @Test
    void C10_releaseArgumentLimit() {
        assertTrue(ControlHandler.validRelease("e", new byte[][] {{1}}, "3gpp", 0));
        // An event without a known subscription is released on the default SMS subscription.
        assertTrue(ControlHandler.validRelease("e", new byte[][] {{1}}, "3gpp", -1));
        byte[][] tooMany = new byte[33][];
        for (int i = 0; i < tooMany.length; i++) tooMany[i] = new byte[] {1};
        assertFalse(ControlHandler.validRelease("e", tooMany, "3gpp", 0));
    }

    /** Fixed clock for log-only helpers. */
    static final class FixedClock implements dev.nightjar.payload.env.Clock {
        @Override
        public long elapsedNs() {
            return 0L;
        }

        @Override
        public long wallMs() {
            return 1_762_330_000_000L;
        }
    }
}
