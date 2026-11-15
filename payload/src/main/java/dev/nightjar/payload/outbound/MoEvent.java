package dev.nightjar.payload.outbound;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Transport;

/**
 * One outbound hook observation, carried inside a {@code capture.Observation} (T04).
 *
 * <p>A hook thread fills exactly the fields its stage knows and enqueues the object; the worker
 * reads it afterwards and never writes back. That is why the fields are mutable: one class covers
 * the four stages without allocating a per-stage hierarchy, and the hook thread allocates once.
 *
 * <p>Thread: written on the hooked thread before {@code offer}, read on the worker.
 */
public final class MoEvent {
    /** Which stage produced this observation. */
    public enum Type {
        /** HK-19: an {@code SmsController#send*} call, before it runs. */
        REQUEST,
        /** HK-20: a dispatcher submit, carrying the tracker and its PDU. */
        DISPATCH,
        /** HK-22: the SIM toolkit asked to send an SMS. */
        STK,
        /** HK-27: the tracker reported success or failure. */
        RESULT
    }

    public final Type type;

    /** {@link Type#REQUEST}: the caller's arguments. */
    public OutboundRequest request;
    /** {@link Type#REQUEST}: the outbound gate blocked the send. */
    public boolean blocked;
    /** {@link Type#REQUEST}: the rule that blocked it, −1 when none. */
    public long ruleId = -1L;
    /** {@link Type#REQUEST}: reason code for {@code fateDetail.reason} ({@code }). */
    public String reason;
    /** {@link Type#REQUEST}: the blocked package for {@code fateDetail.package}, null when not blocked. */
    public String blockedPackage;
    /** {@link Type#REQUEST}: the class of the blocked send for {@code fateDetail.class}. */
    public ClassId blockedClass;
    /** {@link Type#REQUEST}: {@code fateDetail.sentIntentFailed}; null when the gate did not try. */
    public Boolean sentIntentFailed;

    /** {@link Type#DISPATCH}/{@link Type#RESULT}: identity of the framework tracker. */
    public int trackerId = Integer.MIN_VALUE;
    /** {@link Type#DISPATCH}: {@code SmsTracker#getAppPackageName()}, null when unknown. */
    public String pkg;
    /** {@link Type#DISPATCH}: {@code SmsTracker#mDestAddress}. */
    public String destAddr;
    /** {@link Type#DISPATCH}: {@code mData.get("smsc")} (encoded SMSC), or null. */
    public String smsc;
    /** {@link Type#DISPATCH}: {@code mData.get("pdu")}, the TPDU the dispatcher built. */
    public byte[] pdu;
    /** {@link Type#DISPATCH}: {@code mData.get("destPort")} for a data SMS, else −1. */
    public int destPort = -1;
    /** {@link Type#DISPATCH}/{@link Type#RESULT}: {@code SmsTracker#mMessageRef}. */
    public int messageRef = -1;
    /** {@link Type#DISPATCH}: reflected {@code mPersistMessage}, null when unreadable. */
    public Boolean persist;
    /** {@link Type#DISPATCH}: reflected {@code mIsText}, null when unreadable. */
    public Boolean text;
    /** {@link Type#DISPATCH}: reflected {@code mIsForVvm}, null when unreadable. */
    public Boolean vvm;
    /** {@link Type#DISPATCH}: reflected {@code mSubId}, −1 when unreadable. */
    public int subId = -1;
    /** {@link Type#DISPATCH}: {@code MO_CS} or {@code MO_IMS}. */
    public Transport transport = Transport.MO_CS;

    /** {@link Type#RESULT}: true for {@code onSent}, false for {@code onFailed}. */
    public boolean sent;
    /** {@link Type#RESULT}: {@code SmsTracker#onFailed}'s error code, −1 on success. */
    public int errorCode = -1;
    /**
     * {@link Type#RESULT}: a failure that may not be final (HK-27 {@code handleSendComplete}). Android can still retry
     * the tracker, so the ledger waits for HK-27b/27c or the timeout before it seals the send.
     */
    public boolean provisional;

    private MoEvent(Type type) {
        this.type = type;
    }

    /** HK-19. */
    public static MoEvent request(OutboundRequest request) {
        MoEvent e = new MoEvent(Type.REQUEST);
        e.request = request;
        return e;
    }

    /** HK-22: a mark, not an event of its own. */
    public static MoEvent stk() {
        return new MoEvent(Type.STK);
    }

    /** HK-20: one tracker of the submitted array. */
    public static MoEvent dispatch(int trackerId, Transport transport) {
        MoEvent e = new MoEvent(Type.DISPATCH);
        e.trackerId = trackerId;
        e.transport = transport == null ? Transport.MO_CS : transport;
        return e;
    }

    /** HK-27. */
    public static MoEvent result(int trackerId, boolean sent) {
        MoEvent e = new MoEvent(Type.RESULT);
        e.trackerId = trackerId;
        e.sent = sent;
        return e;
    }
}
