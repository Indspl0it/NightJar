package dev.nightjar.app.feature.firewall

import dev.nightjar.app.domain.protection.ProtectionReason
import dev.nightjar.contract.ClassId
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.EnforcementMatrix

/** One row of the Protection "By type" list. */
data class FirewallRow(
    val classId: ClassId,
    val action: Verdict,
    /** The actions the class action sheet may offer for this class (never a local copy). */
    val allowedActions: List<Verdict>,
)

/** Why the firewall cannot block right now (-07). */
enum class FirewallReason {
    HOOKS_FAILED,
    NOT_ACTIVE,
    SAFE_MODE_FLAG,

    /**
     * The blocking hooks work, but the user turned off blocking with the switch on this screen: the
     * report says PROTECTED with `firewallEnabled` false. Blocking is on by default; the switch turns it on
     * again.
     */
    NOT_ENABLED,
}

/** The dialogs Protection can show. */
sealed interface FirewallDialog {
    /** Turning blocking off asks first. Turning it on does not. */
    data object BlockingOffConfirm : FirewallDialog

    data class PresetReplace(
        val preset: PresetId,
    ) : FirewallDialog

    data class GuardSimUpdate(
        val pending: PendingAction,
    ) : FirewallDialog

    data class GuardConsumerClass(
        val pending: PendingAction,
        val classId: ClassId,
    ) : FirewallDialog

    data object EmergencyInfo : FirewallDialog
}

/** A change waiting for the user to confirm a guard-rail dialog. */
sealed interface PendingAction {
    data class ClassAction(
        val classId: ClassId,
        val verdict: Verdict,
    ) : PendingAction

    data class Preset(
        val preset: PresetId,
    ) : PendingAction
}

/** The three preset buttons plus the "Custom" label (01). */
enum class PresetId {
    MONITOR,
    RECOMMENDED,
    STRICT,
    QUARANTINE,
    CUSTOM,
}

/** One-shot results the screen turns into a snackbar. */
sealed interface FirewallEvent {
    data class ClassActionChanged(
        val classId: ClassId,
        val verdict: Verdict,
    ) : FirewallEvent

    data class PresetChanged(
        val preset: PresetId,
    ) : FirewallEvent

    /** The blocking choice is stored, but a running payload did not get it. The next hello sends it again. */
    data object BlockingNotSent : FirewallEvent

    /** A write failed. The screen shows one fixed text, never the exception message. */
    data object Failed : FirewallEvent
}

/** Everything Protection renders. Copy is chosen in the composable from these values. */
data class FirewallUiState(
    val loading: Boolean = true,
    val preset: PresetId = PresetId.MONITOR,
    val rows: List<FirewallRow> = emptyList(),
    val userRuleCount: Int = 0,
    val knownSenderCount: Int = 0,
    /** The user's blocking switch. On by default, also before the first value arrives. */
    val blockingOn: Boolean = true,
    val firewallAvailable: Boolean = false,
    val readOnly: Boolean = false,
    val reasons: List<FirewallReason> = emptyList(),
    val dialog: FirewallDialog? = null,
    val saving: Boolean = false,
) {
    /** The class action for [classId], ALLOW when the row is absent. */
    fun action(classId: ClassId): Verdict = rows.firstOrNull { it.classId == classId }?.action ?: Verdict.ALLOW
}

/**
 * Builds the by-type rows from the snapshot. Only classes whose class action can be something other than ALLOW are
 * listed (a row that can only say "Allow" is noise), and emergency cell broadcast is rendered separately because
 * Locks it.
 */
fun firewallRows(actions: Map<ClassId, Verdict>): List<FirewallRow> =
    dev.nightjar.app.domain.ClassRegistry
        .all()
        .filter { profile ->
            val allowed = EnforcementMatrix.allowedClassActions(profile.classId)
            profile.direction == dev.nightjar.contract.Direction.IN &&
                allowed.any { it != Verdict.ALLOW } &&
                profile.classId != ClassId.CB
        }.map { profile ->
            val allowed = EnforcementMatrix.allowedClassActions(profile.classId).toList()
            FirewallRow(
                classId = profile.classId,
                action = actions[profile.classId] ?: Verdict.ALLOW,
                allowedActions = allowed,
            )
        }

/** Maps the protection engine's reason onto the banner reasons Protection shows. */
fun firewallReason(reason: ProtectionReason?): FirewallReason? =
    when (reason) {
        ProtectionReason.CAPTURE_TIER_FAILED -> FirewallReason.HOOKS_FAILED
        ProtectionReason.NO_HANDSHAKE_THIS_BOOT -> FirewallReason.NOT_ACTIVE
        ProtectionReason.NONE, null -> null
    }
