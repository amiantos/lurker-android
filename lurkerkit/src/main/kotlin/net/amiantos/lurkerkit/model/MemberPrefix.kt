// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * Channel user-mode prefixes, ported from the web client's `memberPrefix.ts` so the
 * @/+/%/~/& glyph and the ordering match between clients.
 *
 * NOTE (inherited from the reference): the q/a/o/h/v → ~/&/@/%/+ mapping is the
 * conventional RFC/ISUPPORT default and is hardcoded. Neither client reads a network's
 * ISUPPORT PREFIX yet, so a server that diverges from the standard ordering won't be
 * honored — a known, deliberate limitation, not an oversight to fix here.
 */
object MemberPrefix {
    /** Ranked owner > admin > op > halfop > voice. Highest held mode wins. */
    val rank: List<String> = listOf("q", "a", "o", "h", "v")
    private val glyph: Map<String, String> = mapOf("q" to "~", "a" to "&", "o" to "@", "h" to "%", "v" to "+")

    /**
     * The single highest-ranked prefix glyph for a set of channel modes, or "" when the
     * member holds none.
     */
    fun of(modes: List<String>): String {
        for (letter in rank) {
            if (modes.contains(letter)) return glyph[letter] ?: ""
        }
        return ""
    }

    /** Sort position: lower is higher-ranked; unprivileged members sort last. */
    fun order(modes: List<String>): Int {
        for ((index, letter) in rank.withIndex()) {
            if (modes.contains(letter)) return index
        }
        return rank.size
    }

    /**
     * The member list as it should read: by rank, then by nick.
     *
     * Nicks fold case for the comparison because IRC nick case is not meaningful — a
     * raw `<` would sort every capitalized nick above every lowercase one, which reads
     * as two separate alphabets rather than one list.
     *
     * Port note: the folded nicks compare by UTF-16 unit, where Swift's `<` compares by
     * Unicode scalar over canonically equivalent forms. Checked against the Swift, the two
     * orders are the same except between a character outside the BMP and one in
     * U+E000–U+FFFF, and for a nick written with combining marks (`e` + U+0301 sorts with `é`
     * there and with `e` here). And this sort is stable — members that tie keep their incoming
     * order — which Swift's does not promise.
     */
    fun sorted(members: List<Member>): List<Member> =
        members.sortedWith { lhs, rhs ->
            val left = order(lhs.modes)
            val right = order(rhs.modes)
            if (left != right) {
                left.compareTo(right)
            } else {
                lhs.nick.lowercase().compareTo(rhs.nick.lowercase())
            }
        }

    /**
     * The glyphs themselves, derived from the map above rather than written out again —
     * a second hand-typed copy of a sigil set is exactly how lurker-ios#98 got in.
     */
    private val glyphs: Set<Char> = glyph.values.mapNotNull { it.firstOrNull() }.toSet()

    /**
     * What [splitChannelToken] answers: the sigils held in the channel, and the channel.
     *
     * Port note: the Swift returns a named tuple, `(prefix: String, name: String)`.
     */
    data class ChannelToken(val prefix: String, val name: String)

    /**
     * Split a `"@#foo"` token from RPL_WHOISCHANNELS into the sigils held there and the
     * channel itself.
     *
     * ⚠⚠ **A greedy peel of `[~&@%+]` is wrong, and the web client (`UserProfileModal.vue`,
     * `channelsList`) has that bug.** `&` and `+` are membership glyphs *and* channel sigils
     * (RFC 2811 §2.1 — see `ChannelName`), so `@&chan` greedily peels `@&` and yields
     * `chan`: a channel that doesn't exist, offered as something to tap.
     *
     * So peel as much as possible **while what's left is still a channel name**: take the
     * largest split point whose remainder passes `ChannelName.isChannelTarget`.
     *
     * - `@#foo` → (`@`, `#foo`) — only one split works.
     * - `&chan` → (``, `&chan`) — peeling the `&` leaves `chan`, which names no channel.
     * - `@&chan` → (`@`, `&chan`) — the case the greedy version breaks.
     *
     * `+#chan` and `+chan` are genuinely ambiguous — both halves are legal readings, and
     * without the network's ISUPPORT `PREFIX`/`CHANTYPES` nothing here can tell them apart.
     * Preferring the largest split resolves them the way traffic actually runs: voiced in
     * `#chan`, and the `+chan` channel (whose remainder `chan` is not a channel) is
     * unaffected because that split isn't legal in the first place.
     *
     * Null for a token that is **nothing but glyphs** — `"@"` names no channel, and a row for
     * it is untappable furniture. Anything else keeps its whole self as the name even when no
     * peel is legal, because a network whose `CHANTYPES` this client doesn't know still has
     * real channels, and dropping those would be worse than showing them unpeeled.
     *
     * Port note: walks UTF-16 units, where the Swift walks `Character`s. Every glyph and
     * sigil is one ASCII unit, so the two agree — checked over a corpus — until a channel
     * SIGIL carries a combining mark. An `@` before a `#` with U+0301 on it is left unpeeled
     * there (what follows the `@` is not a channel to LurkerKit's `isChannelTarget`) and
     * peels off here.
     */
    fun splitChannelToken(token: String): ChannelToken? {
        var best = 0
        var index = 0
        for (character in token) {
            if (!glyphs.contains(character)) break
            index += 1
            if (ChannelName.isChannelTarget(token.substring(index))) best = index
        }
        // `index` is the whole glyph run; reaching the end of the token inside it means there
        // was never a name here.
        if (index >= token.length) return null
        return ChannelToken(prefix = token.substring(0, best), name = token.substring(best))
    }
}
