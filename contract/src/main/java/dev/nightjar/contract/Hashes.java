package dev.nightjar.contract;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Correlation hashing. Thread-safe. */
public final class Hashes {
    private Hashes() {}

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Maximum SMSC address length in octets (TS 24.011: up to 11 octets after the length octet). */
    static final int MAX_SMSC_OCTETS = 11;

    /**
     * Correlation key of a TPDU: SHA-256, first 16 bytes, lower-case hex (32 characters).
     *
     * @throws IllegalArgumentException if {@code tpdu} is null
     */
    public static String tpduKey(byte[] tpdu) {
        if (tpdu == null) throw new IllegalArgumentException("tpdu == null");
        byte[] d = sha256(tpdu);
        char[] out = new char[32];
        for (int i = 0; i < 16; i++) {
            out[i * 2] = HEX[(d[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[d[i] & 0xF];
        }
        return new String(out);
    }

    /**
     * Removes the SMSC address prefix from a radio-style PDU ({@code <len><smsc…><tpdu>}). Returns
     * {@code pdu} unchanged when the first octet cannot be an SMSC length. Callers that don't know
     * the form try both the raw and the stripped bytes.
     */
    public static byte[] stripSmsc(byte[] pdu) {
        if (pdu == null || pdu.length < 2) return pdu;
        int len = pdu[0] & 0xFF;
        if (len > MAX_SMSC_OCTETS || 1 + len >= pdu.length) return pdu;
        byte[] out = new byte[pdu.length - 1 - len];
        System.arraycopy(pdu, 1 + len, out, 0, out.length);
        return out;
    }

    /** One digest per thread: {@code getInstance} looks up the provider and allocates on each call. */
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    });

    private static byte[] sha256(byte[] in) {
        MessageDigest md = SHA256.get();
        md.reset();
        return md.digest(in);
    }
}
