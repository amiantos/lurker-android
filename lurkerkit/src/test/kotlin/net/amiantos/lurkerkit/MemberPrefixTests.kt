// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import kotlin.test.assertNull
import net.amiantos.lurkerkit.model.member
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.MemberPrefix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.amiantos.lurkerkit.model.PrefixMode

/**
 * Ported alongside `MemberPrefix` itself, from the web client's `memberPrefix.ts`, so the
 * two clients can't drift on which glyph a mode maps to or who sorts above whom.
 */
class MemberPrefixTests {

    private fun member(nick: String, modes: List<String> = emptyList(), away: Boolean = false): Member =
        Member(nick = nick, modes = modes, away = away)

    // MARK: - Glyphs

    @Test
    fun testEachModeMapsToItsConventionalGlyph() {
        assertEquals("~", MemberPrefix.of(listOf("q"), prefix = null))
        assertEquals("&", MemberPrefix.of(listOf("a"), prefix = null))
        assertEquals("@", MemberPrefix.of(listOf("o"), prefix = null))
        assertEquals("%", MemberPrefix.of(listOf("h"), prefix = null))
        assertEquals("+", MemberPrefix.of(listOf("v"), prefix = null))
    }

    // MARK: - Your own glyph, for the composer's prompt (lurker-ios#135)

    @Test
    fun testFindingYourselfFoldsNickCase() {
        val members = listOf(member("alice", listOf("o")), member("amiantos", listOf("v", "o")))
        assertEquals(listOf("v", "o"), members.member(named = "Amiantos")?.modes)
    }

    @Test
    fun testANickWhoseFoldChangesLengthIsStillFound() {
        // "İ" is one UTF-16 unit and lowercases to two — a length check in front of the fold
        // would turn this member away, for `channelAccess` as much as the prompt.
        val members = listOf(member("alice"), member("İzmir", listOf("o")))
        assertEquals(listOf("o"), members.member(named = "İzmir")?.modes)
        assertEquals(listOf("o"), members.member(named = "i\u0307zmir")?.modes)
    }

    @Test
    fun testNobodyIsYouBeforeNamesOrWithoutANick() {
        // Another member's glyph would be a lie about you.
        assertNull(listOf(member("alice", listOf("o"))).member(named = "amiantos"))
        assertNull(listOf(member("", listOf("o"))).member(named = ""))
    }

    @Test
    fun testNoModesMeansNoGlyph() {
        assertEquals("", MemberPrefix.of(emptyList(), prefix = null))
    }

    @Test
    fun testAnUnknownModeIsNotAGlyph() {
        // Channel modes that aren't prefix modes must not leak into the nick column.
        assertEquals("", MemberPrefix.of(listOf("z"), prefix = null))
    }

    @Test
    fun testTheHighestHeldModeWins() {
        // A member holding several shows one glyph, the top one — not a pile.
        assertEquals("@", MemberPrefix.of(listOf("v", "o"), prefix = null))
        assertEquals("~", MemberPrefix.of(listOf("v", "o", "q"), prefix = null))
        assertEquals("%", MemberPrefix.of(listOf("h", "v"), prefix = null))
    }

    // MARK: - Sorting

    @Test
    fun testRankOutranksAlphabetical() {
        val sorted = MemberPrefix.sorted(
            listOf(
                member("zoe", listOf("v")),
                member("adam"),
                member("mallory", listOf("o")),
                member("bob", listOf("q")),
            ),
            prefix = null,
        )
        assertEquals(listOf("bob", "mallory", "zoe", "adam"), sorted.map { it.nick })
    }

    @Test
    fun testEqualRankSortsByNick() {
        val sorted = MemberPrefix.sorted(listOf(member("carol", listOf("o")), member("alice", listOf("o"))), prefix = null)
        assertEquals(listOf("alice", "carol"), sorted.map { it.nick })
    }

    @Test
    fun testNickSortIgnoresCase() {
        // A raw `<` would put every capitalized nick above every lowercase one, which reads
        // as two alphabets stacked rather than one list.
        val sorted = MemberPrefix.sorted(listOf(member("bob"), member("Alice"), member("carol")), prefix = null)
        assertEquals(listOf("Alice", "bob", "carol"), sorted.map { it.nick })
    }

    @Test
    fun testAwayMembersHoldTheirPlace() {
        // You look for a nick where you last saw it; away is a dimming, not a re-sort.
        val sorted = MemberPrefix.sorted(listOf(member("bob"), member("alice", away = true)), prefix = null)
        assertEquals(listOf("alice", "bob"), sorted.map { it.nick })
    }

    @Test
    fun testUnprivilegedMembersSortLast() {
        assertEquals(0, MemberPrefix.order(listOf("q"), prefix = null))
        assertTrue(MemberPrefix.order(emptyList(), prefix = null) > MemberPrefix.order(listOf("v"), prefix = null))
    }

    // MARK: - The network's own PREFIX (lurker-ios#191)

    @Test
    fun testTheGlyphIsTheNetworksSymbolForTheTopLetterHeld() {
        // A network whose op is `!` and whose halfop doesn't exist.
        val prefix = listOf(PrefixMode(mode = "o", symbol = "!"), PrefixMode(mode = "v", symbol = "+"))
        assertEquals("!", MemberPrefix.of(listOf("o", "v"), prefix = prefix))
        assertEquals("", MemberPrefix.of(listOf("h"), prefix = prefix), "a letter the network doesn't have")
        assertEquals("", MemberPrefix.of(listOf("v"), prefix = emptyList()), "a network with no prefix modes at all")
    }

    @Test
    fun testTheOrderFollowsTheNetworksRanks() {
        // Voice above op, on a network that says so: the sort follows it.
        val prefix = listOf(PrefixMode(mode = "v", symbol = "+"), PrefixMode(mode = "o", symbol = "@"))
        val members = listOf(Member(nick = "op", modes = listOf("o")), Member(nick = "voice", modes = listOf("v")), Member(nick = "none", modes = emptyList()))
        assertEquals(listOf("voice", "op", "none"), MemberPrefix.sorted(members, prefix = prefix).map { it.nick })
        assertEquals(2, MemberPrefix.order(emptyList(), prefix = prefix), "no prefix mode sorts after every rank")
    }

    /**
     * Coloured by the letter's role, not by symbol and not by position: on Libera's `(ov)@+` op is
     * the top rank and must not take the owner colour; another symbol for op is still op; an
     * unknown letter takes the nearest known letter above it, or owner.
     */
    @Test
    fun testTheTierIsTheLettersRole() {
        val libera = listOf(PrefixMode(mode = "o", symbol = "@"), PrefixMode(mode = "v", symbol = "+"))
        assertEquals(MemberPrefix.Mark(glyph = "@", tier = MemberPrefix.Tier.Op), MemberPrefix.mark(listOf("o"), prefix = libera))
        assertEquals(MemberPrefix.Tier.Op, MemberPrefix.mark(listOf("o"), prefix = listOf(PrefixMode(mode = "o", symbol = "!")))?.tier)
        val wide = listOf(PrefixMode(mode = "Y", symbol = "!")) + MemberPrefix.conventional
        assertEquals(MemberPrefix.Mark(glyph = "!", tier = MemberPrefix.Tier.Owner), MemberPrefix.mark(listOf("Y"), prefix = wide))
        val between = listOf(PrefixMode(mode = "o", symbol = "@"), PrefixMode(mode = "X", symbol = "*"), PrefixMode(mode = "v", symbol = "+"))
        assertEquals(MemberPrefix.Tier.Op, MemberPrefix.mark(listOf("X"), prefix = between)?.tier, "the nearest known letter above")
        assertNull(MemberPrefix.mark(emptyList(), prefix = libera))
    }

    @Test
    fun testBeforeISUPPORTTheConventionalTableStands() {
        assertEquals(MemberPrefix.Mark(glyph = "%", tier = MemberPrefix.Tier.Halfop), MemberPrefix.mark(listOf("h"), prefix = null))
        assertEquals(MemberPrefix.Mark(glyph = "~", tier = MemberPrefix.Tier.Owner), MemberPrefix.mark(listOf("q", "v"), prefix = null))
    }
}
