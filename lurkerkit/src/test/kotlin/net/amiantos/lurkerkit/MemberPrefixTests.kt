// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.MemberPrefix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        assertEquals("~", MemberPrefix.of(listOf("q")))
        assertEquals("&", MemberPrefix.of(listOf("a")))
        assertEquals("@", MemberPrefix.of(listOf("o")))
        assertEquals("%", MemberPrefix.of(listOf("h")))
        assertEquals("+", MemberPrefix.of(listOf("v")))
    }

    @Test
    fun testNoModesMeansNoGlyph() {
        assertEquals("", MemberPrefix.of(emptyList()))
    }

    @Test
    fun testAnUnknownModeIsNotAGlyph() {
        // Channel modes that aren't prefix modes must not leak into the nick column.
        assertEquals("", MemberPrefix.of(listOf("z")))
    }

    @Test
    fun testTheHighestHeldModeWins() {
        // A member holding several shows one glyph, the top one — not a pile.
        assertEquals("@", MemberPrefix.of(listOf("v", "o")))
        assertEquals("~", MemberPrefix.of(listOf("v", "o", "q")))
        assertEquals("%", MemberPrefix.of(listOf("h", "v")))
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
            )
        )
        assertEquals(listOf("bob", "mallory", "zoe", "adam"), sorted.map { it.nick })
    }

    @Test
    fun testEqualRankSortsByNick() {
        val sorted = MemberPrefix.sorted(listOf(member("carol", listOf("o")), member("alice", listOf("o"))))
        assertEquals(listOf("alice", "carol"), sorted.map { it.nick })
    }

    @Test
    fun testNickSortIgnoresCase() {
        // A raw `<` would put every capitalized nick above every lowercase one, which reads
        // as two alphabets stacked rather than one list.
        val sorted = MemberPrefix.sorted(listOf(member("bob"), member("Alice"), member("carol")))
        assertEquals(listOf("Alice", "bob", "carol"), sorted.map { it.nick })
    }

    @Test
    fun testAwayMembersHoldTheirPlace() {
        // You look for a nick where you last saw it; away is a dimming, not a re-sort.
        val sorted = MemberPrefix.sorted(listOf(member("bob"), member("alice", away = true)))
        assertEquals(listOf("alice", "bob"), sorted.map { it.nick })
    }

    @Test
    fun testUnprivilegedMembersSortLast() {
        assertEquals(0, MemberPrefix.order(listOf("q")))
        assertTrue(MemberPrefix.order(emptyList()) > MemberPrefix.order(listOf("v")))
    }
}
