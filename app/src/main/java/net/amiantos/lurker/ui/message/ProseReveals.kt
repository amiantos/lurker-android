// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The spoilers a page's prose row has open, kept by the page rather than the row (`ProseText`) so a
 * lazy row scrolling out and back, or the page being covered and uncovered, draws them as they were
 * — a box the reader opened must not snap shut for either. Kept with the text they were opened in,
 * so a new topic's boxes start shut. iOS's `ProseReveals`.
 */
class ProseReveals {
    private var open by mutableStateOf("" to emptySet<Int>())

    /** The open spoilers of [text] — none, once it differs from the text they were opened in. */
    fun ordinalsFor(text: String): Set<Int> = if (open.first == text) open.second else emptySet()

    fun toggle(text: String, ordinal: Int) {
        val was = ordinalsFor(text)
        open = text to if (ordinal in was) was - ordinal else was + ordinal
    }
}
