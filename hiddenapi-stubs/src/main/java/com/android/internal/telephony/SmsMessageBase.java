package com.android.internal.telephony;

/**
 * Stub. Lives in framework.jar. Verified 2026-11-05 against Pixel 8a CP3A.261005.005
 * (firmware/pixel8a/work/decomp/framework-sel). Used by HK-06/HK-07 callbacks.
 */
public abstract class SmsMessageBase {
    public abstract int getProtocolIdentifier();
    public abstract boolean isMWIClearMessage();
    public abstract boolean isMWISetMessage();
    public abstract boolean isMwiDontStore();
    public abstract boolean isReplace();
    public abstract boolean isStatusReportMessage();
    public String getServiceCenterAddress() { throw new RuntimeException("stub"); }
    public String getOriginatingAddress() { throw new RuntimeException("stub"); }
    public long getTimestampMillis() { throw new RuntimeException("stub"); }
    public byte[] getUserData() { throw new RuntimeException("stub"); }
    public byte[] getPdu() { throw new RuntimeException("stub"); }
}
