// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

/**
 * Channel user-mode prefixes — a member's glyph, rank and glyph colour — read from the network's
 * own PREFIX (`Network.modeSpec.prefix`, highest rank first), as the web client's
 * `memberPrefix.ts` does since lurker#1032 (lurker-ios#191). Display only: rank gates go through
 * `ChannelRank`.
 *
 * `prefix` is null while the network hasn't sent its ISUPPORT yet; that falls back to the
 * conventional q/a/o/h/v → ~/&/@/%/+ table ([conventional]).
 */
object MemberPrefix {
    /** The conventional PREFIX, `(qaohv)~&@%+`, for a network that hasn't said. */
    val conventional: List<PrefixMode> = listOf(
        PrefixMode(mode = "q", symbol = "~"), PrefixMode(mode = "a", symbol = "&"),
        PrefixMode(mode = "o", symbol = "@"), PrefixMode(mode = "h", symbol = "%"),
        PrefixMode(mode = "v", symbol = "+"),
    )

    /** The five glyph colours (`look.color.member.*`). */
    enum class Tier { Owner, Admin, Op, Halfop, Voice }

    /** A member's glyph and the colour it wears. */
    data class Mark(val glyph: String, val tier: Tier)

    /** The symbol of the highest-ranked prefix mode the member holds, or "" when they hold none. */
    fun of(modes: List<String>, prefix: List<PrefixMode>?): String = mark(modes, prefix)?.glyph ?: ""

    /**
     * The glyph and its colour tier, or null when the member holds no prefix mode.
     *
     * The tier is keyed by the LETTER's conventional role, not by the symbol and not by position:
     * another symbol for op is still op, and on Libera's `(ov)@+` op is the top rank — by
     * position it would take the owner colour. A letter outside q/a/o/h/v takes the tier of the
     * nearest known letter that outranks it, or owner when none does: on `(Yqaohv)!~&@%+` a `Y`
     * is coloured as an owner, whatever its symbol. The web's `prefixClass`.
     */
    fun mark(modes: List<String>, prefix: List<PrefixMode>?): Mark? {
        val list = prefix ?: conventional
        val index = ChannelRank.index(modes, list) ?: return null
        var tier = Tier.Owner
        for (candidate in list.subList(0, index + 1).asReversed()) {
            val known = tierByLetter[candidate.mode]
            if (known != null) {
                tier = known
                break
            }
        }
        return Mark(glyph = list[index].symbol, tier = tier)
    }

    /**
     * The conventional letters' tiers, in [conventional]'s order — paired with it rather than
     * written out a second time (lurker-ios#98's lesson).
     */
    private val tierByLetter: Map<String, Tier> =
        conventional.map { it.mode }.zip(listOf(Tier.Owner, Tier.Admin, Tier.Op, Tier.Halfop, Tier.Voice)).toMap()

    /**
     * Sort position: 0 for the top rank, and members with no prefix mode after every rank the
     * network has.
     */
    fun order(modes: List<String>, prefix: List<PrefixMode>?): Int {
        val list = prefix ?: conventional
        return ChannelRank.index(modes, list) ?: list.size
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
    fun sorted(members: List<Member>, prefix: List<PrefixMode>?): List<Member> =
        members.sortedWith { lhs, rhs ->
            val left = order(lhs.modes, prefix)
            val right = order(rhs.modes, prefix)
            if (left != right) {
                left.compareTo(right)
            } else {
                lhs.nick.lowercase().compareTo(rhs.nick.lowercase())
            }
        }

    /**
     * The conventional glyphs, derived from the table above rather than written out again —
     * a second hand-typed copy of a sigil set is exactly how lurker-ios#98 got in. The WHOIS
     * split below keeps the conventional set, as lurker-ios#191 asked: which sigils lead a WHOIS
     * channel token is a separate question from how a member's rank is drawn.
     */
    private val glyphs: Set<Char> = conventional.mapNotNull { it.symbol.firstOrNull() }.toSet()

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
