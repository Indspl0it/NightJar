package dev.nightjar.payload.env;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import dev.nightjar.payload.firewall.FirewallState;
import dev.nightjar.payload.log.PLog;

/**
 * Emergency-call state for the SUPL grace clamp (HK-25).
 *
 * <p>The source is {@link Phone#isInEmergencyCall} on every {@link PhoneFactory} instance,
 * sampled on the payload worker. There is no public getter for this state ({@code TelecomManager}
 * and {@code TelephonyManager} expose none), and the framework's own emergency tracker only notifies
 * its internal listeners, so a getter read is the only stable option across the reference builds.
 *
 * <p>Sampling is on the worker, never on the main looper: the clamp runs on a hook
 * thread and must read a cached volatile flag. {@link FirewallState#setEmergencyCallActive} derives
 * the end time from the falling edge, so a 2 s cadence bounds the grace-period error to one sample.
 *
 * <p>A failed sample (for example before {@code PhoneFactory} has made the phones) does not stop the watcher: it
 * samples again with a growing delay, up to {@link #MAX_BACKOFF_MS}, and returns to {@link #PERIOD_MS} after the next
 * good sample.
 *
 * <p>Thread: {@link #start} and every sample run on the worker.
 */
public final class EmergencyWatcher {

    /** Sampling period. The grace clamp is minutes wide; 2 s is far finer than it needs. */
    public static final long PERIOD_MS = 2_000L;

    /** Longest delay after repeated failed samples. */
    public static final long MAX_BACKOFF_MS = 60_000L;

    private final FirewallState state;
    private final PLog log;
    private boolean started;
    private long delayMs = PERIOD_MS;
    private boolean failureLogged;

    public EmergencyWatcher(FirewallState state, PLog log) {
        this.state = state;
        this.log = log;
    }

    /** Begins sampling on the worker. A second call does nothing. */
    public void start(Worker worker) {
        if (started || worker == null) return;
        started = true;
        worker.postDelayed(() -> sample(worker), PERIOD_MS);
    }

    private void sample(Worker worker) {
        try {
            state.setEmergencyCallActive(anyPhoneInEmergencyCall());
            delayMs = PERIOD_MS;
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                log.w("Emergency", "emergency state not available: sample again later",
                        "err", t.getClass().getSimpleName());
            }
            delayMs = Math.min(MAX_BACKOFF_MS, delayMs * 2);
        }
        worker.postDelayed(() -> sample(worker), delayMs);
    }

    /** True when any phone reports an emergency call in progress. */
    static boolean anyPhoneInEmergencyCall() {
        Phone[] phones = PhoneFactory.getPhones();
        if (phones == null) return false;
        for (Phone phone : phones) {
            if (phone != null && phone.isInEmergencyCall()) return true;
        }
        return false;
    }
}
