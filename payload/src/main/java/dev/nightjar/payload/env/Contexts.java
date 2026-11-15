package dev.nightjar.payload.env;

import android.content.Context;
import java.lang.reflect.Method;

/**
 * Finds the {@code Context} of the phone process. The payload is injected into {@code
 * com.android.phone}, which has an Application, so {@code ActivityThread.currentApplication()}
 * returns it; a process without one falls back to the system context.
 *
 * <p>Reflection only: {@code ActivityThread} is not in the stubs and the call must not fail
 * linkage on a build where it moved.
 *
 * <p>Thread: start-up.
 */
public final class Contexts {
    private Contexts() {}

    /**
     * The phone process's own {@code Application}, or null until {@code PhoneApp} exists. Unlike {@link
     * #application()} it never falls back to the system context, whose package is {@code android}: its
     * files dir is not writable by uid 1001, so wiring the spool against it silently loses the spool.
     */
    public static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            return app instanceof Context c ? c : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The application context, or null when the process has none yet. */
    public static Context application() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object app = m.invoke(null);
            if (app instanceof Context c) return c;
            Method thread = at.getMethod("currentActivityThread");
            Object t = thread.invoke(null);
            if (t != null) {
                Method getSystem = at.getMethod("getSystemContext");
                Object sys = getSystem.invoke(t);
                if (sys instanceof Context c) return c;
            }
        } catch (Throwable ignored) {
            // No context: every Env read falls back to its default (fail open).
        }
        return null;
    }

    /** {@code <de files dir>/nightjar} for the pending-unlock spool, or null. */
    public static java.io.File deviceProtectedDir(Context context, String subdir) {
        if (context == null) return null;
        try {
            Context de = context.createDeviceProtectedStorageContext();
            java.io.File base = (de != null ? de : context).getFilesDir();
            return base == null ? null : new java.io.File(base, subdir);
        } catch (Throwable t) {
            return null;
        }
    }
}
