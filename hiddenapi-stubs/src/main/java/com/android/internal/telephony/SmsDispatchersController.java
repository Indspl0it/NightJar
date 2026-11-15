package com.android.internal.telephony;

import android.telephony.SmsMessage;

/** Stub. HK-R1 target used for release and canaries. */
public class SmsDispatchersController {

    /** Result callback of {@link #injectSmsPdu}. {@code result} is an {@code Intents.RESULT_SMS_*} code. */
    public interface SmsInjectionCallback {
        void onSmsInjectedResult(int result);
    }

    /** HK-R1. Survey pixel8a pipeline line 90 ({@code :492}); OnePlus row 32. */
    public void injectSmsPdu(SmsMessage msg, String format, SmsInjectionCallback callback,
            boolean ignoreClass, boolean isOverIms, int token) { throw new RuntimeException("stub"); }
}
