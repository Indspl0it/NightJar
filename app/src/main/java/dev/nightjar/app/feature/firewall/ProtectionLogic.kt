package dev.nightjar.app.feature.firewall

import dev.nightjar.app.domain.ClassFamily
import dev.nightjar.app.domain.ClassRegistry
import dev.nightjar.app.ui.components.NjAction
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.EnforcementMatrix
import dev.nightjar.rules.Preset
import dev.nightjar.rules.Presets
import dev.nightjar.rules.RulesConfig

/** Why a type does not offer every action. A tap on the type name shows it as one line of text. */
enum class ActionReason {
    /** Allow is the only action, and the type has no bell ("Notify me"). */
    LOCKED,

    /** Allow is the only segment, but the bell ("Notify me") can add a notification (the AN types). */
    ALLOW_OR_NOTIFY,

    /** Hold is not available, Block may be. */
    NO_HOLD,

    /** Block is not available, Hold may be. */
    NO_BLOCK,

    /** No hook can hold or block the type ([EnforcementMatrix.noOwnEnforcementPoint]). */
    NOT_ENFORCED,
}

/** One type card on Protection: the selected action and the actions the control may offer. */
data class ProtectionTypeCard(
    val classId: ClassId,
    val family: ClassFamily,
    val selected: NjAction,
    val enabled: Set<NjAction>,
    val reason: ActionReason?,
    /** True when the action is "Allow and notify" (NOTIFY): the "Notify me" switch is on. */
    val notify: Boolean = false,
    /** True when the matrix allows NOTIFY for this type. */
    val canNotify: Boolean = false,
) {
    /** "Notify me" (the bell) can change only on Allow, and only when the matrix allows NOTIFY. */
    val showNotify: Boolean get() = canNotify && selected == NjAction.ALLOW

    /**
     * The row shows the bell for each type that can notify, except when the type is set to Block (no
     * bell on a blocked type). The bell is on for NOTIFY. On Hold the bell
     * shows off and cannot change ([showNotify] is false).
     */
    val showBell: Boolean get() = canNotify && selected != NjAction.BLOCK
}

/**
 * The control has three segments and the contract has four verdicts. Allow and "Allow and notify" share the Allow
 * segment, and the "Notify me" switch on the card tells them apart. Hold is QUARANTINE.
 */
fun Verdict.toNjAction(): NjAction =
    when (this) {
        Verdict.ALLOW, Verdict.NOTIFY -> NjAction.ALLOW
        Verdict.QUARANTINE -> NjAction.HOLD
        Verdict.BLOCK -> NjAction.BLOCK
    }

/** The verdict that a tap on [this] segment writes. A tap on Allow writes ALLOW: the "Notify me" switch starts off. */
fun NjAction.toVerdict(): Verdict =
    when (this) {
        NjAction.ALLOW -> Verdict.ALLOW
        NjAction.HOLD -> Verdict.QUARANTINE
        NjAction.BLOCK -> Verdict.BLOCK
    }

/** The segments to enable for a type that accepts [allowed] (the matrix answer, never a local copy). */
fun enabledActions(allowed: Collection<Verdict>): Set<NjAction> = allowed.mapTo(HashSet()) { it.toNjAction() }

/**
 * The reason line for the segments that [enabled] leaves out, or null when all three are on. [canNotify] is true when
 * the card shows "Notify me", so an Allow-only type does not say that Allow is its only action next to that switch.
 */
fun actionReason(
    enabled: Set<NjAction>,
    canNotify: Boolean = false,
): ActionReason? =
    when {
        enabled.size >= NjAction.entries.size -> null
        enabled.size <= 1 -> if (canNotify) ActionReason.ALLOW_OR_NOTIFY else ActionReason.LOCKED
        NjAction.HOLD !in enabled -> ActionReason.NO_HOLD
        else -> ActionReason.NO_BLOCK
    }

/** The card of one by-type row. The family comes from [ClassRegistry]. */
fun FirewallRow.toCard(): ProtectionTypeCard {
    val enabled = enabledActions(allowedActions)
    val canNotify = Verdict.NOTIFY in allowedActions
    val notEnforced = EnforcementMatrix.noOwnEnforcementPoint(classId)
    return ProtectionTypeCard(
        classId = classId,
        family = ClassRegistry.of(classId).family,
        selected = action.toNjAction(),
        enabled = enabled,
        reason = if (notEnforced) ActionReason.NOT_ENFORCED else actionReason(enabled, canNotify),
        notify = action == Verdict.NOTIFY,
        canNotify = canNotify,
    )
}

/**
 * The action of every class as the engine applies it. The snapshot does not store ALLOW for most classes,
 * and a snapshot without an entry for CL-UNREADABLE-SMS holds it outside Monitor ([RulesConfig.absentClassAction]). The
 * rows and the preset check read this map, so the screen shows the action that the payload applies.
 */
fun resolvedClassActions(config: RulesConfig): Map<ClassId, Verdict> =
    ClassId.entries.associateWith { config.classAction(it) }

/** The cards for the screen: one for each row, in the order of the registry. */
fun protectionCards(actions: Map<ClassId, Verdict>): List<ProtectionTypeCard> = firewallRows(actions).map { it.toCard() }

/**
 * The verdict to write when the user taps [tapped] on [row], or null when nothing must change: the segment is off, is
 * already selected, or the matrix does not allow the verdict. A tap on Allow when the action is NOTIFY writes nothing,
 * so "Notify me" stays on.
 */
fun verdictForTap(
    row: FirewallRow,
    tapped: NjAction,
): Verdict? {
    val verdict = tapped.toVerdict()
    return when {
        verdict !in row.allowedActions -> null
        tapped == row.action.toNjAction() -> null
        else -> verdict
    }
}

/** The verdict of the Allow segment: NOTIFY when "Notify me" is on, ALLOW when it is off. */
fun allowVerdict(notify: Boolean): Verdict = if (notify) Verdict.NOTIFY else Verdict.ALLOW

/**
 * The verdict to write when the user sets "Notify me" on [row] to [notify], or null when nothing must change: the
 * action is not Allow, the switch already has that value, or the matrix does not allow the verdict.
 */
fun verdictForNotify(
    row: FirewallRow,
    notify: Boolean,
): Verdict? {
    val verdict = allowVerdict(notify)
    return when {
        row.action.toNjAction() != NjAction.ALLOW -> null
        verdict !in row.allowedActions -> null
        verdict == row.action -> null
        else -> verdict
    }
}

/**
 * The preset to show for the [stored] preset name and the class [actions] of the snapshot. The repository writes CUSTOM
 * when the user changes one type. If the class actions differ from those of the stored preset all the same, the screen
 * shows Custom. Each preset has its own class actions (Monitor none, Recommended the default set,
 * Strict and Quarantine more holds and blocks), so this check also finds a stored name that does not fit
 * the actions.
 */
fun effectivePreset(
    stored: PresetId,
    actions: Map<ClassId, Verdict>,
): PresetId {
    if (stored == PresetId.CUSTOM) return PresetId.CUSTOM
    val expected = Presets.classActions(stored.toRulesPreset()).filterValues { it != Verdict.ALLOW }
    val actual = actions.filterValues { it != Verdict.ALLOW }
    return if (actual == expected) stored else PresetId.CUSTOM
}

internal fun Preset.toPresetId(): PresetId =
    when (this) {
        Preset.MONITOR -> PresetId.MONITOR
        Preset.RECOMMENDED -> PresetId.RECOMMENDED
        Preset.STRICT -> PresetId.STRICT
        Preset.QUARANTINE -> PresetId.QUARANTINE
        Preset.CUSTOM -> PresetId.CUSTOM
    }

internal fun PresetId.toRulesPreset(): Preset =
    when (this) {
        PresetId.MONITOR -> Preset.MONITOR
        PresetId.RECOMMENDED -> Preset.RECOMMENDED
        PresetId.STRICT -> Preset.STRICT
        PresetId.QUARANTINE -> Preset.QUARANTINE
        PresetId.CUSTOM -> Preset.CUSTOM
    }
