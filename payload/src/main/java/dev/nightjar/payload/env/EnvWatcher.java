package dev.nightjar.payload.env;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.telephony.SubscriptionManager;

/**
 * Keeps the {@link AndroidEnv} facts current.
 *
 * <p>The environment answers hook threads in O(1) from cached values, so something has to refresh the cache when the
 * facts change. This watcher listens for the two events that matter:
 * <ul>
 *   <li>{@code ACTION_USER_UNLOCKED} — flips {@code userUnlocked}; before it, events are spooled and the app stages
 *  them in its Direct Boot database;</li>
 *   <li>subscription and service-state changes — refresh slot, carrier and roaming, which {@code when.slot} and
 *  {@code when.roaming} rules read at the enforcement point.</li>
 * </ul>
 *
 * <p>The receiver is registered without a handler, so {@code onReceive} runs on the main thread. It only posts the
 * refresh to the worker; the binder reads happen there.
 *
 * <p>Thread: {@link #start} on the worker; receiver callbacks on the main thread, which do no work beyond handing off to
 * the worker through {@link #onEvent}.
 */
public final class EnvWatcher {

    /** What the runtime wants to do when the environment changes. */
    public interface Listener {
        /** The user finished unlocking: the pending-unlock spool can be released. */
        void onUserUnlocked();

        /** A subscription, SIM or service state changed. */
        void onSubscriptionsChanged();
    }

    private final AndroidEnv env;
    private final Worker worker;
    private final Listener listener;
    private BroadcastReceiver receiver;
    private volatile Context context;
    private volatile boolean unlocked;

    public EnvWatcher(AndroidEnv env, Worker worker, Listener listener) {
        this.env = env;
        this.worker = worker;
        this.listener = listener;
    }

    /** Registers the receiver and takes the first snapshot. Safe to call twice. */
    public void start(Context context) {
        if (context == null || receiver != null) return;
        this.context = context;
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(TelephonyIntents.SERVICE_STATE_CHANGED);
        filter.addAction(TelephonyIntents.SIM_STATE_CHANGED);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                onEvent(intent == null ? null : intent.getAction());
            }
        };
        try {
            context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED);
            receiver = r;
        } catch (Throwable t) {
            // Registration is best effort: the startup snapshot below still gives correct values.
            receiver = null;
        }
        refreshNow();
    }

    /** Called on the main thread by the receiver; hands the work to the worker. */
    private void onEvent(String action) {
        if (action == null) return;
        if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
            worker.post(() -> {
                boolean was = unlocked;
                refreshNow();
                if (!was && unlocked && listener != null) listener.onUserUnlocked();
            });
            return;
        }
        worker.post(() -> {
            refreshNow();
            if (listener != null) listener.onSubscriptionsChanged();
        });
    }

    /** Reads the unlock flag and every active subscription into the cache. */
    public void refreshNow() {
        try {
            unlocked = env.refreshUnlockState();
        } catch (Throwable t) {
            unlocked = true;
        }
        try {
            int[] ids = activeSubscriptionIds(context);
            if (ids != null && ids.length > 0) env.refreshSubscriptions(ids);
        } catch (Throwable t) {
            // Subscription service not ready: the defaults stay "unknown", which never blocks a rule.
        }
    }

    /**
     * Active subscription ids, or {@code null} when they cannot be read (the previous cache stays).
     *
     * <p>{@code getActiveSubscriptionIdList} is an instance method and hidden API, so it is called reflectively on the
     * {@link SubscriptionManager} of the context. A build without it falls back to the public
     * {@code getActiveSubscriptionInfoList()}; the phone process holds the permission for both.
     */
    static int[] activeSubscriptionIds(Context context) {
        if (context == null) return null;
        SubscriptionManager sm;
        try {
            sm = context.getSystemService(SubscriptionManager.class);
        } catch (Throwable t) {
            return null;
        }
        if (sm == null) return null;
        try {
            Object v = SubscriptionManager.class.getMethod("getActiveSubscriptionIdList").invoke(sm);
            if (v instanceof int[] ids) return ids;
        } catch (Throwable t) {
            // Not on this build: use the public list below.
        }
        try {
            java.util.List<android.telephony.SubscriptionInfo> infos = sm.getActiveSubscriptionInfoList();
            if (infos == null) return null;
            int[] ids = new int[infos.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = infos.get(i).getSubscriptionId();
            return ids;
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when the last read saw an unlocked user. */
    public boolean unlocked() {
        return unlocked;
    }

    /** Intent actions that mean "the SIM list may have changed" on the builds we support. */
    static final class TelephonyIntents {
        static final String SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED";
        static final String SERVICE_STATE_CHANGED = "android.intent.action.SERVICE_STATE";

        private TelephonyIntents() {}
    }
}
