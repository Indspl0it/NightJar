package dev.nightjar.payload.boot;

import dev.nightjar.payload.log.PLog;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;

/**
 * Startup sweep that puts the Nightjar head filter (HK-08) at the front of every existing
 * {@code InboundSmsHandler}.
 *
 * <p>The per-handler hook (HK-24) covers handlers created later (SIM hot-swap); this sweep covers
 * the handlers that already exist when the payload starts, because their constructors ran before
 * the payload was loaded.
 *
 * <p>Reflection only, and total: a missing field, a not-yet-created phone or any other error is
 * logged once and the rest of the sweep continues. Runs on the worker; each installation is posted
 * to the handler's own looper by {@code FirewallHooks.installNow}.
 */
final class FilterSweep {
    private static final String[] HANDLER_FIELDS = {"mInboundSmsHandler", "mSmsHandler", "mHandler"};
    private static final String[] INBOUND = {"mInboundSmsHandlers", "mSmsHandlers"};

    private FilterSweep() {}

    /** Posts the {@code NjSmsFilter} installation to every handler found; returns how many were posted. */
    static int run(dev.nightjar.payload.hooks.FirewallHooks hooks, PLog log) {
        int installed = 0;
        try {
            for (Object phone : phones()) {
                Object handler = handlerOf(phone);
                if (handler == null) continue;
                installed += installOne(handler, hooks);
            }
        } catch (Throwable t) {
            log.w("firewall", "filter sweep failed", "err", t.getClass().getSimpleName());
        }
        if (installed > 0) log.i("firewall", "head filter installation posted", "handlers", installed);
        return installed;
    }

    /**
     * The class loader of the OnePlus {@code mImpl} object of the first inbound handler, or null (AOSP, or no handler
     * yet). The {@code IMPL_LOADER} hooks (HK-30, HK-32) resolve their classes through it.
     */
    static ClassLoader implLoader() {
        try {
            for (Object phone : phones()) {
                Object handler = handlerOf(phone);
                if (handler == null) continue;
                Object impl = field(handler.getClass(), "mImpl", handler);
                if (impl != null) return impl.getClass().getClassLoader();
            }
        } catch (Throwable ignored) {
            // Not a OnePlus build, or the phones are not ready.
        }
        return null;
    }

    private static int installOne(Object handler, dev.nightjar.payload.hooks.FirewallHooks hooks) {
        if (!(handler instanceof com.android.internal.telephony.InboundSmsHandler h)) return 0;
        try {
            hooks.installNow(h);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Every {@code Phone} the framework knows about, without failing on a partial init. */
    private static Collection<?> phones() {
        try {
            Class<?> factory = Class.forName("com.android.internal.telephony.PhoneFactory");
            Method getPhones = factory.getMethod("getPhones");
            Object phones = getPhones.invoke(null);
            if (phones instanceof Object[] arr) return Arrays.asList(arr);
            if (phones instanceof Collection<?> c) return c;
        } catch (Throwable ignored) {
            // PhoneFactory not initialised yet: the HK-24 path will cover the handlers.
        }
        return java.util.List.of();
    }

    /**
     * The phone's GSM inbound handler. {@code Phone} exposes {@code getInboundSmsHandler(boolean)} on
     * both reference builds; the handler itself lives in {@code SmsDispatchersController}, so a field
     * scan of {@code Phone} finds nothing. Only the 3GPP handler ({@code is3gpp2 = false}) is swept:
     * the target has no CDMA. The field scan is a fallback for a build without the method.
     */
    private static Object handlerOf(Object phone) {
        if (phone == null) return null;
        try {
            Method m = phone.getClass().getMethod("getInboundSmsHandler", boolean.class);
            Object h = m.invoke(phone, false);
            if (h != null) return h;
        } catch (Throwable ignored) {
            // Use the field fallback.
        }
        for (String name : HANDLER_FIELDS) {
            Object h = field(phone.getClass(), name, phone);
            if (h != null) return h;
        }
        for (String name : INBOUND) {
            Object list = field(phone.getClass(), name, phone);
            if (list instanceof Collection<?> c && !c.isEmpty()) return c.iterator().next();
        }
        return null;
    }

    private static Object field(Class<?> owner, String name, Object target) {
        Class<?> k = owner;
        while (k != null && k != Object.class) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
                k = k.getSuperclass();
            }
        }
        return null;
    }
}
