// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.rendering

import net.amiantos.lurkerkit.support.TextRange
import net.amiantos.lurkerkit.support.unicodeRegex
import java.util.regex.PatternSyntaxException

/**
 * Finds nicks mentioned inside a message body, the way the web client's `colorNicksInText`
 * does: any known nick that appears as a whole word — not inside a longer word, and not
 * straddling another nick — is a match, so the caller can color it. Compiled once per
 * channel-membership change and reused across that buffer's messages, because building the
 * alternation regex is the expensive part and the member set rarely changes.
 *
 * Port note: a plain `class`, not a `data class` — all it holds is a compiled regex, which
 * has no value equality to offer (and the Swift struct is not `Equatable` either).
 *
 * @constructor `nicks` should already exclude the reader's own nick: a self-mention keeps the
 * body's own color rather than a palette color, matching the web.
 */
class NickHighlighter(nicks: List<String>) {
    private val regex: Regex?

    init {
        val unique = nicks.filter { it.isNotEmpty() }.toSet().toList()
        regex = if (unique.isEmpty()) {
            null
        } else {
            // Longest first so "alibaba" wins over "ali" when both could match at a position —
            // regex alternation is order-sensitive and takes the first alternative that fits.
            //
            // Port note: length in UTF-16 units, where the Swift counts grapheme clusters. A
            // nick is always longer in units than any nick it starts with, which is the
            // property the order exists for.
            val alternation = unique
                .sortedByDescending { it.length }
                .joinToString("|") { Regex.escape(it) }
            val pattern = "(?<!$nickCharClass)(?:$alternation)(?!$nickCharClass)"
            try {
                unicodeRegex(pattern, ignoreCase = true)
            } catch (_: PatternSyntaxException) {
                null
            }
        }
    }

    /**
     * Whether this highlighter can never produce a match, so the caller can skip the pass.
     * True when there are no candidate nicks — and also, defensively, if the alternation
     * couldn't be compiled; it doesn't distinguish the two, since either way there's nothing
     * to color.
     */
    val isEmpty: Boolean get() = regex == null

    /** The ranges in `string` that name a known nick, in order. Empty when nothing matches. */
    fun matches(string: String): List<TextRange> {
        val regex = regex ?: return emptyList()
        return regex.findAll(string).map { TextRange(it.range.first, it.range.last + 1) }.toList()
    }

    private companion object {
        /**
         * The characters that can appear in an IRC nick. A match must not be flanked by one, so
         * "bob" inside "bobby" or "bob_" isn't a match. Kept identical to the web client's
         * `NICK_CHAR_CLASS` so the two clients agree on what counts as a mention.
         */
        private const val nickCharClass = "[A-Za-z0-9_\\-\\[\\]\\\\^{|}]"
    }
}
