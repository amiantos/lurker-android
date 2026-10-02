// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.support

// Swift's three ideas of "whitespace", none of which is Kotlin's `Char.isWhitespace()`.
// Port-only. The memberships were enumerated from Foundation and the Swift standard library
// on a Mac, and `CharacterSetsTests` pins every one of them.
//
// ⚠ `trim()` looks like the translation of `trimmingCharacters(in: .whitespacesAndNewlines)`
// and is not: it leaves a zero-width space and a NEL where Foundation trims them, and trims
// the C0 separators (U+001C–001F) where Foundation leaves them. A name that is only a
// zero-width space — which a paste produces — would be blank on iOS and a name here.

/**
 * `CharacterSet.whitespaces`: Unicode space separators (Zs), the tab, and the zero-width
 * space. No newlines.
 */
fun Char.isInWhitespaces(): Boolean =
    this == '\t' || this == '​' || Character.getType(this) == Character.SPACE_SEPARATOR.toInt()

/**
 * `CharacterSet.whitespacesAndNewlines`: [isInWhitespaces] plus LF, VT, FF, CR, NEL and the
 * line and paragraph separators.
 */
fun Char.isInWhitespacesAndNewlines(): Boolean =
    isInWhitespaces() || this in '\n'..'\r' || this == '\u0085' || this == ' ' || this == ' '

/**
 * Swift's `Character.isWhitespace` — Unicode's White_Space property. The same as
 * [isInWhitespacesAndNewlines] without the zero-width space, which is not White_Space.
 */
fun Char.isSwiftWhitespace(): Boolean = this != '​' && isInWhitespacesAndNewlines()

/** `trimmingCharacters(in: .whitespacesAndNewlines)`. */
fun String.trimmingWhitespacesAndNewlines(): String = trim { it.isInWhitespacesAndNewlines() }

/** `trimmingCharacters(in: .whitespaces)`. */
fun String.trimmingWhitespaces(): String = trim { it.isInWhitespaces() }

/** Swift's `split(whereSeparator: \.isWhitespace)`: the runs between whitespace, none empty. */
fun String.splitOnSwiftWhitespace(): List<String> {
    val tokens = mutableListOf<String>()
    var start = -1
    for ((index, character) in withIndex()) {
        if (character.isSwiftWhitespace()) {
            if (start >= 0) tokens.add(substring(start, index))
            start = -1
        } else if (start < 0) {
            start = index
        }
    }
    if (start >= 0) tokens.add(substring(start))
    return tokens
}
