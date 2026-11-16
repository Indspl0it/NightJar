package dev.nightjar.payload.boot;

import dev.nightjar.contract.Ipc;

import dev.nightjar.contract.HookState;
import dev.nightjar.payload.capture.Correlator;
import dev.nightjar.payload.capture.Counters;
import dev.nightjar.payload.capture.InjectionTokens;
import dev.nightjar.payload.capture.Observation;
import dev.nightjar.payload.capture.Observations;
import dev.nightjar.payload.capture.PackageResolver;
import dev.nightjar.payload.capture.Sealer;
import dev.nightjar.payload.capture.SwappableSpool;
import dev.nightjar.payload.config.ConfigStore;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.payload.env.Env;
import dev.nightjar.payload.env.Worker;
import dev.nightjar.payload.hook.FaultInjector;
import dev.nightjar.payload.hook.HookCatalog;
import dev.nightjar.payload.hook.HookRegistry;
import dev.nightjar.payload.hook.HookSpec;
import dev.nightjar.payload.hook.Hooker;
import dev.nightjar.payload.hook.HookBackend;
import dev.nightjar.payload.hook.Resolver;
import dev.nightjar.payload.hook.TierManager;
import dev.nightjar.payload.hooks.CaptureHooks;
import dev.nightjar.payload.hooks.CbHooks;
import dev.nightjar.payload.hooks.MeaningHooks;
import dev.nightjar.payload.hooks.OplusHooks;
import dev.nightjar.payload.hooks.WapHooks;
import dev.nightjar.payload.log.LogSink;
import dev.nightjar.payload.log.PLog;
import dev.nightjar.payload.store.EventQueue;
import dev.nightjar.payload.store.RingBuffer;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The payload runtime: everything one phone-process session owns.
 *
 * <p>Construction order matters and is fixed here: clock → log → configuration snapshots → worker
 * queue → correlator → hooks → tiers. {@link #install} performs the hook installation and decides
 * {@link Session.State#RUNNING} or {@link Session.State#UNSUPPORTED}; {@link #scheduleWiring} then
 * creates the services that need the phone process context, on the worker.
 *
 * <p>Thread: {@link #install} on the main thread of the phone process ({@code handleLoadPackage});
 * wiring, {@link #onWorkerTick()} and the health refresh on the worker; every accessor is safe from
 * any thread (volatile fields and thread-safe collaborators).
 */
public final class PayloadRuntime {
    private final Clock clock;
    private final Worker worker;
    private final PLog log;
    private final ConfigStore config;
    private final Env env;
    private final Session session;
    private final EventQueue<Observation> queue = new EventQueue<>();
    private final Observations observations;
    private final Counters counters = new Counters();
    private final SwappableSpool spool = new SwappableSpool();
    /** Outbound pipeline. Created in {@link #wire} with the rest of the services. */
    private volatile dev.nightjar.payload.capture.OutboundLedger ledger;
    private volatile dev.nightjar.payload.hooks.OutboundHooks outboundHooks;
    private final RingBuffer ring = new RingBuffer();
    private final HookRegistry registry = new HookRegistry();
    private final CaptureHooks captureHooks;
    private volatile dev.nightjar.payload.capture.CrashJournal crashJournal;
    private final MeaningHooks meaningHooks;
    private final WapHooks wapHooks;
    private final CbHooks cbHooks;
    private final OplusHooks oplusHooks;
    private final Correlator correlator;
    private final HookBackend bridge;
    private final Dispatcher dispatcher = new Dispatcher();
    private volatile ClassLoader appLoader;
    private final FaultInjector faults = FaultInjector.NONE;
    private volatile boolean wired;
    private volatile dev.nightjar.payload.hooks.FirewallHooks firewallHooks;
    private volatile dev.nightjar.payload.hooks.CbChannelHooks cbChannelHooks;
    private volatile dev.nightjar.payload.inject.TokenRegistry tokenRegistry;
    private volatile dev.nightjar.payload.firewall.FirewallState firewallState;
    /** The stored firewall switch; null until the wiring step knows the device-protected directory. */
    private volatile dev.nightjar.payload.config.FirewallFlagFile firewallFlagFile;
    /** The stored rules and settings copies; null until the wiring step knows the device-protected directory. */
    private volatile dev.nightjar.payload.config.ConfigCopies configCopies;
    private volatile dev.nightjar.payload.firewall.Enforcer enforcer;
    private volatile dev.nightjar.payload.inject.SmsInjector injector;
    private volatile dev.nightjar.payload.inject.Releaser releaser;
    private volatile dev.nightjar.payload.inject.Replayer replayer;
    private volatile dev.nightjar.payload.inject.SelfTestRunner selfTest;
    private volatile dev.nightjar.payload.ipc.ControlHandler controlHandler;
    private volatile dev.nightjar.payload.ipc.IpcWorker ipcWorker;
    private volatile dev.nightjar.payload.ipc.IpcLoop ipcLoop;
    private volatile TierManager.Health health;
    private volatile boolean installedOk;
    private final String info;
    /** Worker only: context polls, wiring failures, wiring steps done, the env watcher, the rules revision logged. */
    private int wiringAttempts;
    private int wireFailures;
    private final java.util.Set<String> wiredSteps = new java.util.HashSet<>();
    private dev.nightjar.payload.env.EnvWatcher envWatcher;
    private long loggedRulesRevision = Long.MIN_VALUE;
    private final java.util.concurrent.atomic.AtomicBoolean tickStarted =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean healthRefreshPending =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** Hook id → installed target, for the late deoptimize pass. */
    private final java.util.Map<String, java.lang.reflect.Member> installedTargets =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Hooks whose callee is small enough to be inlined by AOT/JIT. */
    private static final java.util.Set<String> DEOPTIMIZE_HOOKS =
            java.util.Set.of("HK-06", "HK-15b", "HK-23", "HK-26", "HK-30", "HK-32");

    /** How often {@link #wiringStep()} looks for the process context. */
    private static final long WIRING_POLL_MS = 200L;

    /** About 60 s: the phone process attaches its Application well inside that window. */
    private static final int WIRING_MAX_ATTEMPTS = 300;

    /** Ceiling of the slow retry after the fast window; the payload never stops looking for its context. */
    private static final long WIRING_SLOW_MAX_MS = 30_000L;

    /** Period of {@link #onWorkerTick()}: seal timeouts and expiries are seconds wide. */
    static final long TICK_MS = 1_000L;
    /** Payload subdirectory in device-protected storage: spool, crash journal, firewall switch, config copies. */
    private static final String DE_DIR = "nightjar";

    /**
     * @param bridge hook backend; {@link HookBackend#NONE} in tests
     * @param logSink logcat sink, or {@link LogSink#NONE}
     */
    public PayloadRuntime(Clock clock, Worker worker, LogSink logSink, Env env, HookBackend bridge,
            InjectionTokens tokens, PackageResolver packages, String sessionId, String infoJson) {
        this.clock = clock;
        this.worker = worker;
        this.env = env;
        this.bridge = bridge == null ? HookBackend.NONE : bridge;
        this.log = new PLog(clock, logSink);
        this.config = new ConfigStore(clock);
        // An explicit switch arrives on a binder or IPC thread; the file write runs on the worker.
        this.config.setFirewallPersister(on -> worker.post(() -> persistFirewallFlag(on)));
        // Rules and settings arrive on a binder or IPC thread; the copies are written on the worker.
        this.config.setRulesPersister(json -> worker.post(() -> persistRulesCopy(json)));
        this.config.setSettingsPersister(json -> worker.post(() -> persistSettingsCopy(json)));
        this.session = new Session(sessionId, clock.wallMs());
        this.registry.setBridge(this.bridge);
        // A hook that disables itself must be uninstalled and reported from the worker. One
        // refresh is queued at a time, however many state changes arrive.
        this.registry.addListener(this::requestHealthRefresh);
        this.observations = new Observations(queue, worker, this::drain);
        this.captureHooks = new CaptureHooks(observations, clock);
        this.meaningHooks = new MeaningHooks(observations, clock);
        this.wapHooks = new WapHooks(observations, clock);
        this.cbHooks = new CbHooks(observations, clock, env, config.settings());
        this.oplusHooks = new OplusHooks(observations, clock);
        this.correlator = new Correlator(clock,
                new Sealer((BooleanSupplier) env::userUnlocked), counters, env, spool, tokens, packages,
                sessionId, env.payloadVersion(), config.orphanPurgeDays(env.buildFingerprint()));
        this.info = infoJson == null ? "{}" : infoJson;
    }

    /** The companion-provided info blob (payloadVersion, bootId, moduleVersionCode). */
    public String info() {
        return info;
    }

    /**
     * The phone application's class loader ({@code lpparam.classLoader}), used to resolve the hook targets. Set before
     * {@link #install}; null means the system class loader.
     */
    public void setClassLoader(ClassLoader loader) {
        this.appLoader = loader;
    }

    /** Loads the configuration blobs and installs the hooks. Never throws. */
    public boolean install(byte[] rules, byte[] settings, byte[] profiles) {
        try {
            session.beginHooking();
            // Every later settings push (normally over IPC) updates the debug window and the clamps.
            config.setFirewallListener(this::onConfigChanged);
            if (rules != null) config.applyRules(rules);
            if (settings != null) config.applySettings(settings);
            if (profiles != null) config.applyProfiles(profiles);
            log.setDebugUntil(config.settings().get().debugUntil());
            log.i("boot", "session start", "session", session.sessionId(),
                    "fingerprint", env.buildFingerprint(), "version", env.payloadVersion());
            if (config.rules().lastError() != null) {
                log.eLimited("rules.parse", "config", "rules.json rejected",
                        "err", config.rules().lastError());
            }
            if (config.settings().lastError() != null) {
                log.eLimited("settings.parse", "config", "settings.json rejected",
                        "err", config.settings().lastError());
            }
            installHooks();
            return installedOk;
        } catch (Throwable t) {
            log.e("boot", "install failed", "err", t.getClass().getSimpleName());
            session.unsupported();
            return false;
        }
    }

    /** Settings, profile or kill-switch change: debug window and firewall clamps. Any thread. */
    private void onConfigChanged() {
        log.setDebugUntil(config.settings().get().debugUntil());
        dev.nightjar.payload.firewall.FirewallState st = firewallState;
        if (st == null) return;
        st.setEnabled(config.firewallEnabled());
        st.setSafeMode(config.settings().get().safeMode());
    }

    // ------------------------------------------------------------------ hook installation

    private void installHooks() {
        Map<String, String> notApplicable = HookCatalog.notApplicableByDefault();
        boolean aidlRadioLive = false;
        boolean oplus = isOplus();

        for (HookSpec spec : HookCatalog.all()) {
            registry.register(spec);
            if (!BODIES.contains(spec.id())) {
                registry.unresolved(spec.id(), HookState.NOT_APPLICABLE, "no body in this build");
                continue;
            }
            // OnePlus classes need mImpl's class loader; they stay NOT_APPLICABLE here and are resolved after
            // wiring, when a handler exists (installImplHooks).
            boolean impl = spec.resolver() == HookSpec.ResolverKind.IMPL_LOADER;
            Resolver.Result r = Resolver.resolve(impl ? null : appLoader, spec);
            if (r.isLive() && "HK-01".equals(spec.id())) aidlRadioLive = true;
            if (!r.isLive()) {
                String reason = r.error();
                // HK-01b/HK-02b: the HIDL twins are not used when the AIDL radio is live. HK-19d: absent on OPlus.
                boolean expected = notApplicable.containsKey(spec.id())
                        && ("HK-19d".equals(spec.id()) ? oplus : aidlRadioLive);
                if (expected) {
                    registry.unresolved(spec.id(), HookState.NOT_APPLICABLE, notApplicable.get(spec.id()));
                } else if (impl) {
                    registry.unresolved(spec.id(), HookState.NOT_APPLICABLE,
                            oplus ? "waiting for the mImpl class loader" : "not an OPlus build");
                } else {
                    registry.unresolved(spec.id(), r.state(), reason);
                }
                log.wLimited("resolve." + spec.id(), "hook", "hook unresolved",
                        "id", spec.id(), "state", registry.state(spec.id()).wire(), "reason", reason);
                continue;
            }
            installOne(spec, r.target());
        }

        // A small hooked method (the MWI setter, the undelivered broadcast, the USIM ack) can be
        // inlined into its caller by AOT/JIT; deoptimize it on the worker so the hook actually fires.
        worker.post(this::deoptimizeSmallHooks);

        TierManager.Health h = TierManager.degrade(registry);
        health = h;
        registry.setDroppedObservations(queue.dropped());
        registry.setDroppedCount(ring.droppedCount());
        if (h.unsupported()) {
            session.unsupported();
            log.e("hook", "capture tier failed: unsupported build",
                    "capture", h.capture().live() + "/" + h.capture().total());
            installedOk = false;
            return;
        }
        installedOk = true;
        // Nothing is connected yet: the IPC handshake runs after wiring.
        session.running(false);
        log.i("hook", "hooks installed", "live", countLive(), "capture", h.capture().live(),
                "firewallAvailable", h.firewallAvailable(), "outbound", h.outboundLive());
        if (!h.firewallAvailable()) {
            log.w("hook", "firewall tier incomplete: monitor only");
        }
    }

    /** Installs one resolved hook through the bridge; true when it is live. */
    private boolean installOne(HookSpec spec, java.lang.reflect.Member target) {
        Hooker hooker = new Hooker(spec, dispatcher, registry, faults, target);
        if ("HK-14".equals(spec.id())) hooker.setShield(wapHooks::recover);
        long handle = 0L;
        try {
            handle = bridge.install(target, spec.kind(), hooker);
        } catch (Throwable t) {
            registry.onError(spec.id(), t);
        }
        if (handle != 0L) {
            installedTargets.put(spec.id(), target);
            registry.resolved(spec.id(), target.getName(), handle);
            return true;
        }
        registry.unresolved(spec.id(), HookState.FAILED, "hook install refused by the Xposed bridge");
        log.wLimited("install." + spec.id(), "hook", "hook install refused by the Xposed bridge", "id", spec.id());
        return false;
    }

    /**
     * Resolves and installs the IMPL_LOADER hooks (HK-30, HK-32) with the class loader of the handler's OnePlus
     * {@code mImpl}. Worker; after the head-filter sweep, when the handlers exist. Does nothing on other builds.
     */
    private void installImplHooks() {
        try {
            if (!isOplus()) return;
            ClassLoader loader = FilterSweep.implLoader();
            if (loader == null) {
                log.w("hook", "OPlus mImpl class loader not found: HK-30 and HK-32 stay off");
                return;
            }
            boolean any = false;
            for (HookSpec spec : HookCatalog.all()) {
                if (spec.resolver() != HookSpec.ResolverKind.IMPL_LOADER || !BODIES.contains(spec.id())) continue;
                if (registry.state(spec.id()) == HookState.LIVE) continue;
                Resolver.Result r = Resolver.resolve(loader, spec);
                if (!r.isLive()) {
                    registry.unresolved(spec.id(), r.state(), r.error());
                    log.wLimited("resolve." + spec.id(), "hook", "hook unresolved",
                            "id", spec.id(), "state", r.state().wire(), "reason", r.error());
                    continue;
                }
                any |= installOne(spec, r.target());
            }
            if (any) deoptimizeSmallHooks();
        } catch (Throwable t) {
            log.w("hook", "OPlus hook installation failed", "err", t.getClass().getSimpleName());
        }
    }

    /** True for an OPlus build (OnePlus, OPPO, realme): from the matched profile, else from the fingerprint brand. */
    private boolean isOplus() {
        String fp = env.buildFingerprint();
        if (config.matcher().oem(fp) == dev.nightjar.payload.config.Profile.Oem.OPLUS) return true;
        String brand = fp == null ? "" : fp.toLowerCase(java.util.Locale.ROOT);
        return brand.startsWith("oneplus/") || brand.startsWith("oppo/") || brand.startsWith("realme/");
    }

    private int countLive() {
        int n = 0;
        for (HookRegistry.HookRecord r : registry.all()) {
            if (r.state == HookState.LIVE) n++;
        }
        return n;
    }

    /**
     * The one callback instance every hook uses. It dispatches on the hook id instead of
     * allocating one object per hook, which keeps the payload dex small.
     */
    private final class Dispatcher implements dev.nightjar.payload.hook.HookCallback {
        @Override
        public void before(dev.nightjar.payload.hook.Param p) {
            switch (p.hookId()) {
                case "HK-03b" -> captureHooks.onSimRecordsHandleSms(p);
                case "HK-04" -> captureHooks.onImsSmsReceived(p);
                case "HK-05" -> captureHooks.onImsStatusReport(p);
                case "HK-11" -> meaningHooks.beforeCarrierFilterComplete(p);
                case "HK-14" -> wapHooks.before(p);
                case "HK-15" -> firewall(h -> h.beforeUsimDataDownload(p));
                case "HK-15b" -> meaningHooks.beforeUsimAck(p);
                case "HK-17" -> cbHooks.before(p);
                case "HK-21" -> captureHooks.onInjectSmsPdu(p);
                case "HK-23" -> firewall(h -> h.beforeMwiIndicator(p));
                case "HK-26" -> meaningHooks.beforeBroadcastSms(p);
                case "HK-31" -> oplusHooks.beforeRedirect(p);
                case "HK-19a", "HK-19b", "HK-19c", "HK-19d", "HK-19e", "HK-19f", "HK-19g", "HK-19h",
                        "HK-19i" -> outbound(p, h -> h.beforeSend(p));
                case "HK-20" -> outbound(p, h -> h.beforeSendRawPdu(p));
                case "HK-22" -> outbound(p, h -> h.beforeStkSend(p));
                default -> {
                    // after-hook only.
                }
            }
        }

        @Override
        public void after(dev.nightjar.payload.hook.Param p) {
            switch (p.hookId()) {
                case "HK-01" -> captureHooks.onNewSms(p);
                case "HK-01b" -> captureHooks.onNewSmsHidl(p);
                case "HK-02" -> captureHooks.onSmsStatusReport(p);
                case "HK-02b" -> captureHooks.onSmsStatusReportHidl(p);
                case "HK-03" -> captureHooks.onNewSmsOnSim(p);
                case "HK-24" -> firewall(h -> h.afterHandlerCreated(p));
                case "HK-06" -> meaningHooks.afterDispatchMessage(p);
                case "HK-07" -> meaningHooks.afterRadioSpecific(p);
                case "HK-09" -> meaningHooks.afterVvmFilter(p);
                case "HK-10" -> meaningHooks.afterMissedCallFilter(p);
                case "HK-12" -> meaningHooks.afterAppSmsIntent(p);
                case "HK-13a" -> meaningHooks.afterDispatchIntent(p);
                case "HK-13b" -> meaningHooks.afterDropFiltered(p);
                case "HK-13c" -> meaningHooks.afterBlockStatus(p);
                case "HK-14" -> wapHooks.after(p);
                case "HK-16" -> meaningHooks.afterScanRawTable(p);
                case "HK-30" -> oplusHooks.afterOemCheck(p);
                case "HK-32" -> oplusHooks.afterScanRawTable(p);
                case "HK-27" -> outbound(p, h -> h.afterSendComplete(p));
                case "HK-27b" -> outbound(p, h -> h.afterTrackerSent(p));
                case "HK-27c" -> outbound(p, h -> h.afterTrackerFailed(p));
                default -> {
                    // before-hook only.
                }
            }
        }
    }

    /**
     * Runs a firewall hook body (HK-15, HK-23, HK-24). The bodies exist only after wiring: a call that arrives earlier
     * runs the original unchanged and is not an error. A handler built before wiring is covered by the
     * startup sweep. An exception of the body itself goes to the {@link Hooker} and is counted.
     */
    private void firewall(java.util.function.Consumer<dev.nightjar.payload.hooks.FirewallHooks> body) {
        dev.nightjar.payload.hooks.FirewallHooks h = firewallHooks;
        if (h != null) body.accept(h);
    }

    /**
     * Runs an outbound hook body. The ledger exists only after wiring, so a send that arrives
     * earlier is simply not captured; an exception is contained here so the original send is never
     * affected.
     */
    private void outbound(dev.nightjar.payload.hook.Param p,
            java.util.function.Consumer<dev.nightjar.payload.hooks.OutboundHooks> body) {
        dev.nightjar.payload.hooks.OutboundHooks h = outboundHooks;
        if (h == null) return;
        try {
            body.accept(h);
        } catch (Throwable t) {
            log.eLimited("outbound." + p.hookId(), "hook", "outbound body failed",
                    "err", t.getClass().getSimpleName());
        }
    }

    /** Hook ids this runtime has a body for; anything else is {@code NOT_APPLICABLE}. */
    private static final java.util.Set<String> BODIES = java.util.Set.of(
            "HK-01", "HK-01b", "HK-02", "HK-02b", "HK-03", "HK-03b", "HK-04", "HK-05", "HK-06",
            "HK-07", "HK-09", "HK-10", "HK-11", "HK-12", "HK-13a", "HK-13b", "HK-13c", "HK-14",
            "HK-15", "HK-15b", "HK-24", "HK-16", "HK-17", "HK-21", "HK-23", "HK-26", "HK-30", "HK-31",
            "HK-32", "HK-19a", "HK-19b", "HK-19c", "HK-19d", "HK-19e", "HK-19f", "HK-19g", "HK-19h",
            "HK-19i", "HK-20", "HK-22", "HK-27", "HK-27b", "HK-27c");

    // ------------------------------------------------------------------ worker

    /** Drains the observation queue; runs on the worker. */
    private void drain() {
        observations.drainStarted();
        try {
            Observation o;
            int budget = 256;
            long t = clock.elapsedNs();
            while (budget-- > 0 && (o = queue.poll()) != null) {
                if (o.kind == Observation.Kind.MO) {
                    // Outbound observations are a separate pipeline: they never enter the
                    // inbound correlator's event map.
                    dev.nightjar.payload.capture.OutboundLedger l = ledger;
                    if (l != null) l.accept(o.mo, t);
                    continue;
                }
                correlator.accept(o);
            }
            publish(correlator.drainOutbox());
            dev.nightjar.payload.capture.OutboundLedger l = ledger;
            if (l != null) publish(l.drainOutbox());
        } catch (Throwable t) {
            log.eLimited("drain", "worker", "drain failed", "err", t.getClass().getSimpleName());
        } finally {
            observations.drainFinished();
            registry.setDroppedObservations(queue.dropped());
            registry.setDroppedCount(ring.droppedCount());
        }
    }

    /**
     * Periodic tick, every {@link #TICK_MS} on the worker: seal timeouts and expiries, the
     * firewall statistics and the rules status (RULES-X6).
     */
    public void onWorkerTick() {
        try {
            publish(correlator.tick(clock.elapsedNs()));
            dev.nightjar.payload.capture.OutboundLedger l = ledger;
            if (l != null) publish(l.tick(clock.elapsedNs()));
        } catch (Throwable t) {
            log.eLimited("tick", "worker", "tick failed", "err", t.getClass().getSimpleName());
        }
        dev.nightjar.payload.firewall.Enforcer e = enforcer;
        if (e != null) e.flushStats();
        logRulesStatus();
    }

    /** The tick posts itself again; it ends with the worker. */
    private void tickLoop() {
        try {
            onWorkerTick();
        } finally {
            worker.postDelayed(this::tickLoop, TICK_MS);
        }
    }

    /** Logs once per rules revision when the engine skipped rules it could not compile (RULES-X6). Worker. */
    private void logRulesStatus() {
        try {
            dev.nightjar.payload.config.RulesHolder rules = config.rules();
            long revision = rules.revision();
            if (revision == loggedRulesRevision) return;
            loggedRulesRevision = revision;
            int skipped = rules.engine().skippedRules();
            if (skipped > 0) {
                log.w("config", "rules skipped: the engine cannot use them", "revision", revision, "skipped", skipped);
            }
        } catch (Throwable t) {
            // Status only.
        }
    }

    private void publish(List<dev.nightjar.payload.store.OutboundEvent> events) {
        for (var e : events) {
            ring.add(e);
            log.d("event", "sealed", "eventId", e.eventId(), "revision", e.revision());
        }
        registry.setDroppedCount(ring.droppedCount());
        if (!events.isEmpty()) {
            dev.nightjar.payload.ipc.IpcWorker w = ipcWorker;
            if (w != null) {
                w.onEventSealed();
                dev.nightjar.payload.ipc.IpcLoop l = ipcLoop;
                if (l != null) l.kick();
            }
        }
    }

    /** Restores the pending-unlock spool; runs on the worker. */
    public void restoreSpool() {
        try {
            publish(correlator.restore(clock.elapsedNs()));
        } catch (Throwable t) {
            log.eLimited("spool", "worker", "spool restore failed");
        }
    }

    // ------------------------------------------------------------------ service wiring

    /**
     * Posts the wiring step to the worker and starts the periodic tick (called by {@link Entry} right
     * after a successful install).
     *
     * <p>{@code handleLoadPackage} runs before the phone application exists, so the process has no
     * {@code Context} yet and every service that needs one would fail. The worker polls for the
     * context (200 ms for about 60 s, then slower) and then wires. Nothing here touches the main looper.
     */
    public void scheduleWiring() {
        worker.post(this::wiringStep);
        if (tickStarted.compareAndSet(false, true)) worker.postDelayed(this::tickLoop, TICK_MS);
    }

    /** Poll interval after {@code attempts} misses: fast for ~60 s, then doubling up to {@link #WIRING_SLOW_MAX_MS}. */
    static long wiringDelayMs(int attempts) {
        if (attempts <= WIRING_MAX_ATTEMPTS) return WIRING_POLL_MS;
        int shift = Math.min(8, (attempts - WIRING_MAX_ATTEMPTS) / 2);
        return Math.min(WIRING_SLOW_MAX_MS, 1_000L << shift);
    }

    /** Delay before the next wiring attempt after {@code failures} failed attempts: 1 s, doubling, at most 30 s. */
    static long wiringRetryDelayMs(int failures) {
        int shift = Math.max(0, Math.min(5, failures - 1));
        return Math.min(WIRING_SLOW_MAX_MS, 1_000L << shift);
    }

    /** One polling attempt, on the worker. A failed wiring is attempted again. */
    private void wiringStep() {
        try {
            // The Application, not the system context that exists a few ms earlier: the spool lives in
            // the phone package's device-protected files dir.
            android.content.Context ctx = dev.nightjar.payload.env.Contexts.currentApplication();
            if (ctx == null) {
                wiringAttempts++;
                if (wiringAttempts == WIRING_MAX_ATTEMPTS + 1) {
                    log.e("boot", "no application context yet: wiring tries again later");
                }
                worker.postDelayed(this::wiringStep, wiringDelayMs(wiringAttempts));
                return;
            }
            if (!wire(ctx)) scheduleWiringRetry();
        } catch (Throwable t) {
            log.e("boot", "wiring error: wiring starts again later", "err", t.getClass().getSimpleName());
            scheduleWiringRetry();
        }
    }

    private void scheduleWiringRetry() {
        wireFailures++;
        worker.postDelayed(this::wiringStep, wiringRetryDelayMs(wireFailures));
    }

    /**
     * Wires the IPC, firewall, injection, cell broadcast and outbound components into this runtime.
     *
     * <p>Called on the worker once the process context exists. Each step is fail-open: a step that
     * fails is logged, the runtime keeps capturing, and the next attempt runs only the steps that
     * did not complete. A step publishes its objects at its end, so a failed step leaves nothing half
     * installed.
     *
     * @return true when every step completed
     */
    public synchronized boolean wire(android.content.Context ctx) {
        if (wired) return true;
        boolean ok = step("environment", () -> refreshEnvironment(ctx));
        // --- Injection tokens. Created before the firewall because the WAP and SIM-DD
        // gates read them: a canary must be recognised before any rule runs.
        ok &= step("tokens", this::wireTokens);
        // The stored switch is read before the firewall state is published, so a user who turned blocking
        // off keeps it off after a reboot, also before unlock and before the first hello.
        ok &= step("firewallFlag", () -> wireFirewallFlag(ctx));
        // The stored rules and settings are applied before the firewall starts, so it holds and blocks after a reboot
        // before the user unlocks the phone and before the first hello reply that carries rules.
        ok &= step("configCopies", () -> wireConfigCopies(ctx));
        ok &= step("firewall", this::wireFirewall);
        ok &= step("release", this::wireRelease);
        ok &= step("cbChannels", this::wireCbChannels);
        ok &= step("outbound", this::wireOutbound);
        ok &= step("spool", () -> wireSpool(ctx));
        ok &= step("ipc", () -> wireIpc(ctx));
        ok &= step("replay", this::replayAfterWiring);
        wired = ok;
        return ok;
    }

    /** Runs one wiring step once; false when it failed (it runs again at the next attempt). */
    private boolean step(String name, Runnable body) {
        if (wiredSteps.contains(name)) return true;
        try {
            body.run();
            wiredSteps.add(name);
            return true;
        } catch (Throwable t) {
            log.e("boot", "wiring step failed: the step runs again at the next attempt", "step", name,
                    "err", t.getClass().getSimpleName());
            return false;
        }
    }

    private dev.nightjar.payload.inject.TokenRegistry requireTokens() {
        dev.nightjar.payload.inject.TokenRegistry tokens = tokenRegistry;
        if (tokens == null) throw new IllegalStateException("no token registry");
        return tokens;
    }

    private void wireTokens() {
        dev.nightjar.payload.inject.TokenRegistry tokens = new dev.nightjar.payload.inject.TokenRegistry(clock);
        setInjectionTokens(tokens);
        tokenRegistry = tokens;
    }

    /**
     * Reads the stored firewall switch from device-protected storage; runs on the worker. A switch that
     * arrived before this step is stored now. Without the directory the default (on) and the in-memory switch apply.
     */
    private void wireFirewallFlag(android.content.Context ctx) {
        java.io.File dir = dev.nightjar.payload.env.Contexts.deviceProtectedDir(ctx, DE_DIR);
        if (dir == null) {
            log.w("boot", "no device-protected directory: the firewall switch is not kept after a restart");
            return;
        }
        dev.nightjar.payload.config.FirewallFlagFile file = new dev.nightjar.payload.config.FirewallFlagFile(dir);
        firewallFlagFile = file;
        Boolean override = config.firewallEnabledOverride();
        if (override != null) {
            writeFirewallFlag(file, override);
        } else {
            config.setPersistedFirewallEnabled(file.read());
        }
    }

    /** Stores one explicit firewall switch; runs on the worker. */
    private void persistFirewallFlag(boolean enabled) {
        dev.nightjar.payload.config.FirewallFlagFile file = firewallFlagFile;
        // Before wireFirewallFlag the file is not known: that step stores the current switch.
        if (file == null) return;
        writeFirewallFlag(file, enabled);
    }

    /**
     * Applies the stored rules and settings from device-protected storage; runs on the worker. Documents that came
     * from the app before this step are stored now instead. Without the directory the defaults (everything ALLOW)
     * apply until the app sends rules.
     */
    private void wireConfigCopies(android.content.Context ctx) {
        java.io.File dir = dev.nightjar.payload.env.Contexts.deviceProtectedDir(ctx, DE_DIR);
        if (dir == null) {
            log.w("boot", "no device-protected directory: rules and settings are not kept after a restart");
            return;
        }
        dev.nightjar.payload.config.ConfigCopies copies = new dev.nightjar.payload.config.ConfigCopies(dir, config, log);
        copies.start();
        configCopies = copies;
    }

    /** Stores one rules document from the app; runs on the worker. Before wireConfigCopies, that step stores it. */
    private void persistRulesCopy(String json) {
        dev.nightjar.payload.config.ConfigCopies copies = configCopies;
        if (copies != null) copies.storeRules(json);
    }

    /** Stores one settings document from the app; runs on the worker. Before wireConfigCopies, that step stores it. */
    private void persistSettingsCopy(String json) {
        dev.nightjar.payload.config.ConfigCopies copies = configCopies;
        if (copies != null) copies.storeSettings(json);
    }

    /** A failed write keeps the switch active in memory (fail open: the payload does what the user chose). */
    private void writeFirewallFlag(dev.nightjar.payload.config.FirewallFlagFile file, boolean enabled) {
        if (!file.write(enabled)) {
            log.w("config", "firewall switch not stored: the switch applies until com.android.phone restarts");
        }
    }

    /** Firewall: state, enforcers, ports, the head filter (HK-08 through HK-24 and the sweep). */
    private void wireFirewall() {
        dev.nightjar.payload.inject.TokenRegistry tokens = requireTokens();
        dev.nightjar.payload.firewall.FirewallState fw = new dev.nightjar.payload.firewall.FirewallState(clock);
        // textContains rules need the decoded body; FrameworkText decodes only when a text rule is reached.
        dev.nightjar.payload.firewall.Enforcer enf = new dev.nightjar.payload.firewall.Enforcer(
                () -> config.rules().engine(), fw, new dev.nightjar.payload.firewall.FrameworkText(), log);
        dev.nightjar.payload.firewall.CanaryGate canaries =
                new dev.nightjar.payload.firewall.CanaryGate(tokens, cls -> tokens.hasPendingCanary(cls.wire()));
        dev.nightjar.payload.firewall.WapEnforcer wap = new dev.nightjar.payload.firewall.WapEnforcer(enf, tokens);
        dev.nightjar.payload.firewall.CbEnforcer cb = new dev.nightjar.payload.firewall.CbEnforcer(enf);
        dev.nightjar.payload.firewall.UsimDdEnforcer dd =
                new dev.nightjar.payload.firewall.UsimDdEnforcer(enf, canaries);
        // HK-23 lets a released MWI apply its indicator again.
        dev.nightjar.payload.firewall.MwiEnforcer mwi = new dev.nightjar.payload.firewall.MwiEnforcer(enf, canaries,
                () -> tokens.hasPendingRelease(dev.nightjar.contract.ClassId.MWI.wire()));
        fw.setAvailable(health != null && health.firewallAvailable());
        fw.setEnabled(config.firewallEnabled());
        fw.setSafeMode(config.settings().get().safeMode());
        dev.nightjar.payload.firewall.SmsFilterCore core =
                new dev.nightjar.payload.firewall.SmsFilterCore(enf, tokens, clock, log);
        // Slot and roaming are resolved from the subscription the message belongs to,
        // so when.slot / when.roaming rules can match at every enforcement point.
        dev.nightjar.payload.hooks.FirewallHooks fwHooks = new dev.nightjar.payload.hooks.FirewallHooks(
                observations, clock, new dev.nightjar.payload.firewall.FilterInstaller(log),
                handler -> new dev.nightjar.payload.firewall.NjSmsFilter(handler, core, observations, registry,
                        subId -> new int[] {env.slot(subId), env.roaming(subId) ? 1 : 0}),
                dd, mwi, env::slot, env::roaming);

        // Publish: from here on the hooks and the clamps use these objects.
        wapHooks.setPort(dev.nightjar.payload.firewall.FirewallPorts.wap(wap, env::slot, env::roaming));
        cbHooks.setPort(dev.nightjar.payload.firewall.FirewallPorts.cb(cb, env::slot, env::roaming));
        // Events carry the emergency-call state that the SUPL grace clamp uses.
        correlator.setEmergencyCallActive(fw::emergencyCallActive);
        // A clamp change (safe mode, kill switch, emergency call) is reported in the hook health.
        fw.setListener(this::onFirewallStateChanged);
        enforcer = enf;
        firewallState = fw;
        firewallHooks = fwHooks;
        // HK-25 / the SUPL grace clamp needs the emergency-call state; the watcher starts once the
        // firewall state exists.
        new dev.nightjar.payload.env.EmergencyWatcher(fw, log).start(worker);
        // The sweep only covers handlers that already exist; handlers created later are caught by
        // the real HK-24 constructor hook. A failed sweep is not a firewall failure.
        FilterSweep.run(fwHooks, log);
        installImplHooks();
        refreshHealth();
    }

    private void onFirewallStateChanged() {
        dev.nightjar.payload.ipc.IpcWorker w = ipcWorker;
        if (w != null) w.onHookHealthChanged();
    }

    /** Release, replay and self-test. */
    private void wireRelease() {
        dev.nightjar.payload.inject.TokenRegistry tokens = requireTokens();
        dev.nightjar.payload.inject.SmsInjector inj = new dev.nightjar.payload.inject.SmsDispatchersInjector(log);
        dev.nightjar.payload.inject.Releaser rel = new dev.nightjar.payload.inject.Releaser(tokens, inj, log);
        dev.nightjar.payload.inject.Replayer rep = new dev.nightjar.payload.inject.Replayer(tokens, inj, log);
        // The canary loop blocks up to ten seconds per canary, so it runs on its own thread, not on the
        // payload worker that drains events and runs the IPC.
        dev.nightjar.payload.inject.SelfTestRunner st = new dev.nightjar.payload.inject.SelfTestRunner(tokens, inj,
                new dev.nightjar.payload.env.HandlerWorker("NightjarSelfTest"),
                id -> registry.state(id) == HookState.LIVE, env::userUnlocked, PayloadRuntime::defaultSubId,
                clock, log);
        injector = inj;
        releaser = rel;
        replayer = rep;
        selfTest = st;
    }

    /** The default SMS subscription, else the default subscription; the injector refuses a negative id. */
    private static int defaultSubId() {
        int sub = android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId();
        if (sub < 0) sub = android.telephony.SubscriptionManager.getDefaultSubscriptionId();
        return sub;
    }

    /**
     * "receive all channels" (HK-18). Registered here, not in HookCatalog, because
     * the firewall-extra hooks are installed after the core tiers. The step never runs twice: a
     * partial install must not hook a method a second time.
     */
    private void wireCbChannels() {
        if (cbChannelHooks != null) return;
        dev.nightjar.payload.hooks.CbChannelHooks cbChannels = new dev.nightjar.payload.hooks.CbChannelHooks(
                () -> config.settings().get().cbAllChannels(), log);
        cbChannelHooks = cbChannels;
        try {
            cbChannels.install(registry, bridge, faults, appLoader);
        } catch (Throwable t) {
            log.w("hook", "cell broadcast channel hooks not installed", "err", t.getClass().getSimpleName());
        }
    }

    /**
     * Outbound pipeline: HK-19/20/22/27 feed one ledger that links a request to its dispatcher PDU
     * and to the tracker's result. The outbound gate is installed too; with no blocked
     * package in the settings it allows every send.
     */
    private void wireOutbound() {
        dev.nightjar.payload.capture.OutboundLedger led = new dev.nightjar.payload.capture.OutboundLedger(clock, env,
                counters, () -> config.settings().get(), session.sessionId());
        dev.nightjar.payload.firewall.OutboundGate gate =
                new dev.nightjar.payload.firewall.OutboundGate(this::outboundPolicy, log);
        ledger = led;
        outboundHooks = new dev.nightjar.payload.hooks.OutboundHooks(observations, clock, led, gate);
        dev.nightjar.payload.outbound.OutboundPolicies.install(gate);
    }

    /**
     * The outbound gate policy for the current settings snapshot and firewall switch, built once per pair.
     * Binder threads.
     */
    private dev.nightjar.payload.firewall.OutboundGate.Policy outboundPolicy() {
        dev.nightjar.payload.config.Settings s = config.settings().get();
        boolean firewallOn = config.firewallEnabled();
        GatePolicy cached = gatePolicy;
        if (cached != null && cached.settings() == s && cached.firewallEnabled() == firewallOn) return cached.policy();
        dev.nightjar.payload.firewall.OutboundGate.Policy p = new dev.nightjar.payload.firewall.OutboundGate.Policy(
                s.outgoingEnabled(), s.safeMode(), java.util.Set.copyOf(s.outboundBlocked()), firewallOn);
        gatePolicy = new GatePolicy(s, firewallOn, p);
        return p;
    }

    private volatile GatePolicy gatePolicy;

    /** One settings snapshot, the firewall switch and the outbound gate policy built from them. */
    private record GatePolicy(dev.nightjar.payload.config.Settings settings, boolean firewallEnabled,
            dev.nightjar.payload.firewall.OutboundGate.Policy policy) {}

    /** Pending-unlock spool and the crash journal. */
    private void wireSpool(android.content.Context ctx) {
        java.io.File dir = dev.nightjar.payload.env.Contexts.deviceProtectedDir(ctx, DE_DIR);
        if (dir == null) {
            log.w("boot", "no device-protected directory: no spool and no crash journal");
            return;
        }
        dev.nightjar.payload.capture.CrashJournal journal = new dev.nightjar.payload.capture.CrashJournal(dir);
        spool().set(new dev.nightjar.payload.ipc.PendingUnlockSpool(dir,
                infoValue("bootId", ""), env.payloadVersion(), clock::wallMs, log));
        captureHooks.setJournal(journal);
        crashJournal = journal;
        // An emitted event deletes its journal file at once, so a restart cannot replay a delivered message.
        correlator.setDeliveredListener(journal::delivered);
    }

    /** IPC: handshake, events, counters, health, log; the control binder. */
    private void wireIpc(android.content.Context ctx) {
        dev.nightjar.payload.ipc.AppTarget target = new dev.nightjar.payload.ipc.AppTarget() {
            @Override
            public String appPackage() {
                return config.settings().get().appPackage();
            }

            @Override
            public String appCertSha256() {
                boolean trustInstalled = "true".equals(infoValue("trustInstalledApp", ""));
                // A pin from a stored settings copy can be stale (the app installed again with another key); with
                // trustInstalledApp only a pin that the app sent in this process counts.
                String pinned = trustInstalled ? config.appCertPinFromApp() : config.settings().get().appCertSha256();
                if (!pinned.isEmpty() || !trustInstalled) return pinned;
                // Xposed delivery has no settings.json on disk, and the pin only arrives in the handshake
                // reply that is itself withheld until the pin matches. The installed package's signer is the
                // pin for the first handshake; the app's own settings (same certificate) replace it after.
                return new dev.nightjar.payload.ipc.PackageManagerSigner(ctx.getPackageManager())
                        .signatureSha256(config.settings().get().appPackage());
            }
        };
        dev.nightjar.payload.ipc.HookHealthSource healthSource = new dev.nightjar.payload.ipc.HookHealthSource() {
            @Override
            public String hookHealthJson() {
                return PayloadRuntime.this.hookHealthJson();
            }

            @Override
            public long droppedCount() {
                return ring.droppedCount();
            }
        };
        dev.nightjar.payload.ipc.IpcWorker ipc = new dev.nightjar.payload.ipc.IpcWorker(
                env, target, ring, config,
                new dev.nightjar.payload.ipc.ContentResolverTransport(ctx),
                new dev.nightjar.payload.ipc.PackageManagerSigner(ctx.getPackageManager()),
                clock, log, counters, log, healthSource);
        dev.nightjar.payload.ipc.CallerGate gate = new dev.nightjar.payload.ipc.CallerGate(log);
        ipc.setCallerGate(gate);
        dev.nightjar.payload.ipc.ControlHandler handler =
                new dev.nightjar.payload.ipc.ControlHandler(new ControlTargetImpl(), log);
        dev.nightjar.payload.ipc.ControlBinder binder = new dev.nightjar.payload.ipc.ControlBinder(handler, gate);
        ipc.setControlBinder(binder.asControlBinder());
        controlHandler = handler;
        dev.nightjar.payload.ipc.IpcLoop loop = new dev.nightjar.payload.ipc.IpcLoop(ipc);
        loop.start();
        ipcWorker = ipc;
        ipcLoop = loop;
        registry.addListener(ipc::onHookHealthChanged);
    }

    /** The spool of the previous session, and the messages the previous phone process took with it (a crash). */
    private void replayAfterWiring() {
        restoreSpool();
        dev.nightjar.payload.capture.CrashJournal journal = crashJournal;
        if (journal != null) journal.replay(observations, clock);
    }

    /** Refreshes the cached environment facts and installs the change watcher. Worker. */
    private void refreshEnvironment(android.content.Context ctx) {
        if (!(env instanceof dev.nightjar.payload.env.AndroidEnv androidEnv)) return;
        // The binder and settings reads happen here, on the worker, never on a hook thread.
        androidEnv.refreshBootCount();
        androidEnv.refreshApex();
        log.i("boot", "environment read", Ipc.Key.BOOT_COUNT, env.bootCount());
        if (envWatcher != null) return;
        dev.nightjar.payload.env.EnvWatcher watcher = new dev.nightjar.payload.env.EnvWatcher(androidEnv, worker,
                new dev.nightjar.payload.env.EnvWatcher.Listener() {
                    @Override
                    public void onUserUnlocked() {
                        log.i("env", "user unlocked");
                        dev.nightjar.payload.ipc.IpcWorker w = ipcWorker;
                        if (w == null) return;
                        w.invalidateSession("unlock");
                        // The new handshake runs now, not at the next event.
                        dev.nightjar.payload.ipc.IpcLoop l = ipcLoop;
                        if (l != null) l.kick();
                    }

                    @Override
                    public void onSubscriptionsChanged() {
                        // Rules read slot/roaming per verdict; no other action needed.
                    }
                });
        watcher.start(ctx);
        envWatcher = watcher;
    }

    private String infoValue(String key, String fallback) {
        try {
            String v = new org.json.JSONObject(info).optString(key, null);
            return v == null || v.isEmpty() ? fallback : v;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Target: control binder calls into the payload. */
    private final class ControlTargetImpl implements dev.nightjar.payload.ipc.ControlTarget {
        @Override
        public void updateRules(String json) {
            config.applyRulesJson(json);
            // The next health push carries the new rulesRevision (or the rulesError).
            onFirewallStateChanged();
        }

        @Override
        public void updateSettings(String json) {
            config.applySettingsJson(json);
        }

        @Override
        public void setSafeMode(boolean on) {
            // One source of truth: the settings store, whose listener pushes the value into the
            // clamp. Writing only the FirewallState would be reverted by the next settings push.
            config.setSafeMode(on);
        }

        @Override
        public void enableFirewall(boolean on) {
            config.applyFirewallEnabled(on);
        }

        /** Eligibility is checked on the class of the released PDUs, not on an event in the ring. */
        @Override
        public int release(String eventId, byte[][] pdus, String format, int subId) {
            dev.nightjar.payload.inject.Releaser r = releaser;
            if (r == null) return dev.nightjar.contract.Control.ReleaseResult.NO_DISPATCHER;
            return r.release(eventId, dev.nightjar.payload.firewall.ReleaseEligibility.classify(pdus), pdus, format,
                    subId);
        }

        @Override
        public int replay(String eventId, byte[][] pdus, String format, int subId) {
            dev.nightjar.payload.inject.Replayer r = replayer;
            if (r == null) return dev.nightjar.contract.Control.ReleaseResult.NO_DISPATCHER;
            return r.replay(eventId, dev.nightjar.payload.firewall.ReleaseEligibility.classify(pdus), pdus, format,
                    subId);
        }

        @Override
        public String runSelfTest(String classesJson) {
            dev.nightjar.payload.inject.SelfTestRunner r = selfTest;
            return r == null ? dev.nightjar.contract.Control.SelfTestError.PREFIX
                    + dev.nightjar.contract.Control.SelfTestError.BUSY : r.run(classesJson);
        }

        @Override
        public void cancelSelfTest(String runId) {
            dev.nightjar.payload.inject.SelfTestRunner r = selfTest;
            if (r != null) r.cancel(runId);
        }

        @Override
        public String hookHealthJson() {
            return PayloadRuntime.this.hookHealthJson();
        }

        @Override
        public String logJson(long sinceSeq) {
            return log.linesJson(sinceSeq);
        }

        @Override
        public long pingMs() {
            return clock.elapsedNs() / 1_000_000L;
        }
    }

    // ------------------------------------------------------------------ accessors

    public Session session() {
        return session;
    }

    public PLog log() {
        return log;
    }

    public ConfigStore config() {
        return config;
    }

    public Env env() {
        return env;
    }

    public Correlator correlator() {
        return correlator;
    }

    public Counters counters() {
        return counters;
    }

    public RingBuffer ring() {
        return ring;
    }

    public HookRegistry registry() {
        return registry;
    }

    /** Installs the release/self-test token registry. */
    public void setInjectionTokens(InjectionTokens tokens) {
        correlator.setInjectionTokens(tokens);
    }

    public Observations observations() {
        return observations;
    }

    public SwappableSpool spool() {
        return spool;
    }

    public WapHooks wapHooks() {
        return wapHooks;
    }

    public CbHooks cbHooks() {
        return cbHooks;
    }

    public Clock clock() {
        return clock;
    }

    public Worker worker() {
        return worker;
    }

    public TierManager.Health health() {
        return health;
    }

    public boolean installed() {
        return installedOk;
    }

    /**
     * JSON for the handshake and the control binder. {@code firewallEnabled} and
     * {@code safeMode} are the values the clamps use; the rules keys tell the app whether
     * its last rules push was applied (RULES-X6).
     */
    public String hookHealthJson() {
        TierManager.Health h = health;
        var s = config.settings().get();
        String fp = env.buildFingerprint();
        String profile = config.status(fp, registry::failed).name();
        dev.nightjar.payload.firewall.FirewallState fw = firewallState;
        boolean enabled = fw != null ? fw.firewallEnabled() : config.firewallEnabled();
        boolean safeMode = fw != null ? fw.safeMode() : s.safeMode();
        return registry.hookHealthJson(fp, env.payloadVersion(), profile,
                h == null ? null : h.tiersJson(),
                h != null && h.firewallAvailable(),
                enabled,
                safeMode,
                h != null && h.outboundLive(),
                rulesHealth());
    }

    /** {@code rulesRevision}, {@code rulesError} (class name only, absent when none) and {@code rulesSkipped}. */
    private Map<String, Object> rulesHealth() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        dev.nightjar.payload.config.RulesHolder rules = config.rules();
        m.put("rulesRevision", rules.revision());
        String error = rules.lastError();
        if (error != null) m.put("rulesError", error);
        m.put("rulesSkipped", rules.engine().skippedRules());
        return m;
    }

    /** Queues one {@link #refreshHealth()} on the worker unless one is already queued. Any thread. */
    private void requestHealthRefresh() {
        if (healthRefreshPending.compareAndSet(false, true)) worker.post(this::refreshHealth);
    }

    /** Recomputes tiers after a health change and publishes it. Worker. */
    public void refreshHealth() {
        healthRefreshPending.set(false);
        TierManager.Health h = TierManager.compute(registry);
        health = h;
        // Without this the firewall state stayed "unavailable" forever (the clamp then turned
        // every BLOCK/QUARANTINE into ALLOW), because setAvailable had no caller after wire().
        dev.nightjar.payload.firewall.FirewallState fw = firewallState;
        if (fw != null) {
            fw.setAvailable(h.firewallAvailable());
        }
        // Unhook disabled hooks on the worker, never on the hooked thread.
        uninstallDisabled();
        if (h.unsupported() && session.state() == Session.State.RUNNING) {
            registry.unhookAll();
            session.unsupported();
            log.e("hook", "capture tier lost: unhooking everything");
        }
        dev.nightjar.payload.ipc.IpcWorker w = ipcWorker;
        if (w != null) w.onHookHealthChanged();
    }

    /**
     * Asks ART to deoptimize the small hooked methods so an inlined call site cannot bypass the hook
     * . Runs on the worker after the install, and again after the late IMPL_LOADER hooks install.
     */
    public void deoptimizeSmallHooks() {
        for (String id : DEOPTIMIZE_HOOKS) {
            java.lang.reflect.Member m = installedTargets.get(id);
            if (m == null) continue;
            try {
                bridge.forceDeoptimize(m);
            } catch (Throwable ignored) {
                // Deoptimization is an optimisation; the hook still works on the interpreter.
            }
        }
    }

    /** Unhooks every hook the registry disabled; runs on the worker. */
    private void uninstallDisabled() {
        for (dev.nightjar.payload.hook.HookRegistry.HookRecord r : registry.disabledWithHandle()) {
            long handle = r.handle;
            try {
                bridge.uninstall(handle);
            } catch (Throwable ignored) {
                // Best effort; the hook is already inert in our bookkeeping.
            }
            registry.cleared(handle);
            log.wLimited("disabled." + r.spec.id(), "hook", "hook disabled after repeated errors",
                    "id", r.spec.id());
        }
    }

    /**
     * The firewall state, null before wiring. Exposed for diagnostics and tests.
     */
    public dev.nightjar.payload.firewall.FirewallState firewallState() {
        return firewallState;
    }
}
