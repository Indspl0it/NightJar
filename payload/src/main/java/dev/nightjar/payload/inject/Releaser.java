package dev.nightjar.payload.inject;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Control;
import dev.nightjar.payload.firewall.ReleaseEligibility;
import dev.nightjar.payload.log.PLog;

/**
 * Release of a quarantined message (05).
 *
 * <p>Tokens are registered for every part <em>before</em> the first injection, so a re-injected part can never be
 * classed {@code CL-INJECTED} or quarantined again even if the pipeline is fast. Parts are injected in order so
 * a multipart message arrives complete; the first failure stops the sequence and is returned to the app, which
 * keeps the message quarantined and shows an error.
 *
 * <p>Non-releasable classes are rejected here as well as by the evaluator's clamp, so a crafted rules file or a buggy
 * app cannot release a SIM update or a broadcast.
 *
 * <p>Thread: control-binder thread.
 */
public final class Releaser {

    /** Same limit as the binder's part array. */
    public static final int MAX_PARTS = 32;

    private final TokenRegistry tokens;
    private final SmsInjector injector;
    private final PLog log;
    /** Resolves subId -1; a seam for the JVM tests, which have no SubscriptionManager. */
    private volatile java.util.function.IntSupplier defaultSub = Subscriptions::frameworkDefault;

    public Releaser(TokenRegistry tokens, SmsInjector injector, PLog log) {
        this.tokens = tokens;
        this.injector = injector;
        this.log = log;
    }

    /**
     * @param classId the quarantined event's final class
     * @return one of {@link Control.ReleaseResult}
     */
    public int release(String eventId, ClassId classId, byte[][] pdus, String format, int subId) {
        if (eventId == null || eventId.isEmpty()) return invalid("no event id");
        if (pdus == null || pdus.length == 0 || pdus.length > MAX_PARTS) return invalid("bad part count");
        for (byte[] part : pdus) if (part == null || part.length == 0) return invalid("empty part");
        int sub = Subscriptions.resolve(subId, defaultSub);
        if (sub < 0) return invalid("bad subscription");
        if (!ReleaseEligibility.releasableFormat(format)) {
            warn("Release", "release rejected: format not releasable");
            return Control.ReleaseResult.NOT_RELEASABLE;
        }
        if (!ReleaseEligibility.releasable(classId)) {
            warn("Release", "release rejected: class not releasable");
            return Control.ReleaseResult.NOT_RELEASABLE;
        }

        // The class goes on the token, so HK-23 can let a released MWI apply its indicator again.
        String classWire = classId.wire();
        String[][] keys = new String[pdus.length][];
        String[][] wapKeys = new String[pdus.length][];
        for (int i = 0; i < pdus.length; i++) {
            keys[i] = keys(pdus[i]);
            tokens.registerRelease(keys[i][0], keys[i][1], eventId, classWire);
            wapKeys[i] = payloadKeys(pdus[i]);
            if (wapKeys[i] != null) tokens.registerRelease(wapKeys[i][0], wapKeys[i][1], eventId, classWire);
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
                // This part and every later part never entered the pipeline: drop their tokens so a later
                // message with the same TPDU key is not mistaken for our injection. a timeout is
                // not a failure — the injection may still land after the injector gave up waiting, so
                // the token has to survive until its TTL or the released message would be re-ruled.
                if (result != Control.ReleaseResult.TIMEOUT) {
                    for (int k = i; k < pdus.length; k++) {
                        tokens.discard(keys[k][0], keys[k][1]);
                        if (wapKeys[k] != null) tokens.discard(wapKeys[k][0], wapKeys[k][1]);
                    }
                }
                warn("Release", "part " + (i + 1) + "/" + pdus.length + " failed with result " + result);
                break;
            }
        }
        if (firstFailure == Control.ReleaseResult.HANDLED) {
            info("Release", "released " + pdus.length + " part(s)");
        }
        return firstFailure;
    }

    private int invalid(String why) {
        warn("Release", "release rejected: " + why);
        return Control.ReleaseResult.INVALID_ARGUMENTS;
    }

    /**
     * {@code [stripped, raw]} TPDU keys of one part (tries both).
     *
     * <p>HK-14 only receives the WAP push payload — the SMS user data, never the TPDU — so a
     * port-2948 part additionally needs a key over that payload ({@link #payloadKeys}).
     */
    static String[] keys(byte[] pdu) {
        String stripped = dev.nightjar.contract.Hashes.tpduKey(dev.nightjar.contract.Hashes.stripSmsc(pdu));
        String raw = dev.nightjar.contract.Hashes.tpduKey(pdu);
        return new String[] {stripped, raw};
    }

    /**
     * {@code [withoutHeader, withHeader]} keys of the WAP payload, or {@code null}.
     *
     * <p>The framework hands HK-14 {@code SmsMessage.getUserData()}, which has the user-data header (UDH) already
     * removed, so the first key is over the bytes after the header. {@code PduFacts.userData()} still includes the
     * header; hashing that alone never matched, so every WAP canary reached the messaging app. The second key keeps the
     * with-header spelling for builds that pass the raw user data.
     */
    public static String[] payloadKeys(byte[] pdu) {
        try {
            dev.nightjar.payload.capture.PduFacts facts =
                    dev.nightjar.payload.capture.PduFacts.parseRadio(pdu);
            if (facts == null || !facts.valid()) return null;
            if (facts.destPort() != 2948) return null;
            byte[] userData = facts.userData();
            if (userData == null || userData.length == 0) return null;
            String withHeader = dev.nightjar.contract.Hashes.tpduKey(userData);
            byte[] body = userData;
            if (facts.udhi()) {
                int skip = 1 + (userData[0] & 0xFF);
                body = skip >= userData.length
                        ? new byte[0] : java.util.Arrays.copyOfRange(userData, skip, userData.length);
            }
            if (body.length == 0) return new String[] {withHeader, null};
            return new String[] {dev.nightjar.contract.Hashes.tpduKey(body), withHeader};
        } catch (Throwable t) {
            return null;
        }
    }

    private void warn(String tag, String message) {
        if (log != null) log.w(tag, message);
    }

    private void info(String tag, String message) {
        if (log != null) log.i(tag, message);
    }

    /** Test seam: the default subscription for subId -1. */
    void setDefaultSub(java.util.function.IntSupplier supplier) {
        this.defaultSub = supplier;
    }
}
