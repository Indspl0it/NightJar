package dev.nightjar.pduparse;

import dev.nightjar.contract.AnomalyCode;
import java.util.List;

/**
 * Header facts of a SIM OTA secured packet (3GPP TS 31.115, ETSI TS 102 225), from {@link OtaParser}. Only the
 * header is read: the secured data is never decrypted or kept (step 5).
 *
 * <p>Thread: any (not changed after the parse).
 */
public final class OtaFacts {
    /** SPI1 bits 2-1: 00 none, 01 redundancy check, 10 cryptographic checksum, 11 digital signature. */
    static final int SPI1_INTEGRITY = 0x03;

    /** SPI1 bits 2-1 value 10: cryptographic checksum (11, digital signature, is more). */
    static final int SPI1_CRYPTOGRAPHIC_CHECKSUM = 0x02;
    /** SPI1 bit 3: ciphering. */
    static final int SPI1_CIPHERING = 0x04;

    boolean response;
    int packetLength = -1;
    int headerLength = -1;
    int spi = -1;
    int kic = -1;
    int kid = -1;
    int tar = -1;
    long counter = -1;
    int paddingCounter = -1;
    int status = -1;
    boolean satPush;
    boolean wibCommand;
    int anomalies;

    OtaFacts() {}

    /** True for a response packet (UDH IE 0x71), false for a command packet (IE 0x70). */
    public boolean response() {
        return response;
    }

    /** CPL or RPL: octets after the two length octets, −1 when unread. */
    public int packetLength() {
        return packetLength;
    }

    /** CHL or RHL, −1 when unread. */
    public int headerLength() {
        return headerLength;
    }

    /** Security parameter indicator, two octets as {@code SPI1 << 8 | SPI2}; −1 when unread or a response. */
    public int spi() {
        return spi;
    }

    /**
     * True when the SPI asks for a cryptographic checksum or a digital signature: SPI1 bits 2-1 are {@code 10} or
     * {@code 11} (ETSI TS 102 225). A redundancy check ({@code 01}) is a CRC, not a security check.
     */
    public boolean cryptographicIntegrity() {
        return spi >= 0 && ((spi >>> 8) & SPI1_INTEGRITY) >= SPI1_CRYPTOGRAPHIC_CHECKSUM;
    }

    /** True when SPI1 bit 3 asks for ciphering (ETSI TS 102 225). */
    public boolean ciphered() {
        return spi >= 0 && ((spi >>> 8) & SPI1_CIPHERING) != 0;
    }

    /**
     * True for a command packet whose SPI asks for no cryptographic checksum, no signature and no ciphering
     * (subtype {@code SIM-OTA-NOSEC}). A SIM app with minimum security level 0 runs such a command: the Simjacker and WIBattack path.
     */
    public boolean noSecurity() {
        return !response && spi >= 0 && !cryptographicIntegrity() && !ciphered();
    }

    /** KIc: key and algorithm for ciphering, −1 when unread or a response. */
    public int kic() {
        return kic;
    }

    /** KID: key and algorithm for the checksum, −1 when unread or a response. */
    public int kid() {
        return kid;
    }

    /** Toolkit application reference, three octets; −1 when unread. */
    public int tar() {
        return tar;
    }

    /**
     * Counter (five octets), −1 when unread or when the SPI asks for ciphering: ETSI TS 102 225 §5.1 ciphers CNTR,
     * PCNTR, RC/CC/DS and the data, so the octets are cipher text then.
     */
    public long counter() {
        return ciphered() ? -1 : counter;
    }

    /** Padding counter, −1 when unread or ciphered (see {@link #counter()}). */
    public int paddingCounter() {
        return ciphered() ? -1 : paddingCounter;
    }

    /**
     * Response status code, −1 when unread or a command. A response packet has no SPI, so the parser cannot see
     * whether the status is ciphered; a ciphered response gives cipher text here.
     */
    public int status() {
        return status;
    }

    /**
     * True when the secured data is not ciphered and has the shape of an S@T push command: tag 0x42 and a BER length
     * that ends at the end of the packet (SIMalliance S@T 01.23). Read only for a whole command packet without a
     * header anomaly; the data itself is not kept.
     */
    public boolean satPush() {
        return satPush;
    }

    /**
     * True when the secured data is not ciphered and has the shape of a WIB command: a two-octet length that ends at
     * the end of the packet, then octet 0x10. The shape comes from public WIB samples (SRLabs SIMtester, SmartTrust
     * WIB 1.3); the WIB format is not public. Same conditions as {@link #satPush()}.
     */
    public boolean wibCommand() {
        return wibCommand;
    }

    /** True when every header field is present (also when it is ciphered). */
    public boolean headerComplete() {
        return tar >= 0 && paddingCounter >= 0 && (!response || status >= 0);
    }

    /** Anomaly mask (see {@link Anomalies}). */
    public int anomalyMask() {
        return anomalies;
    }

    public List<AnomalyCode> anomalies() {
        return Anomalies.list(anomalies);
    }
}
