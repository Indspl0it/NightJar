package dev.nightjar.pduparse;

import dev.nightjar.contract.AnomalyCode;
import java.util.List;
import java.util.Locale;

/**
 * Facts of one WAP Push PDU, from {@link WspParser}.
 *
 * <p>{@link #valid()} means that the content type could be read. A field after the content type that cannot be read
 * (for example an X-Wap-Application-Id text without its end octet) keeps {@code valid()} true and is named in
 * {@link #error}. The strict checks are in {@link #anomalies}; a push can be valid and still carry
 * anomalies.
 *
 * <p>Thread: any (not changed after the parse).
 */
public final class WspFacts {
    private static final String VCARD_DIRECTORY = "text/directory";
    private static final String VCARD_PROFILE = "profile=vcard";
    private static final String VCARD = "text/x-vcard";

    boolean valid;
    String error;
    int transactionId = -1;
    int pduType = -1;
    int headerLength = -1;
    int headersOffset = -1;
    String contentTypeText;
    int contentTypeCode = -1;
    String applicationId;
    int applicationIdCode = -1;
    int secParam = -1;
    boolean macParam;
    int bodyOffset = -1;
    int bodyLength;
    int anomalies;

    WspFacts() {}

    public boolean valid() {
        return valid;
    }

    /**
     * Short reason when a part of the push could not be read: why {@link #valid()} is false, or which field after the
     * content type failed (then {@code valid()} is true). Never message content.
     */
    public String error() {
        return error;
    }

    public int transactionId() {
        return transactionId;
    }

    /** WSP PDU type: {@link WspParser#PDU_TYPE_PUSH} or {@link WspParser#PDU_TYPE_CONFIRMED_PUSH} for a push. */
    public int pduType() {
        return pduType;
    }

    /** HeadersLen: the content type and the headers together. */
    public int headerLength() {
        return headerLength;
    }

    /** Offset of the content type (the first header octet). */
    public int headersOffset() {
        return headersOffset;
    }

    /** Content type when it was sent as text (not normalized), else {@code null}. */
    public String contentTypeText() {
        return contentTypeText;
    }

    /** Well-known content type code (short or long integer), −1 when the content type was text. */
    public int contentTypeCode() {
        return contentTypeCode;
    }

    /**
     * The content type in lower case without parameters: the text form, else the registry name of the code
     * ({@link WspTables}), else {@code null}. {@code text/directory;profile=vCard} (code 0x0309) gives
     * {@code text/x-vcard}, because the profile parameter is what makes it a contact card.
     */
    public String contentType() {
        String raw = contentTypeText != null ? contentTypeText
                : (contentTypeCode < 0 ? null : WspTables.contentTypeName(contentTypeCode));
        String type = WspParser.normalize(raw);
        if (VCARD_DIRECTORY.equals(type) && raw.toLowerCase(Locale.ROOT).replace(" ", "").contains(VCARD_PROFILE)) {
            return VCARD;
        }
        return type;
    }

    /** X-Wap-Application-Id as text, or {@code "0x…"} for an integer value, or {@code null}. */
    public String applicationId() {
        return applicationId;
    }

    /** X-Wap-Application-Id integer value, −1 when absent or text. */
    public int applicationIdCode() {
        return applicationIdCode;
    }

    /**
     * OMA CP {@code SEC} content-type parameter (0 NETWPIN, 1 USERPIN, 2 USERNETWPIN, 3 USERPINMAC), −1 when absent.
     */
    public int secParam() {
        return secParam;
    }

    /** True when the OMA CP {@code MAC} content-type parameter is present. */
    public boolean macParam() {
        return macParam;
    }

    /** Offset of the push body (after the headers), −1 when the headers do not end inside the PDU. */
    public int bodyOffset() {
        return bodyOffset;
    }

    public int bodyLength() {
        return bodyLength;
    }

    /** Anomaly mask (see {@link Anomalies}). */
    public int anomalyMask() {
        return anomalies;
    }

    public List<AnomalyCode> anomalies() {
        return Anomalies.list(anomalies);
    }
}
