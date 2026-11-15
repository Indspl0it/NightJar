package dev.nightjar.contract;

/** Payload → app IPC over {@code ContentResolver.call}. */
public final class Ipc {
    private Ipc() {}

    /**
     * Current IPC version. Bump only with a protocol change.
     *
     * <p>2: the event fields in {@link EventKey} and the classes and subtypes of {@link ClassId} and
     * {@link Subtype}. An app that reads version 1 ignores the new fields and parses an unknown class id as no class.
     */
    public static final int VERSION = 2;

    /** Authority suffix; the full authority is {@code <appId>.ipc}. */
    public static final String AUTHORITY_SUFFIX = ".ipc";

    public static String authority(String appId) {
        return appId + AUTHORITY_SUFFIX;
    }

    /** Method names. */
    public static final class Method {
        private Method() {}

        public static final String HELLO = "hello";
        public static final String EVENTS = "events";
        public static final String COUNTERS = "counters";
        public static final String HOOK_HEALTH = "hookHealth";
        public static final String LOG = "log";
    }

    /** Bundle keys, both directions. */
    public static final class Key {
        private Key() {}

        public static final String IPC_VERSION = "ipcVersion";
        public static final String SESSION_ID = "sessionId";
        public static final String PAYLOAD_VERSION = "payloadVersion";
        public static final String BUILD_FINGERPRINT = "buildFingerprint";
        public static final String APEX_VERSIONS = "apexVersions";
        public static final String BOOT_COUNT = "bootCount";
        public static final String HOOK_HEALTH = "hookHealth";
        public static final String DROPPED_COUNT = "droppedCount";
        public static final String CONTROL = "control";
        public static final String USER_UNLOCKED = "userUnlocked";
        public static final String RULES = "rules";
        public static final String SETTINGS = "settings";
        public static final String FIREWALL_ENABLED = "firewallEnabled";
        public static final String ERROR = "error";
        public static final String BATCH = "batch";
        public static final String ACKED = "acked";
        public static final String COUNTERS = "counters";
        public static final String OK = "ok";
        public static final String LINES = "lines";
    }

    /**
     * Event JSON fields added by the classification redesign. Each field is omitted when it has
     * no value.
     */
    public static final class EventKey {
        private EventKey() {}

        /** String: the subtype code of the final class ({@link Subtype}). */
        public static final String SUBTYPE = "subtype";

        /**
         * Array of strings: the other detection rules that matched, in rule order. Each entry has one of three
         * forms: a bare subtype code of the event class, {@code <class wire>:<subtype>} for a rule of another
         * class, or {@code <class wire>} for a rule of another class that has no subtype.
         */
        public static final String EXTRA_SUBTYPES = "extraSubtypes";

        /** Array of strings: {@link AnomalyCode} wire names, in the order the parser found them. */
        public static final String ANOMALIES = "anomalies";

        /**
         * Object: the header of a SIM OTA secured packet, keys in {@link SimOtaKey}. Present when the PDU has
         * a command or response packet identifier and goes to the SIM. Only header fields: never the secured data.
         */
        public static final String SIM_OTA_HEADER = "simOtaHeader";
    }

    /**
     * Keys of {@link EventKey#SIM_OTA_HEADER} (3GPP TS 31.115, ETSI TS 102 225). Each value is a JSON number; a key
     * is omitted when the field could not be read. A response packet has no SPI, KIc or KID.
     */
    public static final class SimOtaKey {
        private SimOtaKey() {}

        /** Command or response packet length (CPL or RPL). */
        public static final String CPL = "cpl";

        /** Command or response header length (CHL or RHL). */
        public static final String CHL = "chl";

        /** Security parameter indicator, {@code SPI1 << 8 | SPI2}. */
        public static final String SPI = "spi";

        public static final String KIC = "kic";

        public static final String KID = "kid";

        /** Toolkit application reference, three octets as one number. */
        public static final String TAR = "tar";

        /** Counter, five octets as one number. */
        public static final String CNTR = "cntr";
    }

    /** Error strings. */
    public static final class Error {
        private Error() {}

        public static final String UNKNOWN_SESSION = "UNKNOWN_SESSION";

        /**
         * Credential-protected storage is not readable yet (before the first unlock). Retry later. This is not
         * a refusal: the app acknowledged nothing.
         */
        public static final String LOCKED = "LOCKED";

        /**
         * Nothing could be staged, so nothing is acknowledged. Retry later. This is not a refusal: the app
         * acknowledged nothing.
         */
        public static final String STAGING_UNAVAILABLE = "STAGING_UNAVAILABLE";
    }

    /** Limits. */
    public static final int MAX_BATCH_EVENTS = 50;
    public static final int MAX_BATCH_BYTES = 256 * 1024;
    public static final int MAX_RULES_BYTES = 64 * 1024;

    /** C.5 settings document: a few hundred bytes in practice, capped so a push cannot grow unbounded. */
    public static final int MAX_SETTINGS_BYTES = 16 * 1024;

    /** Ack entry format {@code "eventId:revision"}. */
    public static String ackKey(String eventId, int revision) {
        return eventId + ":" + revision;
    }
}
