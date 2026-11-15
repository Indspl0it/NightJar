package dev.nightjar.pduparse;

import dev.nightjar.contract.AnomalyCode;
import java.util.List;

/**
 * Facts of one WBXML document header and token stream, from {@link WbxmlScanner}. The scanner checks the structure
 * only; it builds no tree and decodes no text (the app's decoder does that). The payload reads only the anomaly mask;
 * the other facts are module API for the decoder and the tests (R8 removes them from the payload dex).
 *
 * <p>Thread: any (not changed after the scan).
 */
public final class WbxmlFacts {
    int version = -1;
    long publicId = -1;
    long charset = -1;
    long stringTableLength = -1;
    int rootTag = -1;
    int elements;
    int anomalies;

    WbxmlFacts() {}

    /** Version octet: 0x00 to 0x03 for WBXML 1.0 to 1.3, −1 when absent. */
    public int version() {
        return version;
    }

    /**
     * Public identifier (WAP-192): 0x05 SI, 0x06 SL, 0x07 CO, 0x0B provisioning; 0 when the id is a string in the
     * string table; −1 when unread.
     */
    public long publicId() {
        return publicId;
    }

    /** IANA MIBenum of the character set, −1 when unread or for WBXML 1.0 (which has no charset field). */
    public long charset() {
        return charset;
    }

    public long stringTableLength() {
        return stringTableLength;
    }

    /** Tag token (low 6 bits) of the first element, −1 when there is none. */
    public int rootTag() {
        return rootTag;
    }

    /** Number of element start tokens read. */
    public int elements() {
        return elements;
    }

    /** Anomaly mask (see {@link Anomalies}). */
    public int anomalyMask() {
        return anomalies;
    }

    public List<AnomalyCode> anomalies() {
        return Anomalies.list(anomalies);
    }
}
