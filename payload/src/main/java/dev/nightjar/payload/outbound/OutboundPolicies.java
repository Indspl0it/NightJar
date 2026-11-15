package dev.nightjar.payload.outbound;

/**
 * Process-wide slot for the outbound gate. The runtime installs the gate after service
 * wiring, so the firewall epic can ship its gate without this module depending on it.
 *
 * <p>Thread: any; one volatile swap per install, plain reads afterwards.
 */
public final class OutboundPolicies {
    private static volatile OutboundPolicy current = OutboundPolicy.ALLOW;

    private OutboundPolicies() {}

    /**
     * Installs {@code policy}; null restores {@link OutboundPolicy#ALLOW}.
     *
     * <p>There is deliberately no reflective or name-based installer: {@code settings.json} and
     * the control binder must never be able to name a class the phone process would instantiate.
     */
    public static void install(OutboundPolicy policy) {
        current = policy == null ? OutboundPolicy.ALLOW : policy;
    }

    /** The installed policy; never null. */
    public static OutboundPolicy current() {
        return current;
    }

    /** Restores {@link OutboundPolicy#ALLOW} (tests). */
    public static void reset() {
        current = OutboundPolicy.ALLOW;
    }
}
