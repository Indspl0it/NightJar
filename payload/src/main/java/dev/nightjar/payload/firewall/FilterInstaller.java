package dev.nightjar.payload.firewall;

import com.android.internal.telephony.InboundSmsHandler;
import dev.nightjar.payload.log.PLog;
import java.util.ArrayList;
import java.util.List;

/**
 * HK-24 / HK-R2: prepend the Nightjar filter to a handler's filter chain.
 *
 * <p>The framework keeps the chain in {@code InboundSmsHandler.mSmsFilters} and offers
 * {@code setSmsFiltersForTesting(List)} to replace it. {@link FilterChain} decides what the new chain is, so the
 * idempotency rule lives in one tested place; this class only reads the field and calls the framework.
 *
 * <p>Thread: the handler's looper (the HK-24 after-hook posts the install there).
 */
public final class FilterInstaller {

    private static final String FIELD = "mSmsFilters";
    private static final String TAG = "FilterInstall";

    private final PLog log;

    public FilterInstaller(PLog log) {
        this.log = log;
    }

    /**
     * Installs the filter on one handler.
     *
     * @return true when the chain was replaced
     */
    public boolean install(InboundSmsHandler handler, NjSmsFilter filter) {
        if (handler == null || filter == null) return false;
        try {
            List<InboundSmsHandler.SmsFilter> current = currentFilters(handler);
            if (current == null) {
                // Fail open: without the stock chain a new chain would drop the carrier, visual voicemail and other
                // filters of this build. Capture continues without a head filter; the firewall tier then degrades.
                if (log != null) log.w(TAG, "head filter not installed: the filter chain cannot be read");
                return false;
            }
            if (FilterChain.startsWith(current, filter.getClass())) {
                filter.markInstalled(handler.getClass().getName());
                return false;
            }
            List<Object> next = FilterChain.prepend(current, filter);
            List<InboundSmsHandler.SmsFilter> typed = new ArrayList<>(next.size());
            for (Object o : next) typed.add((InboundSmsHandler.SmsFilter) o);
            handler.setSmsFiltersForTesting(typed);
            filter.markInstalled(handler.getClass().getName());
            if (log != null) log.i(TAG, "head filter installed, chain size " + typed.size());
            return true;
        } catch (Throwable t) {
            // Fail open: capture continues without a head filter; the firewall tier then degrades.
            if (log != null) log.w(TAG, "head filter install failed: " + t.getClass().getSimpleName());
            return false;
        }
    }

    /** Reads {@code mSmsFilters}; {@code null} when the field is absent or unreadable. */
    @SuppressWarnings("unchecked")
    static List<InboundSmsHandler.SmsFilter> currentFilters(InboundSmsHandler handler) {
        return (List<InboundSmsHandler.SmsFilter>) FilterChain.read(handler, FIELD);
    }
}
