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

    companion object {
        /** From an `NSRange`'s own terms. */
        fun of(location: Int, length: Int): TextRange = TextRange(location, location + length)
    }
}

/** The text this range covers. */
fun String.substring(range: TextRange): String = substring(range.start, range.end)
