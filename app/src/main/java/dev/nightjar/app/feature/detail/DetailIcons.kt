package dev.nightjar.app.feature.detail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Glyphs that [dev.nightjar.app.ui.components.NjIcons] does not have: the action tiles of the detail screen and
 * the row actions of Messages, Rules and Known senders. Material shapes, 24 dp, drawn in the tint of the icon.
 */
internal object DetailIcons {
    /** An arrow that points right: Release hands the message on. */
    val Release: ImageVector by lazy { icon("Release", "M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z") }

    /** A circle with a slash. */
    val Block: ImageVector by lazy {
        icon(
            "Block",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM4 12c0-4.42 3.58-8 8-8 1.85 0 3.55.63 4.9 1.69" +
                "L5.69 16.9C4.63 15.55 4 13.85 4 12zm8 8c-1.85 0-3.55-.63-4.9-1.69L18.31 7.1C19.37 8.45 20 10.15 20 12" +
                "c0 4.42-3.58 8-8 8z",
        )
    }

    /** A five-point star: Star a message. */
    val Star: ImageVector by lazy {
        icon("Star", "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z")
    }

    /** Three joined dots: Export (share) the selection. */
    val Share: ImageVector by lazy {
        icon(
            "Share",
            "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81" +
                " 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3" +
                "s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92 1.61 0 2.92-1.31" +
                " 2.92-2.92s-1.31-2.92-2.92-2.92z",
        )
    }

    /** A curved arrow back: undo a state, for example "Mark as not reviewed". */
    val Undo: ImageVector by lazy {
        icon(
            "Undo",
            "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5" +
                "l2.37-.78C21.08 11.03 17.15 8 12.5 8z",
        )
    }

    /** Two bars: the drag handle of a rule. */
    val DragHandle: ImageVector by lazy { icon("DragHandle", "M20 9H4v2h16V9zM4 15h16v-2H4v2z") }

    /** A person: a known sender. */
    val Person: ImageVector by lazy {
        icon(
            "Person",
            "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z",
        )
    }

    private fun icon(
        name: String,
        pathData: String,
    ): ImageVector =
        ImageVector
            .Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
            .build()
}
