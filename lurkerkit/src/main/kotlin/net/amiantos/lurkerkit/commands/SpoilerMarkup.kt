// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.commands

/**
 * Discord-style `||spoiler||` → IRC spoiler codes on the way out, ported from the web client's
 * `vue_client/src/utils/spoilerMarkup.ts` so both clients turn the same typed text into the same
 * bytes.
 *
 * A spoiler on the wire is a run whose foreground and background colour are identical —
 * invisible text in any IRC client, which a client that knows the convention can upgrade into a
 * click-to-reveal box. Closing with a bare `\u0003` resets the colour without disturbing any
 * bold/italic still in effect.
 *
 * ⚠ GREY on grey (14,14), not black on black. Any matching pair hides the text, so the choice is
 * only about what the box looks like to a reader whose client draws one — and grey is the one
 * mono slot that reads as a box on both a dark and a light canvas (4.1:1 / 3.7:1, against 1.3:1
 * for black on dark and 1.1:1 for white on light). Keep in step with the web; a spoiler that
 * looks different in each client is the drift this port exists to avoid.
 *
 * Port note: LurkerKit walks the text by `Character` (grapheme cluster); this walks it by
 * UTF-16 unit, which is what the web's original does. The two differ only where a combining
 * mark directly follows a `|` or a `\`: Swift then sees one character that is neither, and
 * this (like the web) still sees the `|` or the `\`. `layout` counts the same units, and
 * `ColorMarkup.chatBody` hands it the same units, so the two writers still agree on every line.
 */
object SpoilerMarkup {
    /** The box's colour, grey on grey. `ColorMarkup.chatBody` paints its boxes from this too. */
    internal const val slot = 14
    internal const val open = "\u0003$slot,$slot"
    internal const val close = "\u0003"

    /**
     * The close to use when the very next character is a digit.
     *
     * ⚠⚠ A bare `\u0003` is a colour RESET only when nothing parseable follows it. `\u0003` then
     * `5` is colour 5, not a reset and a "5" — so `||spoiler||5 stars` put `…spoiler\u00035 stars`
     * on the wire and every client, ours included, read the digit as the code and DELETED it:
     * the channel saw " stars" in colour 5, still on the spoiler's background. `||code||1234`
     * lost two whole characters. Silent, on the wire, unrecoverable.
     *
     * `99` is IRC's "default colour", and being two digits it consumes the parser's whole
     * appetite — the following digit is then plain text. Both halves are specified so the
     * spoiler's background is cleared too; a bare `\u000399` sets only the foreground and would
     * leave the rest of the line sitting on the grey box.
     *
     * Not used unconditionally: it's six bytes heavier, and 99 is less universally understood
     * than a bare reset. Only the collision needs it.
     *
     * ⚠ Not `\u000f` (reset-all), which would work but also drops any bold or italic still in
     * effect around the spoiler — the one thing the bare `\u0003` close was chosen to preserve.
     */
    internal const val closeBeforeDigit = "\u000399,99"

    /**
     * The close that survives whatever comes next.
     *
     * ⚠ ASCII `0`–`9` only, matching `IRCFormatting.isDigit` (`0x30...0x39`) exactly — this
     * predicate has to agree with the parser it's defending against, not with a general notion
     * of numeral. `Char.isDigit` is true of `٣`, and a wider "is a number" test of `²`, `②`
     * and `Ⅷ` as well, none of which any IRC colour parser will touch, so using either would
     * spend the heavier close (and 99's less-universal semantics) on text that never needed it —
     * most often Arabic, Persian or Devanagari, which is a poor place to be needlessly clever.
     *
     * ⚠ The first SCALAR, not the character. The parser reads scalars, and a keycap `1️⃣` is one
     * character that isn't ASCII but opens with an ASCII `1` — which a bare close would read as
     * colour 1, eating the digit and breaking the emoji. (The web's `/^\d/` sees the `1`.)
     *
     * Port note: [next] is the next UTF-16 unit where LurkerKit passes the next `Character` and
     * reads its first scalar. Every digit is a single BMP unit, so the first unit of a cluster is
     * a digit exactly when its first scalar is: the two agree, keycap included.
     */
    internal fun close(next: Char?): String {
        if (next == null || next !in '0'..'9') return close
        return closeBeforeDigit
    }

    /** One spoiler's opening and closing `||`, each by the offset of its first `|`. */
    internal data class Spoiler(val open: Int, val close: Int)

    /**
     * How `apply` reads `text`, by position: where each `||` pair that makes a spoiler sits,
     * which `||` stay literal, and where the `\||` escapes are. Character offsets.
     *
     * Positional rather than a rewrite so a second writer can make the SAME spoilers out of the
     * same text: `ColorMarkup.chatBody` builds them itself on a coloured body, where the user's
     * colour has to stop at the box and resume after it, which a bare-close rewrite can't do.
     * Both read this, so the two can't disagree about what is a spoiler.
     *
     * Port note: the offsets are UTF-16 units, the unit `apply` walks here (see the note on
     * `SpoilerMarkup`), and `ColorMarkup.chatBody` builds its cells in the same units for that
     * reason.
     */
    internal data class Layout(
        /** The opening and closing `||` of each spoiler, by the offset of its first `|`. */
        val spoilers: List<Spoiler> = emptyList(),
        /** `||` that stay as text — unmatched, or an empty `||||`. */
        val literals: List<Int> = emptyList(),
        /** `\||` escapes, by the offset of the backslash. */
        val escapes: List<Int> = emptyList(),
    )

    /**
     * `\||` is the only sequence treated specially, and there is deliberately no escape for the
     * backslash itself: a lone `\` is always literal, so `path\to\file` needs no thought from
     * the user. The cost is that a literal `\||` cannot be written — judged the better trade,
     * since `||` is far commoner in real text than `\||`.
     *
     * Pairing is non-greedy — the nearest closing `||` wins, so `||a||b||c||` is a spoiler, a
     * literal `b`, then another spoiler — and an empty pair (`||||`) is left literal. Both match
     * how Discord treats them, which is where users' expectations come from.
     */
    internal fun layout(chars: CharSequence): Layout {
        val spoilers = mutableListOf<Spoiler>()
        val literals = mutableListOf<Int>()
        val escapes = mutableListOf<Int>()
        val delimiters = mutableListOf<Int>()
        var i = 0
        while (i < chars.length) {
            if (chars[i] == '\\' && i + 2 < chars.length && chars[i + 1] == '|' && chars[i + 2] == '|') {
                escapes.add(i)
                i += 3
            } else if (chars[i] == '|' && i + 1 < chars.length && chars[i + 1] == '|') {
                delimiters.add(i)
                i += 2
            } else {
                i += 1
            }
        }
        var d = 0
        while (d < delimiters.size) {
            // Something between the two — an escape counts — or the opener is just text.
            if (d + 1 < delimiters.size && delimiters[d + 1] > delimiters[d] + 2) {
                spoilers.add(Spoiler(delimiters[d], delimiters[d + 1]))
                d += 2
            } else {
                literals.add(delimiters[d])
                d += 1
            }
        }
        return Layout(spoilers = spoilers, literals = literals, escapes = escapes)
    }

    /**
     * Rewrite every `||spoiler||` pair into IRC spoiler codes, and each `\||` into a literal `||`.
     *
     * ⚠ Apply this to a user-authored CHAT body only, and opt in per command — see the note on
     * `CommandParser`. It must never become something a shared send helper does to everything.
     */
    fun apply(text: String): String {
        if (!text.contains("||")) return text
        val chars = text
        val layout = layout(chars)
        val opens = layout.spoilers.associate { it.open to it.close }
        val escapes = layout.escapes.toSet()

        /**
         * The character at `i` as it will read — an escape reads as `|`. Null for a delimiter or
         * the end, neither of which can be a digit.
         */
        fun reads(i: Int): Char? {
            if (i >= chars.length) return null
            if (i in escapes) return '|'
            if (chars[i] == '|' && i + 1 < chars.length && chars[i + 1] == '|') return null
            return chars[i]
        }
        // Port note: a builder, not `+=` on a String — that copies the whole text per character
        // here, where Swift's `append` does not.
        val out = StringBuilder()
        var i = 0
        var closeAt: Int? = null
        while (i < chars.length) {
            val pairClose = opens[i]
            if (pairClose != null) {
                out.append(open)
                closeAt = pairClose
                i += 2
            } else if (i == closeAt) {
                // What follows the spoiler decides how it has to be closed — see `close(next)`.
                out.append(close(reads(i + 2)))
                closeAt = null
                i += 2
            } else if (i in escapes) {
                out.append("||")
                i += 3
            } else {
                out.append(chars[i])
                i += 1
            }
        }
        return out.toString()
    }
}
