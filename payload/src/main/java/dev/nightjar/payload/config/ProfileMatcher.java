package dev.nightjar.payload.config;

import java.util.function.Predicate;

/**
 * Build-profile matching. Pure data lookup; no I/O.
 *
 * <p>Status: {@code KNOWN} — a profile matched and every hook the profile marks
 * REQUIRED is live; {@code UNKNOWN} — no profile matched the fingerprint; {@code MISMATCH} — a
 * profile matched but a REQUIRED hook failed.
 */
public final class ProfileMatcher {
    /** Result of matching the running build against {@code profiles.json}. */
    public enum Status {
        KNOWN,
        UNKNOWN,
        MISMATCH
    }

    private final Profile.Document document;

    public ProfileMatcher(Profile.Document document) {
        this.document = document == null ? Profile.Document.EMPTY : document;
    }

    public Profile.Document document() {
        return document;
    }

    /** The first profile whose fingerprint rule matches, or null. */
    public Profile profile(String fingerprint) {
        if (fingerprint == null) return null;
        for (Profile p : document.profiles()) {
            if (p.matches(fingerprint)) return p;
        }
        return null;
    }

    /** Classifies the build; {@code requiredHookFailed} answers "did this REQUIRED hook fail?". */
    public Status status(String fingerprint, Predicate<String> requiredHookFailed) {
        Profile p = profile(fingerprint);
        if (p == null) return Status.UNKNOWN;
        if (requiredHookFailed != null) {
            for (var e : p.hooks().entrySet()) {
                if (Profile.REQUIRED.equalsIgnoreCase(e.getValue()) && requiredHookFailed.test(e.getKey())) {
                    return Status.MISMATCH;
                }
            }
        }
        return Status.KNOWN;
    }

    /** {@code orphanPurgeDays} of the matched profile, else the AOSP default of 7. */
    public int orphanPurgeDays(String fingerprint) {
        Profile p = profile(fingerprint);
        return p == null ? 7 : p.orphanPurgeDays();
    }

    /** OEM of the matched profile, AOSP when unknown. */
    public Profile.Oem oem(String fingerprint) {
        Profile p = profile(fingerprint);
        return p == null ? Profile.Oem.AOSP : p.oem();
    }
}
