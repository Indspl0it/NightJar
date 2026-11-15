package dev.nightjar.contract;

/**
 * Cell broadcast channel table shared by the payload (HK-17) and the rule engine's emergency CB
 * clamp (CL-CB emergency row). Message identifiers per 3GPP TS 23.041 §9.4.1.2.2.
 */
public final class CbChannels {
    private CbChannels() {}

    /** ETWS (earthquake, tsunami, test, other): 4352–4359. */
    public static final int ETWS_FIRST = 4352;

    public static final int ETWS_LAST = 4359;

    /**
     * CMAS / EU-Alert / national public warning range 4370–4399: Presidential, Extreme, Severe, AMBER, RMT/exercise,
     * operator-defined and their additional-language duplicates.
     */
    public static final int CMAS_FIRST = 4370;

    public static final int CMAS_LAST = 4399;

    /**
     * First id of the whole public warning (PWS) range, 0x1100. The framework treats 0x1100-0x18FF as emergency
     * ({@code SmsCbConstants.MESSAGE_ID_PWS_FIRST_IDENTIFIER}/{@code _LAST_IDENTIFIER}); ids that 3GPP reserves for
     * future warning types are inside it.
     */
    public static final int PWS_FIRST = 0x1100; // 4352

    /** Last id of the PWS range, 0x18FF. */
    public static final int PWS_LAST = 0x18FF; // 6399

    /**
     * True for channels that must never be blocked, quarantined or alerted on by Nightjar: the whole
     * PWS range 4352-6399, not only the ETWS and CMAS ids that are assigned today.
     */
    public static boolean isEmergency(int messageIdentifier) {
        return messageIdentifier >= PWS_FIRST && messageIdentifier <= PWS_LAST;
    }
}
