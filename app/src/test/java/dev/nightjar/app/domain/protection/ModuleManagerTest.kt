package dev.nightjar.app.domain.protection

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** In-memory [RootAccess]: records every operation and returns scripted results. */
class FakeRootAccess : RootAccess {
    var xposed = true
    var rootSolution = RootSolution(RootManager.MAGISK, "28.1")
    var restartResult = RootResult(0, "")
    var rebootResult = RootResult(0, "")
    val calls = mutableListOf<String>()

    override suspend fun detectRoot(): RootSolution {
        calls += "detectRoot"
        return rootSolution
    }

    override suspend fun detectXposed(): Boolean {
        calls += "detectXposed"
        return xposed
    }

    override suspend fun restartPhoneProcess(): RootResult {
        calls += "restartPhoneProcess"
        return restartResult
    }

    override suspend fun reboot(): RootResult {
        calls += "reboot"
        return rebootResult
    }
}

/** Framework status, phone restart and reboot through a fake root. */
class ModuleManagerTest {
    private val root = FakeRootAccess()
    private val audit = mutableListOf<ProtectionAuditAction>()
    private val manager = ModuleManager(root, ProtectionAudit { action, _ -> audit += action })

    @Test
    fun `reboot is audited`() =
        runTest {
            manager.reboot()
            assertEquals(listOf("reboot"), root.calls)
            assertEquals(listOf(ProtectionAuditAction.REBOOT), audit)
        }

    @Test
    fun `restarting the phone service only kills the process`() =
        runTest {
            manager.restartPhoneService()
            assertEquals(listOf("restartPhoneProcess"), root.calls)
            assertEquals(listOf(ProtectionAuditAction.PHONE_RESTART), audit)
        }

    @Test
    fun `status reports the Xposed framework, not a module directory`() =
        runTest {
            val present = manager.status(activeThisBoot = false)
            assertEquals(ModuleInstallState.INSTALLED, present.state)
            assertEquals(ModuleManager.XPOSED_FRAMEWORK, present.installed)
            assertEquals(null, present.pendingUpdate)
            root.xposed = false
            assertEquals(ModuleInstallState.NOT_INSTALLED, manager.status(activeThisBoot = true).state)
        }

    @Test
    fun `a failed restart is not audited`() =
        runTest {
            root.restartResult = RootResult(1, "denied")
            val result = manager.restartPhoneService()
            assertEquals(ModuleOpResult.Failure(ModuleFailure.CLI_FAILED, "denied"), result)
            assertEquals(emptyList<ProtectionAuditAction>(), audit)
        }

    @Test
    fun `detectRoot delegates to the root layer`() =
        runTest {
            assertEquals(RootManager.MAGISK, manager.detectRoot().manager)
            assertEquals(listOf("detectRoot"), root.calls)
        }
}
