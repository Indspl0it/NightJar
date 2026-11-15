package com.android.internal.telephony;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.List;

/**
 * Stub. Inbound SMS state machine. Verified 2026-11-05 against Pixel 8a CP3A.261005.005 and
 * OnePlus 13 CPH2649 16.0.10.600 (telephony-common decomp). {@code dispatchMessage} (HK-06) is private on
 * both builds, so it is resolved by name and not declared here. {@code dropFilteredSms} (HK-13b, HK-R3) is private on
 * both builds too (dex access flags), so it is also not declared: the payload calls it reflectively (NjSmsFilter).
 * Hooks: HK-13a dispatchIntent, HK-24 constructors, HK-R2 setSmsFiltersForTesting.
 */
public abstract class InboundSmsHandler extends StateMachine {

    /** HK-08 interface. Same signature on the OnePlus 13 and Pixel builds. */
    public interface SmsFilter {
        boolean filterSms(byte[][] pdus, int destPort, InboundSmsTracker tracker,
                SmsBroadcastReceiver resultReceiver, boolean userUnlocked, boolean isBlockedNumber,
                List<SmsFilter> remainingFilters);
    }

    /** Ordered-broadcast receiver that finishes a message's delivery. */
    public final class SmsBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) { throw new RuntimeException("stub"); }
    }

    /** HK-R2: replaces the filter chain (used to prepend the Nightjar filter). */
    public void setSmsFiltersForTesting(List<SmsFilter> smsFilters) { throw new RuntimeException("stub"); }

    public Phone getPhone() { throw new RuntimeException("stub"); }
}
