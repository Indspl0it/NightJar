package dev.nightjar.payload.hooks;

import dev.nightjar.contract.Hashes;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.payload.capture.Observation;
import dev.nightjar.payload.capture.Observations;
import dev.nightjar.payload.capture.PduFacts;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.payload.hook.Param;

/**
 * Hook bodies for the meaning and fate stages: HK-06, HK-07,
 * HK-09…HK-13c, HK-15b, HK-16 and HK-26. {@link FirewallHooks} owns HK-23 and {@link OplusHooks} owns HK-30…HK-32.
 *
 * <p>Every body builds a C.9 detail string with masked values only, pushes one observation and
 * returns. Nothing here reads the message text or an unmasked number into the trace.
 *
 * <p>Threads: phone main looper (HK-06/07/09/10/13a/13b/13c), binder (HK-11), handler (HK-12),
 * scan thread (HK-16/26), main (HK-15b).
 */
public final class MeaningHooks {
    private final Observations out;
    private final Clock clock;

    public MeaningHooks(Observations out, Clock clock) {
        this.out = out;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ HK-06

    /**
     * HK-06 after-hook {@code InboundSmsHandler#dispatchMessage(SmsMessageBase, int source, int
     * token)}. The TPDU comes from the message object ({@code getPdu()}); phoneId from the
     * handler's {@code mPhone} field, because {@code SmsMessageBase} has no phone id.
     */
    public void afterDispatchMessage(Param p) {
        Object message = p.arg(0);
        if (message == null) return;
        byte[] pdu = pduOf(message);
        String key = pdu == null ? null : CaptureHooks.keyFor(pdu);
        String alt = pdu == null ? null : Hashes.tpduKey(pdu);
        int source = intArg(p, 1, -1);
        int token = intArg(p, 2, -1);
        int result = p.result() instanceof Number n ? n.intValue() : Integer.MIN_VALUE;
        int phoneId = phoneIdOf(p.thisObject());
        out.offer(Observation.dispatch(key, alt, pdu, phoneId, source, result, token, clock.elapsedNs()));
    }

    // ------------------------------------------------------------------ HK-07

    /**
     * HK-07 after-hook {@code GsmInboundSmsHandler#dispatchMessageRadioSpecific(SmsMessageBase,
     * int, int)}: the branch is derived from the message getters.
     */
    public void afterRadioSpecific(Param p) {
        Object message = p.arg(0);
        if (message == null) return;
        byte[] pdu = pduOf(message);
        if (pdu == null) return;
        // The GSM SmsMessage keeps the PDU as received, with the SMSC prefix (createFromPdu). A bare-TPDU read of
        // SMSC+TPDU can look valid and give the wrong branch, so the radio framing comes first.
        PduFacts facts = PduFacts.parseRadio(pdu);
        if (!facts.valid() && !facts.statusReport()) facts = PduFacts.parse(pdu);
        String branch = branchOf(facts, p.result());
        out.offer(Observation.stage(CaptureHooks.keyFor(pdu), TraceStage.HANDLER_RADIO_SPECIFIC,
                clock.elapsedNs(), "branch=" + branch));
    }

    /** Branch precedence: type 0, USIM DD, MWI, storage full, normal. */
    static String branchOf(PduFacts facts, Object result) {
        if (facts != null) {
            if (facts.typeZero()) return "type0";
            if (facts.usimDataDownload()) return "usimdd";
            if (facts.mwiSet() || facts.mwiClear()) return "mwi";
        }
        if (result instanceof Number n && n.intValue() == 3) return "storageFull";
        return "normal";
    }

    // ------------------------------------------------------------------ HK-09/10/11/12/13

    /** HK-09 after-hook {@code VisualVoicemailSmsFilter#filter(...)}: true = VVM consumed. */
    public void afterVvmFilter(Param p) {
        boolean consumed = p.result() instanceof Boolean b && b;
        // filter(Context, byte[][], String, int, int) is static: the context is argument 0.
        String key = keyFromPdus(p.arg(1));
        out.offer(Observation.stage(key, TraceStage.FILTER_VVM, clock.elapsedNs(),
                "consumed=" + consumed));
    }

    /** HK-10 after-hook {@code MissedIncomingCallSmsFilter#filter(byte[][], String)}. */
    public void afterMissedCallFilter(Param p) {
        boolean consumed = p.result() instanceof Boolean b && b;
        String key = keyFromPdus(p.arg(0));
        out.offer(Observation.stage(key, TraceStage.FILTER_MISSED_CALL, clock.elapsedNs(),
                "consumed=" + consumed));
    }

    /** HK-11 before-hook {@code CarrierServicesSmsFilterCallback#onFilterComplete(int)}: bit 0 = drop. */
    public void beforeCarrierFilterComplete(Param p) {
        int action = intArg(p, 0, 0);
        // CarrierMessagingService.RECEIVE_OPTIONS_DROP is 0x1; 0x2 only skips the locked-storage
        // notification and the message is still delivered.
        boolean consumed = (action & 0x1) != 0;
        // The callback holds the tracker it is filtering (mTracker), so the stage can be keyed.
        String key = keyFromTracker(Reflect.get(p.thisObject(), "mTracker", null));
        out.offer(Observation.stage(key, TraceStage.FILTER_CARRIER, clock.elapsedNs(),
                "consumed=" + consumed));
    }

    /** HK-12 after-hook {@code AppSmsManager#handleSmsReceivedIntent(Intent)}: true = app consumed. */
    public void afterAppSmsIntent(Param p) {
        boolean consumed = p.result() instanceof Boolean b && b;
        Object intent = p.arg(0);
        String pkg = intent == null ? null : stringField(intent, "mPackage", null);
        out.offer(Observation.stage(keyFromIntent(intent), TraceStage.APP_TOKEN, clock.elapsedNs(),
                "pkg=" + (pkg == null ? "unknown" : pkg) + " consumed=" + consumed));
    }

    /**
     * HK-13a after-hook {@code dispatchIntent(Intent, String, String, Bundle, receiver, user, int)}.
     *
     * <p>Argument 2 is the appOp ({@code "android:receive_sms"}), not the receiving package;
     * the package is the intent's component. Reading the appOp as a package made every trace line
     * read {@code pkg=android:receive_sms}.
     */
    public void afterDispatchIntent(Param p) {
        Object intent = p.arg(0);
        String action = intent == null ? null : stringField(intent, "mAction", null);
        String pkg = packageOfIntent(intent);
        String appOp = p.arg(2) instanceof String s ? s : null;
        StringBuilder d = new StringBuilder(64);
        d.append("action=").append(action == null ? "unknown" : action);
        if (pkg != null) d.append(" pkg=").append(pkg);
        if (appOp != null) d.append(" appOp=").append(appOp);
        out.offer(Observation.stage(keyFromIntent(intent), TraceStage.HANDLER_DISPATCH_INTENT,
                clock.elapsedNs(), d.toString()));
    }

    /** The receiving package of an {@code Intent}, or {@code null} when it has no component. */
    private static String packageOfIntent(Object intent) {
        Object component = intent == null ? null : Reflect.get(intent, "mComponent", null);
        if (component == null) return null;
        try {
            Object name = Reflect.call(component, "getPackageName", null);
            return name instanceof String s && !s.isEmpty() ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** HK-13b after-hook {@code dropFilteredSms(tracker, receiver, boolean)}. */
    public void afterDropFiltered(Param p) {
        Object tracker = p.arg(0);
        String key = keyFromTracker(tracker);
        boolean blocked = p.arg(2) instanceof Boolean b && b;
        out.offer(Observation.stage(key, TraceStage.HANDLER_DROP_FILTERED, clock.elapsedNs(),
                "by=" + (dev.nightjar.payload.firewall.NjSmsFilter.ownDropInProgress() ? "nightjar"
                        : blocked ? "blocklist" : "filter"))); // contract-ok: by= value of the trace detail
    }

    /**
     * HK-13c after-hook {@code BlockChecker#getBlockStatus(Context, String, Bundle)}.
     *
     * <p>The hook sees an address, never a PDU, so it has no correlation key. Keying it on
     * the address hash put the stage in a different key space, where the correlator answered it
     * with a fabricated event. The stage now carries the originator instead, and the correlator
     * joins it to the open message of that originator; the address itself stays out of the trace.
     */
    public void afterBlockStatus(Param p) {
        int status = p.result() instanceof Number n ? n.intValue() : 0;
        String address = p.arg(1) instanceof String s ? s : null;
        out.offer(Observation.stageForOriginator(address, TraceStage.BLOCKLIST, clock.elapsedNs(),
                "status=" + status));
    }

    // ------------------------------------------------------------------ HK-15b, HK-16

    /** HK-15b before-hook {@code sendSmsAckForEnvelopeResponse(IccIoResult, …)}: PoR bytes. */
    public void beforeUsimAck(Param p) {
        Object icc = p.arg(0);
        int sw1 = icc == null ? -1 : Reflect.getInt(icc, "sw1", -1);
        int sw2 = icc == null ? -1 : Reflect.getInt(icc, "sw2", -1);
        // Neither UsimDataDownloadHandler nor GsmInboundSmsHandler keeps the tracker, so
        // there is no key to join on; the PoR is recorded against the newest open message of this
        // phone (see Correlator.attachKeyless).
        out.offer(Observation.stage(null, TraceStage.USIM_POR, clock.elapsedNs(),
                "sw=" + hex(sw1) + hex(sw2)));
    }

    /** HK-16 / HK-32 after-hook {@code scanRawTable}: expired orphans become FT-INCOMPLETE. */
    public void afterScanRawTable(Param p) {
        // The scan itself reports through the tracker list it processed; the payload only records
        // that the sweep ran, and the correlator expires groups on its own timer.
        out.offer(Observation.stage(null, TraceStage.RAW_TABLE_SCAN, clock.elapsedNs(), null));
    }

    /** HK-26 before-hook {@code broadcastSms(InboundSmsTracker)}: post-unlock replay. */
    public void beforeBroadcastSms(Param p) {
        Object tracker = p.arg(0);
        String key = keyFromTracker(tracker);
        out.offer(Observation.stage(key, TraceStage.UNLOCK_REPLAY, clock.elapsedNs(), null));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * {@code getPdu()} of a framework message, resolved once per class.
     *
     * <p>{@code getMethod} walks the hierarchy and copies the parameter array on every call; this
     * runs on the phone main looper for every inbound message.
     */
    private static byte[] pduOf(Object message) {
        if (message == null) return null;
        try {
            java.lang.reflect.Method m = method(GET_PDU, message.getClass(), "getPdu");
            if (m == null) return null;
            Object pdu = m.invoke(message);
            return pdu instanceof byte[] b ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The public no-argument method {@code name} of {@code type}, looked up once per class. A class without it is
     * cached as {@link #ABSENT}, so the lookup does not run again on every call.
     */
    private static java.lang.reflect.Method method(
            java.util.concurrent.ConcurrentHashMap<Class<?>, Object> cache, Class<?> type, String name) {
        Object m = cache.get(type);
        if (m == null) {
            m = ABSENT;
            try {
                java.lang.reflect.Method found = type.getMethod(name);
                found.setAccessible(true);
                m = found;
            } catch (Throwable t) {
                // Not declared on this class: cached as absent.
            }
            cache.putIfAbsent(type, m);
        }
        return m instanceof java.lang.reflect.Method found ? found : null;
    }

    /** Cache value for a class that has no such method. */
    private static final Object ABSENT = new Object();

    /** {@code getPdu()} per concrete class, or {@link #ABSENT}. */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Object> GET_PDU =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** {@code getPhoneId()} per concrete phone class, or {@link #ABSENT}. */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Object> GET_PHONE_ID =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static int phoneIdOf(Object handler) {
        Object phone = Reflect.get(handler, "mPhone", null);
        if (phone == null) return -1;
        try {
            java.lang.reflect.Method m = method(GET_PHONE_ID, phone.getClass(), "getPhoneId");
            if (m != null) {
                Object id = m.invoke(phone);
                return id instanceof Number n ? n.intValue() : -1;
            }
        } catch (Throwable t) {
            // Fall back to the field below.
        }
        return Reflect.getInt(phone, "mPhoneId", -1);
    }

    /** Correlation key from a {@code byte[][]} PDU argument (HK-09/HK-10). */
    private static String keyFromPdus(Object arg) {
        if (!(arg instanceof byte[][] pdus) || pdus.length == 0 || pdus[0] == null) return null;
        return CaptureHooks.keyFor(pdus[0]);
    }

    /**
     * Correlation key from an {@code Intent}'s {@code pdus} extra (HK-12, HK-13a): element 0 of the {@code byte[]}
     * array. Only the extra named {@code pdus} counts. The other byte arrays of a WAP push intent ({@code header},
     * {@code data}) are not a TPDU, so an intent without {@code pdus} has no key.
     */
    static String keyFromIntent(Object intent) {
        if (intent == null) return null;
        Object extras = Reflect.get(intent, "mExtras", null);
        Object map = extras == null ? null : Reflect.get(extras, "mMap", null);
        if (!(map instanceof java.util.Map<?, ?> m)) return null;
        Object pdus = m.get(PDUS_EXTRA);
        if (pdus instanceof Object[] arr && arr.length > 0 && arr[0] instanceof byte[] b) {
            return CaptureHooks.keyFor(b);
        }
        return null;
    }

    /** {@code Telephony.Sms.Intents} extra that holds the message PDUs. */
    private static final String PDUS_EXTRA = "pdus";

    /** Correlation key of an {@code InboundSmsTracker} (its {@code getPdu()} is the message PDU for single-part SMS). */
    static String keyFromTracker(Object tracker) {
        byte[] pdu = pduOf(tracker);
        return pdu == null ? null : CaptureHooks.keyFor(pdu);
    }

    private static String stringField(Object target, String name, String fallback) {
        Object v = Reflect.get(target, name, null);
        return v instanceof String s ? s : fallback;
    }

    private static int intArg(Param p, int index, int fallback) {
        Object v = p.arg(index);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private static String hex(int v) {
        return v < 0 ? "??" : String.format(java.util.Locale.ROOT, "%02x", v);
    }
}
