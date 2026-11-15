package dev.nightjar.payload.firewall;

import dev.nightjar.payload.env.Clock;
import dev.nightjar.rules.GuardState;

/**
 * Firewall availability, enablement, safe mode and emergency state.
 *
 * <p>Every field is volatile and read once per verdict, so a change from the control binder or the app is visible to
 * the next message with no locking (: well under 1 s).
 *
 * <p>Emergency state: the framework tracker (HK-25) exposes only booleans, so this class derives the end time
 * itself — the transition to "no emergency call" stamps {@code emergencyCallEndedAtMs}, which is what the SUPL grace
 * clamp needs.
 *
 * <p>Thread: setters from the worker/control binder; {@link #nowMs()} and the getters from any thread.
 */
public final class FirewallState implements GuardState {

    private final Clock clock;
    private volatile boolean available;
    private volatile boolean enabled;
    private volatile boolean safeMode;
    private volatile boolean emergencyCallActive;
    private volatile long emergencyCallEndedAtMs = -1L;

    public FirewallState(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean safeMode() {
        return safeMode;
    }

    @Override
    public boolean firewallAvailable() {
        return available;
    }

    @Override
    public boolean firewallEnabled() {
        return enabled;
    }

    @Override
    public boolean emergencyCallActive() {
        return emergencyCallActive;
    }

    @Override
    public long emergencyCallEndedAtMs() {
        return emergencyCallEndedAtMs;
    }

    @Override
    public long nowMs() {
        return clock.elapsedMs();
    }

    /** Firewall tier resolved (TierManager: HK-24, HK-14 and HK-15 LIVE). */
    public void setAvailable(boolean value) {
        if (available == value) return;
        available = value;
        notifyChanged();
    }

    /** User enablement: {@code ConfigStore.firewallEnabled()}, on unless the app switched it off. */
    public void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        notifyChanged();
    }

    /** BLOCK/QUARANTINE become ALLOW, NOTIFY survives. */
    public void setSafeMode(boolean value) {
        if (safeMode == value) return;
        safeMode = value;
        notifyChanged();
    }

    /** HK-25 / the telephony callback: starts the grace period when the call ends. */
    public void setEmergencyCallActive(boolean active) {
        boolean was = emergencyCallActive;
        emergencyCallActive = active;
        if (was && !active) {
            emergencyCallEndedAtMs = clock.elapsedMs();
            notifyChanged();
        } else if (was != active) {
            notifyChanged();
        }
    }

    /** Notified on any state change so the payload can push hook health. */
    public interface Listener {
        void onFirewallStateChanged();
    }

    private volatile Listener listener;

    /** PayloadRuntime sets the listener in wireFirewall; it pushes hook health to the app. */
    public void setListener(Listener l) {
        this.listener = l;
    }

    private void notifyChanged() {
        Listener l = listener;
        if (l == null) return;
        try {
            l.onFirewallStateChanged();
        } catch (Throwable t) {
            // A failing listener must never break enforcement.
        }
    }

    /** The state block reported in hook health. */
    public Snapshot snapshot() {
        return new Snapshot(available, enabled, safeMode, emergencyCallActive, emergencyCallEndedAtMs);
    }

    /** Immutable view for health reporting and tests. */
    public record Snapshot(boolean available, boolean enabled, boolean safeMode, boolean emergencyCallActive,
            long emergencyCallEndedAtMs) {}

    /** Test/debug view of the last transition, without exposing the clock. */
    @Override
    public String toString() {
        return "FirewallState{available=" + available + " enabled=" + enabled + " safe=" + safeMode
                + " emergency=" + emergencyCallActive + "}";
    }
}
