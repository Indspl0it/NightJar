package dev.nightjar.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Stroke width of every line icon, in the 24 unit viewport (`stroke-width: 1.8`). */
internal const val NJ_LINE_ICON_STROKE = 1.8f

/**
 * Bundled 24 dp line icons. Every icon uses the same stroke ([NJ_LINE_ICON_STROKE]), round
 * caps and round joins, and no fill. They are drawn in black and tinted by `Icon(tint = ...)`.
 */
object NjLineIcons {
    // ------------------------------------------------------------ the 4 stat icons of Home

    /** Crossed eye: hidden messages. */
    val Hidden: ImageVector by lazy {
        lineIcon(
            "Hidden",
            "M3 3l18 18M10.6 6.1A9.8 9.8 0 0 1 12 6c5 0 8.5 4 9.5 6-.5 1-1.6 2.6-3.2 3.9M6.6 7.6C4.5 9 3.1 10.9 2.5 12" +
                "c1 2 4.5 6 9.5 6 1.6 0 3-.4 4.2-1",
            "M9.9 9.9a3 3 0 0 0 4.2 4.2",
        )
    }

    /** Shield with "!": suspicious messages. */
    val Suspicious: ImageVector by lazy {
        lineIcon("Suspicious", SHIELD, "M12 8.5v4M12 15.8v.2")
    }

    /** Pause in a circle: messages on hold. */
    val OnHold: ImageVector by lazy {
        lineIcon("OnHold", circle(12f, 12f, 8.5f), "M10 9v6M14 9v6")
    }

    /** Circle with a slash: blocked messages. */
    val Blocked: ImageVector by lazy {
        lineIcon("Blocked", circle(12f, 12f, 8.5f), "m6 6 12 12")
    }

    // ------------------------------------------------------------ menu icons

    /** Pulse line: protection status. */
    val Status: ImageVector by lazy { lineIcon("Status", "M4 13h3l2-5 4 9 2-4h5") }

    /** Shield with a tick: safe mode. */
    val SafeMode: ImageVector by lazy { lineIcon("SafeMode", SHIELD, "m9 12 2 2 4-4") }

    /** Test tube with a fill line: run the self-test. */
    val SelfTest: ImageVector by lazy {
        lineIcon("SelfTest", "M9 3h6M10 3v6l-5 9a2 2 0 0 0 1.8 3h10.4a2 2 0 0 0 1.8-3l-5-9V3", "M7.5 15h9")
    }

    /** Circle with "i": info (the menu item "Info" was "Help"). */
    val Info: ImageVector by lazy {
        lineIcon("Info", circle(12f, 12f, 8.5f), "M12 11v5.5M12 7.8v.2")
    }

    /** Two pages: copy the text. */
    val Copy: ImageVector by lazy {
        lineIcon("Copy", "M10 8h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z", "M5 15V6a1 1 0 0 1 1-1h9")
    }

    /** Angle brackets: copy the hex. */
    val Code: ImageVector by lazy { lineIcon("Code", "m9 7-5 5 5 5M15 7l5 5-5 5") }

    /** Tick: mark as expected. */
    val Check: ImageVector by lazy { lineIcon("Check", "m5 12.5 4.5 4.5L19 7.5") }

    /** App square with a tick: trust the app. */
    val TrustApp: ImageVector by lazy {
        lineIcon("TrustApp", "M8 4h8a4 4 0 0 1 4 4v8a4 4 0 0 1-4 4h-8a4 4 0 0 1-4-4v-8a4 4 0 0 1 4-4z", "m9 12 2 2 4-4")
    }

    /** Arrow out of a tray: export. */
    val Export: ImageVector by lazy { lineIcon("Export", "M12 4v11M8 8l4-4 4 4", "M5 14v5a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-5") }

    /** Ring with a filled dot, like the "not reviewed" dot of a list row: mark as not reviewed. */
    val Unreviewed: ImageVector by lazy { lineIcon("Unreviewed", circle(12f, 12f, 8.5f), filled = listOf(circle(12f, 12f, 3f))) }

    /** Ring with a tick: mark as reviewed. */
    val Reviewed: ImageVector by lazy { lineIcon("Reviewed", circle(12f, 12f, 8.5f), "m8.5 12.2 2.4 2.4 4.6-4.8") }

    /** Five-point star outline: star the messages. */
    val Star: ImageVector by lazy { lineIcon("Star", "m12 4 2.4 5 5.4.7-4 3.8 1 5.4L12 16.3 7.2 18.9l1-5.4-4-3.8 5.4-.7z") }

    /** Two stacked pages, the front one with a tick: select all. */
    val SelectAll: ImageVector by lazy {
        lineIcon(
            "SelectAll",
            "M9 7h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z",
            "M4 16V5a1 1 0 0 1 1-1h11",
            "m10.5 13.5 2 2 4-4",
        )
    }

    /** Bin outline: delete. */
    val Delete: ImageVector by lazy { lineIcon("Delete", "M5 7h14M10 7V5h4v2M7 7l1 12h8l1-12") }

    /** Two opposite arrows: invert the selection. */
    val Invert: ImageVector by lazy { lineIcon("Invert", "M6 8h12M15 5l3 3-3 3", "M18 16H6M9 13l-3 3 3 3") }

    /** Ring with a cross: clear the selection. */
    val ClearAll: ImageVector by lazy { lineIcon("ClearAll", circle(12f, 12f, 8.5f), "m9.5 9.5 5 5M14.5 9.5l-5 5") }

    // ------------------------------------------------------------ onboarding (nightjar-welcome-copy.html)

    /** A dot with two radio arcs on each side: a silent SMS (ping). */
    val Ping: ImageVector by lazy {
        lineIcon(
            "Ping",
            "M12 12h.01",
            "M8.5 8.5a5 5 0 0 0 0 7M15.5 8.5a5 5 0 0 1 0 7M5.6 5.6a9 9 0 0 0 0 12.8M18.4 5.6a9 9 0 0 1 0 12.8",
        )
    }

    /** Three list lines with a tick: a settings push (APN, proxy). */
    val SettingsPush: ImageVector by lazy { lineIcon("SettingsPush", "M4 7h16M4 12h10M4 17h7", "m16 15 2 2 4-4") }

    /** Map pin: a location request. */
    val Location: ImageVector by lazy {
        lineIcon("Location", "M12 21s-6-5.5-6-11a6 6 0 0 1 12 0c0 5.5-6 11-6 11z", circle(12f, 10f, 2f))
    }

    /** Padlock: data that stays on the phone. */
    val Lock: ImageVector by lazy {
        lineIcon("Lock", "M8 11h8a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2z", "M9 11V8a3 3 0 0 1 6 0v3")
    }

    private const val SHIELD = "M12 3 5 6v5c0 4.5 3 8 7 10 4-2 7-5.5 7-10V6z"

    /** SVG path data for a circle at ([cx], [cy]) with radius [r], as two arcs. */
    private fun circle(
        cx: Float,
        cy: Float,
        r: Float,
    ): String = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    /** [paths] are stroked. [filled] paths are filled and have no stroke, for example the dot of [Unreviewed]. */
    private fun lineIcon(
        name: String,
        vararg paths: String,
        filled: List<String> = emptyList(),
    ): ImageVector =
        ImageVector
            .Builder(name, 24.dp, 24.dp, 24f, 24f)
            .apply {
                paths.forEach { data ->
                    addPath(
                        pathData = addPathNodes(data),
                        fill = null,
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = NJ_LINE_ICON_STROKE,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
                filled.forEach { data -> addPath(pathData = addPathNodes(data), fill = SolidColor(Color.Black)) }
            }.build()
}
