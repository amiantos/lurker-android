// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.commands

import net.amiantos.lurkerkit.support.TextRange
import net.amiantos.lurkerkit.support.graphemeBoundaries

/**
 * The smallest edit that turns one text into another — so a composer that rewrites its field
 * (a completion, a Reply's address) can touch only what changed, and the colour on the rest of
 * the line survives it.
 */
object TextEdit {

    /**
     * What [difference] answers.
     *
     * Port note: the Swift returns a named tuple, `(range: NSRange, replacement: String)`.
     */
    data class Difference(val range: TextRange, val replacement: String)

    /**
     * The range of `old` to replace, in UTF-16 (`TextRange`'s and the text field's currency), and
     * what to put there.
     *
     * ⚠ The ends are snapped out to whole characters. A common prefix counted in UTF-16 units
     * stops INSIDE a surrogate pair when two emoji share their high half (`😀` and `😃` both
     * open with D83D), and a splice there writes a lone surrogate — a broken character on the
     * wire. Snapping can only widen the edit, which costs nothing.
     *
     * Port note: a character is a grapheme cluster from `support.graphemeBoundaries`, where
     * LurkerKit asks `NSString.rangeOfComposedCharacterSequence(at:)`. Both keep a surrogate pair
     * and a flag whole, but the two are not the same segmentation: a composed sequence ends
     * between the `\r` and `\n` of a CRLF, and around a regional indicator wearing a mark, where
     * a cluster does not. Run against the Swift over 4,000 generated pairs, 122 edits fell
     * somewhere else (115 of them at a CRLF); every one applied gives the same text, and these
     * never cut a cluster.
     */
    fun difference(old: String, new: String): Difference {
        val a = old
        val b = new
        val aEnds = graphemeBoundaries(a)
        val bEnds = graphemeBoundaries(b)
        val shorter = minOf(a.length, b.length)
        var prefix = 0
        while (prefix < shorter && a[prefix] == b[prefix]) prefix += 1
        while (true) {
            val snapped = minOf(start(a, aEnds, prefix), start(b, bEnds, prefix))
            if (snapped == prefix) break
            prefix = snapped
        }
        var suffix = 0
        while (suffix < shorter - prefix && a[a.length - 1 - suffix] == b[b.length - 1 - suffix]) {
            suffix += 1
        }
        while (true) {
            val snapped = minOf(
                a.length - end(a, aEnds, a.length - suffix),
                b.length - end(b, bEnds, b.length - suffix),
            )
            if (snapped == suffix) break
            suffix = snapped
        }
        return Difference(
            TextRange.of(prefix, a.length - prefix - suffix),
            b.substring(prefix, b.length - suffix),
        )
    }

    /** `index`, moved back to the start of the character it falls inside. */
    private fun start(text: String, ends: List<Int>, index: Int): Int {
        if (index >= text.length) return index
        return character(ends, index).start
    }

    /** `index` as the end of an edit, moved forward to the end of the character it falls inside. */
    private fun end(text: String, ends: List<Int>, index: Int): Int {
        if (index <= 0 || index >= text.length) return index
        val character = character(ends, index)
        return if (character.start < index) character.end else index
    }

    /**
     * The character `index` falls inside — `rangeOfComposedCharacterSequence(at:)` over the
     * cluster ends `graphemeBoundaries` gave. Port-only.
     */
    private fun character(ends: List<Int>, index: Int): TextRange {
        var start = 0
        for (end in ends) {
            if (index < end) return TextRange(start, end)
            start = end
        }
        return TextRange(index, index)
    }
}
