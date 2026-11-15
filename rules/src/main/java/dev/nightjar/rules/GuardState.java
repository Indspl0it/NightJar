package dev.nightjar.rules;

/**
 * Runtime state read by the clamps. The payload implements this over its volatile {@code FirewallState}
 * and {@code EmergencyState}; the app uses {@link Guards#NOMINAL} for previews.
 *
 * <p>Implementations must be cheap and non-blocking: they are called on the phone main looper for every verdict.
 * Times use one monotonic clock (e.g. {@code SystemClock.elapsedRealtime()}).
 */
public interface GuardState {
    /** Safe mode: BLOCK/QUARANTINE become ALLOW. */
    boolean safeMode();

    /** Firewall available on this build (profile + hooks). */
    boolean firewallAvailable();

    /** Firewall enabled by the user (handshake reply key {@code Ipc.Key.FIREWALL_ENABLED}). */
    boolean firewallEnabled();

    /** An emergency call is in progress (HK-25). */
    boolean emergencyCallActive();

    /** When the last emergency call ended, in {@link #nowMs()} clock; negative when none this boot. */
    long emergencyCallEndedAtMs();

    /** Current time in the same clock as {@link #emergencyCallEndedAtMs()}. */
    long nowMs();
}
