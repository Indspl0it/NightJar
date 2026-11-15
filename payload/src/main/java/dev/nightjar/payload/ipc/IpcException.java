package dev.nightjar.payload.ipc;

/**
 * A failed payload → app call. {@link Kind} decides how {@link IpcWorker} reacts: every kind backs off and
 * retries, {@link Kind#DEAD_OBJECT} and an {@code UNKNOWN_SESSION} reply also force a new handshake.
 */
public final class IpcException extends Exception {

    /** Why the call failed. */
    public enum Kind {
        /** No provider answered: the app is not installed, is disabled or was force-stopped. */
        APP_ABSENT,
        /** The provider answered {@code null}: foreign caller rejected or wrong authority. */
        REJECTED,
        /** The app process died during the call ({@code DeadObjectException}). */
        DEAD_OBJECT,
        /** Any other failure: transport error, malformed reply, unexpected exception. */
        FAILURE
    }

    private static final long serialVersionUID = 1L;

    private final Kind kind;

    public IpcException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public IpcException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
