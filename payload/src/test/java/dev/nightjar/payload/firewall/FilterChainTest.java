package dev.nightjar.payload.firewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * HK-R2 reads the stock filter chain before it prepends the NightJar filter. A chain it cannot read means "do not
 * install", so the carrier and visual voicemail filters of the build are never dropped.
 */
class FilterChainTest {
    private static final String FIELD = "mSmsFilters";

    static class Base {
        @SuppressWarnings("unused")
        private final List<Object> mSmsFilters;

        Base(List<Object> filters) {
            this.mSmsFilters = filters;
        }
    }

    /** The field is declared on a super class, as on the reference builds (GsmInboundSmsHandler). */
    static final class Handler extends Base {
        Handler(List<Object> filters) {
            super(filters);
        }
    }

    static final class NoChain {}

    static final class WrongType {
        @SuppressWarnings("unused")
        private final Object mSmsFilters = "not a list";
    }

    @Test
    void theStockChainIsReadFromASuperClass() {
        List<Object> stock = new ArrayList<>(List.of("carrier", "vvm"));
        assertSame(stock, FilterChain.read(new Handler(stock), FIELD));
    }

    @Test
    void aMissingFieldMeansDoNotInstall() {
        assertNull(FilterChain.read(new NoChain(), FIELD));
    }

    @Test
    void aFieldThatIsNotAListMeansDoNotInstall() {
        assertNull(FilterChain.read(new WrongType(), FIELD));
    }

    @Test
    void aNullFieldIsAnEmptyChain() {
        List<?> chain = FilterChain.read(new Handler(null), FIELD);
        assertTrue(chain != null && chain.isEmpty());
    }

    @Test
    void aNullOwnerMeansDoNotInstall() {
        assertNull(FilterChain.read(null, FIELD));
    }

    @Test
    void prependKeepsEveryStockFilterInOrder() {
        Object nj = new Object();
        List<Object> next = FilterChain.prepend(List.of("carrier", "vvm"), nj);
        assertEquals(List.of(nj, "carrier", "vvm"), next);
        assertEquals(next, FilterChain.prepend(next, nj), "a second install changes nothing");
    }
}
