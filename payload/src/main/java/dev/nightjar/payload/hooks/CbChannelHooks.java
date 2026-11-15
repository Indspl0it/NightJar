package dev.nightjar.payload.hooks;

import dev.nightjar.contract.HookState;
import dev.nightjar.contract.Tier;
import dev.nightjar.payload.firewall.CbChannelPolicy;
import dev.nightjar.payload.hook.FaultInjector;
import dev.nightjar.payload.hook.HookBackend;
import dev.nightjar.payload.hook.HookKind;
import dev.nightjar.payload.hook.HookRegistry;
import dev.nightjar.payload.hook.HookSpec;
import dev.nightjar.payload.hook.Hooker;
import dev.nightjar.payload.hook.Param;
import dev.nightjar.payload.hook.Resolver;
import dev.nightjar.payload.log.PLog;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * HK-18: "receive all channels".
 *
 * <p>The receiver app re-programs the modem table on every CB config event, so a single call at boot would be lost.
 * Two hooks make the widening persistent: {@code CellBroadcastConfigTracker#setGsmConfig(List, Request)} replaces the
 * list it is about to send with one range covering {@code 0…65535}, and {@code #setActivation(int ranType, boolean,
 * Request)} is forced on for the GSM RAN type only (no CDMA target). Both are before-hooks that rewrite the argument
 * the original receives: the {@link Hooker} passes {@link Param#args()} to the original call, so a replaced element is
 * what the framework method sees.
 *
 * <p>Everything is fail-open: when {@code cb.allChannels} is off nothing is touched, and when the framework's range
 * type cannot be constructed the original list is passed through unchanged (the feature then does nothing instead of
 * breaking cell broadcast).
 *
 * <p>Turning {@code cb.allChannels} off does not program the modem again at once: the widened table stays until the
 * next configuration that the cell broadcast app sends (SIM change, settings change in that app, or a restart of the
 * phone). Nightjar does not call the tracker itself.
 *
 * <p>The class is its own registrar because HK-18 belongs to the P2 firewall-extra set: {@link #install} builds the
 * specs, resolves them against the phone app class loader and reports the result to the {@link HookRegistry}, exactly
 * as the runtime does for its own hooks.
 *
 * <p>Thread: installation on the worker; bodies on the {@code CellBroadcastConfigTracker} handler thread.
 */
public final class CbChannelHooks {

    public static final String ID_GSM_CONFIG = "HK-18a";
    public static final String ID_ACTIVATION = "HK-18b";

    private static final String TRACKER = "com.android.internal.telephony.CellBroadcastConfigTracker";

    private final BooleanSupplier allChannels;
    private final PLog log;
    private final CbChannelPolicy.RangeFactory rangeFactory;
    private volatile Constructor<?> rangeConstructor;
    private volatile boolean rangeLookupFailed;

    public CbChannelHooks(BooleanSupplier allChannels, PLog log) {
        this(allChannels, log, null);
    }

    /**
     * @param rangeFactory builds the framework's range object; {@code null} uses the reflective
     *     {@code CellBroadcastIdRange(int, int, int, boolean)} constructor
     */
    public CbChannelHooks(BooleanSupplier allChannels, PLog log,
            CbChannelPolicy.RangeFactory rangeFactory) {
        this.allChannels = allChannels == null ? () -> false : allChannels;
        this.log = log;
        this.rangeFactory = rangeFactory;
    }

    /** The two hook specs this class owns. */
    public static List<HookSpec> specs() {
        return List.of(
                new HookSpec(ID_GSM_CONFIG, Tier.FIREWALL_EXTRA, HookKind.BEFORE, TRACKER,
                        "setGsmConfig", List.of(), HookSpec.ResolverKind.NAME, null, 2, false, false),
                new HookSpec(ID_ACTIVATION, Tier.FIREWALL_EXTRA, HookKind.BEFORE, TRACKER,
                        "setActivation", List.of(), HookSpec.ResolverKind.NAME, null, 3, false, false));
    }

    /**
     * Registers and installs both hooks.
     *
     * @param faults fault injector of the session ({@link FaultInjector#NONE} in the APK), or {@code null}
     */
    public void install(HookRegistry registry, HookBackend bridge, FaultInjector faults) {
        install(registry, bridge, faults, null);
    }

    /**
     * Same, resolved against {@code loader}.
     *
     * @param loader the phone app class loader ({@code lpparam.classLoader}); {@code null} uses the system loader
     */
    public void install(HookRegistry registry, HookBackend bridge, FaultInjector faults, ClassLoader loader) {
        if (registry == null || bridge == null) return;
        for (HookSpec spec : specs()) {
            registry.register(spec);
            Resolver.Result r = Resolver.resolve(loader, spec);
            if (!r.isLive()) {
                registry.unresolved(spec.id(), r.state(), r.error());
                warn("hook unresolved: " + spec.id() + " " + r.error());
                continue;
            }
            long handle = 0L;
            try {
                handle = bridge.install(r.target(), spec.kind(),
                        new Hooker(spec, this::before, registry, faults, r.target()));
            } catch (Throwable t) {
                registry.onError(spec.id(), t);
            }
            if (handle != 0L) {
                registry.resolved(spec.id(), r.target().getName(), handle);
            } else {
                registry.unresolved(spec.id(), HookState.FAILED, "install refused by the Xposed bridge");
                warn("hook install refused by the Xposed bridge: " + spec.id());
            }
        }
    }

    /** Dispatches one invocation to the right body (the {@link Hooker} callback for both specs). */
    public void before(Param p) {
        switch (p.hookId()) {
            case ID_GSM_CONFIG -> widenConfig(p);
            case ID_ACTIVATION -> forceActivation(p);
            default -> {
                // Not ours.
            }
        }
    }

    /** {@code setGsmConfig(List ranges, Request)}: replace the list the tracker is about to send. */
    void widenConfig(Param p) {
        if (!allChannels.getAsBoolean()) return;
        Object list = p.arg(0);
        if (!(list instanceof List<?>)) return;
        List<Object> widened = CbChannelPolicy.widen(this::newRange);
        if (widened == null) return;
        // args[0] is `this`, so the List parameter is index 1.
        Object[] args = p.args();
        if (args.length > 1) args[1] = widened;
    }

    /** {@code setActivation(int ranType, boolean activate, Request)}: keep activation on for the GSM RAN type. */
    void forceActivation(Param p) {
        if (!allChannels.getAsBoolean()) return;
        if (!(p.arg(0) instanceof Integer ranType) || ranType != CbChannelPolicy.RAN_TYPE_GSM) return;
        // args[0] is `this`, so the boolean parameter is index 2.
        Object[] args = p.args();
        if (args.length > 2) args[2] = Boolean.TRUE;
    }

    /**
     * Builds the framework's range object; null when this build cannot build one.
     *
     * <p>It must be a {@code android.telephony.CellBroadcastIdRange}: the framework casts every element of the list it
     * receives, so any other type makes the original throw on the telephony thread.
     */
    private Object newRange(int from, int to) {
        if (rangeFactory != null) {
            try {
                return rangeFactory.create(from, to);
            } catch (Throwable t) {
                warn("range factory failed: " + t.getClass().getSimpleName());
                return null;
            }
        }
        Constructor<?> ctor = rangeConstructor;
        if (ctor == null) {
            if (rangeLookupFailed) return null;
            ctor = findRangeConstructor();
            if (ctor == null) {
                rangeLookupFailed = true;
                warn("no CellBroadcastIdRange(int,int,int,boolean): all-channels unavailable");
                return null;
            }
            rangeConstructor = ctor;
        }
        try {
            return ctor.newInstance(from, to, CbChannelPolicy.RAN_TYPE_GSM, true);
        } catch (Throwable t) {
            warn("cannot build a CB range: " + t.getClass().getSimpleName());
            return null;
        }
    }

    private static Constructor<?> findRangeConstructor() {
        try {
            Class<?> type = Class.forName("android.telephony.CellBroadcastIdRange", false,
                    CbChannelHooks.class.getClassLoader());
            return type.getConstructor(int.class, int.class, int.class, boolean.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private void warn(String message) {
        if (log != null) log.wLimited("cb-all-channels", "CbChannels", message);
    }
}
