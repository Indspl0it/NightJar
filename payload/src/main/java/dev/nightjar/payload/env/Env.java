package dev.nightjar.payload.env;

/**
 * Environment facts the payload reports.
 *
 * <p>Every method is O(1) and allocation-free after warm-up so it may be called from a hook
 * thread. Values are refreshed on the worker; nothing here performs I/O.
 */
public interface Env {
    /** {@code Build.FINGERPRINT} of the phone process. */
    String buildFingerprint();

    /** Payload version string ({@code payloadVersion} in the handshake). */
    String payloadVersion();

    /** JSON object of APEX versions relevant to telephony (C.2 {@code apexVersions}). */
    String apexVersionsJson();

    /** {@code Settings.Global.BOOT_COUNT} read once per session. */
    int bootCount();

    /** Cached {@code UserManager.isUserUnlocked()}. */
    boolean userUnlocked();

    /** Carrier name of the subscription, empty when unknown. */
    String carrierName(int subId);

    /** SIM slot of the subscription, −1 when unknown. */
    int slot(int subId);

    /**
     * Subscription id of a phone/slot index (: HK-17 only knows the phone id), −1 when unknown.
     *
     * <p>{@link AndroidEnv} overrides this with the cached subscription map; other implementations
     * return −1 so a hook never blocks on a binder call.
     */
    default int subIdForPhone(int phoneId) {
        return -1;
    }

    /** Roaming state of the subscription from the last {@code ServiceState} refresh. */
    boolean roaming(int subId);

    /**
     * True when {@code pkg} is the user's default SMS app (its sends are the user's own and
     * are counted, never logged).
     *
     * <p>{@link AndroidEnv} answers from a short-lived cache over {@code SmsApplication}; the default
     * here says "not the default app", which records the send instead of hiding it — the safe
     * direction for a capture tool.
     */
    default boolean isDefaultSmsApp(String pkg) {
        return false;
    }
}
