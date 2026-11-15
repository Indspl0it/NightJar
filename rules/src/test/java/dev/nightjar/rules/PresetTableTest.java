package dev.nightjar.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Subtype;
import dev.nightjar.contract.Verdict;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The preset table pinned per class.
 * Each row is {class, Recommended, Strict, Quarantine}. Where the table asks for Hold or Block and
 * {@link EnforcementMatrix} does not allow it, the row has the NOTIFY that the preset gives instead: the consumer
 * classes (table: Hold in Strict and Quarantine) and CL-OEM (table: Block) have no enforcement point today. Other
 * cell broadcasts cannot be released, so Quarantine blocks them (table: Hold).
 */
class PresetTableTest {
    private static final Verdict A = Verdict.ALLOW;
    private static final Verdict N = Verdict.NOTIFY;
    private static final Verdict Q = Verdict.QUARANTINE;
    private static final Verdict B = Verdict.BLOCK;

    private static final Object[][] TABLE = {
        {ClassId.NORMAL, A, A, A},
        {ClassId.SILENT, N, N, N},
        {ClassId.FLASH, Q, Q, Q},
        {ClassId.REPLACE, Q, B, Q},
        {ClassId.MWI, A, Q, Q},
        {ClassId.VVM, A, N, N},
        {ClassId.MISSED_CALL, A, N, N},
        {ClassId.CARRIER, A, N, N},
        {ClassId.RCS_PROV, A, N, N},
        {ClassId.APP_TOKEN, A, N, N},
        {ClassId.DATA_PORT, A, B, Q},
        {ClassId.WAP_MMS, A, Q, Q},
        {ClassId.WAP_SISL, Q, B, Q},
        {ClassId.WAP_CP, B, B, Q},
        {ClassId.WAP_DM, B, B, Q},
        {ClassId.WAP_SUPL, B, B, Q},
        {ClassId.WAP_OTHER, Q, B, Q},
        {ClassId.SIM_DD, B, B, B},
        {ClassId.SIM_STORED, A, A, A},
        {ClassId.STATUS, A, A, A},
        {ClassId.CB, A, B, B},
        {ClassId.OEM, A, N, N},
        {ClassId.INJECTED, Q, B, Q},
        {ClassId.MO_VISIBLE, A, A, A},
        {ClassId.MO_UNPERSISTED, A, A, A},
        {ClassId.MO_DATA, A, A, A},
        {ClassId.MO_RAW, A, A, A},
        {ClassId.MO_STK, N, N, N},
        {ClassId.MO_VVM, A, A, A},
        {ClassId.RETURN_CALL, Q, B, Q},
        {ClassId.ME_DOWNLOAD, B, B, Q},
        {ClassId.ME_DEPERSO, Q, B, Q},
        {ClassId.CLASS3, A, B, Q},
        {ClassId.TELEMATIC, Q, B, Q},
        {ClassId.EMPTY, B, B, Q},
        {ClassId.BINARY, Q, B, Q},
        {ClassId.COMPRESSED, Q, B, Q},
        {ClassId.MULTIPART_INCOMPLETE, N, N, N},
        {ClassId.MALFORMED_SMS, B, B, B},
        {ClassId.SMART, Q, B, Q},
        {ClassId.LOC_SUPL, B, B, Q},
        {ClassId.WAP_CO, Q, B, Q},
        {ClassId.WAP_DS, Q, B, Q},
        {ClassId.WAP_EMN, A, Q, Q},
        {ClassId.WAP_DRM, A, Q, Q},
        {ClassId.WAP_VCARD, Q, B, Q},
        {ClassId.WAP_IMPS, Q, B, Q},
        {ClassId.WAP_UNREADABLE, B, B, B},
        {ClassId.CB_SIM, B, B, B},
        {ClassId.UNREADABLE_SMS, Q, B, Q},
    };

    private static final List<Preset> COLUMNS = List.of(Preset.RECOMMENDED, Preset.STRICT, Preset.QUARANTINE);

    private static Map<ClassId, Verdict> column(int index) {
        Map<ClassId, Verdict> m = new EnumMap<>(ClassId.class);
        for (Object[] row : TABLE) m.put((ClassId) row[0], (Verdict) row[index + 1]);
        return m;
    }

    @Test
    void UX2_FW_10_theTableNamesEveryClassOnce() {
        assertEquals(ClassId.values().length, TABLE.length);
        assertEquals(ClassId.values().length, column(0).size());
    }

    @Test
    void UX2_FW_10_eachPresetGivesItsColumn() {
        for (int i = 0; i < COLUMNS.size(); i++) {
            Preset p = COLUMNS.get(i);
            Map<ClassId, Verdict> want = column(i);
            RulesConfig config = Presets.apply(p, RulesConfig.DEFAULT);
            for (ClassId c : ClassId.values()) {
                assertEquals(want.get(c), Presets.classAction(p, c), p + " " + c.wire());
                assertEquals(want.get(c), config.classAction(c), p + " " + c.wire() + ": snapshot");
            }
        }
    }

    @Test
    void UX2_FW_10_everyPresetActionIsAllowedByTheMatrix() {
        for (Preset p : Preset.values()) {
            RulesConfig config = Presets.apply(p, RulesConfig.DEFAULT);
            for (ClassId c : ClassId.values()) {
                Verdict v = config.classAction(c);
                assertTrue(EnforcementMatrix.allows(EnforcementMatrix.classActionMask(c), v),
                        p + " " + c.wire() + ": " + v + " is not allowed");
            }
            for (Rule r : config.rules()) {
                for (ClassId c : r.when().classes()) {
                    assertTrue(EnforcementMatrix.allows(EnforcementMatrix.ruleActionMask(c,
                            r.when().hasSenderPortOrText()), r.action()), p + " " + r.name());
                }
            }
            assertEquals(List.of(), RuleValidator.validate(config), p.toString());
        }
    }

    @Test
    void UX2_FW_10_recommendedIsTheDefault() {
        assertEquals(Preset.RECOMMENDED, Presets.DEFAULT_PRESET);
        assertEquals(Preset.RECOMMENDED, Presets.apply(null, RulesConfig.DEFAULT).preset());
        // The empty document stays Monitor: the payload allows everything before its first snapshot (fail open).
        assertEquals(Preset.MONITOR, RulesConfig.DEFAULT.preset());
        assertTrue(RulesConfig.DEFAULT.classActions().isEmpty());
    }

    @Test
    void UX2_FW_10_subtypeRules() {
        for (Preset p : COLUMNS) {
            RulesConfig config = Presets.apply(p, RulesConfig.DEFAULT);
            assertEquals(B, action(config, ClassId.MWI, Subtype.Mwi.DISCARD_SILENT), p + ": silent indicator");
            assertEquals(B, action(config, ClassId.WAP_SISL, Subtype.WapSisl.SL), p + ": auto-open link push");
            assertEquals(Q, action(config, ClassId.FLASH, Subtype.Flash.CLASS0_EMPTY), p + ": empty flash");
        }
        RulesConfig quarantine = Presets.apply(Preset.QUARANTINE, RulesConfig.DEFAULT);
        for (String st : List.of(Subtype.DataPort.PORT0, Subtype.DataPort.UNKNOWN, Subtype.DataPort.SERVICE_CARD,
                Subtype.DataPort.WAP_OTA_CONFIG)) {
            assertEquals(Q, action(quarantine, ClassId.DATA_PORT, st), "Quarantine " + st);
        }
        assertEquals(Q, action(quarantine, ClassId.WAP_SISL, Subtype.WapSisl.SI));
        assertEquals(
                List.of(Presets.P_MWI_SILENT_ID, Presets.P_SISL_SL_ID),
                quarantine.rules().stream().map(Rule::id).toList());
        RulesConfig rec = Presets.apply(Preset.RECOMMENDED, RulesConfig.DEFAULT);
        assertEquals(Q, action(rec, ClassId.DATA_PORT, Subtype.DataPort.PORT0));
        assertEquals(N, action(rec, ClassId.DATA_PORT, Subtype.DataPort.UNKNOWN));
        assertEquals(N, action(rec, ClassId.DATA_PORT, Subtype.DataPort.INTERNET_ACCESS_CONFIG));
        assertEquals(A, action(rec, ClassId.DATA_PORT, Subtype.DataPort.SERVICE_CARD));
        RulesConfig strict = Presets.apply(Preset.STRICT, RulesConfig.DEFAULT);
        assertEquals(Q, action(strict, ClassId.DATA_PORT, Subtype.DataPort.UNKNOWN), "carrier voicemail SMS");
        assertEquals(B, action(strict, ClassId.DATA_PORT, Subtype.DataPort.PORT0));
        assertEquals(B, action(strict, ClassId.DATA_PORT, Subtype.DataPort.WAP_OTA_CONFIG));
    }

    @Test
    void UX2_FW_11_quarantineIsAPresetOnTheWire() throws RulesFormatException {
        assertEquals("QUARANTINE", Preset.QUARANTINE.wire());
        RulesConfig quarantine = Presets.apply(Preset.QUARANTINE, RulesConfig.DEFAULT);
        RulesConfig back = RulesJson.parse(RulesJson.serialize(quarantine));
        assertEquals(Preset.QUARANTINE, back.preset());
        assertEquals(quarantine.classActions(), back.classActions());
        assertEquals(false, Presets.notifyNotable(Preset.QUARANTINE));
        // Everything that can be held is held, except regular SMS.
        for (ClassId c : ClassId.values()) {
            boolean holdable = EnforcementMatrix.allows(EnforcementMatrix.classActionMask(c), Q);
            if (holdable && c != ClassId.NORMAL) assertEquals(Q, quarantine.classAction(c), c.wire());
        }
    }

    private static Verdict action(RulesConfig config, ClassId c, String subtype) {
        Decision d = new Decision();
        RuleEngine.compile(config).evaluate(new MatchInput().classId(c).subtype(subtype), Guards.NOMINAL, d);
        return d.action();
    }
}
