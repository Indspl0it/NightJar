package dev.nightjar.payload.inject;

import java.lang.reflect.Method;

/**
 * Fails the caller's sent {@code PendingIntent}.
 *
 * <p>An outbound block must look like a normal radio failure to the calling app: the intent is sent with
 * {@code Activity.RESULT_ERROR_GENERIC_FAILURE} (1), which is what {@code SmsManager} does when the modem refuses a
 * message. The payload has no compile-time Android dependency here, so the call is reflective and takes the raw
 * object the hook saw.
 *
 * <p>Multipart sends pass an array (or list) of intents; every one of them is failed. Nothing throws: a
 * {@code PendingIntent} that was already sent, cancelled or is of another type simply reports {@code false}.
 *
 * <p>Thread: the hook thread (binder). One reflective lookup, cached.
 */
public final class SentFailure {

    /** {@code android.app.Activity.RESULT_ERROR_GENERIC_FAILURE}; the value is stable ABI. */
    public static final int RESULT_ERROR_GENERIC_FAILURE = 1;

    /** How one intent is failed. The default is the reflective {@code PendingIntent#send(int)}. */
    @FunctionalInterface
    public interface Sender {
        void send(Object pendingIntent, int resultCode) throws Exception;
    }

    private static volatile Method sendInt;
    private static volatile boolean lookupFailed;

    /** The production sender: reflective {@code PendingIntent#send(int)}. */
    public static final Sender DEFAULT = SentFailure::reflectiveSend;

    private SentFailure() {}

    /**
     * Fails every intent in {@code sentIntent}.
     *
     * @param sentIntent a {@code PendingIntent}, an array of them, an {@link Iterable} of them, or {@code null}
     * @return true when at least one intent was failed
     */
    public static boolean fail(Object sentIntent) {
        return fail(sentIntent, RESULT_ERROR_GENERIC_FAILURE);
    }

    /** Fails every intent in {@code sentIntent} with {@code code}. */
    public static boolean fail(Object sentIntent, int code) {
        return fail(sentIntent, code, DEFAULT);
    }

    /** Fails every intent in {@code sentIntent} with {@code code}, using {@code sender}. */
    public static boolean fail(Object sentIntent, int code, Sender sender) {
        if (sentIntent == null || sender == null) return false;
        if (sentIntent instanceof Object[] array) {
            boolean any = false;
            for (Object element : array) any |= fail(element, code, sender);
            return any;
        }
        if (sentIntent instanceof Iterable<?> iterable) {
            boolean any = false;
            for (Object element : iterable) any |= fail(element, code, sender);
            return any;
        }
        try {
            sender.send(sentIntent, code);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void reflectiveSend(Object pendingIntent, int resultCode) throws Exception {
        Method send = sendInt;
        if (send == null) {
            if (lookupFailed) throw new IllegalStateException("PendingIntent#send(int) unavailable");
            send = findSend();
            if (send == null) {
                lookupFailed = true;
                throw new IllegalStateException("PendingIntent#send(int) unavailable");
            }
            sendInt = send;
        }
        send.invoke(pendingIntent, resultCode);
    }

    private static Method findSend() {
        try {
            return Class.forName("android.app.PendingIntent", false, ClassLoader.getSystemClassLoader())
                    .getMethod("send", int.class);
        } catch (Throwable t) {
            return null;
        }
    }
}
