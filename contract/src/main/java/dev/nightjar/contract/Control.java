package dev.nightjar.contract;

/** App → payload control binder {@code IPayloadControl}. */
public final class Control {
    private Control() {}

    public static final int VERSION = 1;
    public static final String DESCRIPTOR = "dev.nightjar.IPayloadControl/" + VERSION;

    /** Transaction codes. {@code android.os.IBinder.FIRST_CALL_TRANSACTION} is 1, so codes map 1:1. */
    public static final class Code {
        private Code() {}

        public static final int UPDATE_RULES = 1;
        public static final int UPDATE_SETTINGS = 2;
        public static final int SET_SAFE_MODE = 3;
        public static final int ENABLE_FIREWALL = 4;
        public static final int RELEASE = 5;
        public static final int RUN_SELF_TEST = 6;
        public static final int GET_HOOK_HEALTH = 7;
        public static final int GET_LOG = 8;
        public static final int PING = 9;
        public static final int CANCEL_SELF_TEST = 10;
        /** Replay a stored PDU through the internal injection path (P2). */
        public static final int REPLAY = 11;
    }

    /** {@code release} results. */
    public static final class ReleaseResult {
        private ReleaseResult() {}

        public static final int HANDLED = 1;
        public static final int GENERIC_FAILURE = 2;
        public static final int OUT_OF_MEMORY = 3;
        public static final int INVALID_ARGUMENTS = -10;
        public static final int NOT_RELEASABLE = -11;
        public static final int TIMEOUT = -12;
        public static final int NO_DISPATCHER = -13;
        public static final int CALLER_REJECTED = -14;

        /** The payload cannot do this request on this device. */
        public static final int NOT_SUPPORTED = -15;
    }

    /** {@code runSelfTest} error codes, returned as {@code "ERR:<code>"}. */
    public static final class SelfTestError {
        private SelfTestError() {}

        public static final String PREFIX = "ERR:";
        public static final String LOCKED = "LOCKED";
        public static final String BUSY = "BUSY";
        public static final String INVALID = "INVALID";
    }
}
