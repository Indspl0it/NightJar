package dev.nightjar.app.feature.logs

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.core.ipc.PayloadLogStore
import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.core.log.LogLevel
import dev.nightjar.app.core.root.RootOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter
import javax.inject.Inject

sealed interface LogsEvent {
    data object Exported : LogsEvent

    /** The export did not complete. The reason goes to the app log, never to the screen. */
    data object Failed : LogsEvent
}

/**
 * state.
 *
 * App logs are read from the rotating L2 files. The Framework tab shows the NightJar lines of the Vector or LSPosed log. The
 * Payload tab shows the lines the payload pushed with the `log` call ([PayloadLogStore]); it does not depend on the
 * control binder being alive.
 */
@HiltViewModel
class LogsViewModel
    @Inject
    constructor(
        private val log: ALog,
        private val root: RootOps,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val mutable = MutableStateFlow(LogsUiState())
        val state: StateFlow<LogsUiState> = mutable.asStateFlow()

        private val channel = Channel<LogsEvent>(Channel.BUFFERED)
        val events = channel.receiveAsFlow()

        /** The running load. A tab switch cancels it so a slow read of the old tab cannot overwrite the new tab's lines. */
        private var loadJob: Job? = null

        init {
            reload()
        }

        fun selectTab(tab: LogTab) {
            loadJob?.cancel()
            if (tab == LogTab.AUDIT) {
                mutable.update { it.copy(tab = tab, loading = false) }
                return
            }
            mutable.update {
                it.copy(
                    tab = tab,
                    lines = emptyList(),
                    loading = true,
                    payloadUnavailable = false,
                    companionNeedsRoot = false,
                )
            }
            reload()
        }

        fun reload() {
            loadJob?.cancel()
            when (mutable.value.tab) {
                LogTab.APP -> {
                    loadApp()
                }

                // The audit list has its own flow (AuditViewModel); there is no file to load.
                LogTab.AUDIT -> {
                    mutable.update { it.copy(loading = false) }
                }

                LogTab.COMPANION -> {
                    loadCompanion()
                }

                LogTab.PAYLOAD -> {
                    loadPayload()
                }
            }
        }

        fun toggleLevel(
            level: LogLevel,
            enabled: Boolean,
        ) {
            mutable.update {
                val levels = it.levels.toMutableSet()
                if (enabled) levels += level else levels -= level
                it.copy(levels = levels)
            }
        }

        fun setQuery(query: String) {
            mutable.update { it.copy(query = query) }
        }

        /** SAF text export of exactly what the filters show, in the style of the message export. */
        fun exportTo(uri: Uri) {
            val lines = mutable.value.visibleLines
            viewModelScope.launch {
                val written =
                    try {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { stream ->
                                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                                    lines.forEach { writer.append(it.raw).append('\n') }
                                }
                                true
                            } ?: false
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.w(TAG, "log export failed", e)
                        false
                    }
                channel.send(if (written) LogsEvent.Exported else LogsEvent.Failed)
            }
        }

        private fun loadApp() {
            mutable.update { it.copy(loading = true, payloadUnavailable = false, companionNeedsRoot = false) }
            loadJob =
                viewModelScope.launch {
                    val lines =
                        withContext(Dispatchers.IO) {
                            val out = ArrayList<LogLine>(1024)
                            for (file in log.files()) {
                                readNewestFirst(file, out)
                                if (out.size >= MAX_LINES) break
                            }
                            out
                        }
                    mutable.update {
                        it.copy(
                            loading = false,
                            lines = lines.take(MAX_LINES),
                            payloadUnavailable = false,
                            companionNeedsRoot = false,
                        )
                    }
                }
        }

        /** Payload tab: the lines the payload pushed with the `log` call, newest first. */
        private fun loadPayload() {
            mutable.update { it.copy(loading = true, payloadUnavailable = false, companionNeedsRoot = false) }
            loadJob =
                viewModelScope.launch {
                    val lines =
                        withContext(Dispatchers.IO) {
                            PayloadLogStore
                                .of(context)
                                .lines()
                                .asReversed()
                                .take(MAX_LINES)
                                .map(::parseLogLine)
                        }
                    // Nothing stored yet is the honest "not connected / nothing received" state.
                    mutable.update { it.copy(loading = false, lines = lines, payloadUnavailable = lines.isEmpty()) }
                }
        }

        /** Companion tab: the NightJar lines of the Vector/LSPosed module log (start result and per-hook states). */
        private fun loadCompanion() {
            loadJob?.cancel()
            mutable.update { it.copy(loading = true, payloadUnavailable = false, companionNeedsRoot = false) }
            loadJob =
                viewModelScope.launch {
                    try {
                        val text = withContext(Dispatchers.IO) { root.readCompanionLog() }
                        val lines =
                            withContext(Dispatchers.Default) {
                                text
                                    .orEmpty()
                                    .lineSequence()
                                    .filter { it.isNotBlank() }
                                    .map(::parseLogLine)
                                    .toList()
                                    .asReversed()
                                    .take(MAX_LINES)
                            }
                        mutable.update { it.copy(loading = false, lines = lines) }
                    } catch (ignored: Exception) {
                        mutable.update { it.copy(loading = false, companionNeedsRoot = true, lines = emptyList()) }
                    }
                }
        }

        private fun readNewestFirst(
            file: File,
            into: MutableList<LogLine>,
        ) {
            val lines =
                runCatching { file.readLines() }.getOrElse { return }
            for (index in lines.indices.reversed()) {
                if (into.size >= MAX_LINES) return
                val line = lines[index]
                if (line.isBlank()) continue
                into += parseLogLine(line)
            }
        }

        private companion object {
            const val MAX_LINES = 4_000
            const val TAG = "logs"
        }
    }

/** `ISO8601-ms L tag message`. A line that does not split keeps its raw text. */
internal fun parseLogLine(raw: String): LogLine {
    val safe = LogRedactor.redact(raw)
    val parts = safe.split("  ", limit = 4)
    if (parts.size < 3) return LogLine(at = "", level = null, tag = "", message = safe, raw = safe)
    val level = LogLevel.entries.firstOrNull { it.letter.toString() == parts[1].trim() }
    return LogLine(
        at = parts[0].trim(),
        level = level,
        tag = parts[2].trim(),
        message = parts.getOrElse(3) { "" },
        raw = safe,
    )
}
