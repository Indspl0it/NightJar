package dev.nightjar.app.core.root

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RootOps exposes only its fixed root operations and logs name and exit code (never output). A fake shell
 * stands in for libsu.
 */
class LibsuRootOpsTest {
    private class FakeShell(
        private val responses: Map<String, ShellResult> = emptyMap(),
        private val default: ShellResult = ShellResult(0, ""),
    ) : ShellPort {
        val commands = mutableListOf<String>()

        override suspend fun exec(vararg command: String): ShellResult {
            commands += command.toList()
            // Match the first command of the list (the operation's own command), like a real shell would run it.
            val first = command.firstOrNull().orEmpty()
            responses.forEach { (prefix, result) -> if (first.startsWith(prefix)) return result }
            return default
        }

        override suspend fun isRootAvailable(): Boolean = true
    }

    private class RecordingLog : dev.nightjar.app.core.log.ALog by dev.nightjar.app.core.log.NoopALog {
        val info = mutableListOf<String>()

        override fun i(
            tag: String,
            message: String,
            fields: List<Pair<String, Any?>>,
        ) {
            info += fields.joinToString(" ") { "${it.first}=${it.second}" }
        }
    }

    private fun ops(
        shell: ShellPort,
        log: RecordingLog = RecordingLog(),
    ) = LibsuRootOps(shell, log)

    @Test
    fun `magisk is detected with its version`() =
        kotlinx.coroutines.runBlocking {
            val shell = FakeShell(mapOf("id -u" to ShellResult(0, "0\n"), "magisk -V" to ShellResult(0, "30.4\n")))
            val solution = ops(shell).detectRootSolution()
            assertEquals(RootManager.MAGISK, solution.manager)
            assertEquals("30.4", solution.version)
        }

    @Test
    fun `kernelsu is detected when magisk is absent`() =
        kotlinx.coroutines.runBlocking {
            val shell =
                FakeShell(
                    mapOf(
                        "id -u" to ShellResult(0, "0\n"),
                        "magisk -V" to ShellResult(1, "not found"),
                        "ksud -V" to ShellResult(0, "KernelSU v1.0.3\n"),
                    ),
                )
            val solution = ops(shell).detectRootSolution()
            assertEquals(RootManager.KERNEL_SU, solution.manager)
            assertEquals("v1.0.3", solution.version)
        }

    @Test
    fun `apatch is detected when magisk and kernelsu are absent`() =
        kotlinx.coroutines.runBlocking {
            val shell =
                FakeShell(
                    mapOf(
                        "id -u" to ShellResult(0, "0\n"),
                        "magisk -V" to ShellResult(1, ""),
                        "ksud -V" to ShellResult(1, ""),
                        "apd -V" to ShellResult(0, "10933\n"),
                    ),
                )
            assertEquals(RootManager.APATCH, ops(shell).detectRootSolution().manager)
        }

    @Test
    fun `a root shell without a recognised manager binary still counts as rooted`() =
        kotlinx.coroutines.runBlocking {
            // A manager update can move its CLI off PATH. UID 0 is the fact the checks need; the name is cosmetic,
            // and reporting UNKNOWN previously made a working grant read as "root was denied".
            val shell =
                FakeShell(
                    mapOf(
                        "id -u" to ShellResult(0, "0\n"),
                        "magisk" to ShellResult(127, "not found"),
                        "ksud" to ShellResult(127, "not found"),
                        "apd" to ShellResult(127, "not found"),
                    ),
                )
            val solution = ops(shell).detectRootSolution()
            assertEquals(RootManager.UNKNOWN, solution.manager)
            assertTrue(solution.rooted, "a UID-0 shell is root regardless of which CLI answered")
            assertNull(solution.version)
        }

    @Test
    fun `a shell that is not uid zero is not rooted`() =
        kotlinx.coroutines.runBlocking {
            val shell = FakeShell(mapOf("id -u" to ShellResult(0, "10123\n"), "magisk -V" to ShellResult(0, "30.4\n")))
            val solution = ops(shell).detectRootSolution()
            assertTrue(!solution.rooted)
            assertEquals(RootManager.UNKNOWN, solution.manager)
        }

    @Test
    fun `no root manager is reported as unknown`() =
        kotlinx.coroutines.runBlocking {
            val shell = FakeShell(default = ShellResult(1, ""))
            val solution = ops(shell).detectRootSolution()
            assertEquals(RootManager.UNKNOWN, solution.manager)
            assertNull(solution.version)
        }

    @Test
    fun `root detection runs no zygisk probe`() =
        kotlinx.coroutines.runBlocking {
            // Root detection reads neither the Magisk Zygisk setting nor the module list.
            val shell = FakeShell(mapOf("id -u" to ShellResult(0, "0\n"), "magisk -V" to ShellResult(0, "30.4")))
            val solution = ops(shell).detectRootSolution()
            assertEquals(RootManager.MAGISK, solution.manager)
            val joined = shell.commands.joinToString("\n")
            assertTrue(!joined.contains("--sqlite") && !joined.contains("ls /data/adb/modules"), joined)
        }

    @Test
    fun `every operation logs its name and exit code and never its output`() =
        kotlinx.coroutines.runBlocking {
            val log = RecordingLog()
            val shell = FakeShell(mapOf("for f in" to ShellResult(0, "nightjar secret")))
            val value = ops(shell, log).readCompanionLog()
            assertEquals("nightjar secret", value)
            assertTrue(log.info.any { it.contains("op=readCompanionLog") }, log.info.toString())
            assertTrue(log.info.any { it.contains("exit=0") }, log.info.toString())
            assertTrue(log.info.none { it.contains("secret") }, "command output must never be logged: ${log.info}")
        }

    @Test
    fun `reboot uses a single command`() =
        kotlinx.coroutines.runBlocking {
            val shell = FakeShell()
            ops(shell).reboot()
            val joined = shell.commands.joinToString("\n")
            assertTrue(joined.contains("svc power reboot"), joined)
        }

    @Test
    fun `the magisk binary at the ramdisk path names Magisk and its version`() =
        kotlinx.coroutines.runBlocking {
            val shell =
                FakeShell(
                    mapOf(
                        "id -u" to ShellResult(0, "0\n"),
                        "magisk -V" to ShellResult(127, "magisk: not found"),
                        "/debug_ramdisk/magisk -V" to ShellResult(0, "30.4\n"),
                    ),
                )
            val solution = ops(shell).detectRootSolution()
            assertEquals(RootManager.MAGISK, solution.manager)
            assertEquals("30.4", solution.version)
        }
}
