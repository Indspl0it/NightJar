package dev.nightjar.contract;

/**
 * Keys and values inside the event's {@code fateDetail} object that the payload writes and the app reads. Both
 * sides use these names.
 *
 * <p>Not in {@link Ipc}: the literal check protects every Ipc/Control value of 5 or more characters, and words
 * such as "reason", "package" and "class" are common in logs and other code.
 */
public final class FateDetail {
    private FateDetail() {}

    /** Text: why the payload gave this fate. For an outbound block it is {@link #OUTBOUND_BLOCK}. */
    public static final String REASON = "reason";

    /** Text: package name of the app that sent the message (outbound). */
    public static final String PACKAGE = "package";

    /** Text: {@link ClassId} wire name of the blocked message (outbound). */
    public static final String CLASS = "class";

    /** Boolean: the sender app's sent intent was answered with a failure after a block. */
    public static final String SENT_INTENT_FAILED = "sentIntentFailed";

    /** Value of {@link #REASON} for a per-app outbound block. */
    public static final String OUTBOUND_BLOCK = "W-07";

    /** Text: why an OEM component dropped or redirected the message (FT-OEM-DROPPED, FT-OEM-REDIRECTED). */
    public static final String OEM_REASON = "oemReason";

    /** Number: send error code of an outbound message with fate FT-SEND-FAILED; absent when unknown. */
    public static final String ERROR_CODE = "errorCode";

    /** Boolean: an outbound request that never reached the SMS dispatcher (fate FT-NOT-SENT). */
    public static final String NO_DISPATCH = "noDispatch";
}
