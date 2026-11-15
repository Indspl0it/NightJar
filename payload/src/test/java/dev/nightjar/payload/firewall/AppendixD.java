package dev.nightjar.payload.firewall;

import dev.nightjar.contract.ClassId;

/**
 * Enforcement points and verdict forms that only the tests need. Production code has
 * {@link EnforcementPoints#atHeadFilter}; the other points are the hooks themselves (HK-14, HK-15, HK-17).
 */
final class AppendixD {

    private AppendixD() {}

    /** WAP pushes are enforced at HK-14, where the content type is known. */
    static boolean atWap(ClassId c) {
        if (c == null) return false;
        switch (c) {
            case WAP_CP:
            case WAP_DM:
            case WAP_SISL:
            case WAP_MMS:
            case WAP_SUPL:
            case WAP_OTHER:
            // The other WAP push classes.
            case WAP_CO:
            case WAP_DS:
            case WAP_EMN:
            case WAP_DRM:
            case WAP_VCARD:
            case WAP_IMPS:
            case WAP_UNREADABLE:
                return true;
            default:
                return false;
        }
    }

    /** SIM data downloads are enforced at HK-15 only. */
    static boolean atUsimDataDownload(ClassId c) {
        return c == ClassId.SIM_DD;
    }

    /** Cell broadcasts are enforced at HK-17 (non-emergency only), CB data download to the SIM too. */
    static boolean atCellBroadcast(ClassId c) {
        return c == ClassId.CB || c == ClassId.CB_SIM;
    }

    /**
     * Classes with no enforcement point at all: the message is already ACKed and discarded (silent ping),
     * sits on the SIM, or is a delivery report. An incomplete multipart message is known only 24 hours after its parts
     * were handled. Blocking them is not possible, so the UI never offers it.
     */
    static boolean hasNoEnforcementPoint(ClassId c) {
        if (c == null) return true;
        switch (c) {
            case SILENT:
            case SIM_STORED:
            case STATUS:
            case MULTIPART_INCOMPLETE:
                return true;
            default:
                return false;
        }
    }

    /** HK-14 skip result for a verdict: consumed when blocked, else −1 (no skip). */
    static int wapSkipResult(VerdictRecord record) {
        return record != null && record.blocked() ? WapPush.RESULT_CONSUMED : WapPush.RESULT_BROADCAST;
    }

    /** The {@code verdict} object of an IPC event for a record (the production form is {@code EventDto.verdictMap}). */
    static String verdictJson(VerdictRecord r) {
        StringBuilder b = new StringBuilder(64);
        b.append("{\"action\":\"").append(r.action().wire()).append('"');
        if (r.ruleId() != VerdictRecord.NO_RULE) b.append(",\"ruleId\":").append(r.ruleId());
        if (r.clampedFrom() != null) b.append(",\"clampedFrom\":\"").append(r.clampedFrom().wire()).append('"');
        String reason = r.reasonWire();
        if (reason != null) b.append(",\"clampReason\":\"").append(reason).append('"');
        b.append('}');
        return b.toString();
    }
}
