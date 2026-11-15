package dev.nightjar.payload.hook;

import dev.nightjar.contract.Tier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The hook catalog as data for the hooks the payload owns. HK-08/HK-24 (firewall), HK-18 (firewall-extra,
 * registered after the core tiers) and HK-25 (emergency-call state) are not listed here; their owners register them
 * through {@link HookRegistry#register}. HK-19 names nine {@code SmsController#send*} entry points, but one hook is
 * one target, so they are listed as {@code HK-19a…HK-19i}.
 *
 * <p>Signatures come from the 2026-11-05 firmware audit of both reference builds: {@code
 * dispatchMessage}, {@code dispatchMessageRadioSpecific}, {@code updateMessageWaitingIndicator},
 * {@code dropFilteredSms} and {@code handleSms} are non-public and are matched by name and arity
 * only, never by a stub type.
 */
public final class HookCatalog {
    public static final String TELEPHONY = "com.android.internal.telephony.";
    public static final String GSM = TELEPHONY + "gsm.";

    private HookCatalog() {}

    /** NAME-resolved hook with an arity. */
    private static HookSpec spec(String id, Tier tier, HookKind kind, String cls, String method,
            int arity, boolean mainLooper, boolean neverMinusOne) {
        return new HookSpec(id, tier, kind, cls, method, List.of(), HookSpec.ResolverKind.NAME, null,
                arity, neverMinusOne, mainLooper);
    }

    private static HookSpec spec(String id, Tier tier, HookKind kind, String cls, String method,
            int arity) {
        return spec(id, tier, kind, cls, method, arity, false, false);
    }

    /** Overload hook: name + arity, for signatures that drift between builds (HK-19). */
    private static HookSpec overload(String id, Tier tier, HookKind kind, String cls, String method,
            int arity, boolean mainLooper) {
        return new HookSpec(id, tier, kind, cls, method, List.of(),
                HookSpec.ResolverKind.OVERLOAD, null, arity, false, mainLooper);
    }

    /** Anonymous-class hook: the class is found by its superclass/interface. */
    private static HookSpec anon(String id, Tier tier, HookKind kind, String outer, String superName,
            String method, int arity) {
        return new HookSpec(id, tier, kind, outer, method, List.of(),
                HookSpec.ResolverKind.ANONYMOUS, superName, arity, false, false);
    }

    /** Hook resolved through another object's class loader (OnePlus). */
    private static HookSpec impl(String id, Tier tier, HookKind kind, String cls, String method,
            int arity, boolean mainLooper) {
        return new HookSpec(id, tier, kind, cls, method, List.of(),
                HookSpec.ResolverKind.IMPL_LOADER, null, arity, false, mainLooper);
    }

    /**
     * The spec with {@code id}, or null. Test helper: it builds the whole catalog on each call, and production code
     * walks {@link #all()} once. R8 removes it from the payload dex.
     */
    public static HookSpec of(String id) {
        for (HookSpec s : all()) {
            if (s.id().equals(id)) return s;
        }
        return null;
    }

    /** Every hook this payload resolves, in hook id order. */
    public static List<HookSpec> all() {
        List<HookSpec> specs = new ArrayList<>(32);

        // ---- radio (AIDL) and IMS
        specs.add(spec("HK-01", Tier.CAPTURE_REQUIRED, HookKind.AFTER,
                TELEPHONY + "MessagingIndication", "newSms", 2));
        specs.add(spec("HK-01b", Tier.CAPTURE_OPTIONAL, HookKind.AFTER,
                TELEPHONY + "RadioIndication", "newSms", 2));
        specs.add(spec("HK-02", Tier.CAPTURE_OPTIONAL, HookKind.AFTER,
                TELEPHONY + "MessagingIndication", "newSmsStatusReport", 2));
        specs.add(spec("HK-02b", Tier.CAPTURE_OPTIONAL, HookKind.AFTER,
                TELEPHONY + "RadioIndication", "newSmsStatusReport", 2));
        specs.add(spec("HK-03", Tier.CAPTURE_OPTIONAL, HookKind.AFTER,
                TELEPHONY + "MessagingIndication", "newSmsOnSim", 2));
        specs.add(spec("HK-03b", Tier.CAPTURE_OPTIONAL, HookKind.BEFORE,
                TELEPHONY + "uicc.SIMRecords", "handleSms", 1));
        specs.add(anon("HK-04", Tier.CAPTURE_REQUIRED, HookKind.BEFORE,
                TELEPHONY + "ImsSmsDispatcher",
                "com.android.internal.telephony.IImsSmsListener$Stub", "onSmsReceived", 3));
        specs.add(anon("HK-05", Tier.CAPTURE_OPTIONAL, HookKind.BEFORE,
                TELEPHONY + "ImsSmsDispatcher",
                "com.android.internal.telephony.IImsSmsListener$Stub", "onSmsStatusReportReceived", 3));

        // ---- dispatch and radio branch
        specs.add(spec("HK-06", Tier.CAPTURE_REQUIRED, HookKind.AFTER,
                TELEPHONY + "InboundSmsHandler", "dispatchMessage", 3, true, false));
        specs.add(spec("HK-07", Tier.MEANING, HookKind.AFTER,
                GSM + "GsmInboundSmsHandler", "dispatchMessageRadioSpecific", 3, true, false));
        specs.add(spec("HK-23", Tier.FIREWALL_EXTRA, HookKind.BEFORE,
                GSM + "GsmInboundSmsHandler", "updateMessageWaitingIndicator", 1, true, false));

        // ---- meaning (consumers)
        specs.add(spec("HK-09", Tier.MEANING, HookKind.AFTER,
                TELEPHONY + "VisualVoicemailSmsFilter", "filter", 5, true, false));
        specs.add(spec("HK-10", Tier.MEANING, HookKind.AFTER,
                TELEPHONY + "MissedIncomingCallSmsFilter", "filter", 2, true, false));
        specs.add(spec("HK-11", Tier.MEANING, HookKind.BEFORE,
                TELEPHONY + "InboundSmsHandler$CarrierServicesSmsFilterCallback",
                "onFilterComplete", 1));
        specs.add(spec("HK-12", Tier.MEANING, HookKind.AFTER,
                TELEPHONY + "AppSmsManager", "handleSmsReceivedIntent", 1));
        specs.add(spec("HK-13a", Tier.MEANING, HookKind.AFTER,
                TELEPHONY + "InboundSmsHandler", "dispatchIntent", 7, true, false));
        specs.add(spec("HK-13b", Tier.MEANING, HookKind.AFTER,
                TELEPHONY + "InboundSmsHandler", "dropFilteredSms", 3, true, false));
        specs.add(spec("HK-13c", Tier.MEANING, HookKind.AFTER,
                TELEPHONY + "BlockChecker", "getBlockStatus", 3, true, false));
        specs.add(spec("HK-15b", Tier.MEANING, HookKind.BEFORE,
                GSM + "UsimDataDownloadHandler", "sendSmsAckForEnvelopeResponse", 6, true, false));
        specs.add(spec("HK-16", Tier.MEANING, HookKind.AFTER,
                TELEPHONY + "SmsBroadcastUndelivered", "scanRawTable", 2));
        specs.add(spec("HK-26", Tier.MEANING, HookKind.BEFORE,
                TELEPHONY + "SmsBroadcastUndelivered", "broadcastSms", 1));

        // ---- firewall-core enforcement points the payload body needs
        specs.add(spec("HK-14", Tier.FIREWALL_CORE, HookKind.AROUND,
                TELEPHONY + "WapPushOverSms", "dispatchWapPdu", 6, true, true));
        specs.add(spec("HK-15", Tier.FIREWALL_CORE, HookKind.BEFORE,
                GSM + "UsimDataDownloadHandler", "handleUsimDataDownload", 4, true, true));

        // ---- head filter installation (HK-24: every InboundSmsHandler constructor)
        specs.add(new HookSpec("HK-24", Tier.FIREWALL_CORE, HookKind.AFTER,
                TELEPHONY + "InboundSmsHandler", "<init>", List.of(),
                HookSpec.ResolverKind.NAME, null, -1, false, false));

        // ---- cell broadcast (capture, enforcement)
        specs.add(anon("HK-17", Tier.CAPTURE_OPTIONAL, HookKind.BEFORE,
                TELEPHONY + "CellBroadcastServiceManager", "android.os.Handler", "handleMessage", 1));

        // ---- injection attribution
        specs.add(spec("HK-21", Tier.CAPTURE_OPTIONAL, HookKind.BEFORE,
                TELEPHONY + "SmsController", "injectSmsPduForSubscriber", 4));

        // ---- outbound: the nine send entry points, the dispatcher PDU, the STK
        // mark and the tracker's result.
        specs.add(overload("HK-19a", Tier.OUTBOUND, HookKind.BEFORE,
                TELEPHONY + "SmsController", "sendTextForSubscriber", 12, false));
        specs.add(overload("HK-19b", Tier.OUTBOUND, HookKind.BEFORE,
                TELEPHONY + "SmsController", "sendTextForSubscriberWithOptions", 12, false));
        specs.add(overload("HK-19c", Tier.OUTBOUND, HookKind.BEFORE,
                TELEPHONY + "SmsController", "sendDataForSubscriber", 9, false));
        specs.add(overload("HK-19d", Tier.OUTBOUND, HookKind.BEFORE,
                TELEPHONY + "SmsController", "sendRawPduForSubscriber", 7, false));
        specs.add(overload("HK-19e", Tier.OUTBOUND, HookKind.BEFORE,
                TELEPHONY + "SmsController", "sendMultipartTextForSubscriber", 10, false));
        specs.add(overload("HK-19f", Tier.OUTBOUND, HookKind.BEFORE,
                TELEPHONY + "SmsController", "sendMultipartTextForSubscriberWithOptions", 12, false));
        specs.add(overload("HK-19g", Tier.OUTBOUND, HookKind.BEFORE,
                TELEPHONY + "SmsController", "sendStoredText", 7, false));
        specs.add(overload("HK-19h", Tier.OUTBOUND, HookKind.BEFORE,
                TELEPHONY + "SmsController", "sendStoredMultipartText", 7, false));
        specs.add(overload("HK-19i", Tier.OUTBOUND, HookKind.BEFORE,
                TELEPHONY + "SmsController", "sendVisualVoicemailSmsForSubscriber", 8, false));
        // sendRawPdu is declared once, on SMSDispatcher, and calls sendSms per tracker: hooking it
        // alone records every MO exactly once (OutboundHooks).
        specs.add(spec("HK-20", Tier.OUTBOUND, HookKind.BEFORE,
                TELEPHONY + "SMSDispatcher", "sendRawPdu", 1, true, false));
        // CatService#sendStkSms takes the SendSMSParams on A17 and five arguments on OnePlus.
        specs.add(spec("HK-22", Tier.OUTBOUND, HookKind.BEFORE,
                TELEPHONY + "cat.CatService", "sendStkSms", -1, false, false));
        // handleSendComplete(AsyncResult) is the single completion point for every tracker.
        specs.add(spec("HK-27", Tier.OUTBOUND, HookKind.AFTER,
                TELEPHONY + "SMSDispatcher", "handleSendComplete", 1, true, false));
        // An IMS send never passes handleSendComplete: ImsSmsDispatcher reports the result from its
        // onSendSmsResult listener straight to the tracker. SmsTracker#onSent / #onFailed are the completion points
        // that every transport calls (OnePlus 13 decomp: ImsSmsDispatcher and SMSDispatcher). Not found: the result of
        // an IMS send stays unobserved and the ledger seals it FT-SEND-UNCONFIRMED after the timeout.
        specs.add(spec("HK-27b", Tier.OUTBOUND, HookKind.AFTER,
                TELEPHONY + "SMSDispatcher$SmsTracker", "onSent", 1));
        specs.add(spec("HK-27c", Tier.OUTBOUND, HookKind.AFTER,
                TELEPHONY + "SMSDispatcher$SmsTracker", "onFailed", 3));

        // ---- OnePlus (resolved through InboundSmsHandler.mImpl's loader)
        specs.add(impl("HK-30", Tier.MEANING, HookKind.AFTER,
                "com.oplus.internal.telephony.OplusInboundSmsHandlerImpl", "oemCheck", 5, true));
        specs.add(spec("HK-31", Tier.FIREWALL_EXTRA, HookKind.BEFORE,
                TELEPHONY + "InboundSmsHandler", "romDispatchSmsDeliveryIntent", 8, true, false));
        specs.add(impl("HK-32", Tier.MEANING, HookKind.AFTER,
                "com.oplus.internal.telephony.OplusSmsBroadcastUndelivered", "scanRawTable", 2, false));

        return specs;
    }

    /** Hooks that may be {@code NOT_APPLICABLE} on a given build without failing the capture tier. */
    public static Map<String, String> notApplicableByDefault() {
        Map<String, String> m = new LinkedHashMap<>();
        // A17 wires the AIDL radio only; the HIDL twins are never invoked (firmware audit).
        m.put("HK-01b", "AIDL radio in use");
        m.put("HK-02b", "AIDL radio in use");
        // OnePlus has no sendRawPduForSubscriber; the raw-PDU path is still captured at HK-20.
        m.put("HK-19d", "absent on OnePlus");
        return m;
    }
}
