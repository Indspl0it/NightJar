package dev.nightjar.app.core.ipc

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * The app-side copy of the payload's L1 log (Payload tab).
 *
 * The payload pushes its recent lines with the C.2 `log` call; they are kept here, in device-protected storage so
 * lines pushed before the first unlock are not lost, and the Payload tab reads them back. This is deliberately a plain
 * file and not the control binder: the tab then works after the app process was killed and restarted, and while the
 * payload is between two handshakes.
 *
 * Lines are already masked by the payload. The file is trimmed to the newest [MAX_LINES] lines.
 */
class PayloadLogStore(
    private val file: File,
) {
    private val lock = Any()
    private var cached: ArrayDeque<String>? = null

    /** Stores the lines of a `lines` JSON array (`[{seq,t,level,tag,msg}]`). Returns how many were kept. */
    fun appendJson(json: String): Int {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return 0
        val lines = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val msg = o.optString("msg", "")
            if (msg.isNotEmpty()) lines += msg.replace('\n', ' ')
        }
        return append(lines)
    }

    fun append(lines: List<String>): Int {
        if (lines.isEmpty()) return 0
        synchronized(lock) {
            val all = load()
            lines.forEach(all::addLast)
            val trimmed = all.size > MAX_LINES
            while (all.size > MAX_LINES) all.removeFirst()
            runCatching {
                file.parentFile?.mkdirs()
                if (trimmed) {
                    file.writeText(all.joinToString("\n", postfix = "\n"))
                } else {
                    file.appendText(lines.joinToString("\n", postfix = "\n"))
                }
            }
        }
        return lines.size
    }

    /** All stored lines, oldest first. */
    fun lines(): List<String> = synchronized(lock) { load().toList() }

    fun clear() {
        synchronized(lock) {
            cached = ArrayDeque()
            runCatching { file.delete() }
        }
    }

    private fun load(): ArrayDeque<String> {
        cached?.let { return it }
        val read = runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
        val deque = ArrayDeque(read.takeLast(MAX_LINES))
        cached = deque
        return deque
    }

    companion object {
        const val MAX_LINES = 2_000
        private const val FILE_NAME = "payload-log.txt"

        @Volatile private var shared: PayloadLogStore? = null

        /** One instance per process: the provider writes it and the Logs screen reads it. */
        fun of(context: Context): PayloadLogStore =
            shared ?: synchronized(this) {
                shared ?: PayloadLogStore(
                    File(context.applicationContext.createDeviceProtectedStorageContext().filesDir, FILE_NAME),
                ).also { shared = it }
            }
    }
}
