package dev.nightjar.payload.capture;

/**
 * Resolves an injecting uid to a package on the worker. Package lookups are
 * binder calls, so they must never happen in the hook body.
 *
 * <p>Implemented over {@code PackageManager.getPackagesForUid} by the payload runtime.
 */
public interface PackageResolver {
    /** Packages for the uid, best match first; never null. */
    String[] packagesForUid(int uid);

    /** Resolver that knows nothing (tests). */
    PackageResolver NONE = uid -> new String[0];
}
