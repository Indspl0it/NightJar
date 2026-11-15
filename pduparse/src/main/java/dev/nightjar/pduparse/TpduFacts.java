package dev.nightjar.pduparse;

import dev.nightjar.contract.AnomalyCode;
import java.util.Arrays;
import java.util.List;

/**
 * Facts of one inbound SMS TPDU (3GPP TS 23.040), from {@link TpduParser}.
 *
 * <p>{@link #valid()} keeps the meaning of the payload's former {@code PduFacts}: the header fields could be read. The
 * strict checks are in {@link #anomalies}: a message can be valid and still carry anomalies, and a hard anomaly
 * ({@link Anomalies#malformed(int)}) is what the classifier turns into CL-MALFORMED-SMS. An invalid parse without
 * one (AN-MTI, or a parser error) is CL-UNREADABLE-SMS.
 *
 * <p>When the message is a single-part WAP push (port 2948, any DCS), {@link #wsp()} and {@link #wbxml()} hold the
 * push facts and their anomalies are part of {@link #anomalyMask()}. When the UDH has a SIM toolkit packet
 * identifier and the message goes to the SIM, {@link #ota()} holds the secured packet header ({@link SimOtaSubtypes}
 * names it).
 *
 * <p>Thread: any. Not changed after construction. {@link #userData()} and {@link #tpdu()} return the internal arrays
 * (no copy on the hook path): callers read them and never write them.
 */
public final class TpduFacts {
    static final int PID_TYPE_ZERO = 0x40;
    static final int PID_REPLACE_FIRST = 0x41;
    static final int PID_REPLACE_LAST = 0x47;
    static final int PID_USIM_DATA_DOWNLOAD = 0x7F;
    /** TP-PID 0x7C (ANSI-136 R-DATA): the framework also gives it to the UICC when the message is class 2. */
    static final int PID_ANSI136_R_DATA = 0x7C;

    private final boolean valid;
    private final String parseError;
    private final int mti;
    private final int firstOctet;
    private final boolean statusReport;
    private final boolean udhi;
    private final String originator;
    private final int originatorToa;
    private final int originatorLength;
    private final String smsc;
    private final int messageReference;
    private final int status;
    private final int protocolId;
    private final int dataCodingScheme;
    private final int dcsClass;
    private final Encoding encoding;
    private final boolean compressed;
    private final boolean messageWaitingGroup;
    private final boolean mwiActive;
    private final int mwiType;
    private final boolean mwiSet;
    private final boolean mwiClear;
    private final boolean mwiDontStore;
    private final long timestampMs;
    private final int udl;
    private final byte[] userData;
    private final UdhFacts udh;
    private final OtaFacts ota;
    private final WspFacts wsp;
    private final WbxmlFacts wbxml;
    private final int anomalies;
    private final byte[] tpdu;

    TpduFacts(Builder b) {
        valid = b.valid;
        parseError = b.parseError;
        mti = b.mti;
        firstOctet = b.firstOctet;
        statusReport = b.statusReport;
        udhi = b.udhi;
        originator = b.originator;
        originatorToa = b.originatorToa;
        originatorLength = b.originatorLength;
        smsc = b.smsc;
        messageReference = b.messageReference;
        status = b.status;
        protocolId = b.protocolId;
        dataCodingScheme = b.dcs;
        dcsClass = b.dcsClass;
        encoding = b.encoding;
        compressed = b.compressed;
        messageWaitingGroup = b.messageWaitingGroup;
        mwiActive = b.mwiActive;
        mwiType = b.mwiType;
        mwiSet = b.mwiSet;
        mwiClear = b.mwiClear;
        mwiDontStore = b.mwiDontStore;
        timestampMs = b.timestampMs;
        udl = b.udl;
        userData = b.userData;
        udh = b.udh;
        ota = b.ota;
        wsp = b.wsp;
        wbxml = b.wbxml;
        anomalies = b.anomalies;
        tpdu = b.tpdu;
    }

    /** Builder state, written only by {@link TpduParser}. */
    static final class Builder {
        boolean valid;
        String parseError;
        int mti = -1;
        int firstOctet = -1;
        boolean statusReport;
        boolean udhi;
        String originator = "";
        int originatorToa = -1;
        int originatorLength = -1;
        String smsc;
        int messageReference = -1;
        int status = -1;
        int protocolId;
        int dcs;
        int dcsClass = -1;
        Encoding encoding = Encoding.UNKNOWN;
        boolean compressed;
        boolean messageWaitingGroup;
        boolean mwiActive;
        int mwiType = -1;
        boolean mwiSet;
        boolean mwiClear;
        boolean mwiDontStore;
        long timestampMs;
        int udl = -1;
        byte[] userData = new byte[0];
        UdhFacts udh;
        OtaFacts ota;
        WspFacts wsp;
        WbxmlFacts wbxml;
        int anomalies;
        byte[] tpdu = new byte[0];

        void flag(AnomalyCode code) {
            anomalies = Anomalies.with(anomalies, code);
        }
    }

    /** True when the header fields (address, PID, DCS) could be read. */
    public boolean valid() {
        return valid;
    }

    /** Short reason when {@link #valid()} is false; never message content. */
    public String parseError() {
        return parseError;
    }

    /** TP-MTI (0 SMS-DELIVER, 2 SMS-STATUS-REPORT, 1 and 3 not inbound messages), −1 when there is no first octet. */
    public int mti() {
        return mti;
    }

    public int firstOctet() {
        return firstOctet;
    }

    public boolean statusReport() {
        return statusReport;
    }

    public boolean udhi() {
        return udhi;
    }

    /** Originator as received: {@code +…} for international numbers, alphanumeric as decoded. */
    public String originator() {
        return originator;
    }

    /** Type of address octet of TP-OA, −1 when unread. */
    public int originatorToa() {
        return originatorToa;
    }

    /** Address-length octet of TP-OA (semi-octets), −1 when unread. */
    public int originatorLength() {
        return originatorLength;
    }

    /**
     * SMSC address from the radio PDU prefix, or null when there is none: a bare TPDU (IMS), an empty SMSC (length 0
     * or 1), or a length octet above 11.
     */
    public String smsc() {
        return smsc;
    }

    /** TP-MR of a status report, −1 otherwise. */
    public int messageReference() {
        return messageReference;
    }

    /** TP-ST of a status report, −1 otherwise. */
    public int status() {
        return status;
    }

    public int protocolId() {
        return protocolId;
    }

    public int dataCodingScheme() {
        return dataCodingScheme;
    }

    /** Message class 0–3, or −1 when the DCS carries none. */
    public int dcsClass() {
        return dcsClass;
    }

    public Encoding encoding() {
        return encoding;
    }

    /** True when the general data coding group says the text is compressed (DCS bit 5). */
    public boolean compressed() {
        return compressed;
    }

    /** True for the DCS message waiting groups 0xC0 to 0xEF. */
    public boolean messageWaitingGroup() {
        return messageWaitingGroup;
    }

    /** DCS indication sense (bit 3) of a message waiting group. */
    public boolean mwiActive() {
        return mwiActive;
    }

    /** DCS indication type of a message waiting group: 0 voicemail, 1 fax, 2 email, 3 other; −1 otherwise. */
    public int mwiType() {
        return mwiType;
    }

    public boolean mwiSet() {
        return mwiSet;
    }

    public boolean mwiClear() {
        return mwiClear;
    }

    /** True for the "discard message" MWI group (DCS 1100xxxx). */
    public boolean mwiDontStore() {
        return mwiDontStore;
    }

    public boolean replace() {
        return protocolId >= PID_REPLACE_FIRST && protocolId <= PID_REPLACE_LAST;
    }

    /**
     * True when the framework gives the message to the UICC as SMS-PP data download: class 2 with PID 0x7F or 0x7C
     * ({@code SmsMessage.isUsimDataDownload}).
     */
    public boolean usimDataDownload() {
        return dcsClass == 2 && (protocolId == PID_USIM_DATA_DOWNLOAD || protocolId == PID_ANSI136_R_DATA);
    }

    public boolean typeZero() {
        return protocolId == PID_TYPE_ZERO;
    }

    /** SCTS in wall-clock ms, 0 when absent or unreadable. */
    public long timestampMs() {
        return timestampMs;
    }

    /** TP-UDL as received, −1 when absent. */
    public int udl() {
        return udl;
    }

    /**
     * User data with the UDH, as many octets as TP-UDL names and the PDU holds. The array is shared: do not change
     * it.
     */
    public byte[] userData() {
        return userData;
    }

    /** Offset of the data after the UDH in {@link #userData()}; 0 without a UDH. */
    public int bodyOffset() {
        if (!udhi || udh == null) return 0;
        return Math.min(userData.length, udh.totalOctets());
    }

    /** A copy of the user data after the UDH (packed septets for GSM 7-bit). */
    public byte[] body() {
        return Arrays.copyOfRange(userData, bodyOffset(), userData.length);
    }

    /** UDH facts, or null when there is no UDH. */
    public UdhFacts udh() {
        return udh;
    }

    /** Destination port from a 16-bit or 8-bit UDH application port addressing IE, −1 when none. */
    public int destPort() {
        return udh == null ? -1 : udh.destPort();
    }

    /** Concatenation reference, −1 when the message is not part of a concatenated set. */
    public int concatRef() {
        return udh == null ? -1 : udh.concatRef();
    }

    public int concatTotal() {
        return udh == null ? 1 : udh.concatTotal();
    }

    public int concatSeq() {
        return udh == null ? 0 : udh.concatSeq();
    }

    /**
     * SIM OTA secured packet header, or null when the UDH has no IE 0x70 or 0x71, the user data is 7-bit, the message
     * does not go to the SIM (PID is not 0x7F and the DCS is not class 2, step 1), or the message is a later
     * segment of a concatenated packet (only the first segment has the header, TS 31.115).
     */
    public OtaFacts ota() {
        return ota;
    }

    /** WAP push facts of a single-part port 2948 message (any DCS, as the framework reads it), else null. */
    public WspFacts wsp() {
        return wsp;
    }

    /** WBXML facts of the push body when the content type is a WBXML type, else null. */
    public WbxmlFacts wbxml() {
        return wbxml;
    }

    /** Anomaly mask of the TPDU, the UDH, the OTA header, the push and its WBXML body (see {@link Anomalies}). */
    public int anomalyMask() {
        return anomalies;
    }

    public List<AnomalyCode> anomalies() {
        return Anomalies.list(anomalies);
    }

    /** The bare TPDU that was parsed. The array is shared: do not change it. */
    public byte[] tpdu() {
        return tpdu;
    }
}
