package dev.nightjar.contract;

/**
 * Keys of the C.5 settings document ({@code settings.json}, pushed with {@link Control.Code#UPDATE_SETTINGS}) that the
 * app writes and the payload reads. Both sides use these names, so a typo cannot silently switch a feature off.
 *
 * <p>Not in {@link Ipc}: the literal check protects every Ipc/Control value of 5 or more characters, and words
 * such as "blocked" and "enabled" are common in UI code.
 */
public final class SettingsKeys {
    private SettingsKeys() {}

    /** Object with the outbound monitor options. A missing object means the defaults below. */
    public static final String OUTGOING = "outgoing";

    /** Boolean in {@link #OUTGOING}: outbound capture is on. Missing = true. */
    public static final String OUTGOING_ENABLED = "enabled";

    /** Boolean in {@link #OUTGOING}: also log sends of visible apps. Missing = false. */
    public static final String OUTGOING_LOG_VISIBLE = "logVisible";

    /**
     * Array of package names in {@link #OUTGOING} whose sends the payload's outbound gate blocks. Missing or
     * empty = every send is allowed (fail open).
     */
    public static final String OUTGOING_BLOCKED = "blocked";
}
