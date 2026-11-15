package dev.nightjar.app.core.log

import dev.nightjar.contract.Masker

/**
 * L2 app log.
 *
 * Format: `ISO8601-ms  LEVEL  tag  message` + optional `key=value` pairs. Numbers and text that could identify a
 * subscriber or a message go through [Masker] before they reach this class. The `fields` values are written as they
 * are (`key=value`), so the caller must mask a number or a text before it passes it; `checkLogMasking` checks this.
 *
 * Debug lines are dropped unless debug logging is active. Logcat receives W/E in release builds only
 * .
 */
interface ALog {
    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )

    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    )

    fun i(
        tag: String,
        message: String,
        fields: List<Pair<String, Any?>> = emptyList(),
    )

    fun d(
        tag: String,
        message: String,
        fields: List<Pair<String, Any?>> = emptyList(),
    )

    /** Raw access for tests and the log viewer; the file may be empty before the worker drains. */
    fun files(): List<java.io.File>

    /** True when D lines are currently kept. */
    fun debugEnabled(): Boolean

    /** Drops rotated files older than the retention window. */
    fun prune()

    /** Deletes every L2 file ("delete all data"). */
    fun deleteAll()
}

/** No-op implementation for previews and unit tests that do not exercise logging. */
object NoopALog : ALog {
    override fun e(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) = Unit

    override fun w(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) = Unit

    override fun i(
        tag: String,
        message: String,
        fields: List<Pair<String, Any?>>,
    ) = Unit

    override fun d(
        tag: String,
        message: String,
        fields: List<Pair<String, Any?>>,
    ) = Unit

    override fun files(): List<java.io.File> = emptyList()

    override fun debugEnabled(): Boolean = false

    override fun prune() = Unit

    override fun deleteAll() = Unit
}

/**
 * The real logger. [sink] is the rotating file, [logcat] is `android.util.Log` (null in JVM tests), [debugEnabled] is
 * read per call so a settings change applies without a restart, and [release] decides whether logcat gets I/D lines.
 */
class FileALog(
    private val sink: LogFileSink,
    private val logcat: LogcatSink?,
    private val debugEnabled: () -> Boolean,
    private val release: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : ALog {
    override fun e(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        write(LogLevel.E, tag, message, emptyList())
        if (throwable !=
            null
        ) {
            write(LogLevel.E, tag, throwable.javaClass.name + ": " + Masker.maskText(throwable.message.orEmpty()), emptyList())
        }
    }

    override fun w(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        write(LogLevel.W, tag, message, emptyList())
        if (throwable !=
            null
        ) {
            write(LogLevel.W, tag, throwable.javaClass.name + ": " + Masker.maskText(throwable.message.orEmpty()), emptyList())
        }
    }

    override fun i(
        tag: String,
        message: String,
        fields: List<Pair<String, Any?>>,
    ) = write(LogLevel.I, tag, message, fields)

    override fun d(
        tag: String,
        message: String,
        fields: List<Pair<String, Any?>>,
    ) {
        if (!debugEnabled()) return
        write(LogLevel.D, tag, message, fields)
    }

    override fun files() = sink.files()

    override fun debugEnabled() = debugEnabled.invoke()

    override fun prune() = sink.prune()

    override fun deleteAll() = sink.truncateAll()

    private fun write(
        level: LogLevel,
        tag: String,
        message: String,
        fields: List<Pair<String, Any?>>,
    ) {
        if (level == LogLevel.D && !debugEnabled()) return
        sink.write(LogFileSink.format(clock(), level, tag, message, fields))
        val toLogcat = if (release) level.goesToLogcatInRelease else true
        if (toLogcat && logcat != null) logcat.log(level, tag, message)
    }

    companion object {
        /** Tag used by `adb logcat -s Nightjar` and by the privacy tests. */
        const val TAG = "Nightjar"
    }
}

/** Thin seam over `android.util.Log` so the logger is testable on the JVM. */
interface LogcatSink {
    fun log(
        level: LogLevel,
        tag: String,
        message: String,
    )
}
