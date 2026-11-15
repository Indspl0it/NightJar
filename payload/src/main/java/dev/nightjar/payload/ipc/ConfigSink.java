package dev.nightjar.payload.ipc;

/**
 * Applies the snapshots the app returns from {@code hello}. The payload parses on the IPC worker and
 * swaps atomically; a parse error keeps the previous snapshot (: unknown fields ignored).
 *
 * <p>Thread: IPC worker only.
 */
public interface ConfigSink {

    /** {@code rules} JSON, ≤ {@link dev.nightjar.contract.Ipc#MAX_RULES_BYTES} bytes. */
    void applyRulesJson(String json);

    /** {@code settings} JSON. */
    void applySettingsJson(String json);

    /** {@code firewallEnabled} from the handshake reply. */
    void applyFirewallEnabled(boolean enabled);
}
