package dev.nightjar.app.feature.export

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.core.settings.SettingsStore
import dev.nightjar.app.data.audit.AuditAction
import dev.nightjar.app.data.audit.AuditActor
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.model.FilterSpec
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.data.repository.MessageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import javax.inject.Inject

/**
 * State and the export run.
 *
 * The writers are streaming: one message is formatted and written before the next is read, so the progress state is the
 * only growing thing. Cancelling stops between messages and removes a partially written share file. Old share files in
 * `cache/exports` are deleted before a new run, because they hold message data.
 */
@HiltViewModel
class ExportViewModel
    @Inject
    constructor(
        private val source: ExportSource,
        private val messages: MessageRepository,
        private val store: SettingsStore,
        private val audit: AuditLog,
        private val clock: Clock,
        private val log: ALog,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val mutable = MutableStateFlow(ExportUiState())
        val state: StateFlow<ExportUiState> = mutable.asStateFlow()

        private val channel = Channel<ExportEvent>(Channel.BUFFERED)
        val events = channel.receiveAsFlow()

        private var job: Job? = null

        /**
         * What Messages/Detail deposited before navigating. Captured once and removed from the singleton right
         * away, so a cancelled or abandoned export cannot leak its selection into the next one.
         */
        private val request: ExportRequest = ExportRequestStore.snapshot().also { ExportRequestStore.clear() }

        init {
            viewModelScope.launch {
                val settings = store.current()
                val all = runCatching { messages.count(EVERYTHING) }.getOrDefault(0)
                val filter = request.filter?.let { runCatching { messages.count(it) }.getOrDefault(0) } ?: 0
                val scope =
                    when {
                        request.selection.isNotEmpty() -> ExportScope.SELECTION
                        request.filter != null -> ExportScope.FILTER
                        else -> ExportScope.ALL
                    }
                mutable.value =
                    ExportUiState(
                        loading = false,
                        counts = ExportCounts(request.selection.size, filter, all),
                        scope = scope,
                        format = ExportFormat.JSON,
                        // Message text is off in both modes (export_include_text_hint): the user turns it on.
                        options =
                            ExportOptions(
                                includeText = false,
                                includeTree = false,
                                maskNumbers = settings.maskNumbers,
                            ),
                        hasFilter = request.filter != null,
                        hasSelection = request.selection.isNotEmpty(),
                    )
            }
        }

        fun setScope(scope: ExportScope) {
            if (state.value.busy) return
            if (scope == ExportScope.SELECTION && !state.value.hasSelection) return
            if (scope == ExportScope.FILTER && !state.value.hasFilter) return
            mutable.update { it.copy(scope = scope) }
        }

        fun setFormat(format: ExportFormat) {
            if (state.value.busy) return
            mutable.update {
                it.copy(
                    format = format,
                    options =
                        if (format == ExportFormat.JSON) {
                            it.options
                        } else {
                            it.options.copy(includeTree = false)
                        },
                )
            }
        }

        fun setIncludeText(include: Boolean) {
            if (state.value.busy) return
            mutable.update {
                // A decoded tree contains the message text, so it needs the text option (EXP redaction).
                it.copy(options = it.options.copy(includeText = include, includeTree = include && it.options.includeTree))
            }
        }

        fun setIncludeTree(include: Boolean) {
            if (state.value.busy) return
            if (!state.value.options.showsRawPdu) return
            mutable.update { it.copy(options = it.options.copy(includeTree = include)) }
        }

        fun setMaskNumbers(mask: Boolean) {
            if (state.value.busy) return
            mutable.update {
                it.copy(
                    options =
                        it.options.copy(
                            maskNumbers = mask,
                            // A decoded tree can repeat the sender, so masking disables it (EXP redaction).
                            includeTree = if (mask) false else it.options.includeTree,
                        ),
                )
            }
        }

        /** SAF export: the document is written straight to the picked [uri]. */
        fun exportTo(uri: Uri) {
            runExport(
                open = {
                    val stream =
                        context.contentResolver.openOutputStream(uri)
                            ?: throw IOException("no output stream for the picked document")
                    BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8))
                },
                terminal = { count -> ExportEvent.Done(count, uri.toString()) },
                // A cancelled or failed run leaves a truncated document the user picked; remove it rather than keep it.
                cleanup = { runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) } },
            )
        }

        /** Share sheet: write into `cache/exports`, then let the screen hand out a FileProvider URI. */
        fun share() {
            val dir = File(context.cacheDir, "exports")
            val file = File(dir, exportFileName(state.value.format, clock.now()))
            runExport(
                open = {
                    // The previous share file was already handed out; it holds message data, so it goes now.
                    dir.listFiles()?.forEach { old -> old.delete() }
                    dir.mkdirs()
                    file.bufferedWriter()
                },
                terminal = { ExportEvent.ShareReady(file) },
                cleanup = { file.delete() },
            )
        }

        fun cancel() {
            job?.cancel()
            job = null
        }

        private fun runExport(
            open: () -> BufferedWriter,
            terminal: (Int) -> ExportEvent,
            cleanup: () -> Unit,
        ) {
            if (state.value.busy) return
            val snapshot = state.value
            val scope = snapshot.scope
            val format = snapshot.format
            val options =
                snapshot.options.copy(
                    // The decoded tree is JSON-only and needs the raw PDU.
                    includeTree = snapshot.options.includeTree && format == ExportFormat.JSON && snapshot.options.showsRawPdu,
                )
            mutable.update { it.copy(progress = ExportProgress(0, snapshot.counts.of(scope))) }
            job =
                viewModelScope.launch(Dispatchers.IO) {
                    var count = 0
                    try {
                        val spec: FilterSpec = if (scope == ExportScope.FILTER) request.filter ?: EVERYTHING else EVERYTHING
                        val selection = if (scope == ExportScope.SELECTION) request.selection else null
                        // The counts on screen are from when it opened; new messages may have arrived since.
                        var total =
                            if (selection != null) selection.size else runCatching { messages.count(spec) }.getOrDefault(0)
                        mutable.update { it.copy(progress = ExportProgress(0, total)) }
                        open().use { out ->
                            val writer =
                                exportWriter(format, out, options, clock.now(), dev.nightjar.app.BuildConfig.VERSION_NAME)
                            writer.begin()
                            source.forEach(spec, selection, options.includeTree) { message ->
                                ensureActive()
                                writer.message(message)
                                count++
                                total = maxOf(total, count)
                                mutable.update { it.copy(progress = ExportProgress(count, total)) }
                            }
                            writer.end(count)
                            out.flush()
                        }
                        audit.record(
                            action = AuditAction.EXPORT,
                            actor = AuditActor.USER,
                            target = format.name,
                            after = AuditLog.scalar(count),
                        )
                        channel.send(terminal(count))
                    } catch (_: CancellationException) {
                        // The job is cancelled: the cleanup and the event must still run.
                        withContext(NonCancellable) {
                            cleanup()
                            channel.send(ExportEvent.Cancelled)
                        }
                    } catch (e: Exception) {
                        log.w(TAG, "export failed", e)
                        cleanup()
                        channel.send(ExportEvent.Failed)
                    } finally {
                        mutable.update { it.copy(progress = null) }
                        job = null
                    }
                }
        }

        private companion object {
            const val TAG = "export"
            val EVERYTHING = FilterSpec(showDeliveryReports = true)
        }
    }
