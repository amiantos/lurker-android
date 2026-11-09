// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.support

/**
 * A span of a string in UTF-16 code units, half-open: `[start, end)`.
 *
 * Port-only — the stand-in for every `NSRange` in LurkerKit. An `NSRange` counts UTF-16
 * units and so does a Kotlin `String` index, so offsets carry over unchanged; only the shape
 * differs (`location`/`length` there, `start`/`end` here, to match `String.substring` and
 * Compose's own `TextRange`).
 *
 * Not an `IntRange`: that one is closed, so an empty span has no honest spelling, and "where
 * the caret is" is a span this code asks about.
 */
data class TextRange(val start: Int, val end: Int) {
    init {
        require(start in 0..end) { "TextRange($start, $end)" }
    }

    val length: Int get() = end - start

    val isEmpty: Boolean get() = start == end

    /**
     * Where a span boundary at [position] lands once this range has been swapped for something
     * [replacementLength] units long — the one rule a body's marks follow when text under them is
     * deleted (`replacementLength` 0) or a `:name:` becomes its glyph (lurker#1294).
     *
     * A boundary before the swap stays; one after it moves by the difference; one INSIDE it goes to
     * the end of the replacement — so a span that began before and ended inside takes the
     * replacement in (a bold `:tada:` is a bold 🎉, iOS's "the glyph takes the attributes of the
     * first character"), a span that began inside and ran on starts after it, and a span wholly
     * inside (a nick coloured inside the name) collapses to nothing and is dropped.
     */
    fun moved(position: Int, replacementLength: Int): Int = when {
        position <= start -> position
        position >= end -> position - length + replacementLength
        else -> start + replacementLength
    }

    companion object {
        /** From an `NSRange`'s own terms. */
        fun of(location: Int, length: Int): TextRange = TextRange(location, location + length)
    }
}

/** The text this range covers. */
fun String.substring(range: TextRange): String = substring(range.start, range.end)
