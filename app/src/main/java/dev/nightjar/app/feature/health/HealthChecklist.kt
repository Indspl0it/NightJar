package dev.nightjar.app.feature.health

import dev.nightjar.app.domain.protection.ModuleInstallState

/**
 * One row of the Protection status checklist, in display order. There is no Build profile row and no
 * USB debugging row.
 */
enum class HealthCheckId {
    MODULE,
    MODULE_VERSION,
    ROOT,
    FRAMEWORK,
    RULES,
    HOOKS,
    EVENTS,
    STORAGE,
}

enum class HealthCheckState { OK, WARNING, FAILED }

/** What "How to fix" opens: a help text, or the help text with a button to the logs. */
enum class HealthFix { HELP, OPEN_LOGS }

data class HealthCheck(
    val id: HealthCheckId,
    val state: HealthCheckState,
) {
    val needsFix: Boolean get() = state != HealthCheckState.OK
}

/**
 * Pure decisions of the Health checklist, kept out of the screen so they can be tested without Android.
 *
 * Every value comes from [HealthUiState]; the checklist adds no new source. The hooks row is Researcher detail
 * (hook ids are internal), so it only shows in Researcher mode and only when the hook table has rows. The module
 * version row and the rules row need a payload session, so they show only with one.
 */
object HealthChecklist {
    fun evaluate(
        state: HealthUiState,
        researcher: Boolean,
    ): List<HealthCheck> =
        buildList {
            add(HealthCheck(HealthCheckId.MODULE, moduleState(state.moduleState)))
            state.payloadNewer?.let { newer -> add(entry(HealthCheckId.MODULE_VERSION, ok = !newer)) }
            add(HealthCheck(HealthCheckId.ROOT, rootState(state.rooted)))
            add(entry(HealthCheckId.FRAMEWORK, ok = state.framework, otherwise = HealthCheckState.FAILED))
            state.rules?.let { rules -> add(HealthCheck(HealthCheckId.RULES, rulesState(rules))) }
            if (researcher && state.hooksTotal > 0) {
                add(entry(HealthCheckId.HOOKS, ok = state.hooksLive == state.hooksTotal))
            }
            add(entry(HealthCheckId.EVENTS, ok = state.lostEvents <= 0))
            add(entry(HealthCheckId.STORAGE, ok = !state.showDatabaseRow))
        }

    /** The number of checks that are not OK, for the status card badge. */
    fun issueCount(checks: List<HealthCheck>): Int = checks.count { it.needsFix }

    fun fix(id: HealthCheckId): HealthFix = if (id == HealthCheckId.HOOKS) HealthFix.OPEN_LOGS else HealthFix.HELP

    /**
     * The module row. The view model only produces NOT_INSTALLED (no Xposed framework), PENDING_REBOOT (framework but no
     * handshake) and INSTALLED (handshake); the other values come from the old module installer and map by meaning.
     */
    internal fun moduleState(state: ModuleInstallState): HealthCheckState =
        when (state) {
            ModuleInstallState.INSTALLED -> {
                HealthCheckState.OK
            }

            ModuleInstallState.UPDATE_AVAILABLE, ModuleInstallState.PENDING_REBOOT, ModuleInstallState.REMOVAL_PENDING -> {
                HealthCheckState.WARNING
            }

            ModuleInstallState.NOT_INSTALLED, ModuleInstallState.DISABLED -> {
                HealthCheckState.FAILED
            }
        }

    /**
     * A snapshot the payload did not accept fails (it still uses the old rules). Old rules and skipped rules
     * only warn, because the payload still applies the other rules.
     */
    internal fun rulesState(rules: RulesHealth): HealthCheckState =
        when {
            rules.error != null -> HealthCheckState.FAILED
            rules.behind || rules.skipped > 0 -> HealthCheckState.WARNING
            else -> HealthCheckState.OK
        }

    /** Root as on the onboarding checklist: OK only when rooted, FAILED when not, WARNING when detection gave no result. */
    internal fun rootState(rooted: Boolean?): HealthCheckState =
        when (rooted) {
            true -> HealthCheckState.OK
            false -> HealthCheckState.FAILED
            null -> HealthCheckState.WARNING
        }

    private fun entry(
        id: HealthCheckId,
        ok: Boolean,
        otherwise: HealthCheckState = HealthCheckState.WARNING,
    ): HealthCheck = HealthCheck(id, if (ok) HealthCheckState.OK else otherwise)
}
