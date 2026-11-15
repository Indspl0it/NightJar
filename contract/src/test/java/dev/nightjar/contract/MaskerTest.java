package dev.nightjar.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/** Masking rules. */
class MaskerTest {
    private static final String H = Masker.HIDDEN;

    @Test
    void SEC_07_internationalNumbersKeepCountryCodeAndLastThree() {
        assertEquals("+91" + H + "210", Masker.maskNumber("+919000000210"));
        assertEquals("+1" + H + "167", Masker.maskNumber("+1 (650) 555-0167"));
        assertEquals("+44" + H + "123", Masker.maskNumber("+447700900123"));
        assertEquals("+353" + H + "456", Masker.maskNumber("+353851234456"));
    }

    @Test
    void SEC_07_nationalNumbersKeepLastThree() {
        assertEquals(H + "210", Masker.maskNumber("09000000210"));
    }

    @Test
    void SEC_07_shortCodesAreKept() {
        assertEquals("121", Masker.maskNumber("121"));
        assertEquals("57575", Masker.maskNumber("57575"));
        assertEquals("+" + H, Masker.maskNumber("+12"));
    }

    @Test
    void SEC_07_alphanumericSenders() {
        assertEquals("VM-AIRTEL", Masker.maskNumber("VM-AIRTEL"));
        assertEquals("JD-HDFCBK", Masker.maskNumber("JD-HDFCBK"));
        assertEquals(H + "789", Masker.maskNumber("ACC123456789"));
        assertEquals(H + "234", Masker.maskNumber("AB1234"));
        assertEquals(H + "ERS", Masker.maskNumber("AVERYLONGSENDERS"));
    }

    @Test
    void SEC_07_alphanumericWithSplitDigitsIsMasked() {
        // The digit count decides, not the longest run: these hold most of a phone number.
        assertEquals(H + "432", Masker.maskNumber("987-65A-432"));
        assertEquals(H + "654", Masker.maskNumber("+91x987x654"));
        assertEquals(H + "5e6", Masker.maskNumber("1a2b3c4d5e6"));
        assertEquals("AX-BANK123", Masker.maskNumber("AX-BANK123"));
    }

    @Test
    void SEC_07_nonAsciiDigitsCount() {
        // Arabic-Indic digits 4 3 2 1 0 after five ASCII digits: a 10-digit national number.
        assertEquals(H + "210", Masker.maskNumber("98765\u0664\u0663\u0662\u0661\u0660"));
        // Fullwidth digits only.
        assertEquals(H + "210", Masker.maskNumber("\uFF19\uFF18\uFF17\uFF16\uFF15\uFF14\uFF13\uFF12\uFF11\uFF10"));
        assertEquals("+91" + H + "210", Masker.maskNumber("+\u0669\u0661\u0669\u0668\u0667\u0666\u0665\u0664\u0663\u0662\u0661\u0660"));
    }

    @Test
    void SEC_07_controlCharactersAreReplaced() {
        assertEquals("VM-A?IRTEL", Masker.maskNumber("VM-A\nIRTEL"));
        assertEquals(H + "?ab", Masker.maskNumber("LONGSENDERX\rab"));
    }

    @Test
    void SEC_07_nullAndEmpty() {
        assertEquals("null", Masker.maskNumber(null));
        assertEquals("", Masker.maskNumber("  "));
    }

    @Test
    void LOG_31_textIsNeverShown() {
        assertEquals("«5 chars»", Masker.maskText("hello"));
        assertEquals("«1 char»", Masker.maskText("😀"));
        assertEquals("«null»", Masker.maskText(null));
        assertFalse(Masker.maskText("secret").contains("secret"));
    }
}
