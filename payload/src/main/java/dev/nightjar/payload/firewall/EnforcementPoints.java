package dev.nightjar.payload.firewall;

import dev.nightjar.contract.ClassId;

/**
 * "Enforcement point" column as code: which classes each hook is responsible for.
 *
 * <p>The split matters because enforcing twice would double-count and could block a WAP push at HK-08 before HK-14 has
 * parsed its content type, so a per-port rule would silently also drop settings pushes. Each message is therefore
 * enforced at exactly one point. The other points are the hooks themselves (HK-14 WAP, HK-15 SIM data download,
 * HK-17 cell broadcast); the test table {@code AppendixD} checks that each class has exactly one.
 *
 * <p>Thread: any (pure).
 */
public final class EnforcementPoints {

    private EnforcementPoints() {}

    /** Classes decided at the head filter (HK-08). */
    public static boolean atHeadFilter(ClassId c) {
        if (c == null) return true;
        switch (c) {
            case INJECTED:
            case REPLACE:
            // Not CL-VVM, CL-MISSED-CALL or CL-APP-TOKEN. The head filter never predicts them
            // (EnforcementMatrix.noOwnEnforcementPoint).
            case DATA_PORT:
            case FLASH:
            case NORMAL:
            case MWI:
            // Other SMS classes that the head filter can predict from the PDU.
            case RETURN_CALL:
            case ME_DOWNLOAD:
            case ME_DEPERSO:
            case CLASS3:
            case TELEMATIC:
            case EMPTY:
            case BINARY:
            case COMPRESSED:
            case MALFORMED_SMS:
            case UNREADABLE_SMS:
            case SMART:
            case LOC_SUPL:
                return true;
            default:
                return false;
        }
    }
}
