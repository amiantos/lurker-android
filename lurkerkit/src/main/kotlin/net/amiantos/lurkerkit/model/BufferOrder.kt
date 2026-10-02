// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.text.Collator

/**
 * The order the buffer list puts things in, where that order is the *user's* rather than
 * this client's: which network comes first, and which of its buffers they pinned.
 *
 * Both settings are made on the web — the iOS app deliberately offers no way to change either
 * (lurker-ios#11's follow-up) — so the whole job here is to render a decision that was already
 * taken somewhere else, rather than to impose an alphabet on it. A phone that lists your
 * networks in a different order from the browser you arranged them in is a phone you have to
 * re-read every time you pick it up.
 */
object BufferOrder {

    /**
     * Networks in the user's configured order.
     *
     * `(position, id)` matches the server's own `ORDER BY position ASC, id ASC`, so the two
     * agree on ties — and ties are normal, since `position` is only densified when something
     * is reordered. Id is the tiebreak rather than name: it's what the server falls back to,
     * and a name-based tiebreak would have two networks swap places on a rename.
     *
     * Port note: a total order as long as the ids are distinct — which they are, being the
     * map's keys — so nothing here rests on which of the two sorts is stable.
     */
    fun networks(networks: Map<Int, Network>): List<Network> =
        networks.values.sortedWith { lhs, rhs ->
            if (lhs.position != rhs.position) lhs.position.compareTo(rhs.position) else lhs.id.compareTo(rhs.id)
        }

    /**
     * The buffers each network section lists, grouped by network id.
     *
     * Two things don't appear here. The **system buffer** has no network and no row of its
     * own — it's opened from the buffer list's menu. And a **favorited** buffer lives in its
     * Friends/Favorites chip: the favorite is a relocation, not a shortcut, matching the web
     * (`isFavoriteBuf`, which filters them out of both halves of every network group).
     *
     * ⚠ That second rule used to apply to favorited DMs alone, so a favorited *channel*
     * appeared twice — once as a chip and again in its network. In use that means the list
     * you scan most is the one with every row you care about duplicated in it, since the
     * buffers people favorite are the ones they look at most.
     *
     * Keyed by `BufferKey.id`, so the caller's favorites set has to be too.
     *
     * Port note: within a network the buffers keep the order `buffers` handed them in, as in
     * LurkerKit. What differs is the caller's side of it: a Swift dictionary's values come out
     * in an order that changes from run to run, and a Kotlin map's in the order they went in.
     */
    fun byNetwork(buffers: Iterable<Buffer>, excluding: Set<String>): Map<Int, List<Buffer>> {
        val grouped = mutableMapOf<Int, MutableList<Buffer>>()
        for (buffer in buffers) {
            val networkId = buffer.networkId
            if (networkId == null || excluding.contains(buffer.key.id)) continue
            grouped.getOrPut(networkId) { mutableListOf() }.add(buffer)
        }
        return grouped
    }

    /**
     * One network's buffers, with its server log guaranteed to be among them.
     *
     * ⚠⚠ Synthesized when absent, rather than shown only if the server sent a row. A
     * buffer row for `:server:<id>` arrives with the connect burst and is dropped again by
     * `pruneToBurst` whenever that burst doesn't name it, so the row came and went on its
     * own — a network's log present on one launch and missing on the next, with nothing the
     * user did to explain it. Synthesizing costs nothing: `ChatState.buffer(for:)` already
     * materializes a buffer from a key for exactly this reason, and the chat screen hydrates
     * whatever it lands on.
     *
     * ⚠ Only for a network that is *in use* — this fills a gap in a section, it does not
     * conjure one. Synthesizing unconditionally would give every network a section forever,
     * which reads well (the web always lists every network) but makes "No buffers yet"
     * unreachable, because having a network would imply having a row. Making every network
     * appear is a good idea and a separate one.
     *
     * ⚠⚠ "In use" is not the same as "has rows here", and the difference is a network that
     * disappears. Favorites are lifted out of their network's section, so a network whose
     * only open buffer is a favorited channel contributes no rows — and if its `:server:`
     * row happens to be one of the ones the burst didn't name, the whole network vanishes:
     * no header, no connection state, no way into its log. `networkHasOpenBuffers` is asked
     * *before* the favorites exclusion for exactly that case.
     */
    fun withServerLog(
        buffers: List<Buffer>,
        networkId: Int,
        networkHasOpenBuffers: Boolean = false,
    ): List<Buffer> {
        if (buffers.isEmpty() && !networkHasOpenBuffers) return buffers
        if (buffers.any { it.kind == BufferKind.Server }) return buffers
        return buffers + listOf(
            Buffer(networkId = networkId, target = Buffer.serverTarget(networkId), kind = BufferKind.Server)
        )
    }

    /**
     * What [split] answers: one network's pinned buffers, and the rest.
     *
     * Port note: the Swift returns a named tuple, `(pinned: [Buffer], rest: [Buffer])`.
     */
    data class Split(val pinned: List<Buffer>, val rest: List<Buffer>)

    /**
     * One network's buffers as its two lists: the pinned ones in the user's pin order, and
     * everything else in the ordinary channels-then-DMs-then-server, sigil-stripped
     * alphabetical order.
     *
     * Split rather than concatenated because the two render as separate sections. A list
     * on iOS has no separator *inside* it — a section header is the separator — so pins
     * ordered first inside one section would have been an order with nothing to explain it,
     * which reads as a sort bug rather than as your own arrangement.
     *
     * ⚠ A pin whose buffer isn't open contributes nothing — the pin row survives on the
     * server when a channel is parted or closed, so a pin list is a superset of what can be
     * shown, and mapping it blindly would produce rows for buffers that aren't there. The
     * web filters the same way for the same reason.
     *
     * Targets are matched case-insensitively. The pin is stored under the spelling the
     * server last saw and the buffer under the one the client holds, and IRC lets those
     * differ — an exact match would silently drop a pin after a CASEMAPPING refold.
     *
     * ⚠⚠ Keyed on `target.lowercase()`, which is what `BufferKey.id` uses — NOT
     * `ChannelName.fold`, which is the *autocomplete* fold and also drops a leading sigil so
     * `li` matches `#linux`. As a target key that collides two real buffers: `#ops` and
     * `&ops` both fold to "ops", so a pin on one could render the other in its slot, and the
     * `rest` filter below — matching the same collided key — would drop the loser out of the
     * buffer list entirely.
     *
     * Port note: `lowercase()` where LurkerKit has `lowercased()`. The two differ only on a
     * word-final `Σ` (see `BufferKey.id`), and the pin and the buffer are both folded here, by
     * the same function, so a pin still finds its buffer.
     *
     * Port note: `rest` is sorted with a stable sort, so two buffers [order] ranks level stay
     * in the order `buffers` gave them. Swift's sort does not promise that (though it does it).
     * [order] is not a total order — see its own note for which buffers tie.
     */
    fun split(buffers: List<Buffer>, pinned: List<String>): Split {
        if (pinned.isEmpty()) return Split(emptyList(), sorted(buffers))
        val byTarget = mutableMapOf<String, Buffer>()
        for (buffer in buffers) byTarget[buffer.target.lowercase()] = buffer
        val pinnedBuffers = mutableListOf<Buffer>()
        val claimed = mutableSetOf<String>()
        for (target in pinned) {
            val key = target.lowercase()
            // `claimed` before `byTarget`: a pin list with a duplicate in it must not print
            // the same buffer twice.
            if (claimed.contains(key)) continue
            val buffer = byTarget[key] ?: continue
            claimed.add(key)
            pinnedBuffers.add(buffer)
        }
        val rest = buffers.filter { !claimed.contains(it.target.lowercase()) }
        return Split(pinnedBuffers, sorted(rest))
    }

    /**
     * Channels, then DMs, then the server log; alphabetical within each.
     *
     * Matches the web client's ordering: the alphabetical key strips leading channel sigils
     * (`##anime` sorts as "anime", not before `#aardvark`), so the two clients list the same
     * network the same way. All four sigils, via `ChannelName.stripSigils` — a hand-written
     * `#&` here floated `+`/`!` channels above every named one until lurker-ios#98, which
     * the web (`stripChannelPrefix`) never did.
     *
     * A `=bob` DCC chat files among the DMs under its PEER, beside a DM with bob — keyed on the
     * buffer name, every chat piled up at the top of the block under `=`. The web does the same
     * (`bufferSortKey`). When the two tie, the DM goes first.
     *
     * Port note: a "comes before" test, as in LurkerKit, where it is handed straight to
     * `sorted(by:)`. A Kotlin sort wants a three-way answer, which is this asked both ways
     * round: `if (order(a, b)) -1 else if (order(b, a)) 1 else 0`.
     *
     * Port note: NOT a total order, here or there. Two buffers of the same rank whose names are
     * level once the sigils are stripped — `#ops` and `&ops`, `#anime` and `##anime` — tie, as do
     * two names the collator calls equal (below). Only a DM against its own DCC chat is told
     * apart.
     *
     * Port note: the names are compared with `localizedCaseInsensitiveCompare` in LurkerKit —
     * the user's locale's collation, case-insensitive: `Foo`, `foo` and `FOO` are level, an
     * accent is a difference (`a` before `ä`), digits are not read as numbers (`a10` before
     * `a2`), and punctuation counts (`foo-z` before `fooa`). Here that is a [Collator] for the
     * default locale at `SECONDARY` strength — a locale's collation, not `compareTo`.
     * ⚠ What a `Collator` IS differs by platform. On Android it is ICU, the library iOS collates
     * with. Checked against the Swift with ICU4J on the host (`en_US`, some 49,000 pairs of
     * names): the same answer everywhere but two places — `ß` is level with `ss` on iOS and not
     * in ICU, and a trailing zero-width space, zero-width joiner or soft hyphen is a difference
     * on iOS and nothing in ICU. On the host JVM these tests run on it is the JDK's own rule
     * tables, which agree for names made of letters and digits and NOT for punctuation: they
     * ignore a space and a hyphen until everything else is level (there, `fooa` sorts before
     * `foo-z`), and rank `_ [ ] { } ^ ~ | . '` and the backtick differently — most of what an
     * IRC nick is allowed besides letters. So a test that orders names with punctuation in them
     * belongs in the instrumented suite, not in this one.
     */
    fun order(lhs: Buffer, rhs: Buffer): Boolean = order(lhs, rhs, nameCollator())

    private fun order(lhs: Buffer, rhs: Buffer, collator: Collator): Boolean {
        fun rank(kind: BufferKind): Int =
            when (kind) {
                BufferKind.Channel -> 0
                BufferKind.Dm, BufferKind.Dcc -> 1
                BufferKind.Server -> 2
                BufferKind.System -> 3
            }
        if (rank(lhs.kind) != rank(rhs.kind)) return rank(lhs.kind) < rank(rhs.kind)
        val byName = collator.compare(
            ChannelName.stripSigils(DccChat.peer(lhs.target)),
            ChannelName.stripSigils(DccChat.peer(rhs.target)),
        )
        if (byName != 0) return byName < 0
        return lhs.kind == BufferKind.Dm && rhs.kind == BufferKind.Dcc
    }

    /** Port note: LurkerKit's `sorted(by: order)`, with one collator for the whole sort. */
    private fun sorted(buffers: List<Buffer>): List<Buffer> {
        val collator = nameCollator()
        return buffers.sortedWith { lhs, rhs ->
            if (order(lhs, rhs, collator)) -1 else if (order(rhs, lhs, collator)) 1 else 0
        }
    }

    /**
     * Port note: the stand-in for `localizedCaseInsensitiveCompare` — see [order]. A fresh one
     * per sort rather than a shared one: it follows the default locale as that changes, and a
     * `Collator` is a mutable object (its strength is set here), not a value to hand around.
     */
    private fun nameCollator(): Collator =
        Collator.getInstance().apply { strength = Collator.SECONDARY }
}
