package dev.nightjar.pduparse;

import dev.nightjar.contract.CbChannels;
import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Subtype;

/**
 * Cell broadcast message identifier → class and subtype. One table for the payload
 * (HK-17 event and enforcement) and the app (title, explanation, severity).
 *
 * <p>Message identifier ranges from 3GPP TS 23.041 §9.4.1.2.2 (the same clause as {@link CbChannels}):
 *
 * <pre>
 * 0x0000-0x03E7  0-999      channels the operator allocates; 50 is area information (cell or area name)
 * 0x1000-0x10FF  4096-4351  not for broadcasts to the user: the phone gives them to the SIM (CB data download,
 *  TS 31.111 §7.1.2 ENVELOPE (CELL BROADCAST DOWNLOAD)) → CL-CB-SIM
 * 0x1100-0x1107  4352-4359  ETWS: earthquake, tsunami, both, test, other emergency type, future extension
 * 0x1108-0x1111  4360-4369  PWS identifiers without a name yet → ETWS-OTHER (the generic emergency warning)
 * 0x1112-0x112F  4370-4399  CMAS / EU-Alert: 4370-4382 one alert class each, 4383-4395 the same classes in an
 *                           additional language, 4396-4397 public safety, 4398-4399 state or local test
 * 0x1130-0x18FF  4400-6399  PWS: geo-fencing trigger (0x1130) and future PWS use → ETWS-OTHER. The framework's
 *                           {@code SmsCbConstants} puts the whole PWS range at 0x1100-0x18FF; the team decision
 *  (2026-11-13) is that all of 4352-6399 counts as emergency
 * other                     a channel (CL-CB, subtype CHANNEL)
 * </pre>
 *
 * <p>The CMAS level groups follow the CMAS class of each identifier in TS 23.041 §9.4.1.2.2 (Extreme: Immediate
 * Observed and Likely; Severe: Extreme Expected and every Severe identifier). Each emergency identifier keeps the
 * Guard of {@link CbChannels#isEmergency}: this class only names a broadcast, it never decides to block one.
 *
 * <p>The SIM also lists identifiers for data download in EF_CBMID (TS 31.102). The phone service cannot read
 * that list, so only the fixed 0x1000-0x10FF range gives CL-CB-SIM. Unconfirmed: the range comes from the
 * {@code cb-sim-download} scenario ({@code checkSpec: true}), not from a cited TS 23.041 row. Do not change the
 * range without a spec source.
 *
 * <p>Thread: any. No allocation. Never throws.
 */
public final class CbCategories {
    /** Area information channel: the cell or area name (TS 23.041, operator range). */
    public static final int AREA_INFO_CHANNEL = 50;

    /** First message identifier of CB data download to the SIM (unconfirmed, see the class comment). */
    public static final int SIM_DOWNLOAD_FIRST = 0x1000;

    /** Last message identifier of CB data download to the SIM (unconfirmed, see the class comment). */
    public static final int SIM_DOWNLOAD_LAST = 0x10FF;

    /** ETWS earthquake warning (0x1100), the first ETWS identifier. 0x1101 to 0x1103 follow it in order. */
    static final int ETWS_EARTHQUAKE = CbChannels.ETWS_FIRST;

    /** Offset from a CMAS identifier to the same alert class in an additional language (4370 → 4383). */
    static final int CMAS_LANGUAGE_OFFSET = 13;

    /** CMAS public safety alert (0x112C) and its additional-language copy (0x112D). */
    static final int CMAS_PUBLIC_SAFETY = 4396;

    /** CMAS state or local test (0x112E) and its additional-language copy (0x112F). */
    static final int CMAS_STATE_LOCAL_TEST = 4398;

    /** First PWS message identifier (0x1100, framework {@code SmsCbConstants.MESSAGE_ID_PWS_FIRST_IDENTIFIER}). */
    static final int PWS_FIRST = 0x1100;

    /** Last PWS message identifier (0x18FF, framework {@code SmsCbConstants.MESSAGE_ID_PWS_LAST_IDENTIFIER}). */
    static final int PWS_LAST = 0x18FF;

    private CbCategories() {}

    /** True for a broadcast that the phone gives to the SIM (CB data download). */
    public static boolean isSimDownload(int messageIdentifier) {
        return messageIdentifier >= SIM_DOWNLOAD_FIRST && messageIdentifier <= SIM_DOWNLOAD_LAST;
    }

    /** True for a broadcast that must never be blocked or held. Same table as {@link CbChannels}. */
    public static boolean isEmergency(int messageIdentifier) {
        return CbChannels.isEmergency(messageIdentifier);
    }

    /** {@link ClassId#CB_SIM} for CB data download to the SIM, else {@link ClassId#CB}. */
    public static ClassId classOf(int messageIdentifier) {
        return isSimDownload(messageIdentifier) ? ClassId.CB_SIM : ClassId.CB;
    }

    /**
     * The {@link Subtype.Cb} code of a CL-CB broadcast, or {@code null} for CL-CB-SIM (it has no
     * subtype). A PWS identifier without its own name gives {@link Subtype.Cb#ETWS_OTHER}; an unknown or negative
     * identifier gives {@link Subtype.Cb#CHANNEL}.
     */
    public static String subtypeOf(int messageIdentifier) {
        if (isSimDownload(messageIdentifier)) return null;
        if (messageIdentifier >= CbChannels.ETWS_FIRST && messageIdentifier <= CbChannels.ETWS_LAST) {
            return etws(messageIdentifier);
        }
        if (messageIdentifier >= CbChannels.CMAS_FIRST && messageIdentifier <= CbChannels.CMAS_LAST) {
            return cmas(messageIdentifier);
        }
        if (messageIdentifier >= PWS_FIRST && messageIdentifier <= PWS_LAST) return Subtype.Cb.ETWS_OTHER;
        if (messageIdentifier == AREA_INFO_CHANNEL) return Subtype.Cb.AREA_INFO;
        return Subtype.Cb.CHANNEL;
    }

    /** TS 23.041 §9.4.1.2.2, 0x1100-0x1107. 0x1104 (other emergency type) and 0x1105-0x1107 have no own name. */
    private static String etws(int id) {
        switch (id - ETWS_EARTHQUAKE) {
            case 0:
                return Subtype.Cb.ETWS_EQ;
            case 1:
                return Subtype.Cb.ETWS_TSUNAMI;
            case 2:
                return Subtype.Cb.ETWS_BOTH;
            case 3:
                return Subtype.Cb.ETWS_TEST;
            default:
                return Subtype.Cb.ETWS_OTHER;
        }
    }

    /** TS 23.041 §9.4.1.2.2, 0x1112-0x112F. */
    private static String cmas(int id) {
        if (id >= CMAS_STATE_LOCAL_TEST) return Subtype.Cb.TEST;
        if (id >= CMAS_PUBLIC_SAFETY) return Subtype.Cb.PUBLIC_SAFETY;
        int base = id - CbChannels.CMAS_FIRST;
        if (base >= CMAS_LANGUAGE_OFFSET) base -= CMAS_LANGUAGE_OFFSET;
        switch (base) {
            case 0: // 4370 Presidential (EU-Alert level 1)
                return Subtype.Cb.PRESIDENTIAL;
            case 1: // 4371 Extreme, Immediate, Observed
            case 2: // 4372 Extreme, Immediate, Likely
                return Subtype.Cb.EXTREME;
            case 9: // 4379 Child abduction emergency (AMBER)
                return Subtype.Cb.AMBER;
            case 10: // 4380 Required monthly test
                return Subtype.Cb.TEST;
            case 11: // 4381 Exercise
                return Subtype.Cb.EXERCISE;
            case 12: // 4382 Operator defined use
                return Subtype.Cb.OPERATOR;
            default: // 4373-4378 Extreme Expected and Severe
                return Subtype.Cb.SEVERE;
        }
    }
}
