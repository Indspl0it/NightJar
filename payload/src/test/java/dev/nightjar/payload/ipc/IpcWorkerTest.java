package dev.nightjar.payload.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.Ipc;
import dev.nightjar.payload.store.OutboundEvent;
import dev.nightjar.payload.store.RingBuffer;
import dev.nightjar.payload.testing.FakeClock;
import dev.nightjar.payload.testing.FakeEnv;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Batching, backoff, the handshake, certificate pinning and the worker's call
 * order, driven by fakes for the transport, the clock, the signer and the outbox.
 */
class IpcWorkerTest {

    // ------------------------------------------------------------------ fakes

    static final class FakeTarget implements AppTarget {
        String pkg = "dev.nightjar";
        String pin = "AA:BB";

        @Override
        public String appPackage() {
            return pkg;
        }

        @Override
        public String appCertSha256() {
            return pin;
        }
    }

    static final class FakeSigner implements AppSigner {
        String value = "AA:BB";

        @Override
        public String signatureSha256(String packageName) {
            return value;
        }
    }

    /** Records every call and answers through a per-method handler. */
    static final class FakeTransport implements IpcTransport {
        final List<IpcRequest> calls = new ArrayList<>();
        final Map<String, Function<IpcRequest, IpcReply>> handlers = new HashMap<>();
        IpcException.Kind failWith;
        int failCount;

        @Override
        public IpcReply call(IpcRequest request) throws IpcException {
            calls.add(request);
            if (failWith != null && failCount > 0) {
                failCount--;
                throw new IpcException(failWith, "scripted");
            }
            Function<IpcRequest, IpcReply> handler = handlers.get(request.method());
            return handler == null ? IpcReply.empty() : handler.apply(request);
        }

        long count(String method) {
            long n = 0;
            for (IpcRequest r : calls) if (r.method().equals(method)) n++;
            return n;
        }
    }

    static final class FakeConfig implements ConfigSink {
        String rules;
        String settings;
        Boolean firewall;

        @Override
        public void applyRulesJson(String json) {
            rules = json;
        }

        @Override
        public void applySettingsJson(String json) {
            settings = json;
        }

        @Override
        public void applyFirewallEnabled(boolean enabled) {
            firewall = enabled;
        }
    }

    private static HookHealthSource health() {
        return new HookHealthSource() {
            @Override
            public String hookHealthJson() {
                return "{}";
            }

            @Override
            public long droppedCount() {
                return 0L;
            }
        };
    }

    private static IpcReply helloReply(String sessionId, int version) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(Ipc.Key.IPC_VERSION, version);
        values.put(Ipc.Key.SESSION_ID, sessionId);
        values.put(Ipc.Key.RULES, "{\"schema\":1}");
        values.put(Ipc.Key.SETTINGS, "{\"schema\":1}");
        values.put(Ipc.Key.FIREWALL_ENABLED, true);
        return new IpcReply(values);
    }

    private static OutboundEvent event(String id, int revision, String json) {
        return new OutboundEvent(id, revision, json, json.length());
    }

    private static IpcWorker worker(FakeTransport transport, RingBuffer ring, FakeClock clock, FakeTarget target,
            FakeSigner signer, FakeConfig config) {
        return new IpcWorker(new FakeEnv(), target, ring, config, transport, signer, clock,
                new dev.nightjar.payload.log.PLog(clock, null), null, null, null);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void IPC_02_helloCarriesTheDocumentedExtrasAndAppliesTheReply() {
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        FakeConfig config = new FakeConfig();
        RingBuffer ring = new RingBuffer();
        FakeClock clock = new FakeClock(0);
        IpcWorker w = worker(transport, ring, clock, new FakeTarget(), new FakeSigner(), config);

        w.tick();

        IpcRequest hello = transport.calls.get(0);
        assertEquals(Ipc.Method.HELLO, hello.method());
        assertEquals(Ipc.authority("dev.nightjar"), hello.authority());
        assertEquals(Ipc.VERSION, hello.get(Ipc.Key.IPC_VERSION));
        assertEquals("1.0.0", hello.get(Ipc.Key.PAYLOAD_VERSION));
        assertEquals(new FakeEnv().fingerprint, hello.get(Ipc.Key.BUILD_FINGERPRINT));
        assertEquals(7, hello.get(Ipc.Key.BOOT_COUNT));
        assertEquals(Boolean.TRUE, hello.get(Ipc.Key.USER_UNLOCKED));
        assertTrue(w.connected());
        assertEquals("s-1", w.sessionId());
        assertEquals("{\"schema\":1}", config.rules);
        assertEquals(Boolean.TRUE, config.firewall);
        assertTrue(w.contentAllowed());
    }

    @Test
    void CROSS_02_anErrorReplyKeepsTheHealthDocumentDirty() {
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        transport.handlers.put(Ipc.Method.HOOK_HEALTH, r -> new IpcReply(Map.of(Ipc.Key.ERROR, "BUSY")));
        FakeClock clock = new FakeClock(0);
        IpcWorker w = new IpcWorker(new FakeEnv(), new FakeTarget(), new RingBuffer(), new FakeConfig(), transport,
                new FakeSigner(), clock, new dev.nightjar.payload.log.PLog(clock, null), null, null, health());
        w.tick(); // handshake
        w.tick(); // health: BUSY
        assertEquals(1, transport.count(Ipc.Method.HOOK_HEALTH));
        assertEquals(1, w.failureCount());

        transport.handlers.put(Ipc.Method.HOOK_HEALTH, r -> new IpcReply(Map.of(Ipc.Key.OK, true)));
        clock.advanceMs(Backoff.MAX_MS);
        w.tick(); // health again
        assertEquals(2, transport.count(Ipc.Method.HOOK_HEALTH));
        w.tick();
        assertEquals(2, transport.count(Ipc.Method.HOOK_HEALTH), "delivered: nothing more to push");
    }

    @Test
    void CROSS_01_helloWithoutTheFirewallKeyKeepsTheCurrentValue() {
        // Before the first unlock the app cannot read its persisted flag and leaves the key out.
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> {
            Map<String, Object> values = new LinkedHashMap<>(helloReply("s-1", 1).values());
            values.remove(Ipc.Key.FIREWALL_ENABLED);
            return new IpcReply(values);
        });
        FakeConfig config = new FakeConfig();
        IpcWorker w = worker(transport, new RingBuffer(), new FakeClock(0), new FakeTarget(), new FakeSigner(), config);
        w.tick();
        assertTrue(w.connected());
        assertNull(config.firewall, "no call: the payload keeps its own value");

        transport.handlers.put(Ipc.Method.HELLO, r -> {
            Map<String, Object> values = new LinkedHashMap<>(helloReply("s-2", 1).values());
            values.put(Ipc.Key.FIREWALL_ENABLED, false);
            return new IpcReply(values);
        });
        w.invalidateSession("test");
        w.tick();
        assertEquals(Boolean.FALSE, config.firewall, "an explicit false still applies");
    }

    @Test
    void IPC_07_lowerAppVersionIsUsedOnTheWire() {
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        FakeClock clock = new FakeClock(0);
        IpcWorker w = worker(transport, new RingBuffer(), clock, new FakeTarget(), new FakeSigner(), new FakeConfig());
        w.tick();
        assertEquals(1, w.sendVersion());
    }

    @Test
    void IPC_02_missingSessionIdIsAFailedHandshake() {
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> new IpcReply(Map.of(Ipc.Key.IPC_VERSION, 1)));
        FakeClock clock = new FakeClock(0);
        IpcWorker w = worker(transport, new RingBuffer(), clock, new FakeTarget(), new FakeSigner(), new FakeConfig());
        w.tick();
        assertFalse(w.connected());
        assertEquals(1, w.failureCount());
    }

    @Test
    void IPC_06_backoffGrowsToSixtySecondsAndResetsOnSuccess() {
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        FakeClock clock = new FakeClock(0);
        IpcWorker w = worker(transport, new RingBuffer(), clock, new FakeTarget(), new FakeSigner(), new FakeConfig());

        transport.failWith = IpcException.Kind.APP_ABSENT;
        transport.failCount = 100;
        w.tick();
        assertEquals(1, w.failureCount());
        // No call before the 1 s delay elapses.
        long before = transport.calls.size();
        w.tick();
        assertEquals(before, transport.calls.size());
        clock.advanceMs(1_000);
        w.tick();
        assertEquals(2, w.failureCount());
        clock.advanceMs(2_000);
        w.tick();
        assertEquals(3, w.failureCount());

        for (int i = 0; i < 10; i++) {
            clock.advanceMs(Backoff.MAX_MS);
            w.tick();
        }
        // Delay is capped: after the cap, one call per 60 s, never longer.
        long calls = transport.calls.size();
        clock.advanceMs(Backoff.MAX_MS);
        w.tick();
        assertEquals(calls + 1, transport.calls.size());

        transport.failWith = null;
        clock.advanceMs(Backoff.MAX_MS);
        w.tick();
        assertTrue(w.connected());
        assertEquals(0, w.failureCount());
    }

    @Test
    void IPC_06_deadObjectOnASessionCallForcesANewHandshake() {
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        transport.handlers.put(Ipc.Method.HOOK_HEALTH, r -> new IpcReply(Map.of(Ipc.Key.OK, true)));
        FakeClock clock = new FakeClock(0);
        IpcWorker w = new IpcWorker(new FakeEnv(), new FakeTarget(), new RingBuffer(), new FakeConfig(), transport,
                new FakeSigner(), clock, new dev.nightjar.payload.log.PLog(clock, null), null, null, health());
        w.tick(); // handshake
        assertTrue(w.connected());

        // The health push is the next call; it meets a dead binder.
        transport.failWith = IpcException.Kind.DEAD_OBJECT;
        transport.failCount = 1;
        w.tick();
        assertEquals(1, transport.count(Ipc.Method.HOOK_HEALTH));
        assertFalse(w.connected());
        clock.advanceMs(Backoff.MAX_MS);
        w.tick();
        assertEquals(2, transport.count(Ipc.Method.HELLO));
        assertTrue(w.connected());
    }

    @Test
    void IPC_06_invalidateSessionAppliesOnTheNextTick() {
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        FakeClock clock = new FakeClock(0);
        IpcWorker w = worker(transport, new RingBuffer(), clock, new FakeTarget(), new FakeSigner(), new FakeConfig());
        w.tick();
        assertTrue(w.connected());

        // Another thread only stores the request; the IPC thread applies it.
        w.invalidateSession("unlock");
        assertFalse(w.connected(), "a waiting reset already reads as not connected");
        assertEquals(50L, w.nextDelayMs(), "the loop wakes at once");
        assertEquals(1, transport.count(Ipc.Method.HELLO));
        w.tick();
        assertEquals(2, transport.count(Ipc.Method.HELLO));
        assertTrue(w.connected());
    }

    @Test
    void IPC_03_batchIsSentAfterCoalescingAndOnlyAckedEventsAreRemoved() {
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        transport.handlers.put(Ipc.Method.EVENTS,
                r -> new IpcReply(Map.of(Ipc.Key.ACKED, "[\"e1:1\",\"e3:1\"]")));
        RingBuffer ring = new RingBuffer();
        ring.add(event("e1", 1, "{\"eventId\":\"e1\"}"));
        ring.add(event("e2", 1, "{\"eventId\":\"e2\"}"));
        ring.add(event("e3", 1, "{\"eventId\":\"e3\"}"));
        FakeClock clock = new FakeClock(0);
        IpcWorker w = worker(transport, ring, clock, new FakeTarget(), new FakeSigner(), new FakeConfig());

        w.tick(); // handshake
        w.onEventSealed();
        w.tick();
        assertEquals(0, transport.count(Ipc.Method.EVENTS)); // still inside the 100 ms window
        clock.advanceMs(IpcWorker.COALESCE_MS);
        w.tick();

        IpcRequest events = null;
        for (IpcRequest r : transport.calls) if (r.method().equals(Ipc.Method.EVENTS)) events = r;
        assertNotNull(events);
        String batch = events.getString(Ipc.Key.BATCH);
        assertTrue(batch.contains("e1") && batch.contains("e2") && batch.contains("e3"));
        assertEquals("s-1", events.get(Ipc.Key.SESSION_ID));
        // Partial ack: e2 stays queued.
        assertEquals(1, ring.pendingCount());
        assertEquals("e2", ring.peek(1).get(0).eventId());
    }

    @Test
    void IPC_03_unknownSessionReplyRehandshakes() {
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        transport.handlers.put(Ipc.Method.EVENTS,
                r -> new IpcReply(Map.of(Ipc.Key.ERROR, Ipc.Error.UNKNOWN_SESSION)));
        RingBuffer ring = new RingBuffer();
        ring.add(event("e1", 1, "{\"eventId\":\"e1\"}"));
        FakeClock clock = new FakeClock(0);
        IpcWorker w = worker(transport, ring, clock, new FakeTarget(), new FakeSigner(), new FakeConfig());
        w.tick();
        w.onEventSealed();
        clock.advanceMs(IpcWorker.COALESCE_MS);
        w.tick();
        assertFalse(w.connected());
        assertEquals(1, ring.pendingCount());
        w.tick();
        assertEquals(2, transport.count(Ipc.Method.HELLO));
    }

    @Test
    void SEC_03_certMismatchWithholdsMessageContentButNotHealth() {
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        transport.handlers.put(Ipc.Method.HOOK_HEALTH, r -> new IpcReply(Map.of(Ipc.Key.OK, true)));
        RingBuffer ring = new RingBuffer();
        ring.add(event("e1", 1, "{\"eventId\":\"e1\"}"));
        FakeClock clock = new FakeClock(0);
        FakeSigner signer = new FakeSigner();
        signer.value = "CC:DD";
        FakeTarget target = new FakeTarget();
        IpcWorker w = new IpcWorker(new FakeEnv(), target, ring, new FakeConfig(), transport, signer, clock,
                new dev.nightjar.payload.log.PLog(clock, null), null, null, health());

        w.tick();
        assertTrue(w.connected());
        assertFalse(w.contentAllowed());
        w.onEventSealed();
        clock.advanceMs(IpcWorker.COALESCE_MS);
        w.tick();
        assertEquals(0, transport.count(Ipc.Method.EVENTS));
        assertEquals(1, ring.pendingCount());
        // Health still flows.
        w.tick();
        assertEquals(1, transport.count(Ipc.Method.HOOK_HEALTH));
    }

    @Test
    void SEC_03_missingPinWithholdsContent() {
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        FakeTarget target = new FakeTarget();
        target.pin = "";
        FakeClock clock = new FakeClock(0);
        IpcWorker w = worker(transport, new RingBuffer(), clock, target, new FakeSigner(), new FakeConfig());
        w.tick();
        assertTrue(w.connected());
        assertFalse(w.contentAllowed());
    }

    @Test
    void IPC_04_countersRespectTheIntervalAndSkipUnchangedPayloads() {
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        transport.handlers.put(Ipc.Method.COUNTERS, r -> new IpcReply(Map.of(Ipc.Key.OK, true)));
        transport.handlers.put(Ipc.Method.HOOK_HEALTH, r -> new IpcReply(Map.of(Ipc.Key.OK, true)));
        FakeClock clock = new FakeClock(0);
        dev.nightjar.payload.capture.Counters counters = new dev.nightjar.payload.capture.Counters();
        counters.increment(clock.wallMs(), 1, dev.nightjar.contract.Direction.IN,
                dev.nightjar.contract.ClassId.NORMAL);
        IpcWorker w = new IpcWorker(new FakeEnv(), new FakeTarget(), new RingBuffer(), new FakeConfig(), transport,
                new FakeSigner(), clock, new dev.nightjar.payload.log.PLog(clock, null), counters, null, health());

        w.tick(); // handshake
        w.tick(); // counters: due at once after the handshake
        w.tick(); // health
        assertEquals(1, transport.count(Ipc.Method.COUNTERS));
        w.tick();
        assertEquals(1, transport.count(Ipc.Method.COUNTERS));
        clock.advanceMs(IpcWorker.COUNTERS_INTERVAL_MS);
        w.tick();
        // The delta was delivered and nothing new was counted: no call.
        assertEquals(1, transport.count(Ipc.Method.COUNTERS));
    }

    @Test
    void IPC_03_unknownSessionOnCountersRehandshakesAndKeepsTheDeltas() {
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        transport.handlers.put(Ipc.Method.HOOK_HEALTH, r -> new IpcReply(Map.of(Ipc.Key.OK, true)));
        transport.handlers.put(Ipc.Method.COUNTERS,
                r -> new IpcReply(Map.of(Ipc.Key.ERROR, Ipc.Error.UNKNOWN_SESSION)));
        FakeClock clock = new FakeClock(0);
        dev.nightjar.payload.capture.Counters counters = new dev.nightjar.payload.capture.Counters();
        counters.increment(clock.wallMs(), 1, dev.nightjar.contract.Direction.IN,
                dev.nightjar.contract.ClassId.NORMAL);
        IpcWorker w = new IpcWorker(new FakeEnv(), new FakeTarget(), new RingBuffer(), new FakeConfig(), transport,
                new FakeSigner(), clock, new dev.nightjar.payload.log.PLog(clock, null), counters, null, health());

        w.tick(); // handshake
        w.tick(); // counters (due before health): the app answers UNKNOWN_SESSION
        assertFalse(w.connected());
        assertEquals(1, counters.total(), "the delta is back in the map");
        w.tick(); // new handshake
        assertEquals(2, transport.count(Ipc.Method.HELLO));
        assertTrue(w.connected());

        transport.handlers.put(Ipc.Method.COUNTERS, r -> new IpcReply(Map.of(Ipc.Key.OK, true)));
        w.tick(); // counters again
        IpcRequest last = null;
        for (IpcRequest r : transport.calls) if (r.method().equals(Ipc.Method.COUNTERS)) last = r;
        assertNotNull(last);
        assertTrue(last.getString(Ipc.Key.COUNTERS).contains("\"n\":1"), last.getString(Ipc.Key.COUNTERS));
        assertEquals(0, counters.total());
    }

    @Test
    void IPC_04_lockedCountersReplyKeepsTheDeltasWithoutABackoff() {
        // Before the first unlock the app answers LOCKED; the deltas must survive it.
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        transport.handlers.put(Ipc.Method.HOOK_HEALTH, r -> new IpcReply(Map.of(Ipc.Key.OK, true)));
        transport.handlers.put(Ipc.Method.COUNTERS, r -> new IpcReply(Map.of(Ipc.Key.ERROR, Ipc.Error.LOCKED)));
        FakeClock clock = new FakeClock(0);
        dev.nightjar.payload.capture.Counters counters = new dev.nightjar.payload.capture.Counters();
        counters.increment(clock.wallMs(), 1, dev.nightjar.contract.Direction.IN,
                dev.nightjar.contract.ClassId.NORMAL);
        IpcWorker w = new IpcWorker(new FakeEnv(), new FakeTarget(), new RingBuffer(), new FakeConfig(), transport,
                new FakeSigner(), clock, new dev.nightjar.payload.log.PLog(clock, null), counters, null, health());

        w.tick(); // handshake
        w.tick(); // counters: LOCKED
        assertTrue(w.connected());
        assertEquals(1, counters.total());
        assertEquals(0, w.failureCount(), "LOCKED is not a failure");
        w.tick(); // health still flows at once
        assertEquals(1, transport.count(Ipc.Method.HOOK_HEALTH));

        transport.handlers.put(Ipc.Method.COUNTERS, r -> new IpcReply(Map.of(Ipc.Key.OK, true)));
        clock.advanceMs(IpcWorker.COUNTERS_INTERVAL_MS);
        w.tick();
        assertEquals(2, transport.count(Ipc.Method.COUNTERS));
        assertEquals(0, counters.total());
    }

    @Test
    void IPC_03_stagingUnavailableKeepsTheSingleEventQueued() {
        // An error reply acknowledges nothing; a one-event batch is not poison.
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        transport.handlers.put(Ipc.Method.EVENTS,
                r -> new IpcReply(Map.of(Ipc.Key.ERROR, Ipc.Error.STAGING_UNAVAILABLE)));
        RingBuffer ring = new RingBuffer();
        ring.add(event("e1", 1, "{\"eventId\":\"e1\"}"));
        FakeClock clock = new FakeClock(0);
        IpcWorker w = worker(transport, ring, clock, new FakeTarget(), new FakeSigner(), new FakeConfig());
        w.tick();
        for (int i = 0; i < IpcWorker.POISON_REFUSALS + 2; i++) {
            w.onEventSealed();
            clock.advanceMs(Backoff.MAX_MS);
            w.tick();
        }
        assertEquals(IpcWorker.POISON_REFUSALS + 2, transport.count(Ipc.Method.EVENTS));
        assertEquals(1, ring.pendingCount());
        assertTrue(w.connected());
    }

    @Test
    void IPC_03_aRefusedSingleEventIsDroppedOnlyAfterRepeatedRefusals() {
        // With an empty ack without an error is a refusal; three in sequence drop the event.
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        transport.handlers.put(Ipc.Method.EVENTS, r -> new IpcReply(Map.of(Ipc.Key.ACKED, "[]")));
        RingBuffer ring = new RingBuffer();
        ring.add(event("e1", 1, "{\"eventId\":\"e1\"}"));
        FakeClock clock = new FakeClock(0);
        IpcWorker w = worker(transport, ring, clock, new FakeTarget(), new FakeSigner(), new FakeConfig());
        w.tick();
        for (int i = 1; i < IpcWorker.POISON_REFUSALS; i++) {
            w.onEventSealed();
            clock.advanceMs(Backoff.MAX_MS);
            w.tick();
            assertEquals(1, ring.pendingCount(), "refusal " + i);
        }
        w.onEventSealed();
        clock.advanceMs(Backoff.MAX_MS);
        w.tick();
        assertEquals(0, ring.pendingCount());
    }

    @Test
    void IPC_04_missingAppPackageFailsTheHandshakeWithoutThrowing() {
        FakeTransport transport = new FakeTransport();
        FakeTarget target = new FakeTarget();
        target.pkg = "";
        FakeClock clock = new FakeClock(0);
        IpcWorker w = worker(transport, new RingBuffer(), clock, target, new FakeSigner(), new FakeConfig());
        w.tick();
        assertFalse(w.connected());
        assertEquals(0, transport.calls.size());
    }

    @Test
    void IPC_01_logPushAdvancesTheSequenceOnlyAfterSuccess() {
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        transport.handlers.put(Ipc.Method.HOOK_HEALTH, r -> new IpcReply(Map.of(Ipc.Key.OK, true)));
        transport.handlers.put(Ipc.Method.LOG, r -> new IpcReply(Map.of(Ipc.Key.OK, true)));
        FakeClock clock = new FakeClock(0);
        LogSource logs = new LogSource() {
            long seq = 5;
            boolean sent;

            @Override
            public long latestSeq() {
                return seq;
            }

            @Override
            public String errorLinesJson(long sinceSeq, long upToSeq) {
                if (sent) return "[]";
                sent = true;
                return "[{\"seq\":5,\"t\":1,\"level\":\"W\",\"tag\":\"T\",\"msg\":\"m\"}]";
            }
        };
        IpcWorker w = new IpcWorker(new FakeEnv(), new FakeTarget(), new RingBuffer(), new FakeConfig(), transport,
                new FakeSigner(), clock, new dev.nightjar.payload.log.PLog(clock, null), null, logs, health());
        w.tick(); // handshake
        w.tick(); // health
        w.tick(); // log
        assertEquals(1, transport.count(Ipc.Method.LOG));
        w.tick();
        assertEquals(1, transport.count(Ipc.Method.LOG));
    }

    @Test
    void IPC_01_aFailedLogPushKeepsTheSequence() {
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        transport.handlers.put(Ipc.Method.HOOK_HEALTH, r -> new IpcReply(Map.of(Ipc.Key.OK, true)));
        transport.handlers.put(Ipc.Method.LOG, r -> new IpcReply(Map.of(Ipc.Key.OK, true)));
        FakeClock clock = new FakeClock(0);
        List<Long> asked = new ArrayList<>();
        LogSource logs = new LogSource() {
            @Override
            public long latestSeq() {
                return 5;
            }

            @Override
            public String errorLinesJson(long sinceSeq, long upToSeq) {
                asked.add(sinceSeq);
                return "[{\"seq\":5,\"t\":1,\"level\":\"W\",\"tag\":\"T\",\"msg\":\"m\"}]";
            }
        };
        IpcWorker w = new IpcWorker(new FakeEnv(), new FakeTarget(), new RingBuffer(), new FakeConfig(), transport,
                new FakeSigner(), clock, new dev.nightjar.payload.log.PLog(clock, null), null, logs, health());
        w.tick(); // handshake
        w.tick(); // health
        transport.failWith = IpcException.Kind.FAILURE;
        transport.failCount = 1;
        w.tick(); // log: fails
        clock.advanceMs(Backoff.MAX_MS);
        w.tick(); // log again, from the same sequence
        assertEquals(List.of(0L, 0L), asked);
        w.tick();
        assertEquals(2, transport.count(Ipc.Method.LOG), "after the success nothing is left to push");
    }

    @Test
    void IPC_06_rejectedCallerKeepsEventsQueued() {
        FakeTransport transport = new FakeTransport();
        transport.handlers.put(Ipc.Method.HELLO, r -> helloReply("s-1", 1));
        RingBuffer ring = new RingBuffer();
        ring.add(event("e1", 1, "{\"eventId\":\"e1\"}"));
        FakeClock clock = new FakeClock(0);
        IpcWorker w = worker(transport, ring, clock, new FakeTarget(), new FakeSigner(), new FakeConfig());
        w.tick();
        transport.failWith = IpcException.Kind.REJECTED;
        transport.failCount = 1;
        w.onEventSealed();
        clock.advanceMs(IpcWorker.COALESCE_MS);
        w.tick();
        assertFalse(w.connected());
        assertEquals(1, ring.pendingCount());
        assertNull(w.sessionId());
    }
}
