package dev.nightjar.app.feature.export

import dev.nightjar.app.data.model.FilterSpec
import java.io.File

/** Export scope (-01). */
enum class ExportScope {
    SELECTION,
    FILTER,
    ALL,
}

/** Export format. */
enum class ExportFormat(
    val mime: String,
    val extension: String,
) {
    JSON("application/json", "json"),
    PDU_HEX("text/plain", "txt"),
    CSV("text/csv", "csv"),
}

/** Scr-11-01 options. [maskNumbers] is the EXP redaction switch. */
data class ExportOptions(
    val includeText: Boolean = false,
    val includeTree: Boolean = false,
    val maskNumbers: Boolean = false,
)

/** How many messages each scope currently covers. */
data class ExportCounts(
    val selection: Int = 0,
    val filter: Int = 0,
    val all: Int = 0,
) {
    fun of(scope: ExportScope): Int =
        when (scope) {
            ExportScope.SELECTION -> selection
            ExportScope.FILTER -> filter
            ExportScope.ALL -> all
        }
}

data class ExportProgress(
    val done: Int,
    val total: Int,
)

data class ExportUiState(
    val loading: Boolean = true,
    val counts: ExportCounts = ExportCounts(),
    val scope: ExportScope = ExportScope.ALL,
    val format: ExportFormat = ExportFormat.JSON,
    val options: ExportOptions = ExportOptions(),
    val progress: ExportProgress? = null,
    val hasFilter: Boolean = false,
    val hasSelection: Boolean = false,
) {
    val busy: Boolean get() = progress != null
    val scopeCount: Int get() = counts.of(scope)
}

sealed interface ExportEvent {
    data class Done(
        val count: Int,
        val destination: String?,
    ) : ExportEvent

    /** The export did not complete. The reason goes to the app log, never to the screen. */
    data object Failed : ExportEvent

    data object Cancelled : ExportEvent

    /** Share sheet input: the file written into `cache/exports`. */
    data class ShareReady(
        val file: File,
    ) : ExportEvent
}

/**
 * The scope and filter passed in from Messages/Detail (AC3, -01).
 *
 * The typed `Route.Export` carries no arguments (owns that file), so the messages screens deposit their selection or
 * active filter here before navigating. Until they do, those two scopes show as unavailable instead of silently
 * exporting everything.
 */
object ExportRequestStore {
    @Volatile private var selectionIds: List<Long> = emptyList()

    @Volatile private var activeFilter: FilterSpec? = null

    fun setSelection(ids: List<Long>) {
        selectionIds = ids.distinct()
    }

    fun setFilter(spec: FilterSpec?) {
        activeFilter = spec
    }

    fun snapshot(): ExportRequest = ExportRequest(selectionIds, activeFilter)

    /** Forgets both slots once Export has captured them, so the next export does not inherit this one. */
    fun clear() {
        selectionIds = emptyList()
        activeFilter = null
    }
}

/**
 * The Detail "Export" entry point: a single message becomes a one-row selection (AC3).
 *
 * Kept next to the store so the detail screen does not have to know the store's two-slot shape.
 */
fun exportOne(messageId: Long) {
    ExportRequestStore.setSelection(listOf(messageId))
    ExportRequestStore.setFilter(null)
}

data class ExportRequest(
    val selection: List<Long> = emptyList(),
    val filter: FilterSpec? = null,
)
