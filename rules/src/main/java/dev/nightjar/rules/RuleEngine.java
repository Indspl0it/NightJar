package dev.nightjar.rules;

import dev.nightjar.contract.ClassId;
import dev.nightjar.contract.Direction;
import dev.nightjar.contract.Verdict;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiled rule snapshot and the verdict evaluator. One engine is compiled when a new
 * {@code rules.json} arrives and swapped atomically by the payload/app; evaluation never allocates and never blocks.
 *
 * <p>Order: enabled rules, first match wins; otherwise the class action; otherwise ALLOW. Every
 * {@link RuleOrigin#USER} rule runs before every {@link RuleOrigin#PRESET} rule, so that a user exception can override
 * a preset Hold or Block. Inside each origin, rules run by ascending {@code priority}, file order on ties.
 * The five clamps ({@link ClampReason}) run afterwards in order and always leave NOTIFY untouched unless
 * {@link EnforcementMatrix} allows only ALLOW for the class (emergency CB, emergency/grace SUPL).
 *
 * <p>Thread: immutable after {@link #compile}; {@link #evaluate} is safe on any thread as long as each thread uses its
 * own {@link MatchInput} and {@link Decision}.
 */
public final class RuleEngine {
    private final RulesConfig config;
    private final Verdict[] classActions = new Verdict[ClassId.values().length];
    private final boolean[] classActionSet = new boolean[ClassId.values().length];
    private final CompiledRule[] rules;
    private final NumberTrie knownOriginators;
    private final long suplGraceMs;
    private final int skippedRules;

    private RuleEngine(RulesConfig config, CompiledRule[] rules, NumberTrie known, int skippedRules) {
        this.config = config;
        this.rules = rules;
        this.knownOriginators = known;
        this.suplGraceMs = config.effectiveSuplGraceMin() * 60_000L;
        this.skippedRules = skippedRules;
        // A class that the snapshot does not name gets RulesConfig.absentClassAction (CL-UNREADABLE-SMS:
        // Hold outside Monitor). Every other absent class stays unset and falls through to DEFAULT ALLOW.
        for (ClassId c : ClassId.values()) {
            classActions[c.ordinal()] = config.classAction(c);
            classActionSet[c.ordinal()] = config.classActions().containsKey(c)
                    || RulesConfig.absentClassAction(c, config.preset()) != Verdict.ALLOW;
        }
    }

    /**
     * Compiles {@code config}. Rules that cannot be evaluated faithfully (a regex that does not compile, an originator
     * or SMSC value that normalises to empty, a port range outside 0..65535) are skipped (fail open) and counted.
     */
    public static RuleEngine compile(RulesConfig config) {
        if (config == null) config = RulesConfig.DEFAULT;

        List<Rule> ordered = new ArrayList<>(config.rules().size());
        for (Rule r : config.rules()) {
            if (r.enabled() && r.unsupported() == null) ordered.add(r);
        }
        // User rules first, then preset rules; inside each origin by priority. List.sort is stable: equal priorities
        // keep file order.
        ordered.sort((a, b) -> {
            boolean ap = a.origin() == RuleOrigin.PRESET;
            boolean bp = b.origin() == RuleOrigin.PRESET;
            if (ap != bp) return ap ? 1 : -1;
            return Integer.compare(a.priority(), b.priority());
        });

        List<CompiledRule> compiled = new ArrayList<>(ordered.size());
        int skipped = 0;
        for (Rule r : ordered) {
            CompiledRule cr = CompiledRule.of(r);
            if (cr == null) skipped++;
            else compiled.add(cr);
        }
        NumberTrie known = new NumberTrie(config.knownExact(), config.knownPrefix());
        return new RuleEngine(config, compiled.toArray(new CompiledRule[0]), known, skipped);
    }

    public RulesConfig config() {
        return config;
    }

    /** Number of enabled rules dropped at compile time (see {@link #compile(RulesConfig)}). */
    public int skippedRules() {
        return skippedRules;
    }

    /**
     * Evaluates one message into {@code out} (reset first). Never throws: any internal error yields ALLOW with
     * {@link ClampReason#ENGINE_ERROR}.
     */
    public void evaluate(MatchInput in, GuardState guards, Decision out) {
        out.reset();
        if (in == null) {
            out.source = Decision.Source.ERROR;
            out.clampReason = ClampReason.ENGINE_ERROR;
            return;
        }
        GuardState g = guards == null ? Guards.NOMINAL : guards;
        try {
            CompiledRule deciding = null;
            for (CompiledRule cr : rules) {
                if (cr.matches(in, out, knownOriginators)) {
                    deciding = cr;
                    break;
                }
            }

            ClassId classId = in.classId;
            if (deciding != null) {
                out.action = deciding.rule.action();
                out.source = Decision.Source.RULE;
                out.hasRule = true;
                out.ruleId = deciding.rule.id();
                out.ruleOrigin = deciding.rule.origin();
            } else if (classId != null && classActionSet[classId.ordinal()]) {
                out.action = classActions[classId.ordinal()];
                out.source = Decision.Source.CLASS_ACTION;
            } else {
                out.action = Verdict.ALLOW;
                out.source = Decision.Source.DEFAULT;
            }

            applyClamps(in, g, deciding, out);
        } catch (Throwable t) {
            out.reset();
            out.source = Decision.Source.ERROR;
            out.action = Verdict.ALLOW;
            out.clampReason = ClampReason.ENGINE_ERROR;
        }
    }

    private void applyClamps(MatchInput in, GuardState g, CompiledRule deciding, Decision out) {
        Verdict pre = out.action;
        ClassId classId = in.classId;
        boolean rule = deciding != null;
        boolean senderPortOrText = rule && deciding.senderPortOrText;
        ClampReason reason = null;

        // (1) the action must be allowed for the class (class action) or by this rule (included).
        int mask = rule
                ? EnforcementMatrix.ruleActionMask(classId, senderPortOrText)
                : EnforcementMatrix.classActionMask(classId);
        if (rule && (pre == Verdict.BLOCK || pre == Verdict.QUARANTINE) && classId == ClassId.NORMAL
                && !senderPortOrText) {
            reason = ClampReason.BLOCKS_ALL_NORMAL;
        } else if (!EnforcementMatrix.allows(mask, pre)) {
            reason = ClampReason.ACTION_NOT_ALLOWED;
        }

        // (2) an emergency cell broadcast is never held back or alerted on. The flag decides, whatever class the
        // caller predicted, so a wrong prediction cannot take the guard away.
        if (reason == null && in.cbEmergency && pre != Verdict.ALLOW) {
            reason = ClampReason.EMERGENCY_CB;
        }

        // (3) SUPL provisioning stays untouched during an emergency call and its grace period. SUPL INIT comes as
        // WAP push (CL-WAP-SUPL) or on SMS port 7275 (CL-LOC-SUPL); both can carry the emergency location fix.
        if (reason == null && (classId == ClassId.WAP_SUPL || classId == ClassId.LOC_SUPL) && pre != Verdict.ALLOW
                && (g.emergencyCallActive() || inSuplGrace(g))) {
            reason = ClampReason.SUPL_EMERGENCY;
        }

        // (4) safe mode turns BLOCK/QUARANTINE into ALLOW; NOTIFY survives.
        if (reason == null && g.safeMode() && (pre == Verdict.BLOCK || pre == Verdict.QUARANTINE)) {
            reason = ClampReason.SAFE_MODE;
        }

        // (5) Firewall unavailable or disabled for this build: same clamp.
        if (reason == null && (!g.firewallAvailable() || !g.firewallEnabled())
                && (pre == Verdict.BLOCK || pre == Verdict.QUARANTINE)) {
            reason = ClampReason.FIREWALL_OFF;
        }

        if (reason != null) {
            out.clampedFrom = pre;
            out.clampReason = reason;
            out.action = Verdict.ALLOW;
        }
    }

    private boolean inSuplGrace(GuardState g) {
        long ended = g.emergencyCallEndedAtMs();
        if (ended < 0) return false;
        long now = g.nowMs();
        long elapsed = now - ended;
        return elapsed >= 0 && elapsed < suplGraceMs;
    }

    /** One rule with its conditions pre-normalised and its regex compiled. */
    private static final class CompiledRule {
        final Rule rule;
        final ClassId[] classes;
        final Direction direction;
        final Boolean originatorKnown;
        final StringMatch.Op originOp;
        final char[] originValue;
        final SafeRegex originRegex;
        final StringMatch.Op smscOp;
        final char[] smscValue;
        final StringMatch.Op contentTypeOp;
        final char[] contentTypeValue;
        final PortRange port;
        final Integer slot;
        final Boolean roaming;
        final char[] textLower;
        final String[] subtypes;
        final boolean senderPortOrText;

        private CompiledRule(Rule rule, ClassId[] classes, Direction direction, Boolean originatorKnown,
                StringMatch.Op originOp, char[] originValue, SafeRegex originRegex, StringMatch.Op smscOp,
                char[] smscValue, StringMatch.Op contentTypeOp, char[] contentTypeValue, PortRange port, Integer slot,
                Boolean roaming, char[] textLower, String[] subtypes) {
            this.senderPortOrText = rule.when().hasSenderPortOrText();
            this.rule = rule;
            this.classes = classes;
            this.direction = direction;
            this.originatorKnown = originatorKnown;
            this.originOp = originOp;
            this.originValue = originValue;
            this.originRegex = originRegex;
            this.smscOp = smscOp;
            this.smscValue = smscValue;
            this.contentTypeOp = contentTypeOp;
            this.contentTypeValue = contentTypeValue;
            this.port = port;
            this.slot = slot;
            this.roaming = roaming;
            this.textLower = textLower;
            this.subtypes = subtypes;
        }

        /** Returns {@code null} when the rule cannot be evaluated faithfully (the rule then never matches). */
        static CompiledRule of(Rule r) {
            Conditions w = r.when();
            // A port range outside 0..65535 would match messages without a port (destPort -1).
            if (w.port() != null && !w.port().isValid()) return null;
            ClassId[] classes = w.classes() == null ? null : w.classes().toArray(new ClassId[0]);
            StringMatch originator = w.originator();
            SafeRegex regex = null;
            char[] originValue = null;
            if (originator != null) {
                if (originator.op() == StringMatch.Op.REGEX) {
                    try {
                        regex = SafeRegex.compile(originator.value());
                    } catch (SafeRegex.SyntaxException e) {
                        return null;
                    }
                } else {
                    // An empty value would be a prefix of every number and an IS_NOT that holds for every sender.
                    originValue = Numbers.normalize(originator.value()).toCharArray();
                    if (originValue.length == 0) return null;
                }
            }
            StringMatch smsc = w.smsc();
            char[] smscValue = smsc == null ? null : Numbers.normalize(smsc.value()).toCharArray();
            if (smscValue != null && smscValue.length == 0) return null;
            StringMatch ct = w.contentType();
            char[] ctValue = ct == null ? null : foldAscii(ct.value());
            char[] text = w.textContains() == null ? null : foldLower(w.textContains());
            String[] subtypes = w.subtypes() == null ? null : w.subtypes().toArray(new String[0]);
            return new CompiledRule(r, classes, w.direction(), w.originatorKnown(),
                    originator == null ? null : originator.op(), originValue, regex,
                    smsc == null ? null : smsc.op(), smscValue,
                    ct == null ? null : ct.op(), ctValue,
                    w.port(), w.slot(), w.roaming(), text, subtypes);
        }

        boolean matches(MatchInput in, Decision out, NumberTrie knownOriginators) {
            if (classes != null) {
                if (in.classId == null) return false;
                boolean found = false;
                for (ClassId c : classes) {
                    if (c == in.classId) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            if (subtypes != null) {
                // Exact match. No subtype (unknown, or the payload does not send one) never matches.
                if (in.subtype == null) return false;
                boolean found = false;
                for (String st : subtypes) {
                    if (in.subtype.equals(st)) {
                        found = true;
                        break;
                    }
                }
                if (!found) return false;
            }
            if (direction != null && in.direction != direction) return false;
            if (slot != null && in.slot != slot) return false;
            if (roaming != null && in.roaming != roaming) return false;
            if (port != null && (in.destPort < port.from() || in.destPort > port.to())) return false;

            if (originatorKnown != null) {
                boolean known = in.origNormLen >= 0 && knownOriginators.contains(in.origNorm, in.origNormLen);
                if (known != originatorKnown) return false;
            }

            if (originOp != null) {
                if (in.originator == null) return false;
                switch (originOp) {
                    case IS:
                        if (!Numbers.equalsFold(in.origNorm, in.origNormLen, originValue)) return false;
                        break;
                    case IS_NOT:
                        if (Numbers.equalsFold(in.origNorm, in.origNormLen, originValue)) return false;
                        break;
                    case PREFIX:
                        if (!Numbers.startsWithFold(in.origNorm, in.origNormLen, originValue)) return false;
                        break;
                    case REGEX:
                        // One budget for all sender patterns of the message: when it is used up, the rest count as
                        // no match, the same as a pattern that reaches its own step limit.
                        int left = SafeRegex.MESSAGE_STEP_BUDGET - out.regexSteps;
                        int r = left > 0 ? originRegex.find(in.originator, left) : SafeRegex.ABORTED;
                        if (left > 0) out.regexSteps += SafeRegex.lastSteps();
                        if (r == SafeRegex.ABORTED) out.regexAborts++;
                        if (r != SafeRegex.MATCH) return false;
                        break;
                    default:
                        return false;
                }
            }

            if (smscOp != null) {
                if (in.smsc == null) return false;
                switch (smscOp) {
                    case IS:
                        if (!Numbers.equalsFold(in.smscNorm, in.smscNormLen, smscValue)) return false;
                        break;
                    case PREFIX:
                        if (!Numbers.startsWithFold(in.smscNorm, in.smscNormLen, smscValue)) return false;
                        break;
                    default:
                        return false;
                }
            }

            if (contentTypeOp != null) {
                if (in.contentType == null) return false;
                switch (contentTypeOp) {
                    case IS:
                        if (!Numbers.equalsFold(in.contentType, contentTypeValue)) return false;
                        break;
                    case PREFIX:
                        if (!Numbers.startsWithFold(in.contentType, contentTypeValue)) return false;
                        break;
                    default:
                        return false;
                }
            }

            if (textLower != null) {
                out.textEvaluated = true;
                CharSequence text = in.text();
                if (text == null || !Numbers.containsIgnoreCase(text, textLower)) return false;
            }
            return true;
        }

        private static char[] foldLower(String s) {
            char[] out = new char[s.length()];
            for (int i = 0; i < out.length; i++) out[i] = Character.toLowerCase(s.charAt(i));
            return out;
        }

        /** Content types compare ASCII case-insensitively (Conditions), so the value uses the same fold. */
        private static char[] foldAscii(String s) {
            char[] out = new char[s.length()];
            for (int i = 0; i < out.length; i++) out[i] = Numbers.fold(s.charAt(i));
            return out;
        }
    }
}
