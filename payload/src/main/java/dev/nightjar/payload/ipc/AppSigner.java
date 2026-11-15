package dev.nightjar.payload.ipc;

/**
 * Reads the signing certificate of an installed package. Production uses
 * {@link PackageManagerSigner}; tests fake it.
 *
 * <p>Thread: IPC worker.
 */
public interface AppSigner {

    /**
     * SHA-256 of the package's signing certificate.
     *
     * @return colon-separated upper-case hex ({@code AB:CD:…}), or {@code null} when the package is not installed or
     *     cannot be queried
     */
    String signatureSha256(String packageName);

    /**
     * The application uid that owns {@code packageName}, or −1 when unknown.
     *
     * <p>Used to prime the control-binder caller gate before the binder is handed to the app.
     */
    default int uidOf(String packageName) {
        return -1;
    }
}
