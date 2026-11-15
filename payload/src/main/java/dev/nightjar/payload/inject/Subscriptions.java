package dev.nightjar.payload.inject;

import java.util.function.IntSupplier;

/**
 * Subscription id of a release or replay. The app sends -1 when the event has no known
 * subscription; -1 then means the default SMS subscription, else the default subscription.
 *
 * <p>Thread: binder (release, replay). The two framework calls are static reads of a cached value.
 */
final class Subscriptions {
    /** The id the app sends for "no known subscription". */
    static final int DEFAULT_SUB = -1;

    private Subscriptions() {}

    /** {@code subId}, or the default for {@link #DEFAULT_SUB}; a negative result means "no subscription". */
    static int resolve(int subId, IntSupplier defaultSub) {
        if (subId != DEFAULT_SUB) return subId;
        try {
            return defaultSub == null ? -1 : defaultSub.getAsInt();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** The default SMS subscription, else the default subscription; negative when there is none. */
    static int frameworkDefault() {
        int sub = android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId();
        if (sub < 0) sub = android.telephony.SubscriptionManager.getDefaultSubscriptionId();
        return sub;
    }
}
