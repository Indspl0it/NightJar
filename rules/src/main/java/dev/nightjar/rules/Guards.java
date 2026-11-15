package dev.nightjar.rules;

/** Immutable {@link GuardState} value (app previews, tests, fixtures). */
public record Guards(
        boolean safeMode,
        boolean firewallAvailable,
        boolean firewallEnabled,
        boolean emergencyCallActive,
        long emergencyCallEndedAtMs,
        long nowMs)
        implements GuardState {

    /** Firewall available and enabled, no safe mode, no emergency: shows what the rules themselves decide. */
    public static final Guards NOMINAL = new Guards(false, true, true, false, -1, 0);

    public Guards withSafeMode(boolean v) {
        return new Guards(v, firewallAvailable, firewallEnabled, emergencyCallActive, emergencyCallEndedAtMs, nowMs);
    }

    public Guards withFirewall(boolean available, boolean enabled) {
        return new Guards(safeMode, available, enabled, emergencyCallActive, emergencyCallEndedAtMs, nowMs);
    }

    public Guards withEmergency(boolean active, long endedAtMs, long now) {
        return new Guards(safeMode, firewallAvailable, firewallEnabled, active, endedAtMs, now);
    }
}
