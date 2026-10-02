// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.support

import java.text.BreakIterator
import java.util.Locale

/**
 * Where each of `text`'s grapheme clusters — a Swift `Character` — ends, in UTF-16 units; as
 * many entries as `text.count` is in Swift. Port-only, and the one place this module segments
 * text into characters.
 *
 * ⚠ `java.text.BreakIterator`, which is two implementations — the host JVM's and, on Android,
 * ICU's — neither of them Swift's, each with its own version of Unicode's rules. Checked
 * against the Swift on the host (JDK 21): CR-LF, astral letters, ZWJ families (Unicode 15.1's
 * included), flags, tag-sequence flags, skin tones, keycaps, Hangul jamo and combining marks
 * all segment as Swift segments them. Indic conjuncts do not — `क्ष` is one cluster to Swift
 * (Unicode 15.1's rule GB9c) and two to JDK 21. A device's answer depends on its Android
 * version and is unverified. Where two implementations differ it is by splitting what the
 * other joins.
 */
internal fun graphemeBoundaries(text: String): List<Int> {
    val clusters = BreakIterator.getCharacterInstance(Locale.ROOT)
    clusters.setText(text)
    val ends = mutableListOf<Int>()
    while (true) {
        val end = clusters.next()
        if (end == BreakIterator.DONE) break
        ends.add(end)
    }
    return ends
}

/**
 * `String(decoding:as: UTF16.self)` over `this[start, end)`: the units as a string, with half
 * a surrogate pair repaired to U+FFFD — what a caret sitting inside an emoji leaves at the edge
 * of a slice. `substring` would hand the lone surrogate back. Port-only.
 */
internal fun String.decodingUtf16(start: Int, end: Int): String {
    val out = StringBuilder(end - start)
    var index = start
    while (index < end) {
        val unit = this[index]
        if (unit.isHighSurrogate() && index + 1 < end && this[index + 1].isLowSurrogate()) {
            out.append(unit).append(this[index + 1])
            index += 2
            continue
        }
        out.append(if (unit.isSurrogate()) REPLACEMENT else unit)
        index += 1
    }
    return out.toString()
}

private const val REPLACEMENT = 0xFFFD.toChar()
