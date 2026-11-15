package dev.nightjar.contract;

/**
 * Where a message ended up.
 *
 * <p>Every event gets a fate that says what NightJar saw. {@link #UNKNOWN} is only the guard value of a parse
 * that finds no fate: the payload never emits it, and the app replaces it with a true fate at ingest.
 */
public enum Fate implements WireEnum {
    SHOWN("FT-SHOWN"),
    APP("FT-APP"),
    NO_RECEIVER("FT-NO-RECEIVER"),
    VVM("FT-VVM"),
    CARRIER("FT-CARRIER"),
    MISSED_CALL("FT-MISSED-CALL"),
    SYSTEM("FT-SYSTEM"),
    SIM("FT-SIM"),
    OEM_DROPPED("FT-OEM-DROPPED"),
    OEM_REDIRECTED("FT-OEM-REDIRECTED"),
    BLOCKLIST("FT-BLOCKLIST"),
    BLOCKED("FT-BLOCKED"),
    QUARANTINED("FT-QUARANTINED"),
    RELEASED("FT-RELEASED"),
    NACK_STORAGE("FT-NACK-STORAGE"),
    INCOMPLETE("FT-INCOMPLETE"),
    PENDING_UNLOCK("FT-PENDING-UNLOCK"),
    SENT("FT-SENT"),
    SEND_FAILED("FT-SEND-FAILED"),
    /** Android refused the message at dispatch ({@link AckResult#rejected(int)}); {@code ackResult} gives the reason. */
    REJECTED("FT-REJECTED"),
    /** Android accepted the message ({@link AckResult#accepted(int)}), and no consumer reported it in 30 s. */
    ACCEPTED("FT-ACCEPTED"),
    /** The message arrived, and Android did not dispatch it in 30 s (no HK-06 result). */
    NOT_DISPATCHED("FT-NOT-DISPATCHED"),
    /** The phone process stopped before the message had an outcome (crash journal). */
    PHONE_STOPPED("FT-PHONE-STOPPED"),
    /** Android kept the message for the first unlock, and the payload stopped waiting (expiry, cap or restart). */
    UNLOCK_EXPIRED("FT-UNLOCK-EXPIRED"),
    /** An app asked to send, and the request did not reach the SMS dispatcher (HK-19 without HK-20). */
    NOT_SENT("FT-NOT-SENT"),
    /** The SMS dispatcher sent the message, and no send result came back (HK-20 without HK-27/27b/27c). */
    SEND_UNCONFIRMED("FT-SEND-UNCONFIRMED"),
    UNKNOWN("FT-UNKNOWN");

    private final String wire;

    Fate(String wire) {
        this.wire = wire;
    }

    @Override
    public String wire() {
        return wire;
    }
}
