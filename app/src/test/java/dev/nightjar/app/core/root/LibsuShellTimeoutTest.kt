package dev.nightjar.app.core.root

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class LibsuShellTimeoutTest {
    private val windows = System.getProperty("os.name").lowercase().contains("win")

    private fun hung(): ProcessBuilder =
        if (windows) ProcessBuilder("ping", "-n", "30", "127.0.0.1") else ProcessBuilder("sleep", "30")

    @Test
    fun hungProcessIsKilledAtTheTimeout() {
        var problem: String? = null
        val started = System.nanoTime()
        val result = LibsuShell.runBounded(hung(), 1) { problem = it }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(-2, result.exitCode)
        assertTrue(elapsedMs < 10_000, "took ${elapsedMs}ms")
        assertTrue(problem.orEmpty().contains("timed out"))
    }

    @Test
    fun missingBinaryReportsNoSu() {
        var problem: String? = null
        val result = LibsuShell.runBounded(ProcessBuilder("nightjar-no-such-binary"), 5) { problem = it }
        assertEquals(-2, result.exitCode)
        assertTrue(problem.orEmpty().startsWith("su could not start"))
    }

    @Test
    fun outputAndExitCodeAreReturned() {
        assumeTrue(windows)
        val result = LibsuShell.runBounded(ProcessBuilder("cmd", "/c", "echo hello & exit 3"), 10)
        assertEquals(3, result.exitCode)
        assertEquals("hello", result.stdout.trim())
    }
}
