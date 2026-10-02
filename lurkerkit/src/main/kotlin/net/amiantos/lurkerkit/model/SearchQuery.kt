// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import net.amiantos.lurkerkit.support.splitOnSwiftWhitespace
import net.amiantos.lurkerkit.support.isSwiftWhitespace

/**
 * The inline message-search grammar, shared with the web client:
 *
 *     from:nick in:#channel on:network free text…
 *
 * `from:` / `in:` / `on:` are peeled off as structured filters and everything else is joined
 * back up as the free-text query. Deliberately a *parser over one text field* rather than a
 * row of filter chips: the same string round-trips between the two clients, a scoped entry
 * point can seed it by prefixing `in:#chan on:net `, and the whole grammar stays legible in
 * the field the user is already typing in.
 *
 * Port of `vue_client/src/utils/searchQuery.ts`, including its two forgiving cases: a bare
 * prefix (`from:`) and an unknown one (`word:`) are left in the free text, where the server's
 * FTS layer handles them harmlessly. Getting that wrong would swallow a real search term.
 */
data class SearchQuery(
    /**
     * Everything that wasn't a filter, re-joined with single spaces. Empty when the user
     * typed only filters — which is a legal search (`in:#dev` alone means "that buffer").
     */
    val text: String,
    /**
     * The `from:` nicks. Repeatable, and OR-matched by the server — a friend's alts, or the
     * Friends screen's "view activity". Every other filter is last-wins, matching the web.
     */
    val from: List<String>,
    /** `in:` — a buffer target (channel name or peer nick). Empty when unfiltered. */
    val target: String,
    /**
     * `on:` — a *network name*, which the client resolves against its own roster to the
     * network id the server wants. Empty when unfiltered.
     */
    val network: String,
) {
    /**
     * Nothing to search on: no free text and no filter. The server would have nothing to
     * match, so the caller shows its "type to search" prompt rather than dispatching.
     */
    val isEmpty: Boolean
        get() = text.isEmpty() && from.isEmpty() && target.isEmpty() && network.isEmpty()

    /**
     * Typed, but not yet enough to be worth asking the server — the state a search-as-you-type
     * field passes through on the way to a real query.
     *
     * **A search is the most expensive thing a client can ask this server for.** The FTS query
     * runs synchronously on the event loop that also services every IRC connection on the
     * instance, so the cheapest thing to type must not be the most expensive thing to answer —
     * and a lone `a` or `i` is exactly that, being among the most common tokens in the index.
     *
     * **The floor is on the free text, not on what's typed.** `in:#dev` is a complete question
     * the moment it's finished: it runs no full-text pass at all, only an indexed filter, so
     * demanding extra characters would gate a query that costs almost nothing.
     *
     * **And only for text that's entirely ASCII.** One CJK character is routinely a whole
     * word; a floor that couldn't tell it from a lone Latin letter would lock those users out
     * of searching for it.
     *
     * Port note: counts UTF-16 units where LurkerKit counts `Character`s. The ASCII test makes
     * that the same count — a run of ASCII is one unit per character — with one exception: Swift
     * reads CR-LF as a single ASCII character, so a `text` that is exactly `"\r\n"` needs more
     * text there and not here. `parse` cannot produce one (it splits on both); only a query
     * built by hand can.
     */
    val needsMoreText: Boolean
        get() = text.isNotEmpty() && text.length < minimumFreeText && text.all { it.code < 0x80 }

    companion object {
        /**
         * Two, not three: it rules out the single-character case that is both the most expensive
         * to answer and the least likely to be meant, without blocking the two-letter words people
         * genuinely search for ("hi", "ok", "wg").
         */
        private const val minimumFreeText = 2

        /**
         * Port note: tokens are split at whitespace UTF-16 units, and the key from the value at
         * the first `:` unit, where LurkerKit does both by `Character`. Checked against the Swift
         * over a corpus, they differ only when a combining mark or a zero-width joiner follows the
         * space or the colon: Swift takes the mark as part of that character — the marked space
         * still separates and takes the mark with it, the marked colon is no colon at all — and
         * here the mark is left on the front of whatever follows.
         */
        fun parse(raw: String): SearchQuery {
            val from = mutableListOf<String>()
            var target = ""
            var network = ""
            val free = mutableListOf<String>()
            for (token in raw.splitOnSwiftWhitespace()) {
                // Split once, so a value may itself contain a colon (`in:#c++`, a nick with one).
                val colon = token.indexOf(':')
                if (colon < 0) {
                    free.add(token)
                    continue
                }
                val key = token.substring(0, colon).lowercase()
                val value = token.substring(colon + 1)
                // A bare prefix carries no filter, so it stays free text rather than setting an
                // empty one — an empty `in:` would otherwise read as "filter to the buffer named
                // ''" and match nothing.
                if (value.isEmpty()) {
                    free.add(token)
                    continue
                }
                when (key) {
                    "from" -> from.add(value)
                    "in" -> target = value
                    "on" -> network = value
                    else -> free.add(token)
                }
            }
            return SearchQuery(text = free.joinToString(" "), from = from, target = target, network = network)
        }

        /**
         * The `in:`/`on:` prefix that scopes a search to `buffer`, with a trailing space so the
         * user's first keystroke adds a term instead of editing the scope. Null for a buffer with
         * no meaningful scope — the system buffer and a `:server:` log aren't conversations, and
         * the web's scoped entry points leave both unscoped for the same reason.
         *
         * `on:` is dropped when the network's name contains whitespace: tokens split on spaces,
         * so it could not round-trip through `parse`. `in:` alone still scopes by target, which
         * is the part that matters — a channel name colliding across two networks is rare, and a
         * few extra rows beats a filter that silently means something else.
         */
        fun scope(buffer: Buffer, networkName: String?): String? {
            when (buffer.kind) {
                BufferKind.Channel, BufferKind.Dm, BufferKind.Dcc -> Unit
                BufferKind.Server, BufferKind.System -> return null
            }
            if (buffer.target.isEmpty()) return null
            val on = networkName?.let { name ->
                if (name.isNotEmpty() && name.none { it.isSwiftWhitespace() }) " on:$name" else null
            } ?: ""
            return "in:${buffer.target}$on "
        }
    }
}
