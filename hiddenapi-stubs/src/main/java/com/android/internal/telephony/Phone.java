package com.android.internal.telephony;

/** Stub. Only the members the payload reads. */
public abstract class Phone {
    public int getPhoneId() { throw new RuntimeException("stub"); }
    public int getSubId() { throw new RuntimeException("stub"); }
    public SmsDispatchersController getSmsDispatchersController() { throw new RuntimeException("stub"); }
    public boolean isInEmergencyCall() { throw new RuntimeException("stub"); }
}
