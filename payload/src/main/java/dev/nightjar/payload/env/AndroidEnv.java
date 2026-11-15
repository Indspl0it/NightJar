package dev.nightjar.payload.env;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/**
 * {@link Env} over the framework. Every getter returns a cached value: it is O(1), does no
 * binder or file I/O, and a hook thread can ask for a fact at any time.
 *
 * <p>Refresh points: {@link #refreshBootCount()}, {@link #refreshApex()}, {@link #refreshUnlockState()},
 * {@link #refreshSubscriptions(int[])}. The worker calls them at wiring and when the corresponding
 * broadcast arrives. Before the first refresh, the boot count is -1 and the APEX versions are {@code {}}.
 *
 * <p>Thread: any.
 */
public final class AndroidEnv implements Env {
    /** APEX packages whose versions matter for hook resolution. */
    static final String[] APEX = {
        "com.google.android.telephonycore", "com.google.android.cellbroadcast",
        "com.android.telephonycore", "com.android.cellbroadcast"
    };

    private volatile Context context;
    private final String payloadVersion;
    private final ConcurrentHashMap<Integer, SubInfo> subs = new ConcurrentHashMap<>();
    private volatile int bootCount = -1;
    private volatile boolean unlocked = true;
    private volatile String apexJson = "{}";

    /**
     * @param context the phone process's context, or null when the payload starts before the
     *     Application exists (the Xposed entry runs in {@code handleLoadPackage}). A null context is
     *     resolved on first use through {@link Contexts#application()}.
     */
    public AndroidEnv(Context context, String payloadVersion) {
        this.context = context;
        this.payloadVersion = payloadVersion == null ? "unknown" : payloadVersion;
    }

    /** The context, resolved once it exists; null while the process is still starting. */
    private Context ctx() {
        Context c = context;
        if (c != null) return c;
        c = Contexts.application();
        if (c != null) context = c;
        return c;
    }

    @Override
    public String buildFingerprint() {
        try {
            return Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public String payloadVersion() {
        return payloadVersion;
    }

    @Override
    public String apexVersionsJson() {
        return apexJson;
    }

    /** Reads the APEX versions (package manager binder calls). Worker only. */
    public void refreshApex() {
        JSONObject o = new JSONObject();
        try {
            Context c = ctx();
            if (c == null) return;
            PackageManager pm = c.getPackageManager();
            for (String pkg : APEX) {
                try {
                    String v = pm.getPackageInfo(pkg, 0).versionName;
                    if (v != null) o.put(pkg, v);
                } catch (Throwable ignored) {
                    // Not installed on this build.
                }
            }
        } catch (Throwable ignored) {
            // PackageManager unavailable: report an empty object.
        }
        apexJson = o.toString();
    }

    @Override
    public int bootCount() {
        return bootCount;
    }

    /** Read once per session, on the worker. */
    public int refreshBootCount() {
        try {
            Context c = ctx();
            bootCount = c == null ? -1
                    : Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable t) {
            bootCount = -1;
        }
        return bootCount;
    }

    @Override
    public boolean userUnlocked() {
        return unlocked;
    }

    /** Called on the worker for {@code ACTION_USER_UNLOCKED} and at start. */
    public boolean refreshUnlockState() {
        try {
            Context c = ctx();
            UserManager um = c == null ? null : c.getSystemService(UserManager.class);
            unlocked = um == null || um.isUserUnlocked();
        } catch (Throwable t) {
            unlocked = true; // Fail open: never suppress capture on a read error.
        }
        return unlocked;
    }

    /**
     * Reads carrier, slot and roaming for every active subscription. A subscription that
     * is no longer active is removed from the cache.
     */
    public void refreshSubscriptions(int[] subIds) {
        if (subIds == null) return;
        java.util.Set<Integer> active = new java.util.HashSet<>();
        for (int subId : subIds) {
            active.add(subId);
            subs.put(subId, readSub(subId));
        }
        subs.keySet().retainAll(active);
    }

    /** Sets one subscription's facts (tests, or a service-state broadcast). */
    public void putSub(SubInfo info) {
        if (info != null) subs.put(info.subId(), info);
    }

    private SubInfo readSub(int subId) {
        String carrier = "";
        int slot = -1;
        boolean roaming = false;
        try {
            Context c = ctx();
            TelephonyManager tm = c == null ? null : c.getSystemService(TelephonyManager.class);
            if (tm != null) {
                TelephonyManager sub = tm.createForSubscriptionId(subId);
                carrier = sub.getNetworkOperatorName();
                if (carrier == null) carrier = "";
                ServiceState ss = sub.getServiceState();
                roaming = ss != null && ss.getRoaming();
                slot = android.telephony.SubscriptionManager.getSlotIndex(subId);
            }
        } catch (Throwable ignored) {
            // Radio service not ready; the defaults are correct for "unknown".
        }
        return new SubInfo(subId, slot, carrier, roaming);
    }

    @Override
    public String carrierName(int subId) {
        SubInfo s = subs.get(subId);
        return s == null ? "" : s.carrierName();
    }

    @Override
    public int slot(int subId) {
        SubInfo s = subs.get(subId);
        return s == null ? -1 : s.slot();
    }

    /** Roaming state of {@code subId}; false when the subscription is not in the cache (unknown). */
    @Override
    public boolean roaming(int subId) {
        SubInfo s = subs.get(subId);
        return s != null && s.roaming();
    }

    /**
     * Phone id → sub id mapping is owned by the telephony stack; hooks pass phoneId, this maps it.
     * -1 ({@code SubscriptionManager.INVALID_SUBSCRIPTION_ID}) when no cached subscription is in that slot:
     * a phone id is never a subscription id.
     */
    @Override
    public int subIdForPhone(int phoneId) {
        for (SubInfo s : subs.values()) {
            if (s.slot() == phoneId) return s.subId();
        }
        return -1;
    }

    /** How long a default-SMS-app answer stays valid before it is read again. */
    static final long DEFAULT_SMS_TTL_MS = 30_000L;

    private final ConcurrentHashMap<String, DefaultSms> defaultSms = new ConcurrentHashMap<>();

    /**
     * Is {@code pkg} the default SMS app right now? Read from {@code SmsApplication} and
     * cached for {@link #DEFAULT_SMS_TTL_MS}, so the worker asks at most twice a minute and a user
     * who changes their messaging app is picked up within half a minute.
     *
     * <p>Called on the worker only: the read is a settings lookup, not a per-hook cost.
     */
    @Override
    public boolean isDefaultSmsApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        long now = android.os.SystemClock.elapsedRealtime();
        DefaultSms cached = defaultSms.get(pkg);
        if (cached != null && now - cached.readAtMs < DEFAULT_SMS_TTL_MS) return cached.isDefault;
        boolean v = false;
        try {
            Class<?> c = Class.forName("com.android.internal.telephony.SmsApplication");
            Object component = c.getMethod("getDefaultSendToApplication",
                    android.content.Context.class, boolean.class).invoke(null, ctx(), true);
            if (component instanceof android.content.ComponentName cn) {
                v = pkg.equals(cn.getPackageName());
            }
        } catch (Throwable ignored) {
            // Unreadable: report "not the default app", which records rather than hides.
        }
        defaultSms.put(pkg, new DefaultSms(v, now));
        return v;
    }

    /** One cached default-SMS-app answer. */
    private record DefaultSms(boolean isDefault, long readAtMs) {}
}
