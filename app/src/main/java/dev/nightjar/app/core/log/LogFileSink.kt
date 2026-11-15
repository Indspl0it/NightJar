package dev.nightjar.app.core.log

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Rotating file sink for L2.
 *
 * Two files of at most [maxBytes] each in [directory]: `app-0.log` is current, `app-1.log` is the previous one. When
 * the current file would exceed the limit it becomes `app-1.log` (replacing the older one) and a new `app-0.log`
 * starts. Files whose last write is older than [maxAgeMs] are deleted on [prune].
 *
 * Writes happen on one background thread; callers never block on disk. [flush] makes a test deterministic.
 */
class LogFileSink(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Declared before `init`: the worker thread starts there and must see initialized fields.
    private val queue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)

    /** Lines accepted by [write] and lines the worker has finished with; [flush] waits until they are equal. */
    private val accepted = AtomicLong(0)
    private val done = AtomicLong(0)

    @Volatile private var stopped = false

    // Guarded by `this`: the worker, [flush], [truncateAll] and [close] all touch the stream.
    private var currentOut: FileOutputStream? = null
    private var currentSize = 0L

    init {
        Thread(
            {
                while (true) {
                    val line = queue.take()
                    if (line === STOP) return@Thread
                    append(line)
                    done.incrementAndGet()
                }
            },
            "nightjar-alog",
        ).apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** Queues one already-formatted line. Drops the line when the queue is full: logging never blocks a hook. */
    fun write(line: String) {
        if (stopped) return
        if (queue.offer(line)) accepted.incrementAndGet()
    }

    /**
     * Blocks until every line queued before this call is on disk (tests and diagnostics). The queue being empty is not
     * enough: the worker can hold the last line it took. Gives up after [FLUSH_TIMEOUT_MS].
     */
    fun flush() {
        val target = accepted.get()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FLUSH_TIMEOUT_MS)
        while (done.get() < target && !stopped && System.nanoTime() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(2)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        synchronized(this) { currentOut?.flush() }
    }

    /** Deletes rotated files older than [maxAgeMs] (7 days). */
    fun prune() {
        val cutoff = clock() - maxAgeMs
        directory.listFiles()?.forEach { f ->
            if (f.name != CURRENT && f.name.endsWith(LOG_SUFFIX) && f.lastModified() < cutoff) f.delete()
        }
    }

    /** Files currently on disk, newest first. */
    fun files(): List<File> =
        directory
            .listFiles { f -> f.name.endsWith(LOG_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    /**
     * Deletes every log file and keeps the sink running ("delete all data"): the next line starts a new
     * `app-0.log`. Lines still queued are written after the deletion.
     */
    fun truncateAll() {
        flush()
        synchronized(this) {
            currentOut?.close()
            currentOut = null
            currentSize = 0
            files().forEach { it.delete() }
        }
    }

    /** Stops the worker after flushing and releases the file handle (needed before deleting on Windows). */
    fun close() {
        flush()
        stopped = true
        queue.offer(STOP)
        synchronized(this) {
            currentOut?.flush()
            currentOut?.close()
            currentOut = null
            currentSize = 0
        }
    }

    private fun append(line: String) {
        synchronized(this) {
            try {
                if (currentOut == null) openCurrent()
                val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                if (currentSize + bytes.size > maxBytes) rotate()
                currentOut?.write(bytes)
                currentSize += bytes.size
            } catch (_: IOException) {
                // A full or read-only filesystem must never break the app; drop the line.
            }
        }
    }

    private fun openCurrent() {
        directory.mkdirs()
        val file = File(directory, CURRENT)
        currentOut = FileOutputStream(file, true)
        currentSize = file.length()
    }

    private fun rotate() {
        currentOut?.flush()
        currentOut?.close()
        currentOut = null
        val current = File(directory, CURRENT)
        val previous = File(directory, PREVIOUS)
        if (previous.exists()) previous.delete()
        if (current.exists()) current.renameTo(previous)
        openCurrent()
        prune()
    }

    companion object {
        const val CURRENT = "app-0.log"
        const val PREVIOUS = "app-1.log"
        const val LOG_SUFFIX = ".log"

        /** 1 MB per file, two files (L2). */
        const val DEFAULT_MAX_BYTES = 1L * 1024 * 1024

        /** Seven days. */
        const val DEFAULT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        private const val QUEUE_CAPACITY = 4096
        private const val FLUSH_TIMEOUT_MS = 2_000L
        private val STOP = String(charArrayOf('\u0000', 's', 't', 'o', 'p'))

        private val FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneId.systemDefault())

        /** `ISO8601-ms LEVEL tag message`. */
        fun format(
            atMillis: Long,
            level: LogLevel,
            tag: String,
            message: String,
            fields: List<Pair<String, Any?>> = emptyList(),
        ): String {
            val sb = StringBuilder(96)
            sb.append(FORMAT.format(Instant.ofEpochMilli(atMillis)))
            sb
                .append("  ")
                .append(level.letter)
                .append("  ")
                .append(tag)
                .append("  ")
                .append(message)
            fields.forEach { (k, v) ->
                sb
                    .append(' ')
                    .append(k)
                    .append('=')
                    .append(v ?: "-")
            }
            return sb.toString()
        }
    }
}
