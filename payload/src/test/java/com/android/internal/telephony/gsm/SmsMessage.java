package com.android.internal.telephony.gsm;

import com.android.internal.telephony.SmsMessageBase;

/**
 * Test double of the GSM message HK-15 receives — the class {@code UsimDataDownloadHandler#handleUsimDataDownload}
 * declares, not the public {@code android.telephony.SmsMessage}.
 */
public class SmsMessage extends SmsMessageBase {

    private final String originator;
    private final byte[] pdu;

    public SmsMessage(String originator, byte[] pdu) {
        this.originator = originator;
        this.pdu = pdu;
    }

    @Override
    public String getOriginatingAddress() {
        return originator;
    }

    @Override
    public byte[] getPdu() {
        return pdu;
    }

    @Override
    public int getProtocolIdentifier() {
        return 0;
    }

    @Override
    public boolean isMWIClearMessage() {
        return false;
    }

    @Override
    public boolean isMWISetMessage() {
        return false;
    }

    @Override
    public boolean isMwiDontStore() {
        return false;
    }

    @Override
    public boolean isReplace() {
        return false;
    }

    @Override
    public boolean isStatusReportMessage() {
        return false;
    }
}
