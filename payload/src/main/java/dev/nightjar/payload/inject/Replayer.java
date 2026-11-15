package dev.nightjar.payload.inject;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Control;
import dev.nightjar.payload.firewall.ReleaseEligibility;
import dev.nightjar.payload.log.PLog;

/**
 * Replay of a stored message into the local pipeline.
 *
 * <p>A replay goes through the same internal injection path as a release ({@link SmsInjector}), so it never touches the
 * radio and cannot be billed. Every part's TPDU key is registered as a {@link
 * dev.nightjar.payload.capture.InjectionTokens.Kind#REPLAY} token before the first injection: the correlator then
 * marks the resulting record {@code flags.replay = true} and links it to the original event instead of classing it
 * {@code CL-INJECTED}.
 *
 * <p>Classes that cannot be reproduced by an injection (cell broadcasts, SIM data downloads, status reports, every
 * outbound class) are refused with {@link Control.ReleaseResult#NOT_RELEASABLE}, exactly as a release is.
 *
 * <p>Thread: control-binder thread.
 */
public final class Replayer {

    /** Same limit as the binder's part array. */
    public static final int MAX_PARTS = 32;

    private final TokenRegistry tokens;
    private final SmsInjector injector;
    private final PLog log;
    /** Resolves subId -1; a seam for the JVM tests, which have no SubscriptionManager. */
    private volatile java.util.function.IntSupplier defaultSub = Subscriptions::frameworkDefault;

    public Replayer(TokenRegistry tokens, SmsInjector injector, PLog log) {
        this.tokens = tokens;
        this.injector = injector;
        this.log = log;
    }

    /**
     * @param eventId the stored event being replayed; the new record links back to it
     * @param classId the stored record's final class
     * @return one of {@link Control.ReleaseResult}
     */
    public int replay(String eventId, ClassId classId, byte[][] pdus, String format, int subId) {
        if (eventId == null || eventId.isEmpty()) return invalid("no event id");
        if (pdus == null || pdus.length == 0 || pdus.length > MAX_PARTS) return invalid("bad part count");
        for (byte[] part : pdus) if (part == null || part.length == 0) return invalid("empty part");
        int sub = Subscriptions.resolve(subId, defaultSub);
        if (sub < 0) return invalid("bad subscription");
        if (!ReleaseEligibility.releasableFormat(format)) {
            warn("replay rejected: format not replayable");
            return Control.ReleaseResult.NOT_RELEASABLE;
        }
        if (!ReleaseEligibility.releasable(classId)) {
            warn("replay rejected: class not replayable");
            return Control.ReleaseResult.NOT_RELEASABLE;
        }

        String[][] keys = new String[pdus.length][];
        String[][] wapKeys = new String[pdus.length][];
        for (int i = 0; i < pdus.length; i++) {
            keys[i] = Releaser.keys(pdus[i]);
            tokens.registerReplay(keys[i][0], keys[i][1], eventId);
            // HK-14 sees only the WAP payload, so a replayed push needs its payload keys too.
            wapKeys[i] = Releaser.payloadKeys(pdus[i]);
            if (wapKeys[i] != null) tokens.registerReplay(wapKeys[i][0], wapKeys[i][1], eventId);
        }

        int firstFailure = Control.ReleaseResult.HANDLED;
        for (int i = 0; i < pdus.length; i++) {
            int result;
            try {
                result = injector.inject(pdus[i], format, sub);
            } catch (Throwable t) {
                result = Control.ReleaseResult.GENERIC_FAILURE;
            }
            if (result != Control.ReleaseResult.HANDLED) {
                firstFailure = result;
                // Parts that never entered the pipeline must not leave tokens behind. After a timeout the
                // injection can still land, so the tokens stay until their TTL (as for a release).
                if (result != Control.ReleaseResult.TIMEOUT) {
                    for (int k = i; k < pdus.length; k++) {
                        tokens.discard(keys[k][0], keys[k][1]);
                        if (wapKeys[k] != null) tokens.discard(wapKeys[k][0], wapKeys[k][1]);
                    }
                }
                warn("replay part " + (i + 1) + "/" + pdus.length + " failed with result " + result);
                break;
            }
        }
        if (firstFailure == Control.ReleaseResult.HANDLED) {
            info("replayed " + pdus.length + " part(s) of " + eventId);
        }
        return firstFailure;
    }

    private int invalid(String why) {
        warn("replay rejected: " + why);
        return Control.ReleaseResult.INVALID_ARGUMENTS;
    }

    private void warn(String message) {
        if (log != null) log.w("Replay", message);
    }

    private void info(String message) {
        if (log != null) log.i("Replay", message);
    }

    /** Test seam: the default subscription for subId -1. */
    void setDefaultSub(java.util.function.IntSupplier supplier) {
        this.defaultSub = supplier;
    }
}
