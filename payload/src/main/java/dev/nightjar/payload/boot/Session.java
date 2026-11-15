package dev.nightjar.payload.boot;

/**
 * Payload session state machine.
 *
 * <pre>
 * LOADED → HOOKING → (RUNNING | UNSUPPORTED)
 * RUNNING{connected | disconnected}
 * </pre>
 *
 * <p>Illegal transitions are refused and reported ({@link #lastRefused()}), never thrown: a hook
 * body or the control binder may drive the machine and must not break telephony.
 *
 * <p>Thread: any (all transitions are synchronized).
 */
public final class Session {
    /** Session states. */
    public enum State {
        LOADED,
        HOOKING,
        RUNNING,
        UNSUPPORTED
    }

    /** Connectivity while RUNNING. */
    public enum Connection {
        CONNECTED,
        DISCONNECTED
    }

    private final String sessionId;
    private final long startedAtWallMs;
    private State state = State.LOADED;
    private Connection connection = Connection.DISCONNECTED;
    private String refused;

    public Session(String sessionId, long startedAtWallMs) {
        this.sessionId = sessionId;
        this.startedAtWallMs = startedAtWallMs;
    }

    public String sessionId() {
        return sessionId;
    }

    public long startedAtWallMs() {
        return startedAtWallMs;
    }

    public synchronized State state() {
        return state;
    }

    public synchronized Connection connection() {
        return connection;
    }

    public synchronized boolean running() {
        return state == State.RUNNING;
    }

    /** Last refused transition, for the log; null when the last transition was legal. */
    public synchronized String lastRefused() {
        return refused;
    }

    /** LOADED → HOOKING. Returns false when the session is not LOADED. */
    public synchronized boolean beginHooking() {
        if (state != State.LOADED) return refuse("beginHooking in " + state);
        state = State.HOOKING;
        refused = null;
        return true;
    }

    /** HOOKING → RUNNING. */
    public synchronized boolean running(boolean connected) {
        if (state != State.HOOKING) return refuse("running in " + state);
        state = State.RUNNING;
        connection = connected ? Connection.CONNECTED : Connection.DISCONNECTED;
        refused = null;
        return true;
    }

    /** HOOKING → UNSUPPORTED (capture tier failed). */
    public synchronized boolean unsupported() {
        if (state != State.HOOKING && state != State.RUNNING) return refuse("unsupported in " + state);
        state = State.UNSUPPORTED;
        connection = Connection.DISCONNECTED;
        refused = null;
        return true;
    }

    /** RUNNING{connected|disconnected} driven by the handshake. */
    public synchronized boolean setConnected(boolean connected) {
        if (state != State.RUNNING) return refuse("setConnected in " + state);
        connection = connected ? Connection.CONNECTED : Connection.DISCONNECTED;
        refused = null;
        return true;
    }

    public synchronized boolean connected() {
        return state == State.RUNNING && connection == Connection.CONNECTED;
    }

    /** True while events must be buffered instead of sent. */
    public synchronized boolean buffering() {
        return state == State.RUNNING && connection == Connection.DISCONNECTED;
    }

    private boolean refuse(String what) {
        refused = what;
        return false;
    }

    @Override
    public synchronized String toString() {
        return state + (state == State.RUNNING ? "{" + connection + "}" : "");
    }
}
