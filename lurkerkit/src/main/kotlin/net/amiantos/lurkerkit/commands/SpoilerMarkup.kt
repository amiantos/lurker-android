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
 * this (like the web) still sees the `|` or the `\`.
 */
object SpoilerMarkup {
    internal const val open = "\u000314,14"
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
     * Port note: [next] is a UTF-16 unit where LurkerKit passes a `Character`. They disagree on
     * a digit that leads a longer cluster — a keycap emoji (`5` + U+FE0F + U+20E3) is not ASCII
     * to Swift, so LurkerKit gives it the bare close; here it is a `5` and gets the long one.
     * The wire parser reads units, and so does the web's original, so this side is the one that
     * keeps the digit.
     */
    internal fun close(next: Char?): String {
        if (next == null || next !in '0'..'9') return close
        return closeBeforeDigit
    }

    private sealed interface Token {
        data class Text(val value: String) : Token
        data object Delimiter : Token
    }

    /**
     * Split into literal-text and `||`-delimiter tokens, resolving `\||` escapes into a literal
     * `||` inside the text tokens as we go.
     *
     * `\||` is the only sequence treated specially, and there is deliberately no escape for the
     * backslash itself: a lone `\` is always literal, so `path\to\file` needs no thought from
     * the user. The cost is that a literal `\||` cannot be written — judged the better trade,
     * since `||` is far commoner in real text than `\||`.
     */
    private fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        // Port note: builders, not `+=` on a String — that copies the whole text per character
        // here, where Swift's `append` does not.
        val buffer = StringBuilder()
        val chars = text
        var i = 0
        while (i < chars.length) {
            if (chars[i] == '\\' && i + 2 < chars.length && chars[i + 1] == '|' && chars[i + 2] == '|') {
                buffer.append("||")
                i += 3
                continue
            }
            if (chars[i] == '|' && i + 1 < chars.length && chars[i + 1] == '|') {
                if (buffer.isNotEmpty()) {
                    tokens.add(Token.Text(buffer.toString()))
                    buffer.setLength(0)
                }
                tokens.add(Token.Delimiter)
                i += 2
                continue
            }
            buffer.append(chars[i])
            i += 1
        }
        if (buffer.isNotEmpty()) tokens.add(Token.Text(buffer.toString()))
        return tokens
    }

    /**
     * Rewrite every `||spoiler||` pair into IRC spoiler codes.
     *
     * Pairing is non-greedy — the nearest closing `||` wins, so `||a||b||c||` is a spoiler, a
     * literal `b`, then another spoiler — and an empty pair (`||||`) is left literal. Both match
     * how Discord treats them, which is where users' expectations come from.
     *
     * ⚠ Apply this to a user-authored CHAT body only, and opt in per command — see the note on
     * `CommandParser`. It must never become something a shared send helper does to everything.
     */
    fun apply(text: String): String {
        if (!text.contains("||")) return text
        val tokens = tokenize(text)
        val out = StringBuilder()
        var i = 0
        while (i < tokens.size) {
            when (val token = tokens[i]) {
                is Token.Text -> {
                    out.append(token.value)
                    i += 1
                    continue
                }
                Token.Delimiter -> Unit
            }
            // An opening `||`: gather everything up to the next delimiter.
            val content = StringBuilder()
            var closeIndex = -1
            for (j in i + 1 until tokens.size) {
                when (val candidate = tokens[j]) {
                    Token.Delimiter -> {
                        closeIndex = j
                        break
                    }
                    is Token.Text -> content.append(candidate.value)
                }
            }
            if (closeIndex != -1 && content.isNotEmpty()) {
                // What follows the spoiler decides how it has to be closed — see `close(next)`.
                // The next character is the first of the next text token, if there is one; a
                // delimiter or the end of the message can't be a digit.
                var next: Char? = null
                if (closeIndex + 1 < tokens.size) {
                    when (val following = tokens[closeIndex + 1]) {
                        is Token.Text -> next = following.value.firstOrNull()
                        Token.Delimiter -> Unit
                    }
                }
                out.append(open).append(content).append(close(next))
                i = closeIndex + 1
            } else {
                // Unmatched, or an empty `||||` — the opening `||` is just literal text.
                out.append("||")
                i += 1
            }
        }
        return out.toString()
    }
}
