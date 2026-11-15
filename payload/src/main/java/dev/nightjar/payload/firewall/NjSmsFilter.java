package dev.nightjar.payload.firewall;

import com.android.internal.telephony.InboundSmsHandler;
import com.android.internal.telephony.InboundSmsTracker;
import dev.nightjar.contract.Hashes;
import dev.nightjar.contract.HookState;
import dev.nightjar.contract.Tier;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.payload.capture.Observation;
import dev.nightjar.payload.capture.Observations;
import dev.nightjar.payload.hook.HookKind;
import dev.nightjar.payload.hook.HookRegistry;
import dev.nightjar.payload.hook.HookSpec;
import java.util.List;

/**
 * HK-08 adapter: the {@code InboundSmsHandler.SmsFilter} the framework calls on the main looper.
 *
 * <p>It only marshals framework objects into {@link SmsFilterCore} and routes a drop to HK-R3
 * ({@code dropFilteredSms}) with {@code blocked=true}. Every decision lives in the core so it is unit-tested on the JVM.
 *
 * <p>HK-08 has no native hook of its own: the filter runs because HK-24 (or the startup sweep) put it in the handler's
 * chain. {@link FilterInstaller} therefore marks the hook LIVE the moment the chain really contains this filter, so the
 * self-test's "is the interception point live?" check and the health report see the truth.
 *
 * <p>Thread: main looper.
 */
public final class NjSmsFilter implements InboundSmsHandler.SmsFilter {

    /** Hook id for health accounting. */
    public static final String HOOK_ID = "HK-08";

    /** Row: the head filter the framework calls before its own filters. */
    private static final HookSpec SPEC = new HookSpec(HOOK_ID, Tier.FIREWALL_CORE, HookKind.BEFORE,
            "com.android.internal.telephony.InboundSmsHandler$SmsFilter", "filterSms", List.of(),
            HookSpec.ResolverKind.NAME, null, 7, false, true);

    private final InboundSmsHandler handler;
    private final SmsFilterCore core;
    private final Observations observations;
    private final HookRegistry hooks;
    private final SlotRoaming facts;

    /** Live slot/roaming lookup for the subscription of a message. */
    @FunctionalInterface
    public interface SlotRoaming {
        /** @return {@code {slot, roaming ? 1 : 0}}; {@code {-1, 0}} when unknown */
        int[] of(int subId);

        SlotRoaming UNKNOWN = subId -> new int[] {-1, 0};
    }

    public NjSmsFilter(InboundSmsHandler handler, SmsFilterCore core, Observations observations, HookRegistry hooks,
            SlotRoaming facts) {
        this.handler = handler;
        this.core = core;
        this.observations = observations;
        this.hooks = hooks;
        this.facts = facts == null ? SlotRoaming.UNKNOWN : facts;
    }

    /**
     * Called by the framework itself on the main looper, not through a hook, so no {@code Hooker} catch-all protects
     * it: every failure here is caught, counted against HK-08 and answered with {@code false} (deliver).
     */
    @Override
    public boolean filterSms(byte[][] pdus, int destPort, InboundSmsTracker tracker,
            InboundSmsHandler.SmsBroadcastReceiver resultReceiver, boolean userUnlocked, boolean isBlockedNumber,
            List<InboundSmsHandler.SmsFilter> remainingFilters) {
        try {
            byte[] first = pdus == null || pdus.length == 0 ? null : pdus[0];
            int slot = -1;
            boolean roaming = false;
            int[] slotRoaming = facts.of(subIdOf(tracker));
            if (slotRoaming != null && slotRoaming.length >= 2) {
                slot = slotRoaming[0];
                roaming = slotRoaming[1] != 0;
            }
            SmsFilterCore.Input input = new SmsFilterCore.Input(pdus, destPort, userUnlocked, slot, roaming);
            return core.filter(input, new Host(first, tracker, resultReceiver));
        } catch (Throwable t) {
            try {
                HookRegistry registry = hooks;
                if (registry != null) registry.onError(HOOK_ID, t);
            } catch (Throwable ignored) {
                // Accounting must never turn a delivered message into a crash.
            }
            return false;
        }
    }

    /**
     * Marks HK-08 LIVE: the filter is in a handler's chain, so the framework will call it.
     *
     * <p>The handle stays 0 because there is no native hook to uninstall; the hook is dead the moment the process is.
     */
    public void markInstalled(String target) {
        HookRegistry registry = hooks;
        if (registry == null) return;
        registry.register(SPEC);
        if (registry.state(HOOK_ID) != HookState.LIVE) {
            registry.resolved(HOOK_ID, target == null ? SPEC.targetName() : target, 0L);
        }
    }

    /** Per-call host: carries the framework objects the core needs, and nothing else. */
    private final class Host implements SmsFilterHost {
        private final byte[] firstPdu;
        private final InboundSmsTracker tracker;
        private final InboundSmsHandler.SmsBroadcastReceiver receiver;

        Host(byte[] firstPdu, InboundSmsTracker tracker, InboundSmsHandler.SmsBroadcastReceiver receiver) {
            this.firstPdu = firstPdu;
            this.tracker = tracker;
            this.receiver = receiver;
        }

        @Override
        public void dropFilteredSms() {
            // HK-R3: drop with blocked=true — a permanent delete plus sendMessage(3). blocked=false would only mark the
            // raw-table row deleted, and re-injecting the same PDU then hits the duplicate check and is discarded
            // (: a quarantined CL-NORMAL message could never be released).
            OWN_DROP.set(Boolean.TRUE);
            try {
                invokeDrop(handler, tracker, receiver);
            } finally {
                OWN_DROP.remove();
            }
        }

        @Override
        public void publishVerdict(VerdictRecord record, long tNs) {
            if (firstPdu == null) return;
            String key = Hashes.tpduKey(Hashes.stripSmsc(firstPdu));
            String altKey = Hashes.tpduKey(firstPdu);
            observations.offer(Observation.verdict(key, altKey, phoneId(), record.action(), record.ruleId(),
                    record.reasonWire(), tNs));
        }

        @Override
        public void publishStage(TraceStage stage, String detail, long tNs) {
            if (firstPdu == null) return;
            String key = Hashes.tpduKey(Hashes.stripSmsc(firstPdu));
            observations.offer(Observation.stage(key, Hashes.tpduKey(firstPdu), phoneId(), stage, tNs, detail));
        }

        @Override
        public void onError(Throwable t) {
            if (hooks != null) hooks.onError(HOOK_ID, t);
        }

        private int phoneId() {
            try {
                com.android.internal.telephony.Phone phone = handler.getPhone();
                return phone == null ? -1 : phone.getPhoneId();
            } catch (Throwable t) {
                return -1;
            }
        }
    }


    /**
     * {@code dropFilteredSms} is private on both reference builds (dex access flags), so a direct call from the module's
     * class loader fails with IllegalAccessError: it goes through reflection. {@code getPhone} and
     * {@code InboundSmsTracker.getSubId} are public and are called directly.
     *
     * <p>The cached method is a volatile field without a lock: two threads that look it up at the same time both find
     * the same method, so the race is harmless and the main looper never waits on a monitor.
     */
    private static volatile java.lang.reflect.Method dropMethod;

    private static final ThreadLocal<Boolean> OWN_DROP = new ThreadLocal<>();

    /** True while this thread is inside the head filter's own {@code dropFilteredSms}: the drop is ours, not the block list's. */
    public static boolean ownDropInProgress() {
        return Boolean.TRUE.equals(OWN_DROP.get());
    }

    private static void invokeDrop(InboundSmsHandler handler, InboundSmsTracker tracker, Object receiver) {
        try {
            java.lang.reflect.Method m = dropMethod;
            if (m == null || !m.getDeclaringClass().isInstance(handler)) {
                m = findDrop(handler.getClass());
                if (m == null) throw new NoSuchMethodError("dropFilteredSms");
                m.setAccessible(true);
                dropMethod = m;
            }
            m.invoke(handler, tracker, receiver, Boolean.TRUE);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            if (c instanceof Error) throw (Error) c;
            throw new IllegalStateException(c);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.lang.reflect.Method findDrop(Class<?> start) {
        for (Class<?> k = start; k != null; k = k.getSuperclass()) {
            for (java.lang.reflect.Method c : k.getDeclaredMethods()) {
                if (c.getName().equals("dropFilteredSms") && c.getParameterCount() == 3) return c;
            }
        }
        return null;
    }

    private static int subIdOf(InboundSmsTracker tracker) {
        if (tracker == null) return -1;
        try {
            return tracker.getSubId();
        } catch (Throwable t) {
            return -1;
        }
    }
}
