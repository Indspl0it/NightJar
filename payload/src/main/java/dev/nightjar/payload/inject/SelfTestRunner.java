package dev.nightjar.payload.inject;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Control;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.payload.env.Worker;
import dev.nightjar.payload.log.PLog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Self-test engine.
 *
 * <p>{@code runSelfTest(classesJson)} returns a run id or {@code ERR:LOCKED|BUSY|INVALID}. For each
 * requested class it checks the Appendix F safety preconditions and reports {@code SKIPPED:<hook>} instead of injecting
 * when a required hook is not LIVE — injecting anyway could let a canary reach Google Messages (AC2).
 *
 * <p>Each canary's TPDU key is registered in the {@link TokenRegistry} <em>before</em> injection, which is what
 * lets HK-08/HK-14/HK-15/HK-23 intercept it and the correlator flag the event with {@code selfTest}, {@code runId} and
 * {@code canaryClass}. Interception is not a verdict: it works with the firewall unavailable or disabled.
 *
 * <p>Cancel stops further injections; a canary already in flight keeps its token and is still intercepted.
 *
 * <p>Thread: {@link #run(String)} is called on the control-binder thread and only validates and
 * schedules; the injections run on the worker given to the constructor. The injector is
 * synchronous and can block for up to ten seconds per canary, which must never hold a binder thread
 * of the phone process. The runtime gives a separate self-test thread, not the payload worker, so the
 * event drain and the IPC keep running during a run.
 */
public final class SelfTestRunner {

    /** Prefixes of the per-class status. */
    public static final String SKIPPED = "SKIPPED:";
    public static final String INJECTED = "INJECTED";
    public static final String FAILED = "FAILED:";

    /** Runs everything inline; only for tests that call {@code run()} without a payload worker. */
    private static final Worker INLINE_WORKER = new Worker() {
        @Override
        public void post(Runnable r) {
            r.run();
        }

        @Override
        public void postDelayed(Runnable r, long delayMs) {
            r.run();
        }

        @Override
        public void cancel(Runnable r) {
            // Nothing is queued.
        }

        @Override
        public boolean isWorkerThread() {
            return true;
        }
    };

    /** What the runner needs to know about hook health (hook registry). */
    @FunctionalInterface
    public interface HookStates {
        boolean live(String hookId);

        /** Everything LIVE (tests, and builds without a registry). */
        HookStates ALL = hookId -> true;
    }

    private final TokenRegistry tokens;
    private final SmsInjector injector;
    private final Worker worker;
    private final HookStates hooks;
    private final BooleanSupplier userUnlocked;
    private final IntSupplier defaultSubId;
    private final Clock clock;
    private final PLog log;

    private final Map<String, String> lastStatus = new LinkedHashMap<>();
    private final AtomicReference<String> currentRunId = new AtomicReference<>();
    private volatile String cancelledRun;

    /** Test/local overload: injections run inline (no payload worker). */
    public SelfTestRunner(TokenRegistry tokens, SmsInjector injector, HookStates hooks,
            BooleanSupplier userUnlocked, IntSupplier defaultSubId, Clock clock, PLog log) {
        this(tokens, injector, null, hooks, userUnlocked, defaultSubId, clock, log);
    }

    /**
     * @param userUnlocked {@code UserManager.isUserUnlocked()}
     * @param defaultSubId subscription used for the injection
     */
    public SelfTestRunner(TokenRegistry tokens, SmsInjector injector, Worker worker, HookStates hooks,
            BooleanSupplier userUnlocked, IntSupplier defaultSubId, Clock clock, PLog log) {
        this.tokens = tokens;
        this.injector = injector;
        this.worker = worker == null ? INLINE_WORKER : worker;
        this.hooks = hooks == null ? HookStates.ALL : hooks;
        this.userUnlocked = userUnlocked == null ? () -> true : userUnlocked;
        this.defaultSubId = defaultSubId == null ? () -> 0 : defaultSubId;
        this.clock = clock;
        this.log = log;
    }

    /**
     * Starts a run.
     *
     * @param classesJson JSON array of class names, or an object with a {@code classes} array
     * @return the run id, or {@code ERR:<code>}
     */
    public String run(String classesJson) {
        if (userUnlocked != null && !userUnlocked.getAsBoolean()) {
            warn("SelfTest", "self-test refused: user locked");
            return Control.SelfTestError.PREFIX + Control.SelfTestError.LOCKED;
        }
        List<CanaryFactory.Canary> classes = parse(classesJson);
        if (classes == null || classes.isEmpty()) {
            warn("SelfTest", "self-test refused: invalid class list");
            return Control.SelfTestError.PREFIX + Control.SelfTestError.INVALID;
        }

        String runId = UUID.randomUUID().toString();
        if (!currentRunId.compareAndSet(null, runId)) {
            return Control.SelfTestError.PREFIX + Control.SelfTestError.BUSY;
        }
        cancelledRun = null;
        synchronized (lastStatus) {
            lastStatus.clear();
        }
        final List<CanaryFactory.Canary> scheduled = classes;
        worker.post(() -> execute(runId, scheduled));
        info("SelfTest", "run scheduled: " + classes.size() + " canaries");
        return runId;
    }

    /** Injects the canaries on the worker; clears the busy flag when it ends. */
    private void execute(String runId, List<CanaryFactory.Canary> classes) {
        int injected = 0;
        try {
            for (CanaryFactory.Canary canary : classes) {
                if (runId.equals(cancelledRun)) break;
                if (inject(runId, canary)) injected++;
            }
        } catch (Throwable t) {
            warn("SelfTest", "run failed: " + t.getClass().getSimpleName());
        } finally {
            currentRunId.compareAndSet(runId, null);
        }
        info("SelfTest", "run finished: " + injected + "/" + classes.size() + " canaries injected");
    }

    /** Stops further injections of {@code runId}; tokens already registered stay until consumed or expired. */
    public void cancel(String runId) {
        if (runId == null || !runId.equals(currentRunId.get())) return;
        cancelledRun = runId;
        info("SelfTest", "run cancelled");
    }

    public boolean running() {
        return currentRunId.get() != null;
    }

    /** The run id currently injecting, or {@code null} when idle. */
    public String currentRunId() {
        return currentRunId.get();
    }

    /** Per-class status of the last run: {@code INJECTED}, {@code SKIPPED:<hook>} or {@code FAILED:<code>}. */
    public Map<String, String> lastStatus() {
        synchronized (lastStatus) {
            return new LinkedHashMap<>(lastStatus);
        }
    }

    /** Per-class status as JSON, for hook health and diagnostics (no message content). */
    public String lastStatusJson() {
        JSONObject o = new JSONObject();
        try {
            synchronized (lastStatus) {
                for (Map.Entry<String, String> e : lastStatus.entrySet()) o.put(e.getKey(), e.getValue());
            }
        } catch (org.json.JSONException e) {
            return "{}";
        }
        return o.toString();
    }

    private boolean inject(String runId, CanaryFactory.Canary canary) {
        String missing = firstMissingHook(canary);
        if (missing != null) {
            status(canary, SKIPPED + missing);
            warn("SelfTest", "canary skipped, " + missing + " not LIVE: " + canary.name());
            return false;
        }
        CanaryFactory.Pdu pdu;
        try {
            pdu = CanaryFactory.build(canary);
        } catch (Throwable t) {
            status(canary, FAILED + Control.ReleaseResult.GENERIC_FAILURE);
            return false;
        }
        String[] keys = Releaser.keys(pdu.radioPdu());
        tokens.registerCanary(keys[0], keys[1], runId, canary.classId().wire());
        // HK-14 sees only the WAP payload, so a port-2948 canary also needs that key.
        String[] payloadKeys = Releaser.payloadKeys(pdu.radioPdu());
        if (payloadKeys != null) tokens.registerCanary(payloadKeys[0], payloadKeys[1], runId, canary.classId().wire());
        int result;
        try {
            result = injector.inject(pdu.radioPdu(), "3gpp", defaultSubId.getAsInt());
        } catch (Throwable t) {
            result = Control.ReleaseResult.GENERIC_FAILURE;
        }
        if (result == Control.ReleaseResult.HANDLED) {
            status(canary, INJECTED);
            return true;
        }
        if (result != Control.ReleaseResult.TIMEOUT) {
            // After a timeout the injection may still land, so the token has to stay until
            // its TTL; dropping it now would let the canary be re-ruled or leak.
            tokens.discard(keys[0], keys[1]);
            if (payloadKeys != null) tokens.discard(payloadKeys[0], payloadKeys[1]);
        }
        status(canary, FAILED + result);
        warn("SelfTest", "canary injection failed: " + canary.name() + " result " + result);
        return false;
    }

    private String firstMissingHook(CanaryFactory.Canary canary) {
        for (String hook : canary.requiredHook().split(",")) {
            String id = hook.trim();
            if (!id.isEmpty() && !hooks.live(id)) return id;
        }
        return null;
    }

    private void status(CanaryFactory.Canary canary, String value) {
        synchronized (lastStatus) {
            lastStatus.put(canary.name(), value);
        }
    }

    /**
     * Parses the class list. Names are case-insensitive and accept {@code CL-} prefixes; duplicates are collapsed and the
     * result is in Appendix F order so the run is reproducible.
     *
     * @return the classes, or {@code null} when the document is not a valid list
     */
    static List<CanaryFactory.Canary> parse(String classesJson) {
        if (classesJson == null || classesJson.isBlank()) return null;
        JSONArray array;
        try {
            String trimmed = classesJson.trim();
            if (trimmed.startsWith("[")) {
                array = new JSONArray(trimmed);
            } else {
                JSONObject o = new JSONObject(trimmed);
                array = o.optJSONArray("classes");
            }
        } catch (Exception e) {
            return null;
        }
        if (array == null) return null;
        List<CanaryFactory.Canary> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (!(item instanceof String)) return null;
            CanaryFactory.Canary c = CanaryFactory.Canary.parse((String) item);
            if (c == null) return null;
            if (!out.contains(c)) out.add(c);
        }
        out.sort(Enum::compareTo);
        return out;
    }

    /** Class ids of the last run, for the app's report. */
    public List<ClassId> lastClasses() {
        List<ClassId> out = new ArrayList<>();
        synchronized (lastStatus) {
            for (String name : lastStatus.keySet()) {
                CanaryFactory.Canary c = CanaryFactory.Canary.parse(name);
                if (c != null) out.add(c.classId());
            }
        }
        return out;
    }

    private void warn(String tag, String message) {
        if (log != null) log.w(tag, message);
    }

    private void info(String tag, String message) {
        if (log != null) log.i(tag, message);
    }
}
