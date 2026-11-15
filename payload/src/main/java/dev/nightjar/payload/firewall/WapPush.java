package dev.nightjar.payload.firewall;

/**
 * WAP push enforcement constants (HK-14).
 *
 * <p>{@code dispatchWapPdu} returns {@code 1} when it consumed the push, {@code 2} when there was no MIME type or the
 * message was deleted, and {@code -1} when it broadcast the intent. A before-hook may replace the result, but the
 * framework treats {@code -1} as "broadcast": skipping with {@code -1} would leak the message and, worse, the hook layer
 * rejects {@code -1} for this hook (guard). Blocking therefore always returns
 * {@link #RESULT_CONSUMED}.
 */
public final class WapPush {

    private WapPush() {}

    /** WAP push destination port (TS 23.040 port addressing). */
    public static final int PORT = 2948;

    /** HK-14 result: consumed (blocked, quarantined or handled by the hook). */
    public static final int RESULT_CONSUMED = 1;

    /** HK-14 result: the framework broadcasts the intent. Never used as a skip result. */
    public static final int RESULT_BROADCAST = -1;
}
