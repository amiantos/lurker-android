// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few glyphs the app draws, as vectors.
 *
 * material3 stopped depending on `material-icons-core` (1.4), so there is no `Icons.Default` in
 * this build, and `material-icons-extended` is a multi-megabyte dependency for a handful of
 * shapes. The path data is Material's own (`edit`, `more_vert`, `arrow_back`, `keyboard`; Apache-2.0), so a
 * glyph here is the one every Android user already reads. Tinted by the `Icon` that draws it.
 */
object LurkerIcons {
    /** A draft waiting in a buffer's composer — the web's pencil (lurker-ios#188). */
    val Pencil: ImageVector by lazy {
        icon(
            "Pencil",
            "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25z" +
                "M20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0" +
                "l-1.83,1.83 3.75,3.75 1.83,-1.83z",
        )
    }

    /** An overflow menu — "More". */
    val MoreVert: ImageVector by lazy {
        icon(
            "MoreVert",
            "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2z" +
                "M12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z" +
                "M12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z",
        )
    }

    /**
     * Back to the buffer list from a conversation on a phone. Mirrored in a right-to-left layout,
     * where "back" points the other way.
     */
    val ArrowBack: ImageVector by lazy {
        icon("ArrowBack", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z", autoMirror = true)
    }

    /**
     * The typing line's lead-in (lurker-ios#61) — iOS's `keyboard` symbol, drawn inline as the
     * line's first glyph. See `MessageText.renderCompactTyping`.
     */
    val Keyboard: ImageVector by lazy {
        icon(
            "Keyboard",
            "M20,5H4c-1.1,0 -1.99,0.9 -1.99,2L2,17c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2z" +
                "M11,8h2v2h-2zM11,11h2v2h-2zM8,8h2v2H8zM8,11h2v2H8zM7,13H5v-2h2v2zM7,10H5V8h2v2z" +
                "M16,17H8v-2h8v2zM16,13h-2v-2h2v2zM16,10h-2V8h2v2zM19,13h-2v-2h2v2zM19,10h-2V8h2v2z",
        )
    }

    private fun icon(name: String, path: String, autoMirror: Boolean = false): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
