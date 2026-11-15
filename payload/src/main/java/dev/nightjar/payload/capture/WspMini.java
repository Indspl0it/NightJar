package dev.nightjar.payload.capture;

import dev.nightjar.pduparse.WspFacts;
import dev.nightjar.pduparse.WspParser;
import dev.nightjar.pduparse.WspTables;

/**
 * Facts of a WAP Push PDU for the payload: transaction id, PDU type, content type and
 * {@code X-Wap-Application-Id}. The parsing lives in the shared {@link WspParser} ({@code:pduparse});
 * this class keeps the payload's API. It never throws.
 *
 * <p>Use {@link WspParser#parse(byte[])} directly for the anomaly codes and the OMA CP parameters.
 *
 * <p>Thread: any.
 */
public final class WspMini {
    /** WSP Push PDU type. */
    public static final int PDU_TYPE_PUSH = WspParser.PDU_TYPE_PUSH;

    private WspMini() {}

    /** Parsed facts; {@link #valid()} false means "UNKNOWN" to the classifier, never an exception. */
    public record Result(
            boolean valid,
            String error,
            int transactionId,
            int pduType,
            int headerLength,
            String contentType,
            int contentTypeCode,
            String applicationId) {

        public static Result invalid(String error) {
            return new Result(false, error, -1, -1, -1, null, -1, null);
        }
    }

    /** Parses a WAP Push PDU. */
    public static Result parse(byte[] pdu) {
        WspFacts f = WspParser.parse(pdu);
        if (!f.valid()) return Result.invalid(f.error());
        return new Result(true, null, f.transactionId(), f.pduType(), f.headerLength(), f.contentType(),
                f.contentTypeCode(), f.applicationId());
    }

    /**
     * Normalized name of a well-known WSP media type (WAP-230 Appendix A, OMNA registry, the whole
     * {@link WspTables} table). An unassigned code stays {@code null} and the classifier names it by its code.
     */
    static String wellKnownName(int code) {
        return WspParser.normalize(WspTables.contentTypeName(code));
    }
}
