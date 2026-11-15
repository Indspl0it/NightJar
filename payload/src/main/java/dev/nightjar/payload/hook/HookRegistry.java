package dev.nightjar.payload.hook;

import dev.nightjar.contract.Ipc;

import dev.nightjar.contract.HookState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-hook state, error counting, latency and health reporting (06, 10).
 *
 * <p>A hook that throws more than {@link #MAX_ERRORS} times in one session becomes
 * {@link HookState#DISABLED} and is unhooked; the tier manager then recomputes.
 *
 * <p>Thread: resolution and health reads on the worker; {@link #onError} and
 * {@link #recordLatency} from any hooked thread.
 */
public final class HookRegistry {
    /** More than this many errors in one session disables the hook. */
    public static final int MAX_ERRORS = 20;

    /** One hook's live state. */
    public static final class HookRecord {
        public final HookSpec spec;
        public volatile HookState state = HookState.NOT_FOUND;
        public volatile String target;
        public volatile String lastError;
        public volatile long handle;
        public final java.util.concurrent.atomic.AtomicInteger errorCount =
                new java.util.concurrent.atomic.AtomicInteger();
        /** Exceptions of the original that the hook's shield turned into a result (HK-14). Not errors. */
        public final java.util.concurrent.atomic.AtomicInteger recoveredCount =
                new java.util.concurrent.atomic.AtomicInteger();
        public final LatencyProbe probe = new LatencyProbe();

        HookRecord(HookSpec spec) {
            this.spec = spec;
        }
    }

    /** Notified after any state change. */
    public interface Listener {
        void onHealthChanged();
    }

    private final Map<String, HookRecord> hooks = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile HookBackend bridge = HookBackend.NONE;
    private volatile long droppedCount;
    private volatile long droppedObservations;
    private volatile boolean spoolDisabled;

    public void setBridge(HookBackend bridge) {
        this.bridge = bridge == null ? HookBackend.NONE : bridge;
    }

    public void addListener(Listener l) {
        if (l != null) listeners.add(l);
    }

    public void register(HookSpec spec) {
        if (spec == null) return;
        hooks.putIfAbsent(spec.id(), new HookRecord(spec));
    }

    public HookRecord get(String id) {
        return hooks.get(id);
    }

    public Collection<HookRecord> all() {
        return hooks.values();
    }

    public HookState state(String id) {
        HookRecord r = hooks.get(id);
        return r == null ? HookState.NOT_FOUND : r.state;
    }

    public boolean live(String id) {
        return state(id) == HookState.LIVE;
    }

    public boolean failed(String id) {
        HookState s = state(id);
        return s == HookState.NOT_FOUND || s == HookState.FAILED || s == HookState.DISABLED;
    }

    public int errorCount(String id) {
        HookRecord r = hooks.get(id);
        return r == null ? 0 : r.errorCount.get();
    }

    /** Records a successful resolution. */
    public void resolved(String id, String target, long handle) {
        HookRecord r = hooks.get(id);
        if (r == null) return;
        r.state = HookState.LIVE;
        r.target = target;
        r.handle = handle;
        fire();
    }

    /** Records a resolution failure; {@code state} is NOT_FOUND, FAILED or NOT_APPLICABLE. */
    public void unresolved(String id, HookState state, String error) {
        HookRecord r = hooks.get(id);
        if (r == null) return;
        r.state = state;
        r.lastError = error;
        fire();
    }

    /**
     * Counts one error and disables the hook after {@link #MAX_ERRORS}.
     *
     * <p>Runs on the hooked thread, so it only flips the state and notifies the listener; the
     * listener posts the Xposed {@code Unhook} to the worker. Unhooking inline would change the
     * hook table on the phone main looper or a HAL binder thread.
     */
    public void onError(String id, Throwable t) {
        HookRecord r = hooks.get(id);
        if (r == null) return;
        int n = r.errorCount.incrementAndGet();
        // An exception message can echo a rule pattern, a certificate hash or
        // argument data. Only the class name is safe to report.
        r.lastError = t == null ? "unknown" : t.getClass().getSimpleName();
        if (n > MAX_ERRORS && r.state != HookState.DISABLED) {
            r.state = HookState.DISABLED;
            fire();
        }
    }

    /**
     * Disables the hook at once: for a failure that will repeat on every call, for example
     * when the original cannot be called at all. The listener posts the uninstall to the worker, so
     * later calls go straight to the framework. Any thread.
     */
    public void disable(String id, Throwable t) {
        HookRecord r = hooks.get(id);
        if (r == null) return;
        r.errorCount.incrementAndGet();
        r.lastError = t == null ? "unknown" : t.getClass().getSimpleName();
        if (r.state != HookState.DISABLED) {
            r.state = HookState.DISABLED;
            fire();
        }
    }

    /** Counts one exception of the original that the shield recovered. Any thread. */
    public void recovered(String id) {
        HookRecord r = hooks.get(id);
        if (r != null) r.recoveredCount.incrementAndGet();
    }

    /** Hooks that were disabled and still hold a hook handle; the worker unhooks them. */
    public List<HookRecord> disabledWithHandle() {
        List<HookRecord> out = new ArrayList<>();
        for (HookRecord r : hooks.values()) {
            if (r.state == HookState.DISABLED && r.handle != 0) out.add(r);
        }
        return out;
    }

    /** Removes one hook's native handle after the worker unhooked it. */
    public void cleared(long handle) {
        for (HookRecord r : hooks.values()) {
            if (r.handle == handle) {
                r.handle = 0;
                return;
            }
        }
    }

    public void recordLatency(String id, long ns) {
        HookRecord r = hooks.get(id);
        if (r != null) r.probe.record(ns);
    }

    public void setDroppedCount(long droppedEvents) {
        this.droppedCount = droppedEvents;
    }

    public void setDroppedObservations(long dropped) {
        this.droppedObservations = dropped;
    }

    public long droppedCount() {
        return droppedCount;
    }

    public long droppedObservations() {
        return droppedObservations;
    }

    /** {@code hookHealth.spool = DISABLED} when the pending-unlock spool failed. */
    public void setSpoolDisabled(boolean disabled) {
        this.spoolDisabled = disabled;
    }

    public boolean spoolDisabled() {
        return spoolDisabled;
    }

    /**
     * Unhooks every live hook (capture-tier failure). Each unhooked record becomes
     * {@link HookState#DISABLED} with the reason "unhooked", so the health document does not report
     * hooks that are gone. No listener is notified: the caller publishes the health.
     */
    public void unhookAll() {
        for (HookRecord r : hooks.values()) {
            long h = r.handle;
            if (h != 0) {
                try {
                    bridge.uninstall(h);
                } catch (Throwable ignored) {
                    // Best effort.
                }
                r.handle = 0;
                r.state = HookState.DISABLED;
                r.lastError = "unhooked";
            }
        }
    }

    // ------------------------------------------------------------------ health JSON

    /**
     * object. {@code tiers} carries the tier counts computed by {@link TierManager};
     * this method only fills the hook map and the counters.
     */
    public String hookHealthJson(String fingerprint, String payloadVersion, String profile,
            java.util.Map<String, Object> tiers, boolean firewallAvailable, boolean firewallEnabled,
            boolean safeMode, boolean outboundEnabled, java.util.Map<String, Object> extra) {
        dev.nightjar.payload.JsonBuilder o = new dev.nightjar.payload.JsonBuilder()
                .put("fingerprint", fingerprint == null ? "" : fingerprint)
                .put(Ipc.Key.PAYLOAD_VERSION, payloadVersion == null ? "" : payloadVersion)
                .put("profile", profile == null ? "UNKNOWN" : profile)
                .put("tiers", tiers == null ? java.util.Map.of() : tiers);
        dev.nightjar.payload.JsonBuilder hookMap = new dev.nightjar.payload.JsonBuilder();
        List<String> ids = new ArrayList<>(hooks.keySet());
        java.util.Collections.sort(ids);
        for (String id : ids) {
            HookRecord r = hooks.get(id);
            java.util.Map<String, Object> h = new dev.nightjar.payload.JsonBuilder()
                    .put("state", r.state.wire())
                    .put("tier", r.spec.tier().wire())
                    .put("target", r.target == null ? r.spec.targetName() : r.target)
                    .put("errorCount", r.errorCount.get())
                    .put("recoveredCount", r.recoveredCount.get())
                    .put("lastError", r.lastError)
                    .put("p50Ns", r.probe.p50())
                    .put("p99Ns", r.probe.p99())
                    .map();
            hookMap.put(id, h);
        }
        o.put("hooks", hookMap.map())
                .put("firewallAvailable", firewallAvailable)
                .put(Ipc.Key.FIREWALL_ENABLED, firewallEnabled)
                .put("safeMode", safeMode)
                .put("outboundEnabled", outboundEnabled)
                .put(Ipc.Key.DROPPED_COUNT, droppedCount)
                .put("droppedObservations", droppedObservations);
        if (spoolDisabled) o.put("spool", "DISABLED");
        if (extra != null) {
            for (Map.Entry<String, Object> e : extra.entrySet()) {
                if (e.getValue() != null) o.put(e.getKey(), e.getValue());
            }
        }
        return o.json();
    }

    /** Convenience for callers that do not have a tier object yet (tests). */
    public String hookHealthJson(String fingerprint, String payloadVersion, String profile) {
        return hookHealthJson(fingerprint, payloadVersion, profile, java.util.Map.of(), false, false,
                false, false, null);
    }

    private void fire() {
        for (Listener l : listeners) {
            try {
                l.onHealthChanged();
            } catch (Throwable ignored) {
                // A listener must never break the hook path.
            }
        }
    }
}
