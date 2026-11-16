package dev.nightjar.app.core.log

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/** Format, 2 × 1 MB rotation, seven-day pruning, and the W/E-only logcat rule. */
class LogFileSinkTest {
    private lateinit var dir: File

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("nightjar-log").toFile()
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `format matches the log line layout`() {
        val line = LogFileSink.format(1762330931204L, LogLevel.I, "ingest", "batch", listOf("count" to 3, "ms" to 12))
        assertTrue(line.contains("  I  ingest  batch count=3 ms=12"), line)
        // ISO8601 with milliseconds and a zone offset, which is "Z" when the zone is UTC (as on CI runners).
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|[+-]\d{2}:\d{2})  I""").containsMatchIn(line), line)
    }

    @Test
    fun `null field renders as a dash`() {
        val line = LogFileSink.format(0L, LogLevel.W, "t", "m", listOf("rule" to null))
        assertTrue(line.endsWith(" rule=-"), line)
    }

    @Test
    fun `writes and flushes one line`() {
        val sink = LogFileSink(dir, maxBytes = 1L shl 20)
        sink.write("first")
        sink.flush()
        val text = File(dir, LogFileSink.CURRENT).readText()
        assertTrue(text.contains("first"), text)
        sink.close()
    }

    @Test
    fun `rotation keeps exactly two files`() {
        // A 40-byte budget forces one rotation per line or two.
        val sink = LogFileSink(dir, maxBytes = 40)
        repeat(10) { i ->
            sink.write("line-$i-xxxxxxxxxxxxxxxx")
            sink.flush()
        }
        sink.close()
        val files = dir.listFiles { f -> f.name.endsWith(".log") }!!.map { it.name }.sorted()
        assertEquals(listOf(LogFileSink.CURRENT, LogFileSink.PREVIOUS).sorted(), files)
        assertTrue(File(dir, LogFileSink.PREVIOUS).length() > 0)
        assertTrue(File(dir, LogFileSink.CURRENT).length() <= 80)
    }

    @Test
    fun `prune deletes files older than the window`() {
        val old = File(dir, "app-9.log")
        old.writeText("old")
        old.setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
        val fresh = File(dir, "app-8.log")
        fresh.writeText("fresh")
        val sink = LogFileSink(dir)
        sink.prune()
        assertFalse(old.exists(), "an eight-day-old file must be deleted")
        assertTrue(fresh.exists(), "a fresh file must survive")
        sink.close()
    }

    @Test
    fun `debug lines are dropped when debug logging is off`() {
        val sink = LogFileSink(dir)
        var enabled = false
        val log = FileALog(sink, logcat = null, debugEnabled = { enabled }, release = true, clock = { 0L })
        log.d("t", "hidden")
        log.i("t", "shown")
        sink.flush()
        val text = File(dir, LogFileSink.CURRENT).readText()
        assertFalse(text.contains("hidden"), text)
        assertTrue(text.contains("shown"), text)
        enabled = true
        log.d("t", "now-visible")
        sink.flush()
        assertTrue(File(dir, LogFileSink.CURRENT).readText().contains("now-visible"))
        sink.close()
    }

    @Test
    fun `release builds send only warnings and errors to logcat`() {
        assertFalse(LogLevel.I.goesToLogcatInRelease)
        assertFalse(LogLevel.D.goesToLogcatInRelease)
        assertTrue(LogLevel.W.goesToLogcatInRelease)
        assertTrue(LogLevel.E.goesToLogcatInRelease)
    }

    @Test
    fun `release logcat sink receives only W and E`() {
        val received = mutableListOf<LogLevel>()
        val sink = LogFileSink(dir)
        val logcat =
            object : LogcatSink {
                override fun log(
                    level: LogLevel,
                    tag: String,
                    message: String,
                ) {
                    received += level
                }
            }
        val log = FileALog(sink, logcat, debugEnabled = { true }, release = true)
        log.i("t", "info")
        log.d("t", "debug")
        log.w("t", "warn")
        log.e("t", "error")
        sink.close()
        assertEquals(listOf(LogLevel.W, LogLevel.E), received)
    }

    @Test
    fun `deleteAll removes every log file`() {
        val sink = LogFileSink(dir)
        sink.write("x")
        sink.flush()
        val log = FileALog(sink, logcat = null, debugEnabled = { false }, release = false)
        log.deleteAll()
        assertTrue(sink.files().isEmpty())
    }
}
