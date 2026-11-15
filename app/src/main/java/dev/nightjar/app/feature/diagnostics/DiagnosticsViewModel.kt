package dev.nightjar.app.feature.diagnostics

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nightjar.app.BuildConfig
import dev.nightjar.app.core.ipc.PayloadLogStore
import dev.nightjar.app.core.log.ALog
import dev.nightjar.app.data.audit.AuditLog
import dev.nightjar.app.data.model.FilterSpec
import dev.nightjar.app.data.repository.Clock
import dev.nightjar.app.data.repository.HookStatusRepository
import dev.nightjar.app.feature.export.ExportSource
import dev.nightjar.app.feature.protection.ModuleInstaller
import dev.nightjar.app.feature.protection.PhoneWatchdog
import dev.nightjar.app.feature.protection.SessionRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class DiagnosticsUiState(
    /**
     * Off by default. When on, the bundle has the newest message rows: metadata only, with the numbers masked.
     * The bundle never has message text.
     */
    val includeMessages: Boolean = false,
    val busy: Boolean = false,
    /** The ZIP once it has been written; the screen hands it to the share sheet and then calls [consumeFile]. */
    val file: File? = null,
    val failed: Boolean = false,
)

/**
 * Builds the diagnostics ZIP of the diagnostics dialog.
 *
 * Everything is a best-effort read: a device without root, without a payload session or with an unreadable log still
 * produces a bundle, because the unsupported-build case is exactly the one where those reads fail. The ZIP is written
 * to `cache/diagnostics/`, which the FileProvider shares only through a temporary grant (`res/xml/file_paths.xml`).
 * The bundle is built and written on the IO dispatcher. A new bundle deletes the older ones, because they hold the same
 * kind of data and were already handed out.
 */
@HiltViewModel
// The bundle is a join of the app's independent state sources; a parameter bag would only move the list one level down.
@Suppress("LongParameterList")
class DiagnosticsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val log: ALog,
        private val audit: AuditLog,
        private val hooks: HookStatusRepository,
        private val sessions: SessionRegistry,
        private val installer: ModuleInstaller,
        private val watchdog: PhoneWatchdog,
        private val export: ExportSource,
        private val clock: Clock,
    ) : ViewModel() {
        private val state = MutableStateFlow(DiagnosticsUiState())
        val uiState: StateFlow<DiagnosticsUiState> = state.asStateFlow()

        fun onIncludeMessages(include: Boolean) {
            state.update { it.copy(includeMessages = include) }
        }

        /**
         * Called when the diagnostics dialog closes: the next dialog starts with the defaults, so an earlier opt-in does
         * not carry over.
         */
        fun reset() {
            if (!state.value.busy) state.value = DiagnosticsUiState()
        }

        /** "Create": gathers the sources, writes the ZIP, then reports the file to the screen. */
        fun create() {
            if (state.value.busy) return
            val includeMessages = state.value.includeMessages
            state.update { it.copy(busy = true, failed = false, file = null) }
            viewModelScope.launch {
                val result =
                    try {
                        withContext(Dispatchers.IO) {
                            runCatching { sessions.restore() }
                            val entries =
                                DiagnosticsBundle.build(
                                    device = deviceLines(),
                                    hooks = hookRows(),
                                    audit = auditRows(),
                                    appLog = appLog(),
                                    messages = messageRows(includeMessages),
                                    payloadLog = payloadLog(),
                                    phoneProcess = phoneProcessLines(),
                                )
                            val dir = File(context.cacheDir, "diagnostics")
                            dir.listFiles()?.forEach { old -> old.delete() }
                            val target = File(dir, fileName())
                            DiagnosticsBundle.writeZip(target, entries)
                            target
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.w(TAG, "diagnostics bundle failed", e)
                        null
                    }
                state.update { it.copy(busy = false, file = result, failed = result == null) }
            }
        }

        /**
         * Called by the screen after the share sheet has been launched: the bundle is never reused, but it is not
         * deleted here either — the chooser reads it through the FileProvider after this call returns.
         */
        fun consumeFile() {
            state.update { it.copy(file = null) }
        }

        fun dismissFailure() {
            state.update { it.copy(failed = false) }
        }

        private suspend fun deviceLines(): List<Pair<String, String>> {
            val handshake = sessions.handshake.value
            val active = handshake != null
            val root = runCatching { installer.detectRoot() }.getOrNull()
            val module = runCatching { installer.status(activeThisBoot = active) }.getOrNull()
            return listOfNotNull(
                "generated.at" to Instant.ofEpochMilli(clock.now()).toString(),
                "app.version" to BuildConfig.VERSION_NAME,
                "app.versionCode" to BuildConfig.VERSION_CODE.toString(),
                "android.release" to Build.VERSION.RELEASE,
                "android.sdk" to Build.VERSION.SDK_INT.toString(),
                "android.device" to Build.DEVICE,
                "android.model" to Build.MODEL,
                "build.fingerprint" to (handshake?.buildFingerprint ?: Build.FINGERPRINT),
                "payload.version" to handshake?.payloadVersion,
                "payload.profile" to handshake?.profile?.name,
                "module.state" to module?.state?.name,
                "root.manager" to root?.manager?.name,
                "root.version" to root?.version,
                "xposed.framework" to (module?.installed != null).toString(),
                "log.debug" to log.debugEnabled().toString(),
            )
        }

        private suspend fun hookRows(): List<HookRow> =
            runCatching { hooks.all() }
                .getOrDefault(emptyList())
                .map { hook ->
                    runCatching {
                        HookRow(
                            id = hook.hookId,
                            state = hook.state.name,
                            errorCount = hook.errorCount,
                            lastError = hook.lastError,
                        )
                    }.getOrNull()
                }.filterNotNull()

        private suspend fun auditRows(): List<AuditRow> {
            val since = clock.now() - AUDIT_WINDOW_MS
            return runCatching { audit.since(since) }
                .getOrDefault(emptyList())
                .map { entry ->
                    AuditRow(
                        at = entry.at,
                        actor = entry.actor,
                        action = entry.action,
                        target = entry.target,
                        before = entry.beforeJson,
                        after = entry.afterJson,
                    )
                }
        }

        /** The rotated app log files, oldest first, so the builder's cap keeps the newest lines. */
        private fun appLog(): String = runCatching { DiagnosticsBundle.appLogText(log.files().map { it.readText() }) }.getOrDefault("")

        private fun payloadLog(): List<String>? = runCatching { PayloadLogStore.of(context).lines() }.getOrNull()

        private fun phoneProcessLines(): List<Pair<String, String>> {
            val handshake = sessions.handshake.value
            return listOf(
                "session.createdAt" to (handshake?.let { Instant.ofEpochMilli(it.receivedAtWallMs).toString() } ?: "none"),
                "payload.version" to (handshake?.payloadVersion ?: "none"),
                "boot.count" to (handshake?.bootCount?.toString() ?: "unknown"),
                // The watchdog only detects. "orphaned" is a phone process that came back without the payload.
                "watchdog.state" to (if (watchdog.problem.value != null) "orphaned" else "ok"),
            )
        }

        /**
         * Opt-in only: the newest [MAX_MESSAGE_ROWS] messages, metadata only. The message text is never read
         * into the bundle. The builder masks the numbers.
         */
        private suspend fun messageRows(include: Boolean): List<MessageRow>? {
            if (!include) return null
            val rows = ArrayList<MessageRow>()
            runCatching {
                export.forEach(
                    spec = FilterSpec.DEFAULT,
                    selection = null,
                    includeTree = false,
                    limit = MAX_MESSAGE_ROWS,
                ) { message ->
                    rows +=
                        MessageRow(
                            id = message.id,
                            receivedAtWall = message.receivedAtWall,
                            classId = message.classId,
                            severity = message.severity,
                            direction = message.direction,
                            slot = message.slot,
                            originator = message.originator,
                            callingPackage = message.callingPackage,
                            consumerPackage = message.consumerPackage,
                        )
                }
            }
            return rows
        }

        private fun fileName(): String =
            "nightjar-diagnostics-" +
                DateTimeFormatter
                    .ofPattern("yyyyMMdd-HHmmss")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.ofEpochMilli(clock.now())) +
                ".zip"

        private companion object {
            const val TAG = "diagnostics"

            /** The diagnostics window is the last 30 days of the audit trail. */
            const val AUDIT_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

            /** A bug report needs the recent messages; this keeps the bundle small enough to attach. */
            const val MAX_MESSAGE_ROWS = 1_000
        }
    }
