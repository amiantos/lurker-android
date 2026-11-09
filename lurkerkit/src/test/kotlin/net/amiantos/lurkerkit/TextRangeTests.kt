// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.support.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

/** The kit's `NSRange` stand-in, and the one rule a body's marks follow when text under them changes. */
class TextRangeTests {

    @Test
    fun testMovedKeepsBoundariesStraightAroundASwap() {
        // `x :tada: y` → `x 🎉 y`: the shortcode is [2, 8), the glyph two units long.
        val range = TextRange(2, 8)
        val glyph = 2
        // Before the swap: unmoved. After it: back by the difference (6 - 2 = 4).
        assertEquals(0, range.moved(0, glyph))
        assertEquals(2, range.moved(2, glyph))
        assertEquals(4, range.moved(8, glyph))
        assertEquals(6, range.moved(10, glyph))
        // Inside it: the end of the glyph — so a span that began before and ended inside takes the
        // glyph in (a bold `:tada:` is a bold 🎉), one that began inside and ran on starts after it,
        // and one wholly inside (a nick coloured in the name) collapses to nothing.
        assertEquals(4, range.moved(3, glyph))
        assertEquals(4, range.moved(7, glyph))
    }

    @Test
    fun testMovedWithNothingPutBackIsADeletion() {
        // The rule `deleteCharacters` always had: before the cut stays, after it moves back by the
        // cut, inside it lands where the cut began.
        val cut = TextRange(2, 8)
        assertEquals(1, cut.moved(1, 0))
        assertEquals(2, cut.moved(2, 0))
        assertEquals(2, cut.moved(5, 0))
        assertEquals(2, cut.moved(8, 0))
        assertEquals(4, cut.moved(10, 0))
    }
}
