package com.android.internal.telephony.gsm;

import com.android.internal.telephony.SmsMessageBase;

/**
 * Stub. GSM/UMTS SMS message; HK-15 argument. Lives in framework.jar. Verified 2026-11-05 against
 * Pixel 8a CP3A.261005.005 and OnePlus 13 CPH2649 16.0.10.600 (firmware/&lt;device&gt;/work/decomp/framework-sel).
 *
 * <p>Not declared on purpose: {@code getDataCodingScheme()} and {@code isUsimDataDownload()} are package-private
 * on both builds (calling them through a public stub would throw IllegalAccessError); read them by reflection.
 * {@code createFromPdu(byte[], NetworkSecurityEvent)} exists only on Pixel.
 */
public class SmsMessage extends SmsMessageBase {
    public static SmsMessage createFromPdu(byte[] pdu) { throw new RuntimeException("stub"); }
    public boolean isTypeZero() { throw new RuntimeException("stub"); }
    @Override public int getProtocolIdentifier() { throw new RuntimeException("stub"); }
    @Override public boolean isMWIClearMessage() { throw new RuntimeException("stub"); }
    @Override public boolean isMWISetMessage() { throw new RuntimeException("stub"); }
    @Override public boolean isMwiDontStore() { throw new RuntimeException("stub"); }
    @Override public boolean isReplace() { throw new RuntimeException("stub"); }
    @Override public boolean isStatusReportMessage() { throw new RuntimeException("stub"); }
}
