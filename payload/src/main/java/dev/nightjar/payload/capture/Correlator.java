package dev.nightjar.payload.capture;

import dev.nightjar.contract.AnomalyCode;
import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Fate;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.contract.Transport;
import dev.nightjar.contract.Verdict;
import dev.nightjar.payload.env.Clock;
import dev.nightjar.payload.env.Env;
import dev.nightjar.payload.firewall.VerdictRecord;
import dev.nightjar.payload.store.OutboundEvent;
import dev.nightjar.pduparse.Anomalies;
import dev.nightjar.pduparse.CbCategories;
import dev.nightjar.pduparse.ConcatTracker;
import dev.nightjar.pduparse.WspParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Correlation and sealing engine.
 *
 * <p>Joins the stages of one message:
 * <ul>
 *   <li>radio/IMS raw PDUs and HK-06 by {@code (phoneId, tpduKey)} within 10 s, with an
 *       arrival-order fallback flagged {@code corrFallback} (RK-P4);
 *   <li>downstream stages by the same TPDU key, or by the multipart group key;
 *   <li>identical PDUs arriving back to back are separate messages, matched in arrival order
 *       because each key keeps a FIFO of pending events.
 * </ul>
 *
 * <p>Sealing: a terminal fate ({@link Sealer}), the 30 s timeout, {@code FT-PENDING-UNLOCK} while
 * locked, and {@code FT-INCOMPLETE} for multipart groups. {@code CL-NORMAL} + {@code FT-SHOWN}
 * becomes a counter and its PDUs are dropped.
 *
 * <p>Classification: {@link ProvisionalClassifier} runs on every PDU the event gets, {@link FinalClassifier}
 * at the seal. The anomaly codes of the TPDU, plus {@code AN-CONCAT-DUP} from a bounded {@link ConcatTracker} (radio
 * parts only), go to the event.
 *
 * <p>Thread: worker only. The only exception is {@link #drainOutbox()}, also worker-only.
 */
public final class Correlator {
    /** Raw-to-dispatch join window. */
    public static final long JOIN_WINDOW_NS = 10_000_000_000L;
    /** HK-21 attribution and HK-03b SIM marking window. */
    public static final long MARK_WINDOW_NS = 10_000_000_000L;
    /** Pending-unlock entries expire after 24 h. */
    public static final long PENDING_EXPIRY_MS = 24L * 60 * 60 * 1000;
    /** Pending-unlock entries kept in RAM, oldest evicted. */
    public static final int MAX_PENDING = 500;

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    /**
     * A group that is still incomplete this long after its first part becomes CL-MULTIPART-INCOMPLETE: the
     * window of the AN-CONCAT-DUP tracker. The stored parts stay until {@code orphanPurgeDays}.
     */
    static final long INCOMPLETE_AFTER_MS = ConcatTracker.DEFAULT_WINDOW_MS;
    /** Sealed keys kept for the late-stage check; only a handful of messages are ever in flight. */
    static final int MAX_RECENTLY_SEALED = 64;
    /** WAP push port and secure WAP push port (IANA wap-push and wap-pushsecure). */
    private static final int WAP_PORT = dev.nightjar.contract.PortNames.WAP_PUSH;
    private static final int WAP_SECURE_PORT = 2949;

    private final Clock clock;
    private final Sealer sealer;
    private final Counters counters;
    private final Env env;
    private final PendingUnlockStore spool;
    private volatile InjectionTokens tokens;
    private final PackageResolver packages;
    private final String sessionId;
    private final String payloadVersion;
    private final int orphanPurgeDays;

    private final Map<String, Long> simMarks = new HashMap<>();
    /** HK-03 record index per phone, with its arrival time; kept for {@link #MARK_WINDOW_NS} like the SIM marks. */
    private final Map<Integer, SimRecord> simRecordIndex = new HashMap<>();
    private final Map<String, Mark> injections = new HashMap<>();
    private final Map<String, ArrayDeque<PendingUnlock>> pendingUnlock = new LinkedHashMap<>();
    private final ArrayDeque<PendingUnlock> pendingOrder = new ArrayDeque<>();
    private final MultipartGroups groups = new MultipartGroups();
    /** AN-CONCAT-DUP detection; confined to the worker like the rest of this class, memory bounded by the tracker. */
    private final ConcatTracker concat = new ConcatTracker();
    private final List<OutboundEvent> outbox = new ArrayList<>();
    private final Set<Event> indexed = new LinkedHashSet<>();
    /** Keys of sealed events and when they were sealed, oldest first; at most {@link #MAX_RECENTLY_SEALED}. */
    private final LinkedHashMap<String, Long> recentlySealed = new LinkedHashMap<>();

    private volatile OutboundLedger outbound;
    /** Told the key of every emitted event, so the crash journal can drop the file of a delivered message. */
    private volatile java.util.function.Consumer<String> deliveredListener;
    /** Emergency call state for {@code env.emergencyCallActive}; set by the runtime from the firewall state. */
    private volatile BooleanSupplier emergencyCallActive = () -> false;
    /** A keyless HK-23 stage that arrived before its event existed. */
    private Observation pendingMwiStage;

    private int counterOnly;
    private boolean spoolAvailable = true;

    /** HK-21 attribution: which package injected this key, and when. */
    private record Mark(String pkg, long tNs) {}

    /** HK-03 record index of the last SIM-stored message of one phone, and when it arrived. */
    private record SimRecord(int index, long tNs) {}

    /** A sealed FT-PENDING-UNLOCK event kept for the post-unlock revision. */
    private record PendingUnlock(String key, String eventId, int revision, long createdAtWall,
            String predictedClass, String eventJson, MultipartGroups.Key group, int seq) {}

    public Correlator(Clock clock, Sealer sealer, Counters counters, Env env,
            PendingUnlockStore spool, InjectionTokens tokens, PackageResolver packages,
            String sessionId, String payloadVersion, int orphanPurgeDays) {
        this.clock = clock;
        this.sealer = sealer;
        this.counters = counters;
        this.env = env;
        this.spool = spool == null ? PendingUnlockStore.NONE : spool;
        this.tokens = tokens == null ? InjectionTokens.NONE : tokens;
        this.packages = packages == null ? PackageResolver.NONE : packages;
        this.sessionId = sessionId;
        this.payloadVersion = payloadVersion;
        this.orphanPurgeDays = orphanPurgeDays <= 0 ? 7 : orphanPurgeDays;
    }

    // ------------------------------------------------------------------ intake

    /** Installs the release/self-test token registry once the inject package exists. */
    public void setInjectionTokens(InjectionTokens registry) {
        tokens = registry == null ? InjectionTokens.NONE : registry;
    }

    /** Installs the outbound pipeline; without one, HK-19/20/27 observations are dropped. */
    public void setOutbound(OutboundLedger ledger) {
        outbound = ledger;
    }

    /**
     * The source of {@code env.emergencyCallActive} for new events (C.3). Safe from any thread; null means "never in an
     * emergency call".
     */
    public void setEmergencyCallActive(BooleanSupplier active) {
        emergencyCallActive = active == null ? () -> false : active;
    }

    /**
     * Called with the correlation key of every emitted event, on the worker ({@code CrashJournal::delivered}). The
     * listener must not block; an exception from it is ignored.
     */
    public void setDeliveredListener(java.util.function.Consumer<String> listener) {
        deliveredListener = listener;
    }

    /** The outbound pipeline, or null when this runtime does not capture outbound. */
    public OutboundLedger outbound() {
        return outbound;
    }

    /** Consumes one observation. Never throws: an internal error drops the observation. */
    public void accept(Observation o) {
        if (o == null) return;
        try {
            switch (o.kind) {
                case RAW -> onRaw(o);
                case STAGE -> onStage(o);
                case DISPATCH -> onDispatch(o);
                case VERDICT -> onVerdict(o);
                case SEAL -> onSeal(o);
                case SIM_MARK -> onSimMark(o);
                case INJECT -> onInject(o);
                case CB -> onCb(o);
            }
        } catch (Throwable ignored) {
            // Fail open: a malformed observation never reaches telephony. Also an Error (a stack overflow on a
            // hostile structure) must not abort the rest of the drain batch.
        }
    }

    private void onSimMark(Observation o) {
        if (o.key != null) simMarks.put(o.key, o.tNs);
        // HK-03: remember the record index until the SIM-sourced dispatch arrives.
        if (o.phoneId >= 0) {
            String index = value(o.detail, "index");
            if (index != null) {
                try {
                    simRecordIndex.put(o.phoneId, new SimRecord(Integer.parseInt(index), o.tNs));
                } catch (NumberFormatException ignored) {
                    // Informational only.
                }
            }
        }
    }

    private void onInject(Observation o) {
        if (o.key == null) return;
        String pkg = "unknown";
        if (o.uid >= 0) {
            String[] pkgs = packages.packagesForUid(o.uid);
            if (pkgs != null && pkgs.length > 0 && pkgs[0] != null && !pkgs[0].isEmpty()) {
                pkg = pkgs[0];
            }
        }
        injections.put(o.key, new Mark(pkg, o.tNs));
    }

    private void onRaw(Observation o) {
        String key = o.key != null ? o.key : "pdu:" + UUID.randomUUID();
        String altKey = o.altKey;
        Event e;

        // While locked a raw is a new message; the pending entry belongs to the post-unlock
        // replay.
        PendingUnlock p = env.userUnlocked() ? pollPending(key) : null;
        if (p != null) {
            // Post-unlock replay of a message that was sealed as pending (S06).
            e = replayEvent(p, key, altKey, o.phoneId, o.tNs, o.transport, o.format, o.pdu == null);
        } else {
            e = find(key, altKey, o.phoneId, o.tNs);
            if (e != null && e.rawSeen) e = null; // identical PDU: a second message, FIFO join
        }
        if (e == null) {
            e = newEvent(key, altKey, o.phoneId, o.tNs, o.transport, o.format);
        }
        e.rawSeen = true;
        e.phoneId = o.phoneId >= 0 ? o.phoneId : e.phoneId;
        // A hook that knows the subscription wins over the lookup from the phone id.
        if (o.subId >= 0 && e.subId < 0) e.subId = o.subId;
        if (o.slot >= 0 && e.slot < 0) e.slot = o.slot;
        if (o.token >= 0) e.token = o.token;
        e.lastTNs = Math.max(e.lastTNs, o.tNs);
        if (o.pdu != null && !e.hasPdu(o.pdu)) {
            e.addPdu(o.pdu, o.tNs);
        }
        PduFacts parsed = parseFacts(o);
        if (parsed != null) {
            // A RAW without a PDU keeps the facts of an earlier stage; the anomaly codes add up.
            e.facts = parsed;
            // A post-unlock replay is the same part again: only a radio arrival counts for AN-CONCAT-DUP.
            e.anomalyMask |= p != null
                    ? e.facts.facts().anomalyMask()
                    : concat.check(e.facts.facts(), o.tNs / 1_000_000L);
            if (e.originator == null) e.originator = e.facts.originator();
            if (e.smsc == null) e.smsc = e.facts.smsc();
            if (e.destPort < 0) e.destPort = e.facts.destPort();
            e.statusReport = e.facts.statusReport();
            if (e.concatRef < 0) {
                e.concatRef = e.facts.concatRef();
                e.concatTotal = e.facts.concatTotal();
            }
        }
        e.fromSim = simMarks.containsKey(key);
        Long simT = simMarks.get(key);
        if (simT != null && o.tNs - simT > MARK_WINDOW_NS) e.fromSim = false;
        if (e.fromSim) {
            SimRecord mark = simRecordIndex.get(e.phoneId);
            Integer record = mark != null && o.tNs - mark.tNs() <= MARK_WINDOW_NS ? mark.index() : null;
            if (record != null) e.fateDetail("simRecord", record);
            e.addTrace(TraceStage.RADIO_CS_SMS_ON_SIM, o.tNs,
                    record == null ? null : "index=" + record);
        }
        if (e.provisional == null) classifyPdu(e);
        e.predictedClass = e.provisionalClass;
        TraceStage stage = o.stage != null ? o.stage : rawStage(o);
        e.addTrace(stage, o.tNs, rawDetail(o, e));
        index(e);
        if (registerPart(e)) return; // the group decides when the parts are sealed
        maybeSeal(e, o.tNs);
    }

    private static TraceStage rawStage(Observation o) {
        if (o.statusReport) {
            return o.transport == Transport.IMS
                    ? TraceStage.RADIO_IMS_STATUS_REPORT
                    : TraceStage.RADIO_CS_STATUS_REPORT;
        }
        return o.transport == Transport.IMS
                ? TraceStage.RADIO_IMS_ON_SMS_RECEIVED
                : TraceStage.RADIO_CS_NEW_SMS;
    }

    private static String rawDetail(Observation o, Event e) {
        StringBuilder sb = new StringBuilder(24);
        sb.append("len=").append(o.pdu == null ? 0 : o.pdu.length);
        if (e.phoneId >= 0) sb.append(" phone=").append(e.phoneId);
        if (o.token >= 0) sb.append(" token=").append(o.token);
        return sb.toString();
    }

    private PduFacts parseFacts(Observation o) {
        return parseFacts(o.pdu, o.transport);
    }

    /**
     * Parses a raw PDU in the framing of its transport. The other framing is only worth a try when the first read
     * failed and is not a status report (a status report has no UDL, so a failure past TP-ST must not be re-read).
     * When both fail, the first read stays: its anomaly codes describe the PDU as it arrived.
     */
    /** A read in the other framing is used only when it is valid and has no hard anomaly. */
    static boolean otherFramingWins(PduFacts other) {
        return other != null && other.valid() && !Anomalies.malformed(other.facts().anomalyMask());
    }

    static PduFacts parseFacts(byte[] pdu, Transport transport) {
        if (pdu == null) return null;
        boolean ims = transport == Transport.IMS;
        PduFacts f = ims ? PduFacts.parse(pdu) : PduFacts.parseRadio(pdu);
        if (!f.valid() && !f.statusReport()) {
            PduFacts other = ims ? PduFacts.parseRadio(pdu) : PduFacts.parse(pdu);
            // As TpduParser.parseRadio: the other framing wins only when it is clean, so a retry cannot hide a hard
            // anomaly of the first read.
            if (otherFramingWins(other)) return other;
        }
        return f;
    }

    /**
     * Runs the PDU rules on what the event knows now: its TPDU facts, the SIM mark, the injection mark and the
     * WAP content type. A push the WAP hook could not read gets {@code AN-WSP-CTYPE} when the TPDU carried no WSP code.
     */
    private static void classifyPdu(Event e) {
        ProvisionalClassifier.Result r =
                ProvisionalClassifier.result(e.facts, e.fromSim, e.injected, e.contentType);
        e.provisional = r;
        e.provisionalClass = r.classId();
        e.predictedClass = r.classId();
        if (r.classId() == ClassId.WAP_UNREADABLE && (e.anomalyMask & WspParser.UNREADABLE) == 0
                && AnomalyCode.WSP_CTYPE.wire().equals(r.subtype())) {
            e.anomalyMask = Anomalies.with(e.anomalyMask, AnomalyCode.WSP_CTYPE);
        }
    }

    private void onStage(Observation o) {
        if (o.stage == TraceStage.UNLOCK_REPLAY && o.key != null) {
            // HK-26 emits no radio RAW: the pending entry is the only link to the sealed event (S06).
            // broadcastSms then delivers the message, and the consumer stage of that delivery (HK-13a, HK-14)
            // gives the replay its real fate. The replay waits for it like any other event; the 30 s timeout stays
            // the last resort.
            PendingUnlock p = pollPending(o.key);
            if (p != null) {
                Event replay = replayEvent(p, o.key, o.altKey, o.phoneId, o.tNs);
                applyStage(replay, o);
                index(replay);
                maybeSeal(replay, o.tNs);
                return;
            }
        }
        if (o.stage == TraceStage.RAW_TABLE_SCAN || o.stage == TraceStage.OEM_SCAN_RAW_TABLE) {
            expireGroups(clock.wallMs());
        }
        Event e = find(o.key, o.altKey, o.phoneId, o.tNs, o.originator);
        if (e == null && o.key == null && o.stage == TraceStage.MWI_INDICATOR) e = newestMwiEvent();
        if (e == null && o.key == null && o.stage == TraceStage.MWI_INDICATOR) {
            // An injected canary has no radio RAW: HK-23 runs before the dispatch observation creates the event.
            // Keep the stage for that event instead of losing it.
            pendingMwiStage = o;
            return;
        }
        if (e == null && o.key == null && o.stage == TraceStage.HANDLER_DISPATCH_INTENT
                && wapPushAction(value(o.detail, "action"))) {
            // A WAP push intent has no "pdus" extra, so HK-13a has no key: it belongs to the push that HK-14 is
            // dispatching now. This gives the event the app that received the push.
            e = newestWapEvent();
        }
        if (e == null && o.key == null) return; // A keyless stage with no live event joins nothing.
        if (e == null && o.stage == TraceStage.WAP_DISPATCH && sealedRecently(o.key, o.tNs)) {
            // The push of this key is already sealed and sent. A new event here would store the same push a
            // second time, without its slot, sender, port and SMSC.
            return;
        }
        if (e == null) e = newEvent(o.key, o.altKey, o.phoneId, o.tNs, Transport.CS, "3gpp");
        applyStage(e, o);
        e.lastTNs = Math.max(e.lastTNs, o.tNs);
        index(e);
        maybeSeal(e, o.tNs);
    }

    private void applyStage(Event e, Observation o) {
        TraceStage s = o.stage;
        if (s == null) return;
        // HK-14 sends its verdict with the stage, so that both reach the event before the seal.
        if (o.verdict != null) applyVerdict(e, o);
        String d = o.detail;
        switch (s) {
            case HANDLER_RADIO_SPECIFIC -> {
                String branch = value(d, "branch");
                if (branch != null) e.branch = branch;
            }
            case MWI_INDICATOR -> {
                if (d != null && d.contains("suppressed")) e.mwiSuppressed = true;
            }
            case FILTER_VVM -> {
                if (boolValue(d, "consumed")) e.vvmConsumed = true;
                setPackage(e, value(d, "pkg"));
            }
            case FILTER_MISSED_CALL -> {
                if (boolValue(d, "consumed")) e.missedCallConsumed = true;
                setPackage(e, value(d, "pkg"));
            }
            case FILTER_CARRIER -> {
                if (boolValue(d, "consumed")) e.carrierConsumed = true;
                setPackage(e, value(d, "pkg"));
            }
            case APP_TOKEN -> {
                // The hook fires for every message and reports whether an app took it; only consumed=true is
                // CL-APP-TOKEN.
                // A missing marker (an older payload line) keeps the previous behaviour.
                if (d == null || !d.contains("consumed=") || boolValue(d, "consumed")) e.appTokenConsumed = true;
                if (e.appTokenConsumed) setPackage(e, value(d, "pkg"));
            }
            case HANDLER_DISPATCH_INTENT -> {
                String action = value(d, "action");
                setPackage(e, value(d, "pkg"));
                // A WAP push intent gives only the receiving app: dispatchWapPdu sends it from inside the HK-14 call,
                // and the wap.dispatch stage that follows decides the fate. A fate here would seal the event before
                // that stage, and the stage would then open a second event.
                if (action != null && !wapPushAction(action)) {
                    if (action.contains("SMS_DELIVER")) e.fate = Fate.SHOWN;
                    else if (action.contains("DATA_SMS_RECEIVED")) e.fate = Fate.APP;
                }
            }
            case HANDLER_DROP_FILTERED -> {
                String by = value(d, "by");
                if (by != null) {
                    // by=blocklist is a value of the drop hook detail (MeaningHooks), not the trace stage name.
                    if (by.contains("blocklist") || by.contains("blocked")) e.blockedByAndroid = true; // contract-ok: hook value
                    else if (!by.contains("nightjar")) setPackage(e, by);
                }
            }
            case BLOCKLIST -> {
                String status = value(d, "status");
                if (status != null && !"0".equals(status)) e.blockedByAndroid = true;
            }
            case WAP_DISPATCH -> {
                String ct = value(d, "ct");
                if (ct != null) e.contentType = ct;
                String result = value(d, "result");
                if (result != null) {
                    // by=nightjar / by=crash: the payload set the result itself.
                    e.wapBy = value(d, "by");
                    try {
                        e.wapResult = Integer.parseInt(result);
                        e.wapHandled = true;
                    } catch (NumberFormatException ignored) {
                        e.wapHandled = true;
                    }
                }
                classifyPdu(e);
            }
            case USIM_DOWNLOAD -> e.branch = "usimdd";
            case USIM_POR -> {
                String sw = value(d, "sw");
                if (sw != null) e.por = sw;
            }
            case RAW_TABLE_SCAN, OEM_SCAN_RAW_TABLE -> {
                if (boolValue(d, "expired")) e.expired = true;
            }
            case UNLOCK_REPLAY -> e.flags.replay = true;
            case OEM_CHECK -> {
                String reason = value(d, "reason");
                if (reason != null || d != null) {
                    e.oemConsumed = true;
                    if (reason != null) e.oemReason = reason;
                }
            }
            case OEM_REDIRECT -> {
                e.oemRedirected = true;
                setPackage(e, value(d, "pkg"));
            }
            default -> {
                // Trace-only stage.
            }
        }
        e.addTrace(s, o.tNs, d);
    }

    private void onDispatch(Observation o) {
        Event e = findForDispatch(o.key, o.altKey, o.phoneId, o.tNs);
        if (e == null) e = newEvent(o.key, o.altKey, o.phoneId, o.tNs, Transport.CS, "3gpp");
        e.dispatchSeen = true;
        e.phoneId = o.phoneId >= 0 ? o.phoneId : e.phoneId;
        e.source = o.source;
        if (o.result != Integer.MIN_VALUE) e.ackResult = o.result;
        if (o.token >= 0) e.token = o.token;
        if (o.source == 2) applySource2(e, o);
        // A dispatch that has no radio RAW (an injected canary, a release, another app's injection) still carries the
        // message's TPDU: parse it so the class is the PDU's (flash, data port, silent, ...) and not a bare NORMAL.
        if (e.facts == null && o.pdu != null) {
            PduFacts f = PduFacts.parse(o.pdu);
            if (!f.valid() && !f.statusReport()) {
                PduFacts radio = PduFacts.parseRadio(o.pdu);
                if (otherFramingWins(radio)) f = radio;
            }
            e.facts = f;
            if (!e.hasPdu(o.pdu)) e.addPdu(o.pdu, o.tNs);
            if (e.originator == null) e.originator = f.originator();
            if (e.destPort < 0) e.destPort = f.destPort();
            e.statusReport = f.statusReport();
            e.anomalyMask |= f.facts().anomalyMask();
            if (e.provisional == null || e.injected) classifyPdu(e);
        }
        Observation waiting = pendingMwiStage;
        if (waiting != null) {
            pendingMwiStage = null;
            if (o.tNs - waiting.tNs <= JOIN_WINDOW_NS && e.facts != null && (e.facts.mwiSet() || e.facts.mwiClear())) {
                applyStage(e, waiting);
            }
        }
        String detail = "source=" + o.source
                + (o.result == Integer.MIN_VALUE ? "" : " result=" + o.result);
        e.addTrace(TraceStage.HANDLER_DISPATCH_MESSAGE, o.tNs, detail);
        if (e.corrFallback) e.fateDetail("corrFallback", true);
        e.lastTNs = Math.max(e.lastTNs, o.tNs);
        index(e);
        maybeSeal(e, o.tNs);
    }

    /** Source 2 is ours (release/canary/debug) or an injection by another app. */
    private void applySource2(Event e, Observation o) {
        // SOURCE_INJECTED_FROM_UNKNOWN did not come from the network, whoever injected it.
        e.transport = Transport.INJECTED;
        InjectionTokens.Match m = e.key == null ? null : tokens.consume(e.key);
        // A second dispatch of the same message (WAP push re-dispatch) finds the token already consumed: the event
        // keeps what the first one decided.
        if (m == null && (e.flags.selfTest || e.released || e.flags.replay || e.flags.debug)) return;
        if (m != null) {
            switch (m.kind()) {
                case RELEASE -> {
                    e.flags.releaseOf = m.releaseOf();
                    e.released = true;
                }
                case CANARY -> {
                    e.flags.selfTest = true;
                    e.flags.runId = m.runId();
                    e.flags.canaryClass = m.canaryClass();
                }
                case DEBUG -> e.flags.debug = true;
                case REPLAY -> {
                    // The replay is marked and linked to the record it came from.
                    e.flags.replay = true;
                    e.flags.releaseOf = m.releaseOf();
                }
            }
            return;
        }
        e.injected = true;
        classifyPdu(e);
        Mark mark = injections.get(e.key);
        if (mark != null && o.tNs - mark.tNs() <= MARK_WINDOW_NS) {
            e.callingPackage = mark.pkg();
        } else {
            e.callingPackage = "unknown";
        }
    }

    private void onVerdict(Observation o) {
        Event e = find(o.key, o.altKey, o.phoneId, o.tNs);
        if (e == null && VerdictRecord.CANARY.equals(o.clampReason)) {
            // An injected canary is filtered before HK-06 has seen it, so the verdict is the first observation.
            e = newEvent(o.key, o.altKey, o.phoneId, o.tNs, Transport.CS, "3gpp");
            index(e);
        }
        if (e == null) return;
        applyVerdict(e, o);
        e.lastTNs = Math.max(e.lastTNs, o.tNs);
        maybeSeal(e, o.tNs);
    }

    /** Puts the verdict, rule and clamp of {@code o} on the event, with its {@code fw.head} trace step. */
    private static void applyVerdict(Event e, Observation o) {
        e.verdict = o.verdict == null ? Verdict.ALLOW : o.verdict;
        e.verdictSeen = true;
        e.ruleId = o.ruleId;
        e.clampReason = o.clampReason;
        e.addTrace(TraceStage.FW_HEAD_FILTER, o.tNs,
                "verdict=" + e.verdict.wire()
                        + (e.ruleId >= 0 ? " rule=" + e.ruleId : "")
                        + (e.clampReason != null ? " clamp=" + e.clampReason : ""));
    }

    private void onSeal(Observation o) {
        Event e = find(o.key, o.altKey, o.phoneId, o.tNs);
        if (e == null) e = newEvent(o.key, o.altKey, o.phoneId, o.tNs, Transport.CS, "3gpp");
        if (o.fate != null) e.fate = o.fate;
        if (o.detail != null) e.fateDetail("reason", o.detail);
        e.addTrace(TraceStage.FATE, o.tNs, o.fate == null ? null : o.fate.wire());
        e.lastTNs = Math.max(e.lastTNs, o.tNs);
        maybeSeal(e, o.tNs);
    }

    private void onCb(Observation o) {
        Event e = newEvent("cb:" + o.subId + ":" + o.cbSerial + ":" + o.cbMessageId + ":"
                + o.cbPage, null, o.phoneId, o.tNs, Transport.CB, o.format == null ? Observation.CB_GSM : o.format);
        e.subId = o.subId;
        e.slot = o.slot >= 0 ? o.slot : env.slot(o.subId);
        e.carrierName = env.carrierName(e.subId);
        e.cb = new Event.Cb(o.cbSerial, o.cbMessageId, o.cbPage, o.cbPages, o.cbEmergency);
        // CL-CB-SIM for the CB data download range, else CL-CB with its CB subtype.
        ClassId cbClass = CbCategories.classOf(o.cbMessageId);
        e.provisionalClass = cbClass;
        e.predictedClass = cbClass;
        e.finalClass = cbClass;
        e.subtype = CbCategories.subtypeOf(o.cbMessageId);
        e.receivedAtWall = o.wallMs > 0 ? o.wallMs : clock.wallMs();
        if (o.pdu != null) e.addPdu(o.pdu, o.tNs);
        e.addTrace(TraceStage.CB_HANDLE, o.tNs,
                "msgId=" + o.cbMessageId + " serial=" + o.cbSerial);
        if (o.fate == Fate.BLOCKED) {
            e.fate = Fate.BLOCKED;
            e.verdict = Verdict.BLOCK;
            e.fateDetail("channel", o.cbMessageId);
        } else {
            e.fate = Fate.SYSTEM;
        }
        emit(e);
        unindex(e);
    }

    // ------------------------------------------------------------------ sealing

    private void maybeSeal(Event e, long nowNs) {
        if (e.sealed) return;
        MultipartGroups.Group g = group(e);
        if (g != null) {
            if (g.complete()) {
                maybeSealGroup(g, nowNs);
                return;
            }
            sealPart(e, g, nowNs);
            return;
        }
        Fate f = sealer.decide(e, nowNs);
        if (f == null) return;
        if (Sealer.isTimeoutFate(f) && sealer.isTimeout(e, nowNs)) sealer.noteTimeout(e);
        seal(e, f);
    }

    private void seal(Event e, Fate fate) {
        e.fate = fate;
        if (e.finalClass == null) FinalClassifier.apply(e);
        emit(e);
        unindex(e);
    }

    /**
     * Registers a raw part in its group. Returns true when the part belongs to a group
     * and the group, not the caller, decides the seal.
     */
    private boolean registerPart(Event e) {
        PduFacts f = e.facts;
        if (f == null || f.concatRef() < 0) return false;
        int total = f.concatTotal();
        int seq = f.concatSeq();
        // A one-part concatenation is an ordinary message; the table is for real sets.
        if (total < 2) return false;
        // The radio the parts came in on keys the group (one InboundSmsHandler per phone): the phone id is known at the
        // first part, a subscription id can resolve between two parts.
        MultipartGroups.Key gk = new MultipartGroups.Key(e.phoneId >= 0 ? e.phoneId : e.subId,
                e.originator == null ? "" : e.originator, f.concatRef(), total);
        byte[] part = e.pdus.isEmpty() ? new byte[0] : e.pdus.get(0);
        MultipartGroups.Group g = groups.add(gk, seq, part, e.lastTNs, e.eventId, e.receivedAtWall, e);
        // A repeated part is not a member (the first one stays), so its anomaly codes (AN-CONCAT-DUP) go to the
        // member of the same sequence number: the sealed group then reports them.
        Event member = g.events.get(seq);
        if (member != null && member != e) {
            member.anomalyMask |= e.anomalyMask;
            // A second radio arrival of a part is its own message. Android can reject it as a
            // duplicate; that result must not reach the group's row, so the copy seals as its own event. A post-unlock
            // replay is the same part again, not a copy (replayEvent makes it the member).
            if (!e.flags.replay) return false;
        }
        e.group = gk;
        e.groupSeq = seq;
        e.groupKey = gk.toString();
        e.concatHave = g.have();
        if (g.complete()) maybeSealGroup(g, e.lastTNs);
        return true;
    }

    /** The group of {@code e}, or null when it is not part of one. */
    private MultipartGroups.Group group(Event e) {
        return e.group == null ? null : groups.get(e.group);
    }

    /**
     * Seals one unsealed part. A consumer fate is emitted as is (the group stays open); otherwise
     * the part is {@code FT-INCOMPLETE} with a purge time. The part is emitted under the group's event id with the
     * group's parts, so the app keeps one row per group and every later revision replaces it.
     */
    private void sealPart(Event e, MultipartGroups.Group g, long nowNs) {
        Fate f = sealer.decide(e, nowNs);
        if (f == null) return;
        e.fateDetail("have", g.have());
        e.fateDetail("total", g.total());
        if (!consumerFate(f)) {
            if (Sealer.isTimeoutFate(f) && sealer.isTimeout(e, nowNs)) sealer.noteTimeout(e);
            f = Fate.INCOMPLETE;
            e.fateDetail("purgeAt", g.firstWallMs + orphanPurgeDays * DAY_MS);
        }
        e.fate = f;
        if (e.finalClass == null) FinalClassifier.apply(e);
        mergeParts(g, e, nowNs);
        e.revision = ++g.revision;
        emit(e);
        unindex(e);
    }

    /**
     * Fates a consumer (or the firewall) decided, as opposed to the group's own two. not FT-REJECTED. Android
     * rejects one part (for example a duplicate of a part its raw table has already) and still delivers the message, so
     * a rejected part does not decide the fate of its group: the other members do.
     */
    private static boolean consumerFate(Fate f) {
        return f != Fate.INCOMPLETE && f != Fate.UNKNOWN && f != Fate.PENDING_UNLOCK && f != Fate.REJECTED
                && !Sealer.isTimeoutFate(f);
    }

    /**
     * Seals a complete group once, for every member: a consumer fate any member already
     * has wins, otherwise the sealer decides for the first part.
     */
    private void maybeSealGroup(MultipartGroups.Group g, long nowNs) {
        Fate fate = null;
        for (Event m : g.events.values()) {
            Fate f = m.fate != null ? m.fate : sealer.decide(m, nowNs);
            if (f != null && consumerFate(f)) {
                fate = f;
                break;
            }
        }
        Event merged = mergedMember(g);
        if (merged == null) return;
        if (fate == null) {
            boolean rejected = false;
            boolean waiting = false;
            for (Event m : g.events.values()) {
                if (m.sealed) continue;
                Fate f = sealer.decide(m, nowNs);
                if (f == Fate.REJECTED) {
                    rejected = true; // The other members decide
                    continue;
                }
                if (f == null) {
                    waiting = true;
                    continue;
                }
                fate = f;
                break;
            }
            // Only when Android rejected every open member is the group rejected.
            if (fate == null && rejected && !waiting) fate = Fate.REJECTED;
        }
        if (fate == null) return;
        if (Sealer.isTimeoutFate(fate)) {
            for (Event m : g.events.values()) {
                if (!m.sealed && sealer.isTimeout(m, nowNs)) {
                    sealer.noteTimeout(m);
                    break;
                }
            }
        }
        mergeParts(g, merged, nowNs);
        merged.revision = ++g.revision;
        merged.fate = fate;
        if (fate != Fate.PENDING_UNLOCK) dropPendingOf(g);
        for (Event m : g.events.values()) {
            if (m.sealed) continue;
            if (m != merged) m.sealed = true;
            unindex(m);
        }
        groups.remove(g.key);
        // A group that completes after its 24 h seal gets the class of its parts again.
        if (merged.finalClass == null || merged.finalClass == ClassId.MULTIPART_INCOMPLETE) {
            FinalClassifier.apply(merged);
        }
        emit(merged);
    }

    /** The member that carries the group: the last unsealed one, else the first. Null for an empty group. */
    private static Event mergedMember(MultipartGroups.Group g) {
        Event merged = null;
        for (Event m : g.events.values()) {
            if (!m.sealed) merged = m;
        }
        if (merged == null && !g.events.isEmpty()) merged = g.events.firstEntry().getValue();
        return merged;
    }

    /** Puts the group's event id, parts, part count and anomaly codes on {@code merged}. */
    private static void mergeParts(MultipartGroups.Group g, Event merged, long nowNs) {
        merged.eventId = g.eventId;
        if (!merged.counterOnly) {
            merged.pdus.clear();
            merged.parts.clear();
            merged.receivedAtElapsedNs = -1;
            for (var partEntry : g.parts.entrySet()) {
                Long at = g.arrivals.get(partEntry.getKey());
                merged.addPdu(partEntry.getValue(), at == null ? nowNs : at);
            }
        }
        merged.concatHave = g.have();
        for (Event m : g.events.values()) merged.anomalyMask |= m.anomalyMask;
    }

    /**
     * Emits a group that is still incomplete {@link #INCOMPLETE_AFTER_MS} after its first part as
     * CL-MULTIPART-INCOMPLETE. The class of the parts goes to the extra subtypes. The group stays in the
     * table: a late part still completes it, and the purge after {@code orphanPurgeDays} drops it.
     */
    private void sealIncomplete(MultipartGroups.Group g, long nowNs) {
        g.incompleteSealed = true;
        Event merged = mergedMember(g);
        if (merged == null) return;
        mergeParts(g, merged, nowNs);
        if (merged.finalClass == null) FinalClassifier.apply(merged);
        ClassId partsClass = merged.finalClass;
        List<String> extras = partsClass == ClassId.NORMAL
                ? List.of()
                : ProvisionalClassifier.extrasFor(ClassId.MULTIPART_INCOMPLETE, new ProvisionalClassifier.Result(
                        partsClass, merged.subtype, new ArrayList<>(merged.extraSubtypes)));
        merged.finalClass = ClassId.MULTIPART_INCOMPLETE;
        merged.subtype = null;
        merged.extraSubtypes.clear();
        merged.extraSubtypes.addAll(extras);
        merged.fate = Fate.INCOMPLETE;
        merged.fateDetail("have", g.have());
        merged.fateDetail("total", g.total());
        merged.fateDetail("purgeAt", g.firstWallMs + orphanPurgeDays * DAY_MS);
        merged.revision = ++g.revision;
        for (Event m : g.events.values()) {
            if (m.sealed) continue;
            if (m != merged) m.sealed = true;
            unindex(m);
        }
        emit(merged);
    }

    private void emit(Event e) {
        notifyDelivered(e.key);
        if (e.finalClass == null) FinalClassifier.apply(e);
        long t = e.lastTNs >= 0 ? e.lastTNs : clock.elapsedNs();
        e.addTrace(TraceStage.FATE, t, e.fate == null ? null : e.fate.wire());

        // A normal message delivered to the default app becomes a counter only. Not a self-test
        // canary or a debug injection: the self-test reads its event. Not a
        // later revision either (an unlock replay, a group after an FT-INCOMPLETE part): the app already has the row
        // of the earlier revision and counted it, so this revision must replace that row and add no counter.
        if (e.finalClass == ClassId.NORMAL && e.fate == Fate.SHOWN && !e.flags.selfTest && !e.flags.debug
                && e.revision <= 1) {
            // A replay with no pending entry (a normal SMS that arrived before the first unlock, sealed
            // FT-ACCEPTED and counted by the app then) is the same message again, not new traffic: no counter.
            if (!e.counterOnly && !e.flags.replay) {
                counters.increment(e.receivedAtWall > 0 ? e.receivedAtWall : clock.wallMs(),
                        e.subId, e.direction, e.finalClass);
                counterOnly++;
            }
            e.counterOnly = true;
            e.pdus.clear();
            e.parts.clear();
            e.sealed = true;
            return;
        }

        String json = EventDto.toJson(e);
        outbox.add(OutboundEvent.of(e.eventId, e.revision, json));
        e.sealed = true;

        if (e.fate == Fate.PENDING_UNLOCK) {
            String predicted = e.predictedClass == null ? null : e.predictedClass.wire();
            addPending(new PendingUnlock(e.key, e.eventId, e.revision, clock.wallMs(),
                    predicted, json, e.group, e.groupSeq));
        }
    }

    private void notifyDelivered(String key) {
        java.util.function.Consumer<String> l = deliveredListener;
        if (l == null || key == null) return;
        try {
            l.accept(key);
        } catch (Throwable ignored) {
            // The journal deletes the file on its own timer.
        }
    }

    // ------------------------------------------------------------------ pending unlock

    private void addPending(PendingUnlock p) {
        pendingUnlock.computeIfAbsent(p.key, k -> new ArrayDeque<>()).addLast(p);
        pendingOrder.addLast(p);
        if (spoolAvailable) {
            try {
                spool.put(new PendingUnlockStore.Entry(p.key, p.eventId, p.revision,
                        p.createdAtWall, p.predictedClass, p.eventJson));
            } catch (RuntimeException ex) {
                // Any spool error disables the spool for this boot.
                spoolAvailable = false;
            }
        }
        while (pendingOrder.size() > MAX_PENDING) {
            PendingUnlock old = pendingOrder.pollFirst();
            if (old == null) break;
            ArrayDeque<PendingUnlock> q = pendingUnlock.get(old.key);
            if (q != null) {
                q.remove(old);
                if (q.isEmpty()) pendingUnlock.remove(old.key);
            }
            removeSpool(old);
            expire(old, "evicted", clock.elapsedNs());
        }
    }

    /** Takes the oldest pending entry for {@code key} (FIFO). */
    private PendingUnlock pollPending(String key) {
        ArrayDeque<PendingUnlock> q = pendingUnlock.get(key);
        if (q == null) return null;
        PendingUnlock p = q.pollFirst();
        if (q.isEmpty()) pendingUnlock.remove(key);
        if (p != null) {
            pendingOrder.remove(p);
            removeSpool(p);
        }
        return p;
    }

    /**
     * Drops the pending entries of a group that just sealed for real, so a later raw of the same
     * key does not replay into an event that is already final.
     */
    private void dropPendingOf(MultipartGroups.Group g) {
        for (Event m : g.events.values()) {
            ArrayDeque<PendingUnlock> q = pendingUnlock.get(m.key);
            if (q == null) continue;
            for (var it = q.iterator(); it.hasNext(); ) {
                PendingUnlock p = it.next();
                if (!p.eventId().equals(m.eventId)) continue;
                it.remove();
                pendingOrder.remove(p);
                removeSpool(p);
            }
            if (q.isEmpty()) pendingUnlock.remove(m.key);
        }
    }

    /** Builds the replay revision of a pending event. */
    /** @param restorePdus false when the replay brings its own PDU (a RAW): the spooled one would be a second copy */
    private Event replayEvent(PendingUnlock p, String key, String altKey, int phoneId, long tNs,
            Transport transport, String format, boolean restorePdus) {
        Event e = newEvent(key, altKey, phoneId, tNs, transport, format);
        // The app replaces the row with the higher revision: carry the capture data of the pending revision over.
        EventDto.restoreFrom(e, p.eventJson(), tNs, restorePdus, false);
        e.eventId = p.eventId();
        e.revision = p.revision() + 1;
        if (e.facts != null) {
            classifyPdu(e);
        } else {
            e.provisionalClass = dev.nightjar.contract.Wire.parse(
                    dev.nightjar.contract.ClassId.class, p.predictedClass(), null);
        }
        e.flags.replay = true;
        MultipartGroups.Group g = p.group() == null ? null : groups.get(p.group());
        if (g != null && p.seq() >= 1) {
            e.group = p.group();
            e.groupSeq = p.seq();
            e.groupKey = p.group().toString();
            g.events.put(p.seq(), e);
        }
        return e;
    }

    private Event replayEvent(PendingUnlock p, String key, String altKey, int phoneId, long tNs) {
        return replayEvent(p, key, altKey, phoneId, tNs, Transport.CS, "3gpp", true);
    }

    private void removeSpool(PendingUnlock p) {
        if (!spoolAvailable) return;
        try {
            spool.remove(p.eventId(), p.revision());
        } catch (RuntimeException ex) {
            spoolAvailable = false;
        }
    }

    // ------------------------------------------------------------------ timeouts and housekeeping

    /** Runs timers: seal timeouts, pending-unlock delay, expiry of marks and spool entries. */
    public List<OutboundEvent> tick(long nowNs) {
        long nowWall = clock.wallMs();
        List<Event> snapshot = new ArrayList<>(indexed);
        for (Event e : snapshot) {
            try {
                maybeSeal(e, nowNs);
            } catch (Throwable ignored) {
                // Fail open.
            }
        }
        expireMarks(nowNs);
        expirePending(nowWall);
        expireGroups(nowWall);
        return drainOutbox();
    }

    private void expireMarks(long nowNs) {
        simMarks.entrySet().removeIf(en -> nowNs - en.getValue() > MARK_WINDOW_NS);
        injections.entrySet().removeIf(en -> nowNs - en.getValue().tNs() > MARK_WINDOW_NS);
        simRecordIndex.entrySet().removeIf(en -> nowNs - en.getValue().tNs() > MARK_WINDOW_NS);
    }

    /** Entries older than 24 h expire as {@code FT-UNLOCK-EXPIRED} with a new revision. */
    private void expirePending(long nowWall) {
        if (pendingOrder.isEmpty()) return;
        List<PendingUnlock> snapshot = new ArrayList<>(pendingOrder);
        for (PendingUnlock p : snapshot) {
            if (nowWall - p.createdAtWall() < PENDING_EXPIRY_MS) continue;
            pendingOrder.remove(p);
            ArrayDeque<PendingUnlock> q = pendingUnlock.get(p.key);
            if (q != null) {
                q.remove(p);
                if (q.isEmpty()) pendingUnlock.remove(p.key);
            }
            removeSpool(p);
            expire(p, "expired", clock.elapsedNs());
        }
    }

    /**
     * / HK-16: drops orphaned groups past {@code orphanPurgeDays} and, past the caps,
     * the oldest ones. Unsealed members are sealed {@code FT-INCOMPLETE} so no event stays open.
     */
    private void expireGroups(long nowWallMs) {
        if (groups.size() == 0) return;
        for (MultipartGroups.Group g : new ArrayList<>(groups.all())) {
            if (!g.incompleteSealed && !g.complete() && nowWallMs - g.firstWallMs >= INCOMPLETE_AFTER_MS) {
                sealIncomplete(g, clock.elapsedNs());
            }
        }
        long maxAge = orphanPurgeDays * DAY_MS;
        for (MultipartGroups.Group g : new ArrayList<>(groups.all())) {
            if (nowWallMs - g.firstWallMs > maxAge) expireGroup(g, "expired");
        }
        for (MultipartGroups.Group g : new ArrayList<>(groups.all())) {
            if (groups.size() <= MultipartGroups.MAX_GROUPS
                    && groups.bytes() <= MultipartGroups.MAX_BYTES) {
                break;
            }
            expireGroup(g, "purge");
        }
    }

    /** Seals the unsealed parts of a dropped group as one {@code FT-INCOMPLETE} revision under the group's event id. */
    private void expireGroup(MultipartGroups.Group g, String why) {
        groups.remove(g.key);
        Event merged = null;
        for (Event m : g.events.values()) {
            if (!m.sealed) merged = m;
        }
        if (merged == null) return;
        mergeParts(g, merged, clock.elapsedNs());
        merged.expired = true;
        merged.fateDetail("reason", why);
        merged.fateDetail("have", g.have());
        merged.fateDetail("total", g.total());
        merged.fate = Fate.INCOMPLETE;
        merged.revision = ++g.revision;
        for (Event m : g.events.values()) {
            if (m.sealed) continue;
            if (m != merged) m.sealed = true;
            unindex(m);
        }
        emit(merged);
    }

    /**
     * Reload the spool at payload start. While locked the entries stay pending; once
     * unlocked each is emitted as {@code FT-UNLOCK-EXPIRED} with {@code revision+1} and removed.
     */
    public List<OutboundEvent> restore(long nowNs) {
        List<PendingUnlockStore.Entry> entries;
        try {
            entries = spool.load();
        } catch (RuntimeException ex) {
            spoolAvailable = false;
            return List.of();
        }
        boolean unlocked = env.userUnlocked();
        long nowWall = clock.wallMs();
        for (PendingUnlockStore.Entry entry : entries) {
            if (entry == null || entry.key() == null || entry.eventId() == null) continue;
            PendingUnlock p = new PendingUnlock(entry.key(), entry.eventId(), entry.revision(),
                    entry.createdAtWall(), entry.predictedClass(), entry.eventJson(), null, -1);
            if (!unlocked && nowWall - entry.createdAtWall() < PENDING_EXPIRY_MS) {
                addPending(p);
                continue;
            }
            removeSpool(p);
            expire(p, "restore", nowNs);
        }
        return drainOutbox();
    }

    /**
     * Emits a pending entry as {@code FT-UNLOCK-EXPIRED} with {@code revision+1}: Android kept
     * the message for the first unlock, and the payload stops waiting for its replay. The new revision carries the
     * content of the pending one (the app replaces the row), with the new fate and the eviction step.
     */
    private void expire(PendingUnlock p, String why, long tNs) {
        Event e = newEvent(p.key(), null, -1, tNs, Transport.CS, "3gpp");
        EventDto.restoreFrom(e, p.eventJson(), tNs, true, true);
        e.eventId = p.eventId();
        e.revision = p.revision() + 1;
        if (e.finalClass == null) {
            e.finalClass = dev.nightjar.contract.Wire.parse(
                    dev.nightjar.contract.ClassId.class, p.predictedClass(), null);
        }
        e.fate = Fate.UNLOCK_EXPIRED;
        e.addTrace(TraceStage.SPOOL_EVICTED, tNs, why);
        emit(e);
    }

    /** Sealed events produced since the last call, in order. */
    public List<OutboundEvent> drainOutbox() {
        if (outbox.isEmpty()) return List.of();
        List<OutboundEvent> out = new ArrayList<>(outbox);
        outbox.clear();
        return out;
    }

    // ------------------------------------------------------------------ indexing helpers

    private Event newEvent(String key, String altKey, int phoneId, long tNs, Transport transport,
            String format) {
        Event e = new Event();
        e.eventId = UUID.randomUUID().toString();
        e.key = key;
        e.altKey = altKey;
        e.phoneId = phoneId;
        e.transport = transport;
        e.format = format;
        e.sessionId = sessionId;
        e.payloadVersion = payloadVersion;
        e.buildFingerprint = env.buildFingerprint();
        e.receivedAtWall = clock.wallMs();
        e.createdAtNs = tNs;
        e.firstTNs = tNs;
        e.lastTNs = tNs;
        e.env.roaming = env.roaming(-1);
        e.env.emergencyCallActive = emergencyCallActive.getAsBoolean();
        e.env.userUnlocked = env.userUnlocked();
        e.env.bootCount = env.bootCount();
        e.direction = dev.nightjar.contract.Direction.IN;
        return e;
    }

    private void index(Event e) {
        indexed.add(e);
        resolveSubscription(e);
    }

    /**
     * Fills the subscription facts of an inbound event from its phone id: subId from the cached
     * subscription map, then slot, carrier name and roaming of that subscription. Without a map entry the phone id
     * stands in for both (in AOSP the phone id is the logical slot index). The CB path sets these itself.
     */
    private void resolveSubscription(Event e) {
        if (e.subId < 0 && e.phoneId >= 0) {
            int sub = env.subIdForPhone(e.phoneId);
            if (sub >= 0) {
                e.subId = sub;
                if (e.slot < 0) {
                    int slot = env.slot(sub);
                    e.slot = slot >= 0 ? slot : e.phoneId;
                }
                if (e.carrierName == null) {
                    String name = env.carrierName(sub);
                    if (name != null && !name.isEmpty()) e.carrierName = name;
                }
            } else if (e.slot < 0) {
                // A phone id is not a subscription id. The subId stays -1 (unknown): a release then uses the
                // default SMS subscription instead of a wrong SIM. The next index() tries again.
                e.slot = e.phoneId;
            }
        }
        // The roaming fact follows the subscription, also one that a RAW hook set.
        if (e.subId >= 0 && e.roamingSubId != e.subId) {
            e.env.roaming = env.roaming(e.subId);
            e.roamingSubId = e.subId;
        }
    }

    private void unindex(Event e) {
        indexed.remove(e);
        if (e.key != null && e.sealed) noteSealed(e.key, e.lastTNs >= 0 ? e.lastTNs : clock.elapsedNs());
    }

    /** Remembers that the event of {@code key} was sealed at {@code tNs}; the oldest entries go first. */
    private void noteSealed(String key, long tNs) {
        recentlySealed.remove(key);
        recentlySealed.put(key, tNs);
        while (recentlySealed.size() > MAX_RECENTLY_SEALED) {
            String oldest = recentlySealed.keySet().iterator().next();
            recentlySealed.remove(oldest);
        }
    }

    /** True when the event of {@code key} was sealed less than {@link #JOIN_WINDOW_NS} before {@code tNs}. */
    private boolean sealedRecently(String key, long tNs) {
        if (key == null) return false;
        Long at = recentlySealed.get(key);
        return at != null && tNs - at <= JOIN_WINDOW_NS;
    }

    /** True for the WAP push intent actions ({@code WAP_PUSH_DELIVER}, {@code WAP_PUSH_RECEIVED}). */
    private static boolean wapPushAction(String action) {
        return action != null && (action.contains("WAP_PUSH_DELIVER") || action.contains("WAP_PUSH_RECEIVED"));
    }

    /**
     * The push that HK-14 is dispatching: the newest unsealed event addressed to a WAP push port (2948, or 2949 for
     * a secure push) that has no {@code wap.dispatch} result yet.
     */
    private Event newestWapEvent() {
        Event best = null;
        for (Event e : indexed) {
            if (e.sealed || e.wapHandled) continue;
            if (e.destPort != WAP_PORT && e.destPort != WAP_SECURE_PORT) continue;
            if (best == null || e.lastTNs >= best.lastTNs) best = e;
        }
        if (best != null) best.corrFallback = true;
        return best;
    }

    /**
     * HK-23 runs on the handler with only the indicator value: no key, no originator and no phone id. The one event it
     * can belong to is the newest unsealed message whose TPDU sets or clears the indicator.
     */
    private Event newestMwiEvent() {
        Event best = null;
        for (Event e : indexed) {
            if (e.sealed || e.facts == null) continue;
            if (!(e.facts.mwiSet() || e.facts.mwiClear())) continue;
            if (best == null || e.lastTNs >= best.lastTNs) best = e;
        }
        if (best != null) best.corrFallback = true;
        return best;
    }

    /**
     * Finds the event a stage belongs to: exact key, alternative key, then the
     * oldest pending event of the same phone within the join window (RK-P4 fallback). Only a handful
     * of messages are ever in flight, so a scan beats three index maps.
     */
    private Event find(String key, String altKey, int phoneId, long tNs) {
        return find(key, altKey, phoneId, tNs, null);
    }

    /**
     * The event that an HK-06 dispatch result belongs to. Identical PDUs (a repeated part, a resent
     * message) are separate radio arrivals with the same key, and Android dispatches them in arrival order. So the
     * result goes to the oldest open event of the key that has no dispatch result yet. When every open event of the key
     * has one and the newest of them is a radio arrival, this dispatch is another copy of the PDU and gets a new event
     * (null). An event with no radio arrival (an injection: canary, release, debug) keeps the old join, so a second
     * dispatch of our own injection does not open a second event (applySource2). With no event of the key, the
     * arrival-order fallback of {@link #find(String, String, int, long)} (RK-P4) decides, again never onto an event
     * that has a result.
     */
    private Event findForDispatch(String key, String altKey, int phoneId, long tNs) {
        Event dispatched = null;
        Event fallback = null;
        for (Event e : indexed) {
            if (e.sealed) continue;
            if (sameKey(e, key, altKey)) {
                if (!e.dispatchSeen) return e;
                dispatched = e;
            } else if (fallback == null && !e.dispatchSeen && phoneId >= 0 && e.phoneId == phoneId
                    && e.firstTNs >= 0 && tNs - e.firstTNs <= JOIN_WINDOW_NS) {
                fallback = e;
            }
        }
        if (dispatched != null) return dispatched.rawSeen ? null : dispatched;
        if (fallback != null) fallback.corrFallback = true;
        return fallback;
    }

    private static boolean sameKey(Event e, String key, String altKey) {
        if (key != null && (key.equals(e.key) || key.equals(e.altKey))) return true;
        return altKey != null && (altKey.equals(e.key) || altKey.equals(e.altKey));
    }

    /**
     * As {@link #find(String, String, int, long)}, plus an originator match for a stage that has no
     * key at all (HK-13c: the blocklist check sees an address, never a PDU). Without it such a
     * stage either invented an event or joined nothing.
     */
    private Event find(String key, String altKey, int phoneId, long tNs, String originator) {
        Event fallback = null;
        for (Event e : indexed) {
            if (e.sealed) continue;
            if (key != null && (key.equals(e.key) || key.equals(e.altKey))) return e;
            if (altKey != null && (altKey.equals(e.key) || altKey.equals(e.altKey))) return e;
            if (key == null && originator != null && originator.equals(e.originator)) return e;
            if (phoneId >= 0 && e.phoneId == phoneId && fallback == null
                    && e.firstTNs >= 0 && tNs - e.firstTNs <= JOIN_WINDOW_NS) {
                fallback = e;
            }
        }
        if (fallback != null) fallback.corrFallback = true;
        return fallback;
    }

    // ------------------------------------------------------------------ detail parsing

    /** Extracts {@code key=value} from a C.9 detail string; null when absent. */
    static String value(String detail, String key) {
        if (detail == null) return null;
        int from = 0;
        while (from < detail.length()) {
            int end = detail.indexOf(' ', from);
            if (end < 0) end = detail.length();
            int eq = detail.indexOf('=', from);
            if (eq > from && eq < end && detail.startsWith(key, from)
                    && eq - from == key.length()) {
                return detail.substring(eq + 1, end);
            }
            from = end + 1;
        }
        return null;
    }

    private static boolean boolValue(String detail, String key) {
        String v = value(detail, key);
        return "true".equals(v) || "1".equals(v);
    }

    private static void setPackage(Event e, String pkg) {
        if (pkg != null && !pkg.isEmpty() && !"null".equals(pkg)) e.consumerPackage = pkg;
    }

    // ------------------------------------------------------------------ introspection

    public int pendingCount() {
        return indexed.size();
    }

    /** Events replaced by a counter; asserted by the correlator tests. */
    public int counterOnlyCount() {
        return counterOnly;
    }

    public int pendingUnlockCount() {
        return pendingOrder.size();
    }

    public boolean spoolAvailable() {
        return spoolAvailable;
    }

    /** Test seam: the multipart group table. */
    MultipartGroups groups() {
        return groups;
    }
}
