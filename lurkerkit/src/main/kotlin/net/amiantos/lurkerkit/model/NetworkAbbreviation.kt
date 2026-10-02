// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * Short network labels for the buffer-list grid chips, where a buffer has been lifted out of
 * its network's section and two chips can otherwise be pixel-identical (`#lurker` on two
 * networks, `alice` on two networks).
 *
 * The label is the **shortest prefix of the network name that no other network shares** —
 * `l` for libera on an instance where it's the only `l…`, `li`/`lu` once libera and lurkernet
 * coexist. The web client (`BufferList.vue`'s `networkAbbrevs`) computes the same thing for
 * the same rows, so the two clients name a network the same way.
 *
 * A prefix rather than the full name because the full name next to a nick read as clutter at
 * every length tried, and because this rides *inside* the chip's one line — the thing it
 * disambiguates is the name it follows, so it has to stay small enough not to become the
 * thing you read first. The full name is still what the accessibility label says, for
 * everyone, hint or no hint.
 *
 * ⚠ Uniqueness is computed against **every** network, not just the ones currently colliding
 * on screen. Otherwise the same network would abbreviate differently from section to section
 * as buffers came and went, and a label that changes under you is worse than a long one.
 */
object NetworkAbbreviation {
    /**
     * Shortest-unique-prefix label per network id, lowercased.
     *
     * Two networks with the *same* name both get the full name: no prefix can separate them,
     * so the loop runs out of string and returns what it has. That's the honest answer — the
     * names really are ambiguous — and it beats inventing a tiebreaker the web doesn't have.
     *
     * Port note: LurkerKit grows the prefix a `Character` (grapheme cluster) at a time; this
     * grows it a code point at a time. A code point, not a UTF-16 unit, so a label never ends
     * on half a surrogate pair (an emoji, or a letter outside the BMP, is one step). It is not a
     * grapheme: a prefix can end between a letter and its combining mark, or inside a
     * multi-code-point emoji, where the Swift one cannot. And `startsWith` compares units, where
     * Swift's `hasPrefix` compares whole characters under canonical equivalence — so a
     * precomposed `é` and `e` + U+0301 are the same prefix there and different ones here.
     */
    fun shortestUniquePrefixes(namesById: Map<Int, String>): Map<Int, String> {
        val lowered = namesById.mapValues { it.value.lowercase() }
        val out = mutableMapOf<Int, String>()
        for ((id, name) in lowered) {
            var length = 1
            while (length < name.codePointCount(0, name.length)) {
                val candidate = prefix(name, length)
                // Against EVERY other name, not just the ones already visited — the latter
                // would make the answer depend on map iteration order.
                val shared = lowered.any { it.key != id && it.value.startsWith(candidate) }
                if (!shared) break
                length += 1
            }
            out[id] = prefix(name, length)
        }
        return out
    }

    /**
     * The first [length] code points of [name], or all of it when it has fewer — Swift's
     * `prefix(_:)`, which clamps rather than throws (the empty name asks for 1 of 0).
     */
    private fun prefix(name: String, length: Int): String {
        val count = name.codePointCount(0, name.length)
        return name.substring(0, name.offsetByCodePoints(0, minOf(length, count)))
    }
}
