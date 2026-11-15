package dev.nightjar.payload.ipc;

import dev.nightjar.payload.log.PLog;

/**
 * Certificate pinning for the app package.
 *
 * <p>While the pin does not match, the worker sends only {@code counters}, {@code hookHealth} and {@code log}; message
 * content stays in the outbox. A missing pin is a mismatch: without a value from {@code settings.json} there is nothing
 * to compare against, so content is withheld (fail closed for privacy, never for capture).
 *
 * <p>Thread: IPC worker only.
 */
public final class CertPin {

    private static final String TAG = "CertPin";

    private final AppSigner signer;
    private final PLog log;
    private String verifiedPackage;
    private String verifiedPin;
    private boolean verified;
    private boolean warned;

    public CertPin(AppSigner signer, PLog log) {
        this.signer = signer;
        this.log = log;
    }

    /**
     * Verifies the app package against the pinned hash, caching the answer for the session.
     *
     * @param packageName resolved from {@code settings.appPackage}
     * @param expectedSha256 {@code settings.appCertSha256}; {@code null} or blank when absent
     * @return true when content may be sent
     */
    public boolean verify(String packageName, String expectedSha256) {
        if (packageName == null || packageName.isEmpty()) return false;
        String expected = normalize(expectedSha256);
        if (expected == null) {
            warnOnce("No pinned certificate in the settings. The payload does not send message content.");
            return false;
        }
        if (verified && packageName.equals(verifiedPackage) && expected.equals(verifiedPin)) return verified;
        boolean ok;
        try {
            String actual = normalize(signer.signatureSha256(packageName));
            ok = expected.equals(actual);
        } catch (Throwable t) {
            ok = false;
        }
        verifiedPackage = packageName;
        verifiedPin = expected;
        verified = ok;
        if (ok) {
            warned = false;
        } else {
            warnOnce("App certificate does not match the pin. The payload does not send message content.");
        }
        return ok;
    }

    /** Drops the cached answer (new handshake after an app update). */
    public void reset() {
        verifiedPackage = null;
        verifiedPin = null;
        verified = false;
        warned = false;
    }

    /** Strips separators and upper-cases; returns {@code null} for blank input. */
    static String normalize(String sha256) {
        if (sha256 == null) return null;
        StringBuilder b = new StringBuilder(sha256.length());
        for (int i = 0; i < sha256.length(); i++) {
            char c = sha256.charAt(i);
            if (c == ':' || c == ' ' || c == '-' || c == '.') continue;
            b.append(Character.toUpperCase(c));
        }
        if (b.length() == 0) return null;
        return b.toString();
    }

    private void warnOnce(String message) {
        if (warned) return;
        warned = true;
        if (log != null) log.w(TAG, message);
    }
}
