package dev.nightjar.contract;

/**
 * Result codes of {@code InboundSmsHandler#dispatchMessage} (HK-06, event key {@code ackResult}): the
 * {@code Telephony.Sms.Intents.RESULT_SMS_*} values and {@code Activity.RESULT_OK}. The payload gives the fate from them
 * and the app names the reason of a rejection.
 */
public final class AckResult {
    private AckResult() {}

    /** {@code Activity.RESULT_OK}. */
    public static final int OK = -1;
    public static final int HANDLED = 1;
    public static final int GENERIC_ERROR = 2;
    /** Phone storage is full: fate {@link Fate#NACK_STORAGE}. */
    public static final int OUT_OF_MEMORY = 3;
    public static final int UNSUPPORTED = 4;
    public static final int DUPLICATED = 5;
    public static final int DISPATCH_FAILURE = 6;
    public static final int NULL_PDU = 7;
    public static final int NULL_MESSAGE = 8;
    public static final int RECEIVED_WHILE_ENCRYPTED = 9;
    public static final int DATABASE_ERROR = 10;
    public static final int INVALID_URI = 11;

    /** True when Android accepted the message for delivery. */
    public static boolean accepted(int code) {
        return code == OK || code == HANDLED;
    }

    /** True when Android refused the message for a reason other than full storage: fate {@link Fate#REJECTED}. */
    public static boolean rejected(int code) {
        return code == GENERIC_ERROR || (code >= UNSUPPORTED && code <= INVALID_URI);
    }
}
