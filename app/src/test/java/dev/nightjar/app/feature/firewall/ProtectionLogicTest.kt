package dev.nightjar.app.feature.firewall

import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.ui.components.NjAction
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Direction
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.Decision
import dev.nightjar.rules.EnforcementMatrix
import dev.nightjar.rules.Guards
import dev.nightjar.rules.MatchInput
import dev.nightjar.rules.Preset
import dev.nightjar.rules.Presets
import dev.nightjar.rules.RuleEngine
import dev.nightjar.rules.RulesConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The action control of each type offers only the actions that [EnforcementMatrix] allows. the
 * "Notify me" switch and the preset that the screen shows.
 */
class ProtectionLogicTest {
    @Test
    fun `every type in the registry offers only the actions the matrix allows`() {
        ClassRegistry.all().forEach { profile ->
            val allowed = EnforcementMatrix.allowedClassActions(profile.classId)
            val enabled = enabledActions(allowed)
            NjAction.entries.forEach { action ->
                assertEquals(
                    action.toVerdict() in allowed,
                    action in enabled,
                    "${profile.classId} $action",
                )
            }
            assertTrue(NjAction.ALLOW in enabled, "${profile.classId} must always offer Allow")
        }
    }

    @Test
    fun `every card built from the rows enables only matrix actions`() {
        val cards = protectionCards(emptyMap())
        assertTrue(cards.isNotEmpty())
        cards.forEach { card ->
            val allowed = EnforcementMatrix.allowedClassActions(card.classId)
            card.enabled.forEach { action ->
                assertTrue(action.toVerdict() in allowed, "${card.classId} offers $action")
            }
            assertTrue(card.selected in card.enabled, "${card.classId} selected segment is on")
            assertEquals(ClassRegistry.of(card.classId).family, card.family)
        }
    }

    @Test
    fun `the cards cover every registry type that can leave Allow, except emergency broadcast`() {
        val ids = protectionCards(emptyMap()).map { it.classId }.toSet()
        ClassRegistry.all().forEach { profile ->
            val canChange =
                EnforcementMatrix
                    .allowedClassActions(profile.classId)
                    .any { it != Verdict.ALLOW }
            val listed = profile.classId in ids
            val inbound = profile.direction == Direction.IN
            assertEquals(canChange && inbound && profile.classId != ClassId.CB, listed, "${profile.classId}")
        }
    }

    @Test
    fun `the protection screen offers exactly what the payload enforces, for every class`() {
        // The payload compiles the snapshot with RuleEngine.compile(config) (RulesHolder). An action that
        // the app offers must reach the payload unclamped, and an action that the app hides must be clamped there.
        val rows = firewallRows(emptyMap()).associateBy { it.classId }
        ClassId.entries.forEach { classId ->
            val offered =
                rows[classId]?.allowedActions
                    ?: EnforcementMatrix.allowedClassActions(classId).toList()
            Verdict.entries.forEach { verdict ->
                val config =
                    RulesConfig(1, 0, Preset.CUSTOM, mapOf(classId to verdict), emptyList(), emptyList(), emptyList(), 5)
                val decision = Decision()
                RuleEngine.compile(config).evaluate(MatchInput().classId(classId), Guards.NOMINAL, decision)
                val enforced = decision.clampReason() == null && decision.action() == verdict
                assertEquals(verdict in offered, enforced, "$classId $verdict")
            }
        }
    }

    @Test
    fun `the types that no hook can stop offer no Hold and no Block and say why`() {
        val notEnforced =
            listOf(ClassId.VVM, ClassId.MISSED_CALL, ClassId.APP_TOKEN, ClassId.CARRIER, ClassId.RCS_PROV, ClassId.OEM)
        val cards = protectionCards(emptyMap()).associateBy { it.classId }
        notEnforced.forEach { classId ->
            assertTrue(EnforcementMatrix.noOwnEnforcementPoint(classId), "$classId")
            val card = cards.getValue(classId)
            assertEquals(setOf(NjAction.ALLOW), card.enabled, "$classId")
            assertEquals(ActionReason.NOT_ENFORCED, card.reason, "$classId")
            assertTrue(card.canNotify, "$classId keeps the bell")
            listOf(true, false).forEach { narrow ->
                assertEquals(
                    setOf(Verdict.ALLOW, Verdict.NOTIFY),
                    EnforcementMatrix.allowedRuleActions(classId, narrow),
                    "$classId rule, narrowing condition $narrow",
                )
            }
        }
        cards.values.filter { it.classId !in notEnforced }.forEach { card ->
            assertTrue(card.reason != ActionReason.NOT_ENFORCED, "${card.classId}")
        }
    }

    @Test
    fun `a tap on a segment that the matrix does not allow writes nothing`() {
        ClassRegistry.all().forEach { profile ->
            val allowed = EnforcementMatrix.allowedClassActions(profile.classId).toList()
            val row = FirewallRow(profile.classId, Verdict.ALLOW, allowed)
            NjAction.entries.forEach { tapped ->
                val verdict = verdictForTap(row, tapped)
                if (verdict != null) {
                    assertTrue(verdict in allowed, "${profile.classId} $tapped wrote $verdict")
                } else {
                    assertTrue(tapped == NjAction.ALLOW || tapped.toVerdict() !in allowed, "${profile.classId} $tapped")
                }
            }
        }
    }

    @Test
    fun `a tap on the selected segment writes nothing and a tap on another allowed segment writes its verdict`() {
        val row = FirewallRow(ClassId.WAP_CP, Verdict.QUARANTINE, Verdict.entries.toList())
        assertNull(verdictForTap(row, NjAction.HOLD))
        assertEquals(Verdict.BLOCK, verdictForTap(row, NjAction.BLOCK))
        assertEquals(Verdict.ALLOW, verdictForTap(row, NjAction.ALLOW))
    }

    @Test
    fun `allow and allow and notify share the Allow segment and a tap on it keeps notify`() {
        val row = FirewallRow(ClassId.SILENT, Verdict.NOTIFY, listOf(Verdict.ALLOW, Verdict.NOTIFY))
        assertEquals(NjAction.ALLOW, Verdict.NOTIFY.toNjAction())
        assertNull(verdictForTap(row, NjAction.ALLOW))
        assertNull(verdictForTap(row, NjAction.HOLD))
        assertNull(verdictForTap(row, NjAction.BLOCK))
    }

    @Test
    fun `the reason says why an action is off`() {
        assertNull(actionReason(NjAction.entries.toSet()))
        assertEquals(ActionReason.LOCKED, actionReason(setOf(NjAction.ALLOW)))
        assertEquals(ActionReason.NO_HOLD, actionReason(setOf(NjAction.ALLOW, NjAction.BLOCK)))
        assertEquals(ActionReason.NO_BLOCK, actionReason(setOf(NjAction.ALLOW, NjAction.HOLD)))
    }

    @Test
    fun `the reason of each card matches its disabled segments`() {
        protectionCards(emptyMap()).forEach { card ->
            val missing = NjAction.entries.toSet() - card.enabled
            assertEquals(missing.isEmpty(), card.reason == null, "${card.classId}")
            if (card.reason == ActionReason.NO_HOLD) assertFalse(NjAction.HOLD in card.enabled)
            if (card.reason == ActionReason.NO_BLOCK) assertFalse(NjAction.BLOCK in card.enabled)
        }
    }

    @Test
    fun `the verdict and segment mapping round trips for the three segments`() {
        NjAction.entries.forEach { assertEquals(it, it.toVerdict().toNjAction()) }
    }

    @Test
    fun `the notify switch maps on to NOTIFY and off to ALLOW`() {
        assertEquals(Verdict.NOTIFY, allowVerdict(notify = true))
        assertEquals(Verdict.ALLOW, allowVerdict(notify = false))
        assertEquals(NjAction.ALLOW, allowVerdict(notify = true).toNjAction())
        assertEquals(NjAction.ALLOW, allowVerdict(notify = false).toNjAction())
    }

    @Test
    fun `turning notify on or off writes NOTIFY or ALLOW, and the same value writes nothing`() {
        val allowed = listOf(Verdict.ALLOW, Verdict.NOTIFY)
        val allow = FirewallRow(ClassId.SILENT, Verdict.ALLOW, allowed)
        val notify = FirewallRow(ClassId.SILENT, Verdict.NOTIFY, allowed)
        assertEquals(Verdict.NOTIFY, verdictForNotify(allow, notify = true))
        assertNull(verdictForNotify(allow, notify = false))
        assertEquals(Verdict.ALLOW, verdictForNotify(notify, notify = false))
        assertNull(verdictForNotify(notify, notify = true))
    }

    @Test
    fun `the notify switch writes nothing when the action is Hold or Block or the matrix has no NOTIFY`() {
        val all = Verdict.entries.toList()
        assertNull(verdictForNotify(FirewallRow(ClassId.WAP_CP, Verdict.QUARANTINE, all), notify = true))
        assertNull(verdictForNotify(FirewallRow(ClassId.WAP_CP, Verdict.BLOCK, all), notify = true))
        assertNull(verdictForNotify(FirewallRow(ClassId.WAP_CP, Verdict.QUARANTINE, all), notify = false))
        val noNotify = FirewallRow(ClassId.WAP_CP, Verdict.ALLOW, listOf(Verdict.ALLOW, Verdict.QUARANTINE))
        assertNull(verdictForNotify(noNotify, notify = true))
    }

    @Test
    fun `the notify switch never writes a verdict the matrix does not allow, for every registry type`() {
        ClassRegistry.all().forEach { profile ->
            val allowed = EnforcementMatrix.allowedClassActions(profile.classId).toList()
            allowed.forEach { current ->
                val row = FirewallRow(profile.classId, current, allowed)
                listOf(true, false).forEach { on ->
                    val verdict = verdictForNotify(row, on)
                    if (verdict != null) {
                        assertTrue(verdict in allowed, "${profile.classId} $current $on wrote $verdict")
                        assertEquals(NjAction.ALLOW, current.toNjAction(), "${profile.classId} $current $on")
                    }
                }
            }
        }
    }

    @Test
    fun `the notify switch shows only on Allow and only when the matrix allows NOTIFY`() {
        Verdict.entries.forEach { verdict ->
            val actions = ClassRegistry.all().associate { it.classId to verdict }
            protectionCards(actions).forEach { card ->
                val allowed = EnforcementMatrix.allowedClassActions(card.classId)
                assertEquals(Verdict.NOTIFY in allowed, card.canNotify, "${card.classId}")
                assertEquals(
                    Verdict.NOTIFY in allowed && card.selected == NjAction.ALLOW,
                    card.showNotify,
                    "${card.classId} $verdict",
                )
                assertEquals(verdict == Verdict.NOTIFY, card.notify, "${card.classId} $verdict")
                // And M6: the bell shows for each type that the matrix lets notify, on Allow and Hold.
                // A type set to Block has no bell.
                assertEquals(
                    Verdict.NOTIFY in allowed && card.selected != NjAction.BLOCK,
                    card.showBell,
                    "${card.classId} $verdict",
                )
                // The drawn bell: it can change only on Allow, and it is on only for NOTIFY (never on Hold or Block).
                if (card.showBell) {
                    assertEquals(card.selected == NjAction.ALLOW, card.showNotify, "${card.classId} $verdict")
                    if (card.selected != NjAction.ALLOW) assertFalse(card.notify, "${card.classId} $verdict")
                }
            }
        }
    }

    @Test
    fun `a tap on Allow from Hold writes ALLOW, so the notify switch starts off`() {
        val row = FirewallRow(ClassId.SILENT, Verdict.QUARANTINE, Verdict.entries.toList())
        assertEquals(Verdict.ALLOW, verdictForTap(row, NjAction.ALLOW))
    }

    @Test
    fun `a stored preset shows as long as the class actions match it`() {
        assertEquals(PresetId.MONITOR, effectivePreset(PresetId.MONITOR, emptyMap()))
        assertEquals(
            PresetId.RECOMMENDED,
            effectivePreset(PresetId.RECOMMENDED, Presets.classActions(Preset.RECOMMENDED)),
        )
        assertEquals(PresetId.STRICT, effectivePreset(PresetId.STRICT, Presets.classActions(Preset.STRICT)))
        assertEquals(
            PresetId.QUARANTINE,
            effectivePreset(PresetId.QUARANTINE, Presets.classActions(Preset.QUARANTINE)),
        )
        assertEquals(PresetId.MONITOR, effectivePreset(PresetId.MONITOR, mapOf(ClassId.WAP_CP to Verdict.ALLOW)))
    }

    @Test
    fun `an unreadable SMS row shows the action that the payload applies`() {
        // A snapshot from before CL-UNREADABLE-SMS has no entry for it. The payload holds it, so the row
        // shows Hold and the stored preset still shows.
        val rec = Presets.apply(Preset.RECOMMENDED, RulesConfig.DEFAULT)
        val actions = rec.classActions - ClassId.UNREADABLE_SMS
        val old = RulesConfig(rec.schema, rec.revision, rec.preset, actions, rec.rules, null, null, 5)
        val resolved = resolvedClassActions(old)
        assertEquals(Verdict.QUARANTINE, resolved[ClassId.UNREADABLE_SMS])
        assertEquals(PresetId.RECOMMENDED, effectivePreset(PresetId.RECOMMENDED, resolved))
        val row = firewallRows(resolved).first { it.classId == ClassId.UNREADABLE_SMS }
        assertEquals(Verdict.QUARANTINE, row.action)
        assertEquals(Verdict.entries.toSet(), row.allowedActions.toSet())
        assertEquals(NjAction.HOLD, row.toCard().selected)
        assertTrue(row.toCard().canNotify)
        // Monitor holds nothing. A stored ALLOW shows Allow.
        assertEquals(Verdict.ALLOW, resolvedClassActions(RulesConfig.DEFAULT)[ClassId.UNREADABLE_SMS])
        assertEquals(PresetId.MONITOR, effectivePreset(PresetId.MONITOR, resolvedClassActions(RulesConfig.DEFAULT)))
        val allowed = RulesConfig.DEFAULT.withClassAction(ClassId.UNREADABLE_SMS, Verdict.ALLOW)
        assertEquals(Verdict.ALLOW, resolvedClassActions(allowed)[ClassId.UNREADABLE_SMS])
        val blocked = allowed.withClassAction(ClassId.UNREADABLE_SMS, Verdict.BLOCK)
        assertEquals(Verdict.BLOCK, resolvedClassActions(blocked)[ClassId.UNREADABLE_SMS])
    }

    @Test
    fun `class actions that differ from every preset show Custom`() {
        assertEquals(PresetId.CUSTOM, effectivePreset(PresetId.CUSTOM, emptyMap()))
        assertEquals(PresetId.CUSTOM, effectivePreset(PresetId.MONITOR, mapOf(ClassId.WAP_CP to Verdict.QUARANTINE)))
        assertEquals(PresetId.CUSTOM, effectivePreset(PresetId.RECOMMENDED, mapOf(ClassId.SILENT to Verdict.NOTIFY)))
        // Recommended has class actions, so an empty map is not Recommended any more.
        assertEquals(PresetId.CUSTOM, effectivePreset(PresetId.RECOMMENDED, emptyMap()))
        val strictChanged = Presets.classActions(Preset.STRICT) + (ClassId.WAP_CP to Verdict.QUARANTINE)
        assertEquals(PresetId.CUSTOM, effectivePreset(PresetId.STRICT, strictChanged))
        assertEquals(PresetId.CUSTOM, effectivePreset(PresetId.STRICT, emptyMap()))
    }

    @Test
    fun `the preset id mapping round trips`() {
        PresetId.entries.forEach { assertEquals(it, it.toRulesPreset().toPresetId()) }
        Preset.entries.forEach { assertEquals(it, it.toPresetId().toRulesPreset()) }
    }
}
