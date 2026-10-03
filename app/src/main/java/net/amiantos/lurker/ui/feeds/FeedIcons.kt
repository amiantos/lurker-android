// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.feeds

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The feeds' glyphs, as `LurkerIcons` builds its own: Material's path data (`alternate_email`,
 * `bookmark_border`, `bookmark_remove` — Apache-2.0), with no icon dependency.
 *
 * Kept beside the feeds rather than added to `LurkerIcons` while other slices are editing that file in
 * parallel; they belong there once the slices have landed.
 */
object FeedIcons {
    /** The activity feed (iOS's `at`). */
    val AlternateEmail: ImageVector by lazy {
        icon(
            "AlternateEmail",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10h5v-2h-5c-4.34,0 -8,-3.66 -8,-8s3.66,-8 8,-8 8,3.66 8,8v1.43" +
                "c0,0.79 -0.71,1.57 -1.5,1.57s-1.5,-0.78 -1.5,-1.57L17,12c0,-2.76 -2.24,-5 -5,-5s-5,2.24 -5,5 2.24,5 5,5" +
                "c1.38,0 2.64,-0.56 3.54,-1.47 0.65,0.89 1.77,1.47 2.96,1.47 1.97,0 3.5,-1.6 3.5,-3.57L22,12" +
                "c0,-5.52 -4.48,-10 -10,-10zM12,15c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3z",
        )
    }

    /** Bookmarks (iOS's `bookmark`). */
    val Bookmark: ImageVector by lazy {
        icon("Bookmark", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2zM17,18l-5,-2.18L7,18V5h10v13z")
    }

    /** A bookmark's swipe-to-remove (iOS's `bookmark.slash`). */
    val BookmarkRemove: ImageVector by lazy {
        icon(
            "BookmarkRemove",
            "M17,11v6.97l-5,-2.14l-5,2.14V5h6V3H7C5.9,3 5,3.9 5,5v16l7,-3l7,3V11H17zM21,7h-6V5h6V7z",
        )
    }

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
