package com.android.internal.telephony;

/**
 * Test double of the framework's {@code SmsMessageBase} (hiddenapi-stubs is compile-only, so the JVM tests supply their
 * own). Only the accessors the payload reads are meaningful.
 */
public abstract class SmsMessageBase {

    public abstract int getProtocolIdentifier();

    public abstract boolean isMWIClearMessage();

    public abstract boolean isMWISetMessage();

    public abstract boolean isMwiDontStore();

    public abstract boolean isReplace();

    public abstract boolean isStatusReportMessage();

    public String getServiceCenterAddress() {
        return null;
    }

    public String getOriginatingAddress() {
        return null;
    }

    public long getTimestampMillis() {
        return 0L;
    }

    public byte[] getUserData() {
        return null;
    }

    public byte[] getPdu() {
        return null;
    }
}
