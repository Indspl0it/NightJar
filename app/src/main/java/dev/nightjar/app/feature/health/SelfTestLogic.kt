package dev.nightjar.app.feature.health

import dev.nightjar.app.domain.selftest.CanaryClass
import dev.nightjar.contract.Verdict
import dev.nightjar.rules.RulesConfig
import org.json.JSONObject

/**
 * Hook states from the control binder's `getHookHealth`.
 *
 * The self-test evaluation needs the same answer the payload's `SelfTestRunner` uses for its Appendix F safety
 * preconditions, otherwise a canary the payload skips would be reported as a five-second timeout instead of
 * `SKIPPED:<hook>`. A missing or malformed document yields an empty map: the run then lets the payload decide and
 * reports whatever arrives.
 */
fun hookStatesFromHealth(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching {
        val hooks = JSONObject(json).optJSONObject("hooks") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        val ids = hooks.keys()
        while (ids.hasNext()) {
            val id = ids.next()
            val state = hooks.optJSONObject(id)?.optString("state", "") ?: ""
            out[id] = state
        }
        out
    }.getOrDefault(emptyMap())
}

/**
 * The self-test asks before injecting when the firewall would block something.
 *
 * Class actions and enabled custom rules both count; a quarantine is a block from the user's point of view.
 */
fun hasBlockingRules(config: RulesConfig): Boolean =
    config.classActions.values.any { it == Verdict.BLOCK || it == Verdict.QUARANTINE } ||
        config.rules.any { it.enabled && (it.action == Verdict.BLOCK || it.action == Verdict.QUARANTINE) }

/**
 * Appendix F safety preconditions, mirrored so the UI can report `SKIPPED:<hook>` immediately.
 *
 * An empty [hookStates] means the control binder did not answer; the map is then empty and every canary is attempted,
 * which is exactly what the payload does when it has no registry.
 */
fun skippedCanaries(
    classes: List<CanaryClass>,
    hookStates: Map<String, String>,
): Map<CanaryClass, String> {
    if (hookStates.isEmpty()) return emptyMap()
    val out = LinkedHashMap<CanaryClass, String>()
    for (canary in classes) {
        val missing = canary.requiredHooks.firstOrNull { hookStates[it] != "LIVE" } ?: continue
        out[canary] = missing
    }
    return out
}
