package dev.nightjar.payload.capture;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Direction;
import dev.nightjar.contract.Fate;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.contract.Transport;
import dev.nightjar.contract.Verdict;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One inbound message under construction, and after sealing the immutable record that is
 * serialized for the app.
 *
 * <p><b>Thread: worker only.</b> Hook threads never touch an {@code Event}; they enqueue
 * {@link Observation}s. Fields are public because this is a mutable record with 40
 * fields; the getters would add nothing. Everything marked "pipeline" is payload-private and is
 * not serialized.
 */
public final class Event {
    // ---------------------------------------------------------------- C.3 identity
    public String eventId;
    public int revision = 1;
    public String sessionId;
    public Direction direction = Direction.IN;
    public Transport transport = Transport.CS;
    public int source = -1;
    public int subId = -1;
    public int slot = -1;
    public String carrierName;
    public String format = "3gpp";

    // ---------------------------------------------------------------- PDUs and grouping
    public final List<byte[]> pdus = new ArrayList<>(1);
    public final List<Part> parts = new ArrayList<>(1);
    public int concatRef = -1;
    public int concatTotal = 0;
    public int concatHave = 0;

    // ---------------------------------------------------------------- times and ACK
    public long receivedAtWall;
    public long receivedAtElapsedNs = -1;
    public Integer ackResult;

    // ---------------------------------------------------------------- classification
    public ClassId provisionalClass;
    public ClassId predictedClass;
    public ClassId finalClass;
    public Verdict verdict = Verdict.ALLOW;
    /** True once an enforcement point reported a verdict; {@link #verdict} is ALLOW before that too. */
    public boolean verdictSeen;
    /** The subscription that {@code env.roaming} was read for; -1 before. */
    int roamingSubId = -1;
    public long ruleId = -1;
    public Verdict clampedFrom;
    public String clampReason;
    public Fate fate;
    public final Map<String, Object> fateDetail = new LinkedHashMap<>();
    /** Subtype code of {@link #finalClass} ({@code Ipc.EventKey.SUBTYPE}), set by {@link FinalClassifier#apply}. */
    public String subtype;
    /** Codes of the other rules that matched, in rule order ({@code Ipc.EventKey.EXTRA_SUBTYPES}). */
    public final List<String> extraSubtypes = new ArrayList<>(0);
    /** Anomaly codes as a {@code pduparse.Anomalies} mask ({@code Ipc.EventKey.ANOMALIES}). */
    public int anomalyMask;

    // ---------------------------------------------------------------- addressing
    public String originator;
    public String smsc;
    public String destination;
    public int destPort = -1;
    public String contentType;

    // ---------------------------------------------------------------- outbound / injected
    public String callingPackage;
    public String attributionTag;
    public String method;
    public Boolean persistFlag;
    public int messageRef = -1;

    // ---------------------------------------------------------------- cell broadcast
    public Cb cb;

    // ---------------------------------------------------------------- trace, flags, env
    public final List<TraceStep> trace = new ArrayList<>(4);
    public final Flags flags = new Flags();
    public final EnvFacts env = new EnvFacts();
    public String payloadVersion;
    public String buildFingerprint;

    // ---------------------------------------------------------------- pipeline (not serialized)
    /** Primary correlation key (TPDU key of the stripped PDU). */
    public String key;
    /** Secondary key (TPDU key of the raw bytes), when it differs. */
    public String altKey;
    public int phoneId = -1;
    public int token = -1;
    /** True once a RAW observation has been attached; a second RAW with the same key is a new message. */
    public boolean rawSeen;
    /** True once an HK-06 dispatch result has been attached; a second dispatch with the same key is another copy. */
    public boolean dispatchSeen;
    /** True when the dispatch was joined by arrival order because the hashes differed (RK-P4). */
    public boolean corrFallback;
    /** Arrival of the first stage, monotonic ns. */
    public long firstTNs = -1;
    /** Arrival of the last stage, monotonic ns; the 30 s seal timeout counts from here. */
    public long lastTNs = -1;
    /** Monotonic ns when the event was created. */
    public long createdAtNs;
    public boolean sealed;
    public boolean fromSim;
    /** HK-07 branch: {@code type0}, {@code usimdd}, {@code mwi}, {@code storageFull}, {@code normal}. */
    public String branch;
    public boolean mwiSuppressed;
    public boolean vvmConsumed;
    public boolean missedCallConsumed;
    public boolean carrierConsumed;
    public boolean appTokenConsumed;
    public boolean oemConsumed;
    public boolean oemRedirected;
    public String consumerPackage;
    public boolean statusReport;
    public boolean blockedByAndroid;
    public boolean wapHandled;
    public int wapResult = Integer.MIN_VALUE;
    /** {@code by} marker of the WAP dispatch stage: {@code nightjar} or {@code crash} when the payload set the result. */
    public String wapBy;
    public String por;
    public boolean expired;
    public String oemReason;
    public String groupKey;
    /** Multipart group of this part, null when the message is not concatenated. */
    public MultipartGroups.Key group;
    /** Sequence number of this part in its group, −1 when it has none. */
    public int groupSeq = -1;
    public boolean released;
    public boolean counterOnly;
    public PduFacts facts;
    /** Class, subtype and extras from the PDU rules; {@link FinalClassifier} adds the consumer rules. */
    public ProvisionalClassifier.Result provisional;
    /** True for a source-2 message without a release or canary token, whatever class the PDU gives. */
    public boolean injected;

    /** One trace step (C.9). {@code tNs} is absolute monotonic; the DTO rebases it. */
    public record TraceStep(TraceStage stage, long tNs, String detail) {}

    /** Arrival time of one PDU part (C.3 {@code parts}). */
    public record Part(int seq, long tNs) {}

    /** Cell broadcast fields (C.3 {@code cb}). */
    public record Cb(int serial, int messageId, int page, int pages, boolean emergency) {}

    /** Event flags (C.3 {@code flags}). */
    public static final class Flags {
        public boolean selfTest;
        public String runId;
        public String canaryClass;
        public boolean replay;
        public String releaseOf;
        public boolean debug;
    }

    /** Environment facts at capture time (C.3 {@code env}). */
    public static final class EnvFacts {
        public boolean roaming;
        public boolean emergencyCallActive;
        public boolean userUnlocked;
        public int bootCount;
    }

    /** Adds a trace step and maintains the first/last stage times. */
    public void addTrace(TraceStage stage, long tNs, String detail) {
        trace.add(new TraceStep(stage, tNs, detail));
        if (firstTNs < 0 || tNs < firstTNs) firstTNs = tNs;
        if (tNs > lastTNs) lastTNs = tNs;
    }

    /** Appends a PDU and its arrival time. */
    public void addPdu(byte[] pdu, long tNs) {
        pdus.add(pdu);
        parts.add(new Part(parts.size() + 1, tNs));
        if (receivedAtElapsedNs < 0) receivedAtElapsedNs = tNs;
    }

    /** True when the event already carries this exact PDU. */
    public boolean hasPdu(byte[] pdu) {
        for (byte[] p : pdus) {
            if (java.util.Arrays.equals(p, pdu)) return true;
        }
        return false;
    }

    public void fateDetail(String key, Object value) {
        if (value != null) fateDetail.put(key, value);
    }

    /** Serializes with the IPC event shape (sorted keys, nulls omitted). */
    public String toJson() {
        return EventDto.toJson(this);
    }
}
