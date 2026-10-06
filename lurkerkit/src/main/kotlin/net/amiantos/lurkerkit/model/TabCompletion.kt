// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import kotlin.math.max
import kotlin.math.min

/**
 * In-place Tab completion from a hardware keyboard (lurker-android#63): the web composer's Tab
 * handler (`MessageInput.vue`), so the three clients complete the same word the same way.
 *
 *  - The token is the whitespace-delimited word around the caret, so `al|ice` completes the
 *    whole word rather than leaving its tail behind.
 *  - A token that starts with `#` completes a channel, `#` included; anything else completes a
 *    nick, with a leading `@` dropped from what's matched (and from what's inserted).
 *  - A nick that opens its line is being addressed, and takes the addressing suffix
 *    (`input.completion.nick_suffix` plus a space); mid-sentence it takes nothing, so a comma or a
 *    question mark can follow it. A channel never takes one.
 *  - Tab again cycles through the matches, Shift-Tab backwards — but only while the caret is
 *    where the last insertion left it. A tap or an arrow that moved it starts a new completion
 *    from whatever word is under it now (`continues`).
 *
 * Offsets are UTF-16, the currency of `NSRange` on iOS and of a text field's selection on
 * Android. Pure, so the whole rule is tested here; the composers own the session and the keys.
 *
 * Port note: a mutable class (PORTING.md, structs that mutate, case 3), since `cycle` both moves
 * the session along and returns the edit. Its one owner is the composer. The Swift's `Equatable`
 * is dropped with the value semantics: nothing compares two sessions.
 */
class TabCompletion private constructor(
    private val prefix: String,
    private val tail: String,
    private val suffix: String,
    private val matches: List<String>,
    index: Int,
) {
    /** What one step of the completion leaves in the composer. */
    data class Edit(
        val text: String,
        /** UTF-16 offset just past the inserted name and its suffix. */
        val caret: Int,
    )

    private var index: Int = index

    companion object {
        /**
         * Start a completion for the word around `caret` in `text`, or null when there's no word
         * under it or nothing matches.
         *
         * `nicks` answers the nick candidates for what's been typed, best first — pass
         * `NickCompletion.candidates` with a limit high enough to cycle through. `channels` is the
         * network's channels, best first (the one you're in leads); this filters them by the typed
         * prefix. `punctuation` is `NickCompletion.addressPunctuation(settings)`.
         *
         * Port note: the token, `prefix` and `tail` are `substring`s where the Swift rebuilds each
         * from its UTF-16 units. The cuts land only at whitespace or at the ends of the text, so
         * none splits a surrogate pair, and the two agree on every text a Swift `String` can hold.
         * The prefix test is `lowercase()` and `startsWith`, as in `NickCompletion.candidates`
         * (whose port note gives the three edges where that differs from `hasPrefix`).
         */
        fun begin(
            text: String,
            caret: Int,
            nicks: (String) -> List<String>,
            channels: List<String>,
            punctuation: String,
        ): TabCompletion? {
            val at = min(max(0, caret), text.length)
            var start = at
            while (start > 0 && !isWhitespace(text[start - 1])) start -= 1
            var end = at
            while (end < text.length && !isWhitespace(text[end])) end += 1
            if (start >= end) return null
            val token = text.substring(start, end)

            val prefix = text.substring(0, start)
            val tail = text.substring(end)
            // ⚠ `#`-only on purpose, as on the web (lurker#724): this asks which SIGIL was typed, not
            // whether a target is a channel. Widening it would make a leading `+` or `!` in ordinary
            // prose start completing channel names.
            val isChannel = token.startsWith("#")
            val matches: List<String>
            if (isChannel) {
                val typed = token.lowercase()
                matches = channels.filter { it.lowercase().startsWith(typed) }
            } else {
                // Port note: `dropFirst()` drops one `Character`; an `@` is one UTF-16 unit.
                val query = if (token.startsWith("@")) token.substring(1) else token
                if (query.isEmpty()) return null
                matches = nicks(query)
            }
            if (matches.isEmpty()) return null
            val suffix = if (!isChannel && isAtLineStart(prefix)) "$punctuation " else ""
            return TabCompletion(prefix = prefix, tail = tail, suffix = suffix, matches = matches, index = 0)
        }

        /**
         * The web's `isAtLineStart`, `/(^|\n)\s*$/`: nothing but whitespace since the last
         * newline, or since the start of the draft.
         */
        internal fun isAtLineStart(before: String): Boolean {
            for (position in before.indices.reversed()) {
                val unit = before[position]
                if (unit == '\n') return true
                if (!isWhitespace(unit)) return false
            }
            return true
        }

        /** JavaScript's `\s` over a UTF-16 unit — the web's token boundary. */
        internal fun isWhitespace(unit: Char): Boolean =
            when (unit.code) {
                in 0x09..0x0D, 0x20, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF ->
                    true
                else ->
                    false
            }
    }

    /** The composer as this step leaves it. */
    val edit: Edit
        get() {
            val head = prefix + matches[index] + suffix
            return Edit(text = head + tail, caret = head.length)
        }

    /** The next match (the previous one, `backward`), wrapping at either end. */
    fun cycle(backward: Boolean): Edit {
        val count = matches.size
        index = (index + (if (backward) -1 else 1) + count) % count
        return edit
    }

    /**
     * Whether the composer still shows what this completion last left — so another Tab cycles it
     * rather than starting over. False once the text or the caret has moved.
     */
    fun continues(text: String, caret: Int): Boolean {
        val current = edit
        return current.text == text && current.caret == caret
    }
}
