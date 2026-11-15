package dev.nightjar.rules;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Verdict;
import java.util.EnumSet;
import java.util.Set;

/**
 * As code: which actions each class accepts (clamp 1 in the evaluator, the action list in the UI, and the
 * validator). Allocation-free except {@link #allowedClassActions} / {@link #allowedRuleActions}.
 *
 * <p>Two sets exist per class:
 *
 * <ul>
 *   <li>class actions: the "Actions" column;
 *   <li>rule actions: the same, except that a rule with a sender, port or text condition may also QUARANTINE/BLOCK
 *  CL-NORMAL, and a rule without one may only ALLOW/NOTIFY CL-NORMAL.
 * </ul>
 *
 * <p>The classes of {@link #noOwnEnforcementPoint} get no QUARANTINE/BLOCK, not even in a narrowing rule.
 * No rule for them ever matches where a message can still be stopped, so such a rule would show a Hold or Block that
 * never happens.
 *
 * <p>{@link #releasable} is the one "Releasable" table. No mask allows QUARANTINE for a class that is not
 * releasable: a Hold that nothing can release would act as a silent Block. The payload's ReleaseEligibility
 * and the app's ClassRegistry use or test against the same table.
 *
 * <p>Emergency CB is handled by clamp 2, not here, because the class id is CL-CB for both kinds.
 */
public final class EnforcementMatrix {
    private EnforcementMatrix() {}

    public static final int A = 1 << 0;
    public static final int N = 1 << 1;
    public static final int Q = 1 << 2;
    public static final int B = 1 << 3;
    private static final int AN = A | N;
    private static final int ANB = A | N | B;
    private static final int ALL = A | N | Q | B;

    static {
        // The masks rely on the Verdict declaration order.
        if (Verdict.ALLOW.ordinal() != 0 || Verdict.NOTIFY.ordinal() != 1 || Verdict.QUARANTINE.ordinal() != 2
                || Verdict.BLOCK.ordinal() != 3) {
            throw new IllegalStateException("Verdict order changed");
        }
    }

    public static int bit(Verdict v) {
        return 1 << v.ordinal();
    }

    public static boolean allows(int mask, Verdict v) {
        return (mask & bit(v)) != 0;
    }

    /**
     * "Releasable" column: a held message of class {@code c} can be re-injected and
     * reproduces faithfully. Not releasable: SIM data download (the network exchange is over), SIM-stored, status
     * reports, the consumer classes and OEM-handled messages (no enforcement point holds them), cell broadcasts,
     * incomplete multipart, every outbound class, and the two classes that can crash the phone service on a
     * re-injection (CL-MALFORMED-SMS, CL-WAP-UNREADABLE). A {@code null} class is not releasable.
     */
    public static boolean releasable(ClassId c) {
        if (c == null) return false;
        switch (c) {
            case NORMAL:
            case FLASH:
            case REPLACE:
            case DATA_PORT:
            case WAP_MMS:
            case WAP_SISL:
            case WAP_CP:
            case WAP_DM:
            case WAP_SUPL:
            case WAP_OTHER:
            case INJECTED:
            // The classes that HK-08 or HK-14 holds; a re-injection reproduces them.
            case RETURN_CALL:
            case ME_DOWNLOAD:
            case ME_DEPERSO:
            case CLASS3:
            case TELEMATIC:
            case EMPTY:
            case BINARY:
            case COMPRESSED:
            case SMART:
            case LOC_SUPL:
            case WAP_CO:
            case WAP_DS:
            case WAP_EMN:
            case WAP_DRM:
            case WAP_VCARD:
            case WAP_IMPS:
            // Wrong message type. AOSP reads TP-MTI 1 and 3 as an SMS-DELIVER, so a re-injection works.
            case UNREADABLE_SMS:
            // A release re-injects the TPDU, and the framework applies the
            // indicator again (HK-23 lets a released MWI pass). The indicator can then be older than the mailbox.
            case MWI:
                return true;
            default:
                return false;
        }
    }

    /**
     * No hook can stop a message of class {@code c} as that class. HK-08 does
     * not know the consumer classes yet (FinalClassifier names them after the consumer hooks ran), and HK-31 (CL-OEM)
     * only observes. HK-08 can still stop the message under the class that it gives it, for example CL-DATA-PORT. So
     * these classes get ALLOW/NOTIFY only, in class actions and in every rule.
     */
    public static boolean noOwnEnforcementPoint(ClassId c) {
        if (c == null) return false;
        switch (c) {
            case VVM:
            case MISSED_CALL:
            case APP_TOKEN:
            case CARRIER:
            case RCS_PROV:
            case OEM:
                return true;
            default:
                return false;
        }
    }

    /** Removes QUARANTINE from {@code mask} when {@code c} is not {@link #releasable}. */
    private static int holdOnlyIfReleasable(ClassId c, int mask) {
        return releasable(c) ? mask : mask & ~Q;
    }

    /** "Actions" column for class actions. A {@code null} class (unknown) allows only ALLOW. */
    public static int classActionMask(ClassId c) {
        return holdOnlyIfReleasable(c, rawClassActionMask(c));
    }

    private static int rawClassActionMask(ClassId c) {
        if (c == null) return A;
        if (noOwnEnforcementPoint(c)) return AN;
        switch (c) {
            case INJECTED:
            case REPLACE:
            case WAP_CP:
            case WAP_DM:
            case WAP_SISL:
            case WAP_MMS:
            case WAP_OTHER:
            case WAP_SUPL:
            case DATA_PORT:
            case FLASH:
                return ALL;
            // Classes that HK-08 (SMS) or HK-14 (WAP push) holds and a release can re-inject.
            case RETURN_CALL:
            case ME_DOWNLOAD:
            case ME_DEPERSO:
            case CLASS3:
            case TELEMATIC:
            case EMPTY:
            case BINARY:
            case COMPRESSED:
            case SMART:
            case LOC_SUPL:
            case WAP_CO:
            case WAP_DS:
            case WAP_EMN:
            case WAP_DRM:
            case WAP_VCARD:
            case WAP_IMPS:
            // Held at HK-08 and released by a re-injection (see releasable).
            case UNREADABLE_SMS:
                return ALL;
            // Held at HK-08/HK-14, but not releasable: a re-injection gives the framework parser the PDU or push that
            // it can trip over. A hold without a release path is no hold, so Block instead (X8).
            case MALFORMED_SMS:
            case WAP_UNREADABLE:
                return ANB;
            case MULTIPART_INCOMPLETE: // classified 24 h after the parts were handled: nothing is left to stop
                return AN;
            case CB_SIM: // like non-emergency CB: HK-17 can skip it, but a broadcast cannot be released
                return ANB;
            case SILENT:
                return AN;
            case MWI: // Held at HK-23 and HK-08, released by a re-injection
                return ALL;
            case SIM_DD:
            case CB: // non-emergency; emergency CB is clamped to ALLOW (EMERGENCY_CB)
                return ANB;
            case SIM_STORED:
            case STATUS:
            case NORMAL: // class action fixed ALLOW
                return A;
            case MO_VISIBLE:
            case MO_UNPERSISTED:
            case MO_DATA:
            case MO_RAW:
            case MO_STK:
            case MO_VVM:
                return AN; // outbound block is P2
            default:
                return A;
        }
    }

    /** Actions a rule may produce for class {@code c}. */
    public static int ruleActionMask(ClassId c, boolean hasSenderPortOrText) {
        return holdOnlyIfReleasable(c, rawRuleActionMask(c, hasSenderPortOrText));
    }

    private static int rawRuleActionMask(ClassId c, boolean hasSenderPortOrText) {
        if (c == null) return A;
        if (c == ClassId.NORMAL) return hasSenderPortOrText ? ALL : AN; // No Q/B without a narrowing condition
        return classActionMask(c);
    }

    /** Class actions that the UI offers for {@code c}. */
    public static Set<Verdict> allowedClassActions(ClassId c) {
        return toSet(classActionMask(c));
    }

    /** Actions a rule may use for {@code c}. */
    public static Set<Verdict> allowedRuleActions(ClassId c, boolean hasSenderPortOrText) {
        return toSet(ruleActionMask(c, hasSenderPortOrText));
    }

    private static Set<Verdict> toSet(int mask) {
        EnumSet<Verdict> s = EnumSet.noneOf(Verdict.class);
        for (Verdict v : Verdict.values()) if (allows(mask, v)) s.add(v);
        return s;
    }
}
