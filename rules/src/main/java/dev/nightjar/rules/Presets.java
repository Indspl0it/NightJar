package dev.nightjar.rules;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Schemas;
import dev.nightjar.contract.Subtype;
import dev.nightjar.contract.Verdict;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Preset definitions as data. Selecting a preset replaces the class actions and the
 * {@code origin=PRESET} rules; user rules, known originators and guards are kept. The engine evaluates every
 * user rule before every preset rule, so a user rule can make an exception to a preset rule.
 *
 * <p>The notification side effect is a setting, not a verdict: {@link #notifyNotable(Preset)} tells the caller the
 * value of {@code notify.notable} that the preset asks for. Applying it is the caller's job.
 *
 * <p>The three presets that act are these. A class action cannot tell subtypes apart, so a subtype with a different
 * action gets a preset rule with a {@code subtypes} condition ({@link #subtypeRules(Preset)}).
 *
 * <ul>
 *   <li>Recommended, the default for a new install ({@link #DEFAULT_PRESET}): holds the hidden types that a user may
 *       want later and blocks the types that attacks use.
 *   <li>Strict: allows only regular SMS and emergency alerts. It holds the types that a phone service needs
 *       (voicemail, missed calls, carrier and chat setup, app codes, MMS, email and DRM notices, flash SMS) and blocks
 *       the rest.
 *  <li>Quarantine: holds every type that can be held, except regular SMS and emergency alerts. Where a
 *       hold is not possible, it blocks, and where a block is not possible, it notifies.
 * </ul>
 *
 * <p>Where the table asks for an action that {@link EnforcementMatrix} does not allow for the class (no enforcement
 * point, or no release path), the preset uses NOTIFY: the message goes through and the user gets a notification.
 * Today this is so for the consumer classes and CL-OEM ({@link #orNotify}).
 *
 * <p>CL-DATA-PORT: the head filter predicts CL-DATA-PORT/UNKNOWN for a carrier voicemail SMS before its
 * consumer is known. Recommended notifies on UNKNOWN and on the three ports that carry phone configuration, and holds
 * port 0. Strict blocks data messages, but its subtype rule holds UNKNOWN, so a carrier voicemail SMS is held (and can
 * be released), not blocked. Quarantine holds every data message.
 */
public final class Presets {
    private Presets() {}

    /** The preset of a new install. A missing snapshot in the payload still allows everything (fail open). */
    public static final Preset DEFAULT_PRESET = Preset.RECOMMENDED;

    /**
     * First id of the reserved range preset rules live in. User rules are allocated below it (from 1 upwards), so a
     * preset rule can never overwrite a user rule and vice versa, whatever order they were created in.
     */
    public static final long PRESET_ID_BASE = 1_000_000_000L;

    /**
     * Stable id of the old {@code P-REC-1} (hold CL-WAP-CP from unknown senders). A later version removed the rule:
     * Recommended now blocks CL-WAP-CP by class action. The id stays reserved so that no other rule gets it.
     */
    public static final long P_REC_1_ID = PRESET_ID_BASE + 1;

    /**
     * Stable id of the old {@code P-STR-1} (block CL-DATA-PORT from unknown senders). A later version removed the rule:
     * Strict now blocks CL-DATA-PORT by class action. The id stays reserved so that no other rule gets it.
     */
    public static final long P_STR_1_ID = PRESET_ID_BASE + 2;

    /** Stable id of the subtype rule: hold CL-FLASH subtype CLASS0-EMPTY. */
    public static final long P_FLASH_EMPTY_ID = PRESET_ID_BASE + 3;

    /** Stable id of the subtype rule: block CL-MWI subtype DISCARD-SILENT (was notify). */
    public static final long P_MWI_SILENT_ID = PRESET_ID_BASE + 4;

    /**
     * Stable id of the subtype rule that holds CL-DATA-PORT messages: PORT0 in Recommended, UNKNOWN in Strict (the
     * carrier voicemail SMS).
     */
    public static final long P_PORT_HOLD_ID = PRESET_ID_BASE + 5;

    /** Stable id of the subtype rule: block CL-WAP-SISL subtype SL. */
    public static final long P_SISL_SL_ID = PRESET_ID_BASE + 6;

    /** Stable id of the Recommended subtype rule: notify on CL-DATA-PORT UNKNOWN and the configuration ports. */
    public static final long P_PORT_NOTIFY_ID = PRESET_ID_BASE + 7;

    /** CL-DATA-PORT subtypes that carry phone configuration (JSR 120 ports 5503, 49996 and 49999). */
    private static final List<String> CONFIG_PORTS = List.of(
            Subtype.DataPort.INTERNET_ACCESS_CONFIG,
            Subtype.DataPort.SYNCML_OTA_CONFIG,
            Subtype.DataPort.WAP_OTA_CONFIG);

    /**
     * Applies {@code preset} to {@code base}: preset class actions replace the current ones, preset rules are
     * replaced, user rules (and their order) survive. {@link Preset#CUSTOM} changes nothing. A {@code null} preset is
     * {@link #DEFAULT_PRESET}.
     */
    public static RulesConfig apply(Preset preset, RulesConfig base) {
        if (preset == null) preset = DEFAULT_PRESET;
        if (base == null) base = RulesConfig.DEFAULT;
        if (preset == Preset.CUSTOM) return base.withPreset(Preset.CUSTOM);

        List<Rule> rules = new ArrayList<>(base.userRules());
        rules.addAll(presetRules(preset));
        return new RulesConfig(
                Schemas.RULES,
                base.revision(),
                preset,
                classActions(preset),
                rules,
                base.knownExact(),
                base.knownPrefix(),
                base.suplGraceMin());
    }

    /** Class actions of {@code preset}; classes not present are ALLOW. Monitor has none. */
    public static Map<ClassId, Verdict> classActions(Preset preset) {
        EnumMap<ClassId, Verdict> m = new EnumMap<>(ClassId.class);
        for (ClassId c : ClassId.values()) {
            Verdict v = classAction(preset, c);
            if (v != Verdict.ALLOW) m.put(c, v);
        }
        return m;
    }

    /** The class action of {@code c} in {@code preset}. Monitor, Custom and {@code null} give ALLOW. */
    public static Verdict classAction(Preset preset, ClassId c) {
        if (preset == null || c == null) return Verdict.ALLOW;
        switch (preset) {
            case RECOMMENDED:
                return recommendedClassAction(c);
            case STRICT:
                return strictClassAction(c);
            case QUARANTINE:
                return quarantineClassAction(c);
            default:
                return Verdict.ALLOW;
        }
    }

    /**
     * Recommended class action of {@code c} (the "Recommended (default)" column of the preset table). For CL-FLASH,
     * CL-MWI, CL-DATA-PORT and CL-WAP-SISL it is the action of the subtypes that {@link #subtypeRules(Preset)} does
     * not name.
     */
    public static Verdict recommendedClassAction(ClassId c) {
        switch (c) {
            case FLASH:
            case REPLACE:
            case WAP_SISL:
            case WAP_OTHER:
            case INJECTED:
            case RETURN_CALL:
            case ME_DEPERSO:
            case TELEMATIC:
            case BINARY:
            case COMPRESSED:
            case SMART:
            case WAP_CO:
            case WAP_DS:
            case WAP_VCARD:
            case WAP_IMPS:
            // NightJar cannot check a message with the wrong message type, but a release works. Hold it.
            case UNREADABLE_SMS:
                return Verdict.QUARANTINE;
            case WAP_CP:
            case WAP_DM: // it can change the phone remotely
            case WAP_SUPL: // Still allows it during an emergency call and the grace time
            case LOC_SUPL:
            case SIM_DD: // cannot be held; Simjacker-type attacks use it
            case ME_DOWNLOAD:
            case EMPTY: // used as a probe
            case WAP_UNREADABLE:
            // RULES-X8: a malformed SMS cannot be released (a re-injection gives the framework parser the PDU that it
            // can trip over). Block keeps the protection; the message stays visible in NightJar.
            case MALFORMED_SMS:
            case CB_SIM: // a cell broadcast cannot be released
                return Verdict.BLOCK;
            case SILENT: // Android discards a Type 0 message before any hook: only a notification is possible
            case MULTIPART_INCOMPLETE: // the parts were delivered already
            case MO_STK: // outbound messages are not blocked by class (is a per-app setting)
                return Verdict.NOTIFY;
            default:
                return Verdict.ALLOW;
        }
    }

    /** Strict class action of {@code c} ("Strict" column). */
    static Verdict strictClassAction(ClassId c) {
        switch (c) {
            case NORMAL:
            case SIM_STORED:
            case STATUS:
                return Verdict.ALLOW;
            case FLASH:
            case MWI:
            case WAP_MMS:
            case WAP_EMN:
            case WAP_DRM:
                return Verdict.QUARANTINE;
            case VVM:
            case MISSED_CALL:
            case CARRIER:
            case RCS_PROV:
            case APP_TOKEN:
                return orNotify(c, Verdict.QUARANTINE);
            case OEM: // a block can stop maker features
                return orNotify(c, Verdict.BLOCK);
            case SILENT:
            case MULTIPART_INCOMPLETE:
            case MO_STK:
                return Verdict.NOTIFY;
            default:
                return outbound(c) ? Verdict.ALLOW : Verdict.BLOCK;
        }
    }

    /**
     * Quarantine class action of {@code c}: the strongest of Hold, Block and Notify that {@link EnforcementMatrix}
     * allows, in that order. Regular SMS and the outbound classes keep their Recommended action.
     */
    static Verdict quarantineClassAction(ClassId c) {
        if (c == ClassId.NORMAL || outbound(c)) return recommendedClassAction(c);
        int mask = EnforcementMatrix.classActionMask(c);
        for (Verdict v : new Verdict[] {Verdict.QUARANTINE, Verdict.BLOCK, Verdict.NOTIFY}) {
            if (EnforcementMatrix.allows(mask, v)) return v;
        }
        return Verdict.ALLOW;
    }

    /** {@code wanted} when {@link EnforcementMatrix} allows it for {@code c}, else NOTIFY. */
    private static Verdict orNotify(ClassId c, Verdict wanted) {
        int mask = EnforcementMatrix.classActionMask(c);
        return EnforcementMatrix.allows(mask, wanted) ? wanted : Verdict.NOTIFY;
    }

    private static boolean outbound(ClassId c) {
        switch (c) {
            case MO_VISIBLE:
            case MO_UNPERSISTED:
            case MO_DATA:
            case MO_RAW:
            case MO_STK:
            case MO_VVM:
                return true;
            default:
                return false;
        }
    }

    /**
     * The Recommended action of {@code c} with subtype {@code subtype} ({@code null} when the message has none): the
     * Recommended subtype rule that names the subtype, else {@link #recommendedClassAction}. This is what the engine
     * gives for a Recommended snapshot when no user rule matches and no clamp applies.
     */
    public static Verdict recommendedAction(ClassId c, String subtype) {
        if (subtype != null) {
            for (Rule r : subtypeRules(Preset.RECOMMENDED)) {
                Conditions w = r.when();
                if (w.classes().contains(c) && w.subtypes().contains(subtype)) return r.action();
            }
        }
        return recommendedClassAction(c);
    }

    /**
     * Preset rules of {@code preset} for the subtypes whose action differs from the class action. Each needs the
     * payload to send the subtype; without it the rule does not match and the class action applies (fail open).
     * Monitor and Custom have none.
     */
    public static List<Rule> subtypeRules(Preset preset) {
        if (preset != Preset.RECOMMENDED && preset != Preset.STRICT && preset != Preset.QUARANTINE) return List.of();
        List<Rule> rules = new ArrayList<>();
        if (preset != Preset.QUARANTINE) {
            // Quarantine holds every flash message by class action.
            rules.add(subtypeRule(P_FLASH_EMPTY_ID, "Hold empty flash messages", Verdict.QUARANTINE,
                    Conditions.builder().classes(ClassId.FLASH).subtypes(Subtype.Flash.CLASS0_EMPTY)));
        }
        rules.add(subtypeRule(P_MWI_SILENT_ID, "Block silent message-waiting signals", Verdict.BLOCK,
                Conditions.builder().classes(ClassId.MWI).subtypes(Subtype.Mwi.DISCARD_SILENT)));
        if (preset == Preset.RECOMMENDED) {
            rules.add(subtypeRule(P_PORT_HOLD_ID, "Hold data messages to port 0", Verdict.QUARANTINE,
                    Conditions.builder().classes(ClassId.DATA_PORT).subtypes(Subtype.DataPort.PORT0)));
            LinkedHashSet<String> notify = new LinkedHashSet<>();
            notify.add(Subtype.DataPort.UNKNOWN);
            notify.addAll(CONFIG_PORTS);
            rules.add(subtypeRule(P_PORT_NOTIFY_ID,
                    "Notify me about data messages to unknown ports and configuration ports", Verdict.NOTIFY,
                    Conditions.builder().classes(ClassId.DATA_PORT).subtypes(notify)));
        } else if (preset == Preset.STRICT) {
            // A carrier voicemail SMS is CL-DATA-PORT/UNKNOWN at the head filter: hold it, so it can be released.
            rules.add(subtypeRule(P_PORT_HOLD_ID, "Hold data messages to unknown ports", Verdict.QUARANTINE,
                    Conditions.builder().classes(ClassId.DATA_PORT).subtypes(Subtype.DataPort.UNKNOWN)));
        }
        rules.add(subtypeRule(P_SISL_SL_ID, "Block auto-open link pushes", Verdict.BLOCK,
                Conditions.builder().classes(ClassId.WAP_SISL).subtypes(Subtype.WapSisl.SL)));
        return List.copyOf(rules);
    }

    private static Rule subtypeRule(long id, String name, Verdict action, Conditions.Builder when) {
        return new Rule(id, 0, true, RuleOrigin.PRESET, name, action, when.build());
    }

    /** Preset rules of {@code preset} in file order: the subtype rules. */
    public static List<Rule> presetRules(Preset preset) {
        return subtypeRules(preset);
    }

    /** True when the preset asks for {@code notify.notable} on (Strict only). */
    public static boolean notifyNotable(Preset preset) {
        return preset == Preset.STRICT;
    }
}
