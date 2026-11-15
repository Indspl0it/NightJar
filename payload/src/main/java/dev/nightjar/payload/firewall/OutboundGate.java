package dev.nightjar.payload.firewall;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.FateDetail;
import dev.nightjar.payload.capture.OutboundLedger;
import dev.nightjar.payload.inject.SentFailure;
import dev.nightjar.payload.log.PLog;
import dev.nightjar.payload.outbound.OutboundClassification;
import dev.nightjar.payload.outbound.OutboundPolicy;
import dev.nightjar.payload.outbound.OutboundRequest;
import java.util.Set;

/**
 * Outbound block: per-app "Block sending" switch.
 *
 * <p>A package in {@code outgoing.blocked} is blocked for every send class: data SMS, raw PDUs, STK sends, unpersisted
 * text, VVM control messages and also a visible persisted text ({@code CL-MO-VISIBLE}, the
 * {@code SmsManager.sendTextMessage} default). The class comes from
 * {@link OutboundClassification}, the same table the outbound capture uses, and goes into the block reason.
 *
 * <p>Two sends are never blocked; the gate allows them and logs W: a send by the phone process itself, and a send to
 * an emergency number ({@link EmergencyNumbers}). A send without a destination argument (stored resend, raw PDU) has
 * no emergency exception.
 *
 * <p>When a send is blocked the caller's sent {@code PendingIntent} (each one of a multipart send) is failed with
 * {@code RESULT_ERROR_GENERIC_FAILURE} and the HK-19 body skips the original, so nothing is transmitted. The
 * HK-19 event of a blocked send reaches the app with the fate {@code FT-BLOCKED}. Safe mode, the user firewall
 * switch turned off and a disabled outgoing monitor turn every block into an allow.
 *
 * <p>{@code PayloadRuntime.wire()} installs the gate through {@code OutboundPolicies.install} with a {@link Source} that
 * caches the policy per settings snapshot and firewall switch.
 *
 * <p>A send without a calling package (the framework resolves it itself) is checked against the packages of the binder
 * caller ({@link CallerPackages}). When the caller is this process (calling identity cleared) or cannot be resolved,
 * the send is allowed (fail open).
 *
 * <p>Thread: binder thread. One volatile read plus an immutable set lookup; only a send of a blocked package also asks
 * the framework emergency list. Every error allows the send.
 */
public final class OutboundGate implements OutboundPolicy {

    /**
     * The policy inputs this gate needs, taken from the live configuration.
     *
     * @param firewallEnabled the user firewall switch ({@code ConfigStore.firewallEnabled()}); false allows every send
     */
    public record Policy(boolean captureEnabled, boolean safeMode, Set<String> blockedPackages,
            boolean firewallEnabled) {
        public static final Policy NONE = new Policy(false, false, Set.of());

        public Policy {
            blockedPackages = blockedPackages == null ? Set.of() : Set.copyOf(blockedPackages);
        }

        /** A policy with the firewall switch on. */
        public Policy(boolean captureEnabled, boolean safeMode, Set<String> blockedPackages) {
            this(captureEnabled, safeMode, blockedPackages, true);
        }
    }

    /** Where the gate reads its policy from. */
    @FunctionalInterface
    public interface Source {
        Policy policy();
    }

    /** The packages of the current binder caller, or {@code null} when unknown or when the caller is this process. */
    @FunctionalInterface
    public interface CallerPackages {
        String[] packages();

        /** {@code Binder.getCallingUid()} through the application's package manager. Device only. */
        CallerPackages BINDER = () -> {
            int uid = android.os.Binder.getCallingUid();
            if (uid == android.os.Process.myUid()) return null;
            android.content.Context ctx = dev.nightjar.payload.env.Contexts.currentApplication();
            return ctx == null ? null : ctx.getPackageManager().getPackagesForUid(uid);
        };
    }

    /** The destination check for the emergency exception. */
    @FunctionalInterface
    public interface EmergencyNumbers {
        boolean isEmergency(String number);

        /**
         * The default list ({@link OutboundGate#isDefaultEmergency}), then {@code TelephonyManager.isEmergencyNumber}
         * (the SIM, network and database list; the phone process holds the permission). Device only.
         */
        EmergencyNumbers FRAMEWORK = number -> {
            if (isDefaultEmergency(number)) return true;
            android.content.Context ctx = dev.nightjar.payload.env.Contexts.currentApplication();
            if (ctx == null) return false;
            android.telephony.TelephonyManager tm = ctx.getSystemService(android.telephony.TelephonyManager.class);
            return tm != null && tm.isEmergencyNumber(number);
        };
    }

    /** Emergency numbers that apply without a SIM (the Android default list); matched on the digits only. */
    private static final Set<String> DEFAULT_EMERGENCY = Set.of("112", "911", "000", "08", "110", "118", "119", "999");

    /** Overridable intent-failure path; {@code null} means {@link SentFailure#DEFAULT} (tests/diagnostics). */
    private static volatile SentFailure.Sender sender;

    /** Overridable caller lookup; {@code null} means {@link CallerPackages#BINDER} (tests). */
    private static volatile CallerPackages callerPackages;

    /** Overridable emergency check; {@code null} means {@link EmergencyNumbers#FRAMEWORK} (tests). */
    private static volatile EmergencyNumbers emergencyNumbers;

    private static final String TAG = "OutboundGate";

    private final Source source;
    private final PLog log;

    public OutboundGate(Source source) {
        this(source, null);
    }

    /** @param log receives the W line of an exception (phone process, emergency destination); null for none */
    public OutboundGate(Source source, PLog log) {
        this.source = source;
        this.log = log;
    }

    /** Replaces the intent-failure path; {@code null} restores the reflective default. */
    public static void setSender(SentFailure.Sender replacement) {
        OutboundGate.sender = replacement;
    }

    /** Replaces the caller lookup; {@code null} restores {@link CallerPackages#BINDER}. */
    public static void setCallerPackages(CallerPackages replacement) {
        OutboundGate.callerPackages = replacement;
    }

    /** Replaces the emergency check; {@code null} restores {@link EmergencyNumbers#FRAMEWORK}. */
    public static void setEmergencyNumbers(EmergencyNumbers replacement) {
        OutboundGate.emergencyNumbers = replacement;
    }

    @Override
    public Decision decide(OutboundRequest request) {
        try {
            if (request == null) return Decision.ALLOW;
            Policy policy = policy();
            // Safe mode allows everything, and so does the firewall switch turned off. A
            // disabled outgoing monitor has no opinion either.
            if (!policy.captureEnabled() || policy.safeMode() || !policy.firewallEnabled()) return Decision.ALLOW;
            if (policy.blockedPackages().isEmpty()) return Decision.ALLOW;
            String pkg = blockedPackage(request.callingPackage(), policy.blockedPackages());
            if (pkg == null) return Decision.ALLOW;
            ClassId classId = sendClass(request);
            if (isPhoneProcess(pkg)) {
                warn("outbound-phone", "outbound block not applied: the sender is the phone process", pkg, classId);
                return Decision.ALLOW;
            }
            if (isEmergencyDestination(request.destAddr())) {
                warn("outbound-emergency", "outbound block not applied: the destination is an emergency number", pkg,
                        classId);
                return Decision.ALLOW;
            }
            SentFailure.Sender s = sender;
            boolean failed = SentFailure.fail(request.sentIntent(),
                    SentFailure.RESULT_ERROR_GENERIC_FAILURE,
                    s == null ? SentFailure.DEFAULT : s);
            return Decision.blockPackage(FateDetail.OUTBOUND_BLOCK, pkg, classId, failed);
        } catch (Throwable t) {
            // A gate must never break an outgoing call.
            return Decision.ALLOW;
        }
    }

    private Policy policy() {
        Policy p = source == null ? null : source.policy();
        return p == null ? Policy.NONE : p;
    }

    /** The blocked package that sends this message, or {@code null}. A missing package is resolved from the caller. */
    private static String blockedPackage(String callingPackage, Set<String> blocked) {
        if (callingPackage != null && !callingPackage.isEmpty()) {
            return blocked.contains(callingPackage) ? callingPackage : null;
        }
        CallerPackages lookup = callerPackages;
        String[] packages;
        try {
            packages = (lookup == null ? CallerPackages.BINDER : lookup).packages();
        } catch (Throwable t) {
            return null;
        }
        if (packages == null) return null;
        for (String p : packages) {
            if (p != null && blocked.contains(p)) return p;
        }
        return null;
    }

    /** The class this send would have; every class of a blocked package is blocked. */
    public static ClassId sendClass(OutboundRequest request) {
        return OutboundClassification
                .classify(request.kind(), request.stk(), request.persist(), false, false)
                .classId();
    }

    /** True for an emergency number of the default list; separators and a leading {@code +} are ignored. */
    public static boolean isDefaultEmergency(String number) {
        if (number == null) return false;
        StringBuilder digits = new StringBuilder(number.length());
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c != '+' && c != ' ' && c != '-' && c != '(' && c != ')' && c != '.') {
                return false;
            }
        }
        return DEFAULT_EMERGENCY.contains(digits.toString());
    }

    private static boolean isPhoneProcess(String pkg) {
        return OutboundLedger.PHONE_PACKAGE.equals(pkg) || OutboundLedger.INTERNAL_PACKAGE.equals(pkg);
    }

    /** Fail open: a check that throws counts as an emergency destination, so the send goes out. */
    private static boolean isEmergencyDestination(String dest) {
        if (dest == null || dest.isEmpty()) return false;
        EmergencyNumbers check = emergencyNumbers;
        try {
            return (check == null ? EmergencyNumbers.FRAMEWORK : check).isEmergency(dest);
        } catch (Throwable t) {
            return true;
        }
    }

    /** One rate-limited W line per exception kind; never the destination. */
    private void warn(String key, String msg, String pkg, ClassId classId) {
        PLog l = log;
        if (l == null) return;
        try {
            l.wLimited(key, TAG, msg, "pkg", pkg, "class", classId.wire());
        } catch (Throwable ignored) {
            // Fail open: logging never changes the decision.
        }
    }
}
