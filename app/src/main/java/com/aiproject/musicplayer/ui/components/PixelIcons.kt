package com.aiproject.musicplayer.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Pixel-grid icons in the style of the airwitech.com glyphs (crisp squares).
 * Each icon is a list of (x, y, w, h) cells on a small grid.
 */
object PixelIcons {
    private fun build(name: String, grid: Float, cells: List<IntArray>): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, grid, grid).apply {
            path(fill = SolidColor(Color.Black)) {
                for (c in cells) {
                    moveTo(c[0].toFloat(), c[1].toFloat())
                    horizontalLineToRelative(c[2].toFloat())
                    verticalLineToRelative(c[3].toFloat())
                    horizontalLineToRelative(-c[2].toFloat())
                    close()
                }
            }
        }.build()

    private fun cells(vararg values: Int): List<IntArray> = values.toList().chunked(4).map { it.toIntArray() }

    val Play = build("Play", 11f, cells(3, 1, 2, 9, 5, 2, 1, 7, 6, 3, 1, 5, 7, 4, 1, 3, 8, 5, 1, 1))
    val Pause = build("Pause", 11f, cells(2, 1, 2, 9, 7, 1, 2, 9))
    val Stop = build("Stop", 11f, cells(2, 2, 7, 7))
    val Next = build("Next", 11f, cells(1, 2, 1, 7, 2, 3, 1, 5, 3, 4, 1, 3, 4, 5, 1, 1, 5, 2, 1, 7, 6, 3, 1, 5, 7, 4, 1, 3, 8, 5, 1, 1, 9, 2, 1, 7))
    val Previous = build("Previous", 11f, cells(1, 2, 1, 7, 2, 5, 1, 1, 3, 4, 1, 3, 4, 3, 1, 5, 5, 2, 1, 7, 6, 5, 1, 1, 7, 4, 1, 3, 8, 3, 1, 5, 9, 2, 1, 7))

    /** Half-filled pixel disc used by the site's theme toggle. */
    val Theme = build(
        "Theme", 7f,
        cells(2, 0, 3, 1, 1, 1, 1, 1, 5, 1, 1, 1, 0, 2, 1, 3, 6, 2, 1, 3, 1, 5, 1, 1, 5, 5, 1, 1, 2, 6, 3, 1, 3, 1, 2, 1, 3, 2, 3, 3, 3, 5, 2, 1),
    )
}
