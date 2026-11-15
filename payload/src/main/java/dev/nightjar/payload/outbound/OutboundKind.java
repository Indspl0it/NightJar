package dev.nightjar.payload.outbound;

/**
 * Which {@code SmsController} entry point asked for the send. The kind is
 * part of the classification table in {@link OutboundClassification}; the method name is kept
 * separately for the trace.
 *
 * <p>Thread: any (enum).
 */
public enum OutboundKind {
    /** {@code sendDataForSubscriber}: port-addressed data SMS. */
    DATA,
    /** {@code sendRawPduForSubscriber}: a TPDU the caller built itself. */
    RAW,
    /** {@code sendTextForSubscriber} / {@code …WithOptions}. */
    TEXT,
    /** {@code sendMultipartTextForSubscriber} / {@code …WithOptions}. */
    MULTIPART,
    /** {@code sendStoredText} / {@code sendStoredMultipartText}: a message re-sent from the Sent box. */
    STORED,
    /** {@code sendVisualVoicemailSmsForSubscriber}: the voicemail client's setup message. */
    VVM
}
