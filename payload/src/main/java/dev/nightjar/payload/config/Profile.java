package dev.nightjar.payload.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * One entry of {@code profiles.json}. Immutable.
 *
 * @param hooks hook id → {@code REQUIRED | OPTIONAL | NA} as declared for this build
 */
public record Profile(
        String id,
        String fingerprint,
        Match match,
        Oem oem,
        Map<String, String> apex,
        int orphanPurgeDays,
        Map<String, String> hooks) {

    /** Fingerprint comparison mode. */
    public enum Match {
        EXACT,
        PREFIX
    }

    /** Who built the ROM. */
    public enum Oem {
        AOSP,
        OPLUS
    }

    /** Hook expectation values in {@code profiles.hooks}. */
    public static final String REQUIRED = "REQUIRED";
    public static final String OPTIONAL = "OPTIONAL";
    public static final String NA = "NA";

    public Profile {
        if (id == null) id = "";
        if (fingerprint == null) fingerprint = "";
        if (match == null) match = Match.EXACT;
        if (oem == null) oem = Oem.AOSP;
        apex = apex == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(apex));
        hooks = hooks == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(hooks));
        if (orphanPurgeDays <= 0) orphanPurgeDays = oem == Oem.OPLUS ? 1 : 7;
    }

    /** True when this profile's fingerprint rule matches {@code fingerprint}. */
    public boolean matches(String fingerprint) {
        if (fingerprint == null) return false;
        return switch (match) {
            case EXACT -> this.fingerprint.equals(fingerprint);
            case PREFIX -> !this.fingerprint.isEmpty() && fingerprint.startsWith(this.fingerprint);
        };
    }

    /** The profile's expectation for a hook, {@code OPTIONAL} when not listed. */
    public String hookExpectation(String hookId) {
        String v = hooks.get(hookId);
        return v == null ? OPTIONAL : v;
    }

    public boolean hookRequired(String hookId) {
        return REQUIRED.equalsIgnoreCase(hookExpectation(hookId));
    }

    public boolean hookNotApplicable(String hookId) {
        return NA.equalsIgnoreCase(hookExpectation(hookId));
    }

    // ------------------------------------------------------------------ parsing

    /** {@code profiles.json} document. */
    public record Document(int schema, List<Profile> profiles) {
        public static final Document EMPTY = new Document(1, List.of());

        public Document {
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
        }

        public static Document parse(byte[] utf8) throws FormatException {
            if (utf8 == null) throw new FormatException("profiles == null");
            return parse(new String(utf8, StandardCharsets.UTF_8));
        }

        public static Document parse(String json) throws FormatException {
            try {
                JSONObject o = new JSONObject(json);
                List<Profile> list = new ArrayList<>();
                var arr = o.optJSONArray("profiles");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject p = arr.optJSONObject(i);
                        if (p == null) continue;
                        list.add(read(p));
                    }
                }
                return new Document(o.optInt("schema", 1), list);
            } catch (RuntimeException | org.json.JSONException e) {
                throw new FormatException("profiles.json: " + e.getMessage(), e);
            }
        }

        private static Profile read(JSONObject p) {
            Map<String, String> apex = new LinkedHashMap<>();
            JSONObject a = p.optJSONObject("apex");
            if (a != null) {
                java.util.Iterator<String> it = a.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    apex.put(k, a.optString(k, ""));
                }
            }
            Map<String, String> hooks = new LinkedHashMap<>();
            JSONObject h = p.optJSONObject("hooks");
            if (h != null) {
                java.util.Iterator<String> it = h.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    hooks.put(k, h.optString(k, OPTIONAL));
                }
            }
            return new Profile(
                    p.optString("id", ""),
                    p.optString("fingerprint", ""),
                    "PREFIX".equalsIgnoreCase(p.optString("match", "EXACT"))
                            ? Match.PREFIX
                            : Match.EXACT,
                    "OPLUS".equalsIgnoreCase(p.optString("oem", "AOSP")) ? Oem.OPLUS : Oem.AOSP,
                    apex,
                    p.optInt("orphanPurgeDays", 0),
                    hooks);
        }
    }
}
