package dev.nightjar.payload.capture;

import dev.nightjar.contract.AnomalyCode;
import dev.nightjar.contract.Hashes;
import dev.nightjar.pduparse.TpduFacts;
import dev.nightjar.pduparse.TpduParser;
import java.util.List;

/**
 * Facts of one inbound 3GPP SMS TPDU. This is a thin adapter over the shared parser
 * {@link TpduParser} in {@code :pduparse}, which the app's decoder uses too. The API is unchanged: it never throws,
 * malformed input yields {@link #valid()} {@code false} with {@link #parseError()} set, and whatever could be read.
 *
 * <p>{@link #anomalies} adds the parser's anomaly codes; {@link #facts} gives the full shared facts (UDH, OTA header,
 * WAP push) for the classifiers.
 *
 * <p>Thread: any (immutable after construction).
 */
public final class PduFacts {
    /** User-data alphabet. */
    public enum Encoding {
        GSM7,
        EIGHT_BIT,
        UCS2,
        UNKNOWN
    }

    private final TpduFacts f;

    private PduFacts(TpduFacts f) {
        this.f = f;
    }

    /** The shared parser facts this adapter reads. */
    public TpduFacts facts() {
        return f;
    }

    public boolean valid() {
        return f.valid();
    }

    public String parseError() {
        return f.parseError();
    }

    public boolean statusReport() {
        return f.statusReport();
    }

    public boolean udhi() {
        return f.udhi();
    }

    /** Originator as received: {@code +…} for international numbers, alphanumeric as decoded. */
    public String originator() {
        return f.originator();
    }

    /** SMSC address from the radio PDU prefix, or null for a bare TPDU (IMS). */
    public String smsc() {
        return f.smsc();
    }

    public int protocolId() {
        return f.protocolId();
    }

    public int dataCodingScheme() {
        return f.dataCodingScheme();
    }

    /** Message class 0–3, or −1 when the DCS carries none. */
    public int dcsClass() {
        return f.dcsClass();
    }

    public Encoding encoding() {
        return switch (f.encoding()) {
            case GSM7 -> Encoding.GSM7;
            case EIGHT_BIT -> Encoding.EIGHT_BIT;
            case UCS2 -> Encoding.UCS2;
            case UNKNOWN -> Encoding.UNKNOWN;
        };
    }

    public boolean mwiSet() {
        return f.mwiSet();
    }

    public boolean mwiClear() {
        return f.mwiClear();
    }

    /** True for the "discard message" MWI group (DCS 1100xxxx). */
    public boolean mwiDontStore() {
        return f.mwiDontStore();
    }

    public boolean replace() {
        return f.replace();
    }

    public boolean usimDataDownload() {
        return f.usimDataDownload();
    }

    public boolean typeZero() {
        return f.typeZero();
    }

    /** Destination port from a 16-bit or 8-bit UDH application port addressing IE, −1 when none. */
    public int destPort() {
        return f.destPort();
    }

    /** Concatenation reference, −1 when the message is not part of a concatenated set. */
    public int concatRef() {
        return f.concatRef();
    }

    public int concatTotal() {
        return f.concatTotal();
    }

    public int concatSeq() {
        return f.concatSeq();
    }

    /** SCTS in wall-clock ms, 0 when absent or unreadable. */
    public long timestampMs() {
        return f.timestampMs();
    }

    public byte[] userData() {
        return f.userData();
    }

    public byte[] tpdu() {
        return f.tpdu();
    }

    /** Anomaly codes of the TPDU and what it carries, in contract order. */
    public List<AnomalyCode> anomalies() {
        return f.anomalies();
    }

    /** Correlation key of the bare TPDU. */
    public String tpduKey() {
        return Hashes.tpduKey(f.tpdu());
    }

    // ------------------------------------------------------------------ parsing

    /** Parses a bare TPDU (no SMSC prefix), as delivered by IMS. */
    public static PduFacts parse(byte[] tpdu) {
        return new PduFacts(TpduParser.parse(tpdu));
    }

    /**
     * Parses a radio PDU ({@code <smsc-len><smsc><tpdu>}). The SMSC prefix is removed with
     * {@link Hashes#stripSmsc(byte[])}; when the prefix is absent the whole array is parsed.
     */
    public static PduFacts parseRadio(byte[] pdu) {
        return new PduFacts(TpduParser.parseRadio(pdu));
    }

    /** Octets occupied by an address field (TS 23.040). */
    static int addressOctets(int length, int toa) {
        return TpduParser.addressOctets(length, toa);
    }

    /** Decodes a semi-octet or alphanumeric (TOA 0xD0, GSM-7 packed) address. */
    static String decodeAddress(byte[] t, int offset, int length, int toa) {
        return TpduParser.decodeAddress(t, offset, length, toa);
    }

    /** SCTS in wall-clock ms (TS 23.040), 0 when unreadable. */
    static long decodeScts(byte[] t, int offset) {
        return TpduParser.decodeScts(t, offset);
    }
}
