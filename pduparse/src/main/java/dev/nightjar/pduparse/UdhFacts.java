package dev.nightjar.pduparse;

import dev.nightjar.contract.AnomalyCode;
import java.util.List;

/**
 * Facts of one User Data Header (3GPP TS 23.040), from {@link UdhParser}.
 *
 * <p>Every fact follows the framework's reading (AOSP {@code SmsHeader.fromByteArray}, see {@link UdhParser}): a later
 * port element replaces an earlier one, a concatenation element with total 0, sequence 0 or sequence above total is
 * ignored, and the fixed-length elements are read with their fixed length whatever their IEDL. The anomaly list
 * reports where this differs from the IEDL reading. In a header whose last element is cut, a fact can hold −1 for an
 * octet that is not there, as in the framework.
 *
 * <p>Thread: any (not changed after the parse).
 */
public final class UdhFacts {
    int headerLength = -1;
    boolean complete;
    int destPort = -1;
    int origPort = -1;
    boolean port8Bit;
    /** True when the framework walk read a port element (then {@link #destPort} is what the framework uses). */
    boolean frameworkPort;
    int portIeCount;
    int concatRef = -1;
    int concatTotal = 1;
    int concatSeq = 0;
    boolean concat16Bit;
    boolean specialSms;
    int specialSmsType = -1;
    int specialSmsCount = -1;
    boolean enhancedVoicemail;
    int singleShift;
    int lockingShift;
    boolean toolkitCommand;
    boolean toolkitResponse;
    int anomalies;

    UdhFacts() {}

    /** UDHL: the declared header length without the length octet, or −1 when there is no header. */
    public int headerLength() {
        return headerLength;
    }

    /** Octets the header occupies with its length octet ({@code 1 + UDHL}), or 0 when there is no header. */
    public int totalOctets() {
        return headerLength < 0 ? 0 : 1 + headerLength;
    }

    /** True when all {@code UDHL} octets are present. */
    public boolean complete() {
        return complete;
    }

    /** Destination port from IE 0x04 or 0x05 (the last one wins), −1 when none (negative when cut, see above). */
    public int destPort() {
        return destPort;
    }

    /** Originator port that goes with {@link #destPort()}, −1 when none. */
    public int origPort() {
        return origPort;
    }

    /** True when {@link #destPort()} came from the 8-bit element (IE 0x04). */
    public boolean port8Bit() {
        return port8Bit;
    }

    /** Number of port elements (IE 0x04 and 0x05) in the IEDL reading, whatever their length. */
    public int portIeCount() {
        return portIeCount;
    }

    /** Concatenation reference, −1 when there is no usable concatenation element. */
    public int concatRef() {
        return concatRef;
    }

    public int concatTotal() {
        return concatTotal;
    }

    public int concatSeq() {
        return concatSeq;
    }

    /** True when the concatenation element is the 16-bit form (IE 0x08). */
    public boolean concat16Bit() {
        return concat16Bit;
    }

    /** True when a special SMS message indication (IE 0x01) is present. */
    public boolean specialSms() {
        return specialSms;
    }

    /** First octet of IE 0x01 (store bit and indication type), −1 when absent. */
    public int specialSmsType() {
        return specialSmsType;
    }

    /** Message count of IE 0x01, −1 when absent. */
    public int specialSmsCount() {
        return specialSmsCount;
    }

    /** True when an enhanced voicemail information element (IE 0x23) is present. */
    public boolean enhancedVoicemail() {
        return enhancedVoicemail;
    }

    /** National language single shift table (IE 0x24), 0 when absent. */
    public int singleShift() {
        return singleShift;
    }

    /** National language locking shift table (IE 0x25), 0 when absent. */
    public int lockingShift() {
        return lockingShift;
    }

    /** True when a command packet identifier (IE 0x70, TS 31.115) is present. */
    public boolean toolkitCommand() {
        return toolkitCommand;
    }

    /** True when a response packet identifier (IE 0x71, TS 31.115) is present. */
    public boolean toolkitResponse() {
        return toolkitResponse;
    }

    /** Anomaly mask (see {@link Anomalies}). */
    public int anomalyMask() {
        return anomalies;
    }

    public List<AnomalyCode> anomalies() {
        return Anomalies.list(anomalies);
    }
}
