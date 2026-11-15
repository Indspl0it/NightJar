package dev.nightjar.payload.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.payload.capture.InjectionTokens;
import dev.nightjar.payload.capture.Observation;
import dev.nightjar.payload.capture.PackageResolver;
import dev.nightjar.payload.env.HookBackendSpy;
import dev.nightjar.payload.hook.HookCatalog;
import dev.nightjar.payload.log.LogSink;
import dev.nightjar.payload.testing.FakeClock;
import dev.nightjar.payload.testing.FakeEnv;
import dev.nightjar.payload.testing.FakeWorker;
import dev.nightjar.payload.testing.Pdu;
import org.junit.jupiter.api.Test;

/** Install, tier decision, and the fail-open behaviour of the runtime. */
class PayloadRuntimeTest {

    private static final String RULES = """
            {"schema":1,"revision":3,"preset":"MONITOR","classActions":{},"rules":[]}
            """;
    private static final String SETTINGS = """
            {"schema":1,"appPackage":"dev.nightjar","debugLogging":{"until":0}}
            """;
    private static final String PROFILES = """
            {"schema":1,"profiles":[{"id":"akita","fingerprint":"google/akita","match":"PREFIX",
              "oem":"AOSP","firewall":true,"orphanPurgeDays":7,
              "hooks":{"HK-01":"REQUIRED","HK-04":"REQUIRED","HK-06":"REQUIRED","HK-30":"NA"}}]}
            """;

    private static PayloadRuntime runtime(HookBackendSpy spy, dev.nightjar.payload.env.Worker worker, FakeEnv env) {
        return new PayloadRuntime(new FakeClock(0), worker, LogSink.NONE, env, spy,
                InjectionTokens.NONE, PackageResolver.NONE, "session-x", "{}");
    }

    @Test
    void aBuildWithoutTheFrameworkClassesIsUnsupportedAndHooksNothing() {
        HookBackendSpy spy = new HookBackendSpy();
        PayloadRuntime rt = runtime(spy, new FakeWorker(), new FakeEnv());
        // The hidden telephony classes do not exist off-device, so capture cannot resolve: this is
        // exactly the UNSUPPORTED path, and it must install nothing.
        assertFalse(rt.install(RULES.getBytes(), SETTINGS.getBytes(), PROFILES.getBytes()));
        assertEquals(Session.State.UNSUPPORTED, rt.session().state());
        assertTrue(rt.health().unsupported());
        assertEquals(3, rt.config().rules().revision(), "config still applied");
    }

    @Test
    void aBrokenConfigBlobIsReportedButDoesNotThrow() {
        HookBackendSpy spy = new HookBackendSpy();
        PayloadRuntime rt = runtime(spy, new FakeWorker(), new FakeEnv());
        assertFalse(rt.install("{not json".getBytes(), "{not json".getBytes(), null));
        assertEquals(0, rt.config().rules().revision(), "the default snapshot is kept");
        assertNotNull(rt.log());
        assertNotNull(rt.config().rules().lastError());
    }

    @Test
    void healthJsonCarriesTheTierReport() {
        HookBackendSpy spy = new HookBackendSpy();
        PayloadRuntime rt = runtime(spy, new FakeWorker(), new FakeEnv());
        rt.install(RULES.getBytes(), SETTINGS.getBytes(), PROFILES.getBytes());
        String json = rt.hookHealthJson();
        assertTrue(json.contains("\"tiers\""), json);
        assertTrue(json.contains("\"capture\""), json);
        assertTrue(json.contains("\"HK-06\""), json);
        assertTrue(json.contains("\"droppedCount\":0"), json);
        assertTrue(json.contains("\"profile\":"), json);
    }

    @Test
    void queuedObservationsReachTheCorrelatorOnTheWorker() {
        HookBackendSpy spy = new HookBackendSpy();
        FakeWorker worker = new FakeWorker();
        PayloadRuntime rt = runtime(spy, worker, new FakeEnv());
        rt.install(RULES.getBytes(), SETTINGS.getBytes(), PROFILES.getBytes());

        byte[] pdu = Pdu.deliver().build();
        rt.observations().offer(Observation.raw(
                dev.nightjar.contract.Hashes.tpduKey(dev.nightjar.contract.Hashes.stripSmsc(pdu)),
                dev.nightjar.contract.Hashes.tpduKey(pdu), pdu, "3gpp",
                dev.nightjar.contract.Transport.CS, 0, -1, -1, -1, false, 1_000L, 1_000L));
        assertTrue(worker.pending() > 0, "the hook thread only enqueues");
        worker.runAll();
        assertEquals(1, rt.correlator().pendingCount());
        assertEquals(0, rt.ring().peek(10).size(), "not sealed before the timeout");
    }

    @Test
    void theCatalogDeclaresHk14AsAnAroundHook() {
        assertEquals(dev.nightjar.payload.hook.HookKind.AROUND, HookCatalog.of("HK-14").kind());
        assertEquals(dev.nightjar.contract.Tier.CAPTURE_REQUIRED, HookCatalog.of("HK-06").tier());
        assertTrue(HookCatalog.all().size() >= 26);
    }

    /** The health reports the kill switch and the rules status. */
    @Test
    void healthReportsTheKillSwitchAndTheRulesStatus() {
        PayloadRuntime rt = runtime(new HookBackendSpy(), new FakeWorker(), new FakeEnv());
        rt.install(RULES.getBytes(), SETTINGS.getBytes(), PROFILES.getBytes());
        String enabledKey = "\"" + dev.nightjar.contract.Ipc.Key.FIREWALL_ENABLED + "\":";
        String json = rt.hookHealthJson();
        assertTrue(json.contains(enabledKey + "true"), json);
        assertTrue(json.contains("\"rulesRevision\":3"), json);
        assertTrue(json.contains("\"rulesSkipped\":0"), json);
        assertFalse(json.contains("rulesError"), json);

        rt.config().applyFirewallEnabled(false);
        rt.config().applyRulesJson("{not json");
        json = rt.hookHealthJson();
        assertTrue(json.contains(enabledKey + "false"), json);
        assertTrue(json.contains("\"rulesError\":"), json);
        assertTrue(json.contains("\"rulesRevision\":3"), "a rejected push keeps the previous snapshot");
    }

    /** A settings push that arrives after the install changes the debug window. */
    @Test
    void aLaterSettingsPushChangesTheDebugWindow() {
        PayloadRuntime rt = runtime(new HookBackendSpy(), new FakeWorker(), new FakeEnv());
        rt.install(null, null, null);
        assertFalse(rt.log().debugEnabled());
        rt.config().applySettingsJson(
                "{\"schema\":1,\"appPackage\":\"dev.nightjar\",\"debugLogging\":{\"until\":9999999999999}}");
        assertTrue(rt.log().debugEnabled());
    }

    /** A firewall hook that fires before wiring runs the original and is not an error. */
    @Test
    void firewallHooksBeforeWiringAreNotErrors() throws Throwable {
        PayloadRuntime rt = runtime(new HookBackendSpy(), new FakeWorker(), new FakeEnv());
        java.lang.reflect.Field f = PayloadRuntime.class.getDeclaredField("dispatcher");
        f.setAccessible(true);
        dev.nightjar.payload.hook.HookCallback dispatcher = (dev.nightjar.payload.hook.HookCallback) f.get(rt);
        dev.nightjar.payload.hook.Param ctor = dev.nightjar.payload.hook.Param.of(HookCatalog.of("HK-24"),
                new Object[] {new Object()}, Object.class);
        dispatcher.after(ctor);
        dev.nightjar.payload.hook.Param usim = dev.nightjar.payload.hook.Param.of(HookCatalog.of("HK-15"),
                new Object[] {new Object(), null, null, 0, 0}, int.class);
        dispatcher.before(usim);
        assertFalse(usim.skipped(), "the original runs");
        dev.nightjar.payload.hook.Param mwi = dev.nightjar.payload.hook.Param.of(HookCatalog.of("HK-23"),
                new Object[] {new Object(), 1}, void.class);
        dispatcher.before(mwi);
        assertFalse(mwi.skipped());
    }

    /** Scheduling the wiring also starts the periodic tick; the poll goes on without a context. */
    @Test
    void scheduleWiringStartsTheTickAndKeepsPolling() {
        RecordingWorker worker = new RecordingWorker();
        PayloadRuntime rt = runtime(new HookBackendSpy(), worker, new FakeEnv());
        rt.scheduleWiring();
        rt.scheduleWiring();
        assertEquals(1, worker.delayed.size(), "one tick loop, however often wiring is scheduled");
        assertEquals(PayloadRuntime.TICK_MS, worker.delays.get(0));

        // Off-device there is no Application: each step polls again instead of giving up.
        worker.runImmediate();
        assertEquals(3, worker.delayed.size(), "the two wiring steps are queued again as polls");

        // The tick runs and queues itself again.
        Runnable tick = worker.delayed.remove(0);
        worker.delays.remove(0);
        tick.run();
        assertTrue(worker.delays.contains(PayloadRuntime.TICK_MS));
    }

    /** A worker that records what was queued; runs only the immediate queue on request. */
    static final class RecordingWorker implements dev.nightjar.payload.env.Worker {
        final java.util.ArrayDeque<Runnable> immediate = new java.util.ArrayDeque<>();
        final java.util.List<Runnable> delayed = new java.util.ArrayList<>();
        final java.util.List<Long> delays = new java.util.ArrayList<>();

        @Override
        public void post(Runnable r) {
            immediate.add(r);
        }

        @Override
        public void postDelayed(Runnable r, long delayMs) {
            delayed.add(r);
            delays.add(delayMs);
        }

        @Override
        public void cancel(Runnable r) {
            immediate.remove(r);
            delayed.remove(r);
        }

        @Override
        public boolean isWorkerThread() {
            return true;
        }

        void runImmediate() {
            Runnable r;
            while ((r = immediate.poll()) != null) r.run();
        }
    }
}
