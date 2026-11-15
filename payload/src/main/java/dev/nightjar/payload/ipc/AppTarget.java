package dev.nightjar.payload.ipc;

/**
 * The app the payload talks to, from {@code settings.json} ({@code appPackage} and
 * {@code appCertSha256}). Read on every handshake so a package change takes effect without a reboot.
 *
 * <p>Thread: IPC worker.
 */
public interface AppTarget {

    /** Application id, e.g. {@code dev.nightjar}; {@code null} or empty when settings are missing. */
    String appPackage();

    /** Pinned signing certificate SHA-256, colon-separated hex as stored; {@code null} when absent. */
    String appCertSha256();
}
