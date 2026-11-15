package dev.nightjar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.nightjar.app.R
import dev.nightjar.app.core.settings.ThemeMode
import dev.nightjar.app.ui.theme.PreviewTheme
import dev.nightjar.contract.TraceStage
import java.util.Locale

/**
 * Hex dump of a PDU (Raw tab).
 *
 * Eight bytes per row, offset column, ASCII gutter. The block scrolls horizontally instead of wrapping
 * and is selectable, so a researcher can copy a range. The whole
 * view is one TalkBack item; each row is not a separate stop.
 */
@Composable
fun HexView(
    bytes: ByteArray,
    modifier: Modifier = Modifier,
    bytesPerRow: Int = BYTES_PER_ROW,
    maxRows: Int = Int.MAX_VALUE,
) {
    val rows = remember(bytes, bytesPerRow, maxRows) { dumpRows(bytes, bytesPerRow, maxRows) }
    val description = pluralStringResource(R.plurals.cd_hex_view, rows.size, rows.size)
    val scroll = rememberScrollState()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(12.dp)
                .semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SelectionContainer {
            Column(Modifier.horizontalScroll(scroll)) {
                rows.forEach { row ->
                    Row {
                        Text(
                            text = row.offset,
                            style = hexStyle(MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                        Text(text = "  " + row.hex, style = hexStyle(MaterialTheme.colorScheme.onSurface))
                        Text(text = "  " + row.ascii, style = hexStyle(MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                }
            }
        }
    }
}

/** One rendered row. */
data class HexRow(
    val offset: String,
    val hex: String,
    val ascii: String,
)

/** Formats [bytes] into rows of [perRow] bytes; at most [maxRows] rows. */
fun dumpRows(
    bytes: ByteArray,
    perRow: Int = BYTES_PER_ROW,
    maxRows: Int = Int.MAX_VALUE,
): List<HexRow> {
    if (bytes.isEmpty()) return emptyList()
    val rowCount = minOf((bytes.size + perRow - 1) / perRow, maxRows)
    return (0 until rowCount).map { row ->
        val start = row * perRow
        val end = minOf(start + perRow, bytes.size)
        val hex = StringBuilder(perRow * 3)
        val ascii = StringBuilder(perRow)
        for (i in start until end) {
            val b = bytes[i].toInt() and 0xFF
            if (i > start) hex.append(' ')
            hex.append(String.format(Locale.ROOT, "%02X", b))
            ascii.append(if (b in 0x20..0x7E) b.toChar() else '.')
        }
        HexRow(
            offset = String.format(Locale.ROOT, "%04X", start),
            hex = hex.toString(),
            ascii = ascii.toString(),
        )
    }
}

@Composable
private fun hexStyle(color: androidx.compose.ui.graphics.Color) =
    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = color)

/** Eight bytes per row. */
const val BYTES_PER_ROW = 8

/**
 * The pipeline trace of a message (Trace tab).
 *
 * Rows are `stage`, relative time from the first stage and the stage detail. The list is one TalkBack item so a long
 * trace does not trap the reader in dozens of stops.
 */
@Composable
fun TraceList(
    steps: List<TraceRow>,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.cd_trace_list)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        steps.forEach { step ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = step.relative,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Column(Modifier.fillMaxWidth()) {
                    Text(step.stage, style = MaterialTheme.typography.bodyMedium)
                    if (!step.detail.isNullOrBlank()) {
                        Text(
                            text = step.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** One trace row, already formatted for display. */
data class TraceRow(
    val stage: String,
    val relative: String,
    val detail: String?,
)

/** Builds display rows from raw `(stage, tNanos, detail)` triples, relative to the first stage. */
fun traceRows(steps: List<Triple<String, Long, String?>>): List<TraceRow> {
    val base = steps.minOfOrNull { it.second } ?: 0L
    return steps.map { (stage, tNanos, detail) ->
        val deltaMs = (tNanos - base) / 1_000_000
        TraceRow(stage, "+${deltaMs}ms", detail)
    }
}

@Preview(name = "Hex view")
@Composable
private fun HexViewPreview() {
    PreviewTheme(themeMode = ThemeMode.LIGHT) {
        HexView(
            bytes = byteArrayOf(0x00, 0x11, 0x02, 0x0A, 0x91.toByte(), 0x98.toByte(), 0x76, 0x54, 0x32, 0x10, 0x00, 0x00, 0x41, 0x42),
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Trace list", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TraceListPreview() {
    PreviewTheme(themeMode = ThemeMode.DARK) {
        TraceList(
            steps =
                traceRows(
                    listOf(
                        Triple(TraceStage.RADIO_IMS_ON_SMS_RECEIVED.wire(), 0L, "token=7"),
                        Triple("radio.dispatchMessage", 1_400_000L, "subId=2"),
                        Triple("app.ingest", 9_000_000L, "class=CL-SILENT"),
                    ),
                ),
            modifier = Modifier.padding(16.dp),
        )
    }
}
