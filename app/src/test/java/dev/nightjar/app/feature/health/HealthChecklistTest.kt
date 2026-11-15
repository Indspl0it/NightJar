package dev.nightjar.app.feature.health

import dev.nightjar.app.data.maintenance.RetentionReport
import dev.nightjar.app.domain.protection.ModuleInstallState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HealthChecklistTest {
    private val healthy =
        HealthUiState(
            loading = false,
            moduleState = ModuleInstallState.INSTALLED,
            rootManager = "Magisk",
            rooted = true,
            rootVersion = "28.1",
            framework = true,
            hooksLive = 6,
            hooksTotal = 6,
            rules = RulesHealth(),
            payloadNewer = false,
        )

    private fun List<HealthCheck>.stateOf(id: HealthCheckId): HealthCheckState? = firstOrNull { it.id == id }?.state

    @Test
    fun `a healthy phone has every check OK and no issue`() {
        val checks = HealthChecklist.evaluate(healthy, researcher = true)
        assertTrue(checks.all { it.state == HealthCheckState.OK })
        assertEquals(0, HealthChecklist.issueCount(checks))
    }

    @Test
    fun `every check of the old screen is in the list, in display order`() {
        val ids = HealthChecklist.evaluate(healthy, researcher = true).map { it.id }
        assertEquals(HealthCheckId.entries.toList(), ids)
    }

    @Test
    fun `the hooks check is Researcher only and needs hook rows`() {
        assertFalse(HealthChecklist.evaluate(healthy, researcher = false).any { it.id == HealthCheckId.HOOKS })
        val noHooks = healthy.copy(hooksLive = 0, hooksTotal = 0)
        assertFalse(HealthChecklist.evaluate(noHooks, researcher = true).any { it.id == HealthCheckId.HOOKS })
        val partly = healthy.copy(hooksLive = 4)
        assertEquals(HealthCheckState.WARNING, HealthChecklist.evaluate(partly, researcher = true).stateOf(HealthCheckId.HOOKS))
    }

    @Test
    fun `module state maps to OK, warning or failed`() {
        assertEquals(HealthCheckState.OK, HealthChecklist.moduleState(ModuleInstallState.INSTALLED))
        assertEquals(HealthCheckState.WARNING, HealthChecklist.moduleState(ModuleInstallState.PENDING_REBOOT))
        assertEquals(HealthCheckState.WARNING, HealthChecklist.moduleState(ModuleInstallState.UPDATE_AVAILABLE))
        assertEquals(HealthCheckState.WARNING, HealthChecklist.moduleState(ModuleInstallState.REMOVAL_PENDING))
        assertEquals(HealthCheckState.FAILED, HealthChecklist.moduleState(ModuleInstallState.NOT_INSTALLED))
        assertEquals(HealthCheckState.FAILED, HealthChecklist.moduleState(ModuleInstallState.DISABLED))
    }

    @Test
    fun `root is OK only when rooted, fails when not rooted and warns when unknown`() {
        assertEquals(HealthCheckState.OK, HealthChecklist.rootState(true))
        assertEquals(HealthCheckState.FAILED, HealthChecklist.rootState(false))
        assertEquals(HealthCheckState.WARNING, HealthChecklist.rootState(null))
        // A manager label alone is not root: an unknown manager without a UID-0 shell still fails.
        val notRooted = healthy.copy(rootManager = "Unknown", rooted = false)
        assertEquals(HealthCheckState.FAILED, HealthChecklist.evaluate(notRooted, researcher = false).stateOf(HealthCheckId.ROOT))
        val unknown = healthy.copy(rootManager = "", rooted = null)
        assertEquals(HealthCheckState.WARNING, HealthChecklist.evaluate(unknown, researcher = false).stateOf(HealthCheckId.ROOT))
    }

    @Test
    fun `not rooted or no framework fails`() {
        val state = healthy.copy(rooted = false, framework = false)
        val checks = HealthChecklist.evaluate(state, researcher = false)
        assertEquals(HealthCheckState.FAILED, checks.stateOf(HealthCheckId.ROOT))
        assertEquals(HealthCheckState.FAILED, checks.stateOf(HealthCheckId.FRAMEWORK))
    }

    @Test
    fun `the checklist has no build profile row and no USB debugging row`() {
        // Both rows were removed. The other checks stay as in 0.2.0.
        val ids = HealthChecklist.evaluate(healthy, researcher = true).map { it.id.name }
        assertEquals(
            listOf("MODULE", "MODULE_VERSION", "ROOT", "FRAMEWORK", "RULES", "HOOKS", "EVENTS", "STORAGE"),
            ids,
        )
    }

    @Test
    fun `lost events and a large database warn`() {
        val state =
            healthy.copy(
                lostEvents = 3,
                databaseBytes = RetentionReport.SIZE_LIMIT_BYTES + 1,
            )
        val checks = HealthChecklist.evaluate(state, researcher = false)
        assertEquals(HealthCheckState.WARNING, checks.stateOf(HealthCheckId.EVENTS))
        assertEquals(HealthCheckState.WARNING, checks.stateOf(HealthCheckId.STORAGE))
        assertEquals(2, HealthChecklist.issueCount(checks))
    }

    @Test
    fun `the module version and rules checks need a payload session`() {
        val ids = HealthChecklist.evaluate(healthy.copy(rules = null, payloadNewer = null), researcher = false).map { it.id }
        assertFalse(HealthCheckId.MODULE_VERSION in ids)
        assertFalse(HealthCheckId.RULES in ids)
    }

    @Test
    fun `a newer module warns (MSG-UPD-01)`() {
        val checks = HealthChecklist.evaluate(healthy.copy(payloadNewer = true), researcher = false)
        assertEquals(HealthCheckState.WARNING, checks.stateOf(HealthCheckId.MODULE_VERSION))
        assertEquals(1, HealthChecklist.issueCount(checks))
    }

    @Test
    fun `a rules error fails, old or skipped rules warn`() {
        fun stateWith(rules: RulesHealth) =
            HealthChecklist.evaluate(healthy.copy(rules = rules), researcher = false).stateOf(HealthCheckId.RULES)
        assertEquals(HealthCheckState.OK, stateWith(RulesHealth()))
        assertEquals(HealthCheckState.FAILED, stateWith(RulesHealth(error = "JSONException", behind = true, skipped = 2)))
        assertEquals(HealthCheckState.WARNING, stateWith(RulesHealth(behind = true)))
        assertEquals(HealthCheckState.WARNING, stateWith(RulesHealth(skipped = 1)))
    }

    @Test
    fun `only the hooks check opens the logs`() {
        HealthCheckId.entries.forEach { id ->
            val expected = if (id == HealthCheckId.HOOKS) HealthFix.OPEN_LOGS else HealthFix.HELP
            assertEquals(expected, HealthChecklist.fix(id))
        }
    }
}
