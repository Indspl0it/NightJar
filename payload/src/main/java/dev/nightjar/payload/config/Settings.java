package dev.nightjar.payload.config;

import dev.nightjar.contract.SettingsKeys;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * {@code settings.json} (schema 1), immutable. Unknown fields are ignored;
 * a missing field takes its default. {@code detect.suplGraceMin} is not read: the SUPL grace comes from the guards of
 * {@code rules.json}. The firewall switch is not in this document.
 *
 * @param outboundBlocked packages whose outbound SMS are all blocked, every send class
 * @param debugUntil wall-clock ms until which D logging is on; 0 = off
 * @param faultInject hook id (or {@code spool}) → mode; parsed, but the APK never acts on it
 */
public record Settings(
        int schema,
        String appPackage,
        String appCertSha256,
        boolean safeMode,
        boolean outgoingEnabled,
        boolean outgoingLogVisible,
        List<String> outboundBlocked,
        boolean cbLog,
        boolean cbAllChannels,
        long debugUntil,
        Map<String, String> faultInject) {

    public static final Settings DEFAULT = new Settings(
            1, "dev.nightjar", "", false, true, false, List.of(), true, false, 0L, Map.of());

    public Settings {
        if (appPackage == null || appPackage.isEmpty()) appPackage = "dev.nightjar";
        if (appCertSha256 == null) appCertSha256 = "";
        outboundBlocked = outboundBlocked == null ? List.of() : List.copyOf(outboundBlocked);
        faultInject = faultInject == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(faultInject));
    }

    /** This snapshot with a different {@code safeMode} flag. */
    public Settings withSafeMode(boolean on) {
        if (safeMode == on) return this;
        return new Settings(schema, appPackage, appCertSha256, on, outgoingEnabled, outgoingLogVisible,
                outboundBlocked, cbLog, cbAllChannels, debugUntil, faultInject);
    }

    /** True when D logging is enabled at {@code nowWallMs}. */
    public boolean debugEnabledAt(long nowWallMs) {
        return debugUntil > 0 && nowWallMs < debugUntil;
    }

    /** Fault mode for a hook id or subsystem, or null. Parsed only: the APK never acts on it. */
    public String faultMode(String id) {
        return faultInject.get(id);
    }

    /** Parses a UTF-8 document; throws on a document that is not a JSON object. */
    public static Settings parse(byte[] utf8) throws FormatException {
        if (utf8 == null) throw new FormatException("settings == null");
        return parse(new String(utf8, StandardCharsets.UTF_8));
    }

    public static Settings parse(String json) throws FormatException {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject outgoing = o.optJSONObject(SettingsKeys.OUTGOING);
            JSONObject cb = o.optJSONObject("cb");
            JSONObject debug = o.optJSONObject("debugLogging");
            JSONObject test = o.optJSONObject("test");
            Map<String, String> fi = new LinkedHashMap<>();
            JSONObject inject = test == null ? null : test.optJSONObject("faultInject");
            if (inject != null) {
                java.util.Iterator<String> it = inject.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    fi.put(k, inject.optString(k, ""));
                }
            }
            return new Settings(
                    o.optInt("schema", 1),
                    o.optString("appPackage", DEFAULT.appPackage()),
                    o.optString("appCertSha256", ""),
                    o.optBoolean("safeMode", false),
                    outgoing == null || outgoing.optBoolean(SettingsKeys.OUTGOING_ENABLED, true),
                    outgoing != null && outgoing.optBoolean(SettingsKeys.OUTGOING_LOG_VISIBLE, false),
                    outgoing == null ? List.of() : strings(outgoing.optJSONArray(SettingsKeys.OUTGOING_BLOCKED)),
                    cb == null || cb.optBoolean("log", true),
                    cb != null && cb.optBoolean("allChannels", false),
                    debug == null ? 0L : debug.optLong("until", 0L),
                    fi);
        } catch (RuntimeException | org.json.JSONException e) {
            throw new FormatException("settings.json: " + e.getMessage(), e);
        }
    }

    private static List<String> strings(JSONArray a) {
        if (a == null) return List.of();
        java.util.ArrayList<String> out = new java.util.ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, null);
            if (s != null && !s.isEmpty()) out.add(s);
        }
        return out;
    }
}
