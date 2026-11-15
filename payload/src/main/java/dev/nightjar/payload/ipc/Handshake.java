package dev.nightjar.payload.ipc;

import dev.nightjar.contract.Ipc;
import dev.nightjar.payload.env.Env;

/**
 * Builds the {@code hello} request and validates the reply.
 *
 * <p>Thread: IPC worker only.
 */
public final class Handshake {

    private Handshake() {}

    /** The reply to {@code hello}, or the reason it was unusable. */
    public static final class Session {
        private final boolean ok;
        private final String error;
        private final int ipcVersion;
        private final String sessionId;
        private final String rulesJson;
        private final String settingsJson;
        /** {@code null} when the reply has no {@code firewallEnabled} key. */
        private final Boolean firewallEnabled;

        private Session(boolean ok, String error, int ipcVersion, String sessionId, String rulesJson,
                String settingsJson, Boolean firewallEnabled) {
            this.ok = ok;
            this.error = error;
            this.ipcVersion = ipcVersion;
            this.sessionId = sessionId;
            this.rulesJson = rulesJson;
            this.settingsJson = settingsJson;
            this.firewallEnabled = firewallEnabled;
        }

        static Session failure(String error) {
            return new Session(false, error, 0, null, null, null, null);
        }

        public boolean ok() {
            return ok;
        }

        /** App-supplied error string, or the local reason the reply was rejected. */
        public String error() {
            return error;
        }

        /** The app's IPC version. */
        public int ipcVersion() {
            return ipcVersion;
        }

        public String sessionId() {
            return sessionId;
        }

        /** Snapshot, or {@code null} when the app sent none or it was oversize. */
        public String rulesJson() {
            return rulesJson;
        }

        /** Snapshot, or {@code null} when the app sent none. */
        public String settingsJson() {
            return settingsJson;
        }

        /**
         * True when the reply carries {@code firewallEnabled}. The app leaves the key out when it cannot read
         * its persisted value (before the first unlock); then the payload keeps its current value.
         */
        public boolean hasFirewallEnabled() {
            return firewallEnabled != null;
        }

        /** The app's value, or false when the reply has no key ({@link #hasFirewallEnabled()}). */
        public boolean firewallEnabled() {
            return firewallEnabled != null && firewallEnabled;
        }
    }

    /**
     * Builds the {@code hello} request.
     *
     * @param controlBinder the {@code IPayloadControl} binder, or {@code null} when the control layer is not ready
     */
    public static IpcRequest hello(String authority, Env env, HookHealthSource health, Object controlBinder) {
        return new IpcRequest(Ipc.Method.HELLO, authority)
                .put(Ipc.Key.IPC_VERSION, Ipc.VERSION)
                .put(Ipc.Key.PAYLOAD_VERSION, env.payloadVersion())
                .put(Ipc.Key.BUILD_FINGERPRINT, env.buildFingerprint())
                .put(Ipc.Key.APEX_VERSIONS, env.apexVersionsJson())
                .put(Ipc.Key.BOOT_COUNT, env.bootCount())
                .put(Ipc.Key.HOOK_HEALTH, health == null ? null : health.hookHealthJson())
                .put(Ipc.Key.DROPPED_COUNT, health == null ? null : health.droppedCount())
                .put(Ipc.Key.CONTROL, controlBinder)
                .put(Ipc.Key.USER_UNLOCKED, env.userUnlocked());
    }

    /**
     * Validates the reply.
     *
     * <p>A reply without {@code ipcVersion} or {@code sessionId}, or with a version below 1, is a failed handshake: the
     * payload cannot address the session. An app-side {@code error} string is passed through so the worker can
     * log it, and an oversize {@code rules} snapshot is dropped rather than applied.
     */
    public static Session parse(IpcReply reply) {
        if (reply == null) return Session.failure("empty reply");
        String appError = reply.getString(Ipc.Key.ERROR);
        if (appError != null && !appError.isEmpty()) return Session.failure(appError);

        int version = reply.getInt(Ipc.Key.IPC_VERSION, 0);
        if (version < 1) return Session.failure("missing ipcVersion");
        String sessionId = reply.getString(Ipc.Key.SESSION_ID);
        if (sessionId == null || sessionId.isEmpty()) return Session.failure("missing sessionId");

        String rules = reply.getString(Ipc.Key.RULES);
        // The limit is bytes, and a UTF-8 rule pattern can be three bytes per character. A
        // snapshot over it is dropped rather than applied; the payload keeps the rules it already has.
        // The health push after the handshake carries rulesRevision, so the app sees the old revision
        // .
        if (rules != null && rules.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > Ipc.MAX_RULES_BYTES) {
            rules = null;
        }

        return new Session(
                true,
                null,
                version,
                sessionId,
                rules,
                reply.getString(Ipc.Key.SETTINGS),
                reply.get(Ipc.Key.FIREWALL_ENABLED) instanceof Boolean b ? b : null);
    }
}
