package dev.nightjar.app.domain.protection

import dev.nightjar.contract.ProtectionState

/**
 * Why the protection state is what it is (copy is chosen from this). A failed firewall tier keeps
 * the state PROTECTED, so it has no reason of its own (SAFE_MODE_FLAG, FIREWALL_TIER_FAILED and
 * FIREWALL_DISABLED were removed because the engine never gave them).
 */
enum class ProtectionReason {
    NONE,
    NO_HANDSHAKE_THIS_BOOT,
    CAPTURE_TIER_FAILED,
}

/** Build-profile match against `profiles.json`. */
enum class ProfileStatus { KNOWN, UNKNOWN, MISMATCH }

/**
 * The last handshake from the payload (`hello`), reduced to what the state machine needs.
 *
 * The tier flags are the decision of the payload, not a value that the app derives from the hook list again.
 *
 * @param captureTierLive the `captureLive` value of the payload hook health, or the TierManager rule of the payload
 *     (HK-06, HK-04 and one of HK-01/HK-01b) when an older payload does not send it
 * @param firewallTierLive the `firewallAvailable` value of the payload (HK-24 + HK-14 + HK-15). A `firewall-extra` hook
 *     that is not found on a build does not make it false.
 */
data class HandshakeSnapshot(
    val bootCount: Int?,
    val payloadVersion: String,
    val buildFingerprint: String?,
    val profile: ProfileStatus,
    val captureTierLive: Boolean,
    val firewallTierLive: Boolean,
    /** The firewall is on (`firewallEnabled` in the payload `hello` reply, set by the control call `ENABLE_FIREWALL`). */
    val firewallEnabled: Boolean,
    val receivedAtWallMs: Long,
    /** First firewall-tier hook that is not LIVE, for the Health screen. */
    val failedFirewallHook: String? = null,
)

/** Everything the state machine reads. */
data class ProtectionInputs(
    val handshake: HandshakeSnapshot?,
    /** `Settings.Global.BOOT_COUNT` as read by the app; null when unavailable. */
    val currentBootCount: Int?,
    /** The user's safe mode setting: blocking pauses, the state stays PROTECTED. */
    val userSafeMode: Boolean = false,
)

/**
 * The result: the state the UI shows, why, and the two derived flags the rest of the app needs.
 *
 * @param blockingPaused true for user safe mode only: the state stays PROTECTED so a user choice is
 *     distinguishable from a failure, and the hero shows the "Blocking paused" badge (MSG-ST-PAUSED)
 * @param firewallAvailable false when blocking cannot work on this build at all (-07)
 */
data class ProtectionReport(
    val state: ProtectionState,
    val reason: ProtectionReason,
    val blockingPaused: Boolean,
    val firewallAvailable: Boolean,
    val firewallEnabled: Boolean,
    val failedHook: String? = null,
) {
    /**
     * The user turned off the Blocking switch. Blocking can work on this build, but the payload does not
     * block. Safe mode wins: [blockingPaused] has its own text.
     */
    val blockingOff: Boolean
        get() = state == ProtectionState.PROTECTED && !blockingPaused && firewallAvailable && !firewallEnabled

    val needsBanner: Boolean
        get() = state == ProtectionState.SAFE_MODE || state == ProtectionState.NOT_ACTIVE || state == ProtectionState.UNSUPPORTED
}

/**
 * The protection-state engine.
 *
 * Order matters and is tested table-driven: a missing or stale handshake is NOT_ACTIVE; a failed capture tier
 * is UNSUPPORTED; otherwise PROTECTED. A failed firewall tier and the user's safe mode stay PROTECTED:
 * the first shows on Health, the second pauses blocking. The engine never gives MONITOR_ONLY or SAFE_MODE.
 */
object ProtectionStateEngine {
    /**
     * A handshake only counts for the boot it came from (`BOOT_COUNT`).
     *
     * Both counts known: they must match. Neither known (a device or emulator without `BOOT_COUNT`): the session is in
     * memory of this process, which cannot outlive a reboot, so it counts. Only one known: the boots cannot be shown to
     * be the same, so it does not count. [HandshakeStore.restorable] is stricter because a saved snapshot can be from
     * an earlier boot.
     */
    fun isHandshakeCurrent(
        handshake: HandshakeSnapshot?,
        currentBootCount: Int?,
    ): Boolean {
        if (handshake == null) return false
        val handshakeBoot = handshake.bootCount
        if (handshakeBoot == null && currentBootCount == null) return true
        return handshakeBoot != null && currentBootCount != null && handshakeBoot == currentBootCount
    }

    fun evaluate(inputs: ProtectionInputs): ProtectionReport {
        val handshake = inputs.handshake
        val current = isHandshakeCurrent(handshake, inputs.currentBootCount)
        val firewallAvailable =
            current &&
                handshake != null &&
                handshake.captureTierLive &&
                handshake.firewallTierLive

        val live = if (current) handshake else null
        return when {
            live == null -> {
                ProtectionReport(
                    state = ProtectionState.NOT_ACTIVE,
                    reason = ProtectionReason.NO_HANDSHAKE_THIS_BOOT,
                    blockingPaused = false,
                    firewallAvailable = false,
                    firewallEnabled = false,
                )
            }

            !live.captureTierLive -> {
                ProtectionReport(
                    state = ProtectionState.UNSUPPORTED,
                    reason = ProtectionReason.CAPTURE_TIER_FAILED,
                    blockingPaused = false,
                    firewallAvailable = false,
                    firewallEnabled = false,
                )
            }

            // Capture is live: the app is protecting. A partly unavailable blocking tier is a detail for the Health
            // screen, not a state that puts a warning on every screen.
            else -> {
                ProtectionReport(
                    state = ProtectionState.PROTECTED,
                    reason = ProtectionReason.NONE,
                    blockingPaused = inputs.userSafeMode,
                    firewallAvailable = firewallAvailable,
                    // The payload blocks only after the control call ENABLE_FIREWALL.
                    firewallEnabled = live.firewallEnabled,
                    failedHook = live.failedFirewallHook,
                )
            }
        }
    }

    /** The boot check runs 120 s after `BOOT_COMPLETED`. */
    const val BOOT_CHECK_DELAY_MS = 120_000L
}
