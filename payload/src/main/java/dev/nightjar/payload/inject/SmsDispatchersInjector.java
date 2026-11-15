package dev.nightjar.payload.inject;

import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.SmsDispatchersController;
import dev.nightjar.contract.Control;
import dev.nightjar.payload.log.PLog;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HK-R1 over the real {@code SmsDispatchersController#injectSmsPdu} ("Called, not hooked").
 *
 * <p>The call is <em>posted</em> to the phone main looper — the framework's SMS state machine is not thread-safe — and
 * the calling (control-binder) thread waits for the result, at most {@link #TIMEOUT_MS}. Arguments are always
 * fixed: {@code ignoreClass = true}, {@code isOverIms = false}, {@code token = 0}.
 *
 * <p>Device only; JVM tests use a fake {@link SmsInjector}.
 *
 * <p>Thread: any caller; the framework work runs on the main looper.
 */
public final class SmsDispatchersInjector implements SmsInjector {

    /** Callback timeout. */
    public static final long TIMEOUT_MS = 10_000L;

    private static final String TAG = "Inject";

    private final Handler main;
    private final PLog log;

    public SmsDispatchersInjector(PLog log) {
        this.main = new Handler(Looper.getMainLooper());
        this.log = log;
    }

    @Override
    public int inject(byte[] pdu, String format, int subId) {
        if (pdu == null || pdu.length == 0 || format == null || subId < 0) {
            return Control.ReleaseResult.INVALID_ARGUMENTS;
        }
        SmsMessage message;
        try {
            message = SmsMessage.createFromPdu(pdu, format);
        } catch (Throwable t) {
            log.w(TAG, "cannot build SmsMessage: " + t.getClass().getSimpleName());
            return Control.ReleaseResult.INVALID_ARGUMENTS;
        }
        if (message == null) return Control.ReleaseResult.INVALID_ARGUMENTS;

        int phoneId = phoneIdFor(subId);
        if (phoneId < 0) return Control.ReleaseResult.NO_DISPATCHER;

        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger(Control.ReleaseResult.GENERIC_FAILURE);
        main.post(() -> {
            try {
                SmsDispatchersController controller = controllerFor(phoneId);
                if (controller == null) {
                    result.set(Control.ReleaseResult.NO_DISPATCHER);
                    done.countDown();
                    return;
                }
                controller.injectSmsPdu(message, format, r -> {
                    result.set(map(r));
                    done.countDown();
                }, true, false, 0);
            } catch (Throwable t) {
                log.w(TAG, "injectSmsPdu failed: " + t.getClass().getSimpleName());
                result.set(Control.ReleaseResult.GENERIC_FAILURE);
                done.countDown();
            }
        });
        try {
            if (!done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log.w(TAG, "injection timed out");
                return Control.ReleaseResult.TIMEOUT;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Control.ReleaseResult.TIMEOUT;
        }
        return result.get();
    }

    /**
     * Maps an {@code Intents.RESULT_SMS_*} code to a {@link Control.ReleaseResult}. {@code Activity.RESULT_OK} (-1) is
     * the framework's "acknowledge later" answer (for example a class 2 message for the SIM): the injected message was
     * accepted, so it is a handled injection.
     */
    static int map(int frameworkResult) {
        switch (frameworkResult) {
            case android.provider.Telephony.Sms.Intents.RESULT_SMS_HANDLED:
            case android.app.Activity.RESULT_OK:
                return Control.ReleaseResult.HANDLED;
            case android.provider.Telephony.Sms.Intents.RESULT_SMS_OUT_OF_MEMORY:
                return Control.ReleaseResult.OUT_OF_MEMORY;
            default:
                return Control.ReleaseResult.GENERIC_FAILURE;
        }
    }

    private int phoneIdFor(int subId) {
        // SubscriptionManager.getPhoneId is hidden API: resolve it reflectively, once, and fail open to -1.
        try {
            java.lang.reflect.Method m = SUBSCRIPTION_PHONE_ID;
            if (m == null) return -1;
            Object v = m.invoke(null, subId);
            return v instanceof Integer ? (Integer) v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static SmsDispatchersController controllerFor(int phoneId) {
        Phone[] phones = PhoneFactory.getPhones();
        if (phones == null) return null;
        for (Phone phone : phones) {
            if (phone != null && phone.getPhoneId() == phoneId) {
                return phone.getSmsDispatchersController();
            }
        }
        return null;
    }

    /** {@code SubscriptionManager.getPhoneId(int)} resolved once; null when the build hides it. */
    private static java.lang.reflect.Method lookupPhoneId() {
        try {
            java.lang.reflect.Method m = SubscriptionManager.class.getMethod("getPhoneId", int.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final java.lang.reflect.Method SUBSCRIPTION_PHONE_ID = lookupPhoneId();
}
