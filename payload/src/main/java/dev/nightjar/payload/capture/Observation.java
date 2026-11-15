package dev.nightjar.payload.capture;

import dev.nightjar.contract.Fate;
import dev.nightjar.contract.TraceStage;
import dev.nightjar.contract.Transport;
import dev.nightjar.contract.Verdict;

/**
 * One hook observation, pushed by a hook thread and consumed by the worker.
 * Immutable: everything a hook body needs to say fits in one object, so the hook thread
 * allocates once and returns.
 *
 * <p>Thread: created on any thread, read on the worker.
 */
public final class Observation {
    /** What the hook observed. */
    public enum Kind {
        /** A raw PDU from HK-01/01b/04/02/02b/05 (radio, IMS, status report). */
        RAW,
        /** A downstream stage for an existing event (HK-07…HK-16, HK-30…32). */
        STAGE,
        /** HK-06 {@code dispatchMessage}: source and ACK result. */
        DISPATCH,
        /** A firewall verdict at an enforcement point. */
        VERDICT,
        /** An explicit seal request (e.g. a status report, a canary interception). */
        SEAL,
        /** HK-03b: the next dispatch with this key came from the SIM. */
        SIM_MARK,
        /** HK-21: this key was injected by {@code pkg}. */
        INJECT,
        /** HK-17: a cell broadcast PDU. */
        CB,
        /** HK-19/20/22/27: an outbound stage, see {@link #mo}. */
        MO
    }

    public final Kind kind;
    public final String key;
    public final String altKey;
    public final byte[] pdu;
    public final String format;
    public final Transport transport;
    public final int phoneId;
    public final int subId;
    public final int slot;
    public final int token;
    public final int source;
    public final int result;
    public final long tNs;
    public final long wallMs;
    public final TraceStage stage;
    public final String detail;
    public final Verdict verdict;
    public final long ruleId;
    public final String clampReason;
    public final Fate fate;
    /** Injecting uid for {@link Kind#INJECT}; −1 when unknown. */
    public final int uid;
    public final boolean statusReport;
    public final int cbSerial;
    public final int cbMessageId;
    public final int cbPage;
    public final int cbPages;
    public final boolean cbEmergency;
    /**
     * Originating address for a stage that has no correlation key (HK-13c): the correlator joins
     * it to the open message of the same originator. Null everywhere else.
     */
    public final String originator;
    /** Outbound stage, non-null exactly when {@link #kind} is {@link Kind#MO}. */
    public final dev.nightjar.payload.outbound.MoEvent mo;

    private Observation(Kind kind, String key, String altKey, byte[] pdu, String format,
            Transport transport, int phoneId, int subId, int slot, int token, int source, int result,
            long tNs, long wallMs, TraceStage stage, String detail, Verdict verdict, long ruleId,
            String clampReason, Fate fate, int uid, boolean statusReport, int cbSerial, int cbMessageId,
            int cbPage, int cbPages, boolean cbEmergency, dev.nightjar.payload.outbound.MoEvent mo,
            String originator) {
        this.kind = kind;
        this.key = key;
        this.altKey = altKey;
        this.pdu = pdu;
        this.format = format;
        this.transport = transport;
        this.phoneId = phoneId;
        this.subId = subId;
        this.slot = slot;
        this.token = token;
        this.source = source;
        this.result = result;
        this.tNs = tNs;
        this.wallMs = wallMs;
        this.stage = stage;
        this.detail = detail;
        this.verdict = verdict;
        this.ruleId = ruleId;
        this.clampReason = clampReason;
        this.fate = fate;
        this.uid = uid;
        this.statusReport = statusReport;
        this.cbSerial = cbSerial;
        this.cbMessageId = cbMessageId;
        this.cbPage = cbPage;
        this.cbPages = cbPages;
        this.cbEmergency = cbEmergency;
        this.mo = mo;
        this.originator = originator;
    }

    /** A radio/IMS PDU. {@code key} is the TPDU key; {@code altKey} the raw-bytes key or null. */
    public static Observation raw(String key, String altKey, byte[] pdu, String format,
            Transport transport, int phoneId, int subId, int slot, int token, boolean statusReport,
            long tNs, long wallMs) {
        return new Observation(Kind.RAW, key, altKey, pdu, format, transport, phoneId, subId, slot, token, -1,
                Integer.MIN_VALUE, tNs, wallMs, null, null, null, -1, null, null, -1, statusReport, -1, -1, -1, -1,
                false, null, null);
    }

    /** A downstream stage; {@code detail} must be masked (C.9). */
    public static Observation stage(String key, TraceStage stage, long tNs, String detail) {
        return stage(key, null, -1, stage, tNs, detail);
    }

    /**
     * A downstream stage with the phone id, for hooks that never see the TPDU (HK-14/15/17): the
     * correlator then joins by {@code (phoneId, arrival order)} within the join window.
     */
    public static Observation stage(String key, String altKey, int phoneId, TraceStage stage,
            long tNs, String detail) {
        return new Observation(Kind.STAGE, key, altKey, null, "3gpp", Transport.CS, phoneId, -1, -1, -1, -1,
                Integer.MIN_VALUE, tNs, 0L, stage, detail, null, -1, null, null, -1, false, -1, -1, -1, -1, false,
                null, null);
    }

    /**
     * A stage that carries the firewall verdict of the same hook call (HK-14). The correlator applies the verdict and
     * the stage to the event in one step. Two separate observations let the first one seal the event, and the second
     * one then opened a second event for the same message.
     */
    public static Observation stageWithVerdict(String key, TraceStage stage, long tNs, String detail,
            Verdict action, long ruleId, String clampReason) {
        return new Observation(Kind.STAGE, key, null, null, "3gpp", Transport.CS, -1, -1, -1, -1, -1,
                Integer.MIN_VALUE, tNs, 0L, stage, detail, action, ruleId, clampReason, null, -1, false, -1, -1, -1,
                -1, false, null, null);
    }

    /**
     * A stage the hook can only attribute by originator (HK-13c). The blocklist check sees an
     * address, never a PDU, so it has no correlation key; the correlator joins it to the open
     * message of that originator.
     */
    public static Observation stageForOriginator(String originator, TraceStage stage, long tNs,
            String detail) {
        return new Observation(Kind.STAGE, null, null, null, "3gpp", Transport.CS, -1, -1, -1, -1, -1,
                Integer.MIN_VALUE, tNs, 0L, stage, detail, null, -1, null, null, -1, false, -1, -1, -1, -1, false,
                null, originator);
    }

    /** HK-06. */
    public static Observation dispatch(String key, String altKey, int phoneId, int source, int result,
            int token, long tNs) {
        return new Observation(Kind.DISPATCH, key, altKey, null, "3gpp", Transport.CS, phoneId, -1, -1, token, source,
                result, tNs, 0L, null, null, null, -1, null, null, -1, false, -1, -1, -1, -1, false, null, null);
    }

    /** HK-06 with the message's TPDU, for a dispatch that never had a radio RAW (source 2: injected, canary, release). */
    public static Observation dispatch(String key, String altKey, byte[] pdu, int phoneId, int source, int result,
            int token, long tNs) {
        return new Observation(Kind.DISPATCH, key, altKey, pdu, "3gpp", Transport.CS, phoneId, -1, -1, token, source,
                result, tNs, 0L, null, null, null, -1, null, null, -1, false, -1, -1, -1, -1, false, null, null);
    }

    /** A firewall verdict at an enforcement point. */
    public static Observation verdict(String key, Verdict action, long ruleId, String clampReason,
            long tNs) {
        return verdict(key, null, -1, action, ruleId, clampReason, tNs);
    }

    /** A firewall verdict with the phone id, for enforcement points that see no TPDU (HK-14/15/17). */
    public static Observation verdict(String key, String altKey, int phoneId, Verdict action,
            long ruleId, String clampReason, long tNs) {
        return new Observation(Kind.VERDICT, key, altKey, null, "3gpp", Transport.CS, phoneId, -1, -1, -1, -1,
                Integer.MIN_VALUE, tNs, 0L, null, null, action, ruleId, clampReason, null, -1, false, -1, -1, -1, -1,
                false, null, null);
    }

    /** An explicit fate (status report, canary interception, OEM redirect…). */
    public static Observation seal(String key, Fate fate, String detail, long tNs) {
        return new Observation(Kind.SEAL, key, null, null, "3gpp", Transport.CS, -1, -1, -1, -1, -1,
                Integer.MIN_VALUE, tNs, 0L, null, detail, null, -1, null, fate, -1, false, -1, -1, -1, -1, false,
                null, null);
    }

    /** HK-03b: the following dispatch of this key came from the SIM. */
    public static Observation simMark(String key, long tNs) {
        return new Observation(Kind.SIM_MARK, key, null, null, "3gpp", Transport.CS, -1, -1, -1, -1, -1,
                Integer.MIN_VALUE, tNs, 0L, null, null, null, -1, null, null, -1, false, -1, -1, -1, -1, false, null,
                null);
    }

    /** HK-21: this key will be injected by {@code uid}. */
    public static Observation inject(String key, int uid, long tNs) {
        return new Observation(Kind.INJECT, key, null, null, "3gpp", Transport.CS, -1, -1, -1, -1, -1,
                Integer.MIN_VALUE, tNs, 0L, null, null, null, -1, null, null, uid, false, -1, -1, -1, -1, false, null,
                null);
    }

    /** HK-03: a SIM record notification (informational; linked to the next SIM-sourced dispatch). */
    public static Observation simOnSim(int phoneId, int recordIndex, long tNs) {
        return new Observation(Kind.SIM_MARK, null, null, null, "3gpp", Transport.CS, phoneId, -1, -1, -1, -1,
                Integer.MIN_VALUE, tNs, 0L, null, "index=" + recordIndex + " phone=" + phoneId, null, -1, null, null,
                -1, false, -1, -1, -1, -1, false, null, null);
    }

    /**
     * HK-17: a cell broadcast PDU. {@code phoneId} is the phone that received it, −1 when unknown;
     * {@code fate} is {@link Fate#BLOCKED} when the firewall skipped it, null for the normal
     * {@link Fate#SYSTEM} path.
     */
    public static Observation cb(byte[] pdu, int serial, int messageId, int page, int pages,
            boolean emergency, int phoneId, int subId, int slot, Fate fate, long tNs, long wallMs) {
        return new Observation(Kind.CB, null, null, pdu, cbFormat(pdu), Transport.CB, phoneId, subId, slot, -1, -1,
                Integer.MIN_VALUE, tNs, wallMs, null, null, null, -1, null, fate, -1, false, serial, messageId, page,
                pages, emergency, null, null);
    }

    /** Format of a GSM cell broadcast PDU (TS 23.041). */
    public static final String CB_GSM = "cb";
    /** Format of a UMTS cell broadcast PDU (TS 23.041); the decoder module uses the same name. */
    public static final String CB_UMTS = "cb_umts";
    /** A GSM CB page is 88 octets; a UMTS PDU is longer. */
    private static final int GSM_CB_PAGE = 88;

    /**
     * True for a UMTS-format CB PDU (message type 1, then message id and serial), as CellBroadcastService
     * tells them apart: first octet 1 and longer than one GSM page.
     */
    public static boolean umtsCb(byte[] pdu) {
        return pdu != null && pdu.length > GSM_CB_PAGE && pdu[0] == 1;
    }

    static String cbFormat(byte[] pdu) {
        return umtsCb(pdu) ? CB_UMTS : CB_GSM;
    }

    /** HK-19/20/22/27: one outbound stage. */
    public static Observation mo(dev.nightjar.payload.outbound.MoEvent mo, long tNs) {
        return new Observation(Kind.MO, null, null, null, "3gpp", Transport.CS, -1, -1, -1, -1, -1, Integer.MIN_VALUE,
                tNs, 0L, null, null, null, -1, null, null, -1, false, -1, -1, -1, -1, false, mo, null);
    }
}
