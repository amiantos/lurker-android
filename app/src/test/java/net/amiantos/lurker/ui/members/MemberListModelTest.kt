// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.members

import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.store.ChatState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The member list's rules — lurker-ios's `MemberListViewController`. */
class MemberListModelTest {

    private val key = BufferKey(networkId = 1, target = "#lurker")

    private fun state(members: List<Member>, ignores: IgnoreSet = IgnoreSet.empty, nick: String = "me") =
        ChatState(
            networks = mapOf(1 to Network(id = 1, name = "Libera", nick = nick)),
            members = mapOf(key.id to members),
            ignores = ignores,
        )

    @Test
    fun rowsAreRankedThenAlphabeticalFoldingCase() {
        val rows = MemberListModel.rows(
            listOf(
                Member("zed"),
                Member("Bob", modes = listOf("v")),
                Member("alice", modes = listOf("o", "v")),
                Member("carol", modes = listOf("q")),
                Member("Amy"),
            ),
        )
        assertEquals(listOf("carol", "alice", "Bob", "Amy", "zed"), rows.map { it.nick })
        // The highest mode held wins the glyph.
        assertEquals(listOf("~", "@", "+", "", ""), rows.map { it.prefix })
    }

    @Test
    fun awayIsCarriedNotSortedToTheBottom() {
        val rows = MemberListModel.rows(listOf(Member("bob", away = true), Member("alice")))
        assertEquals(listOf("alice", "bob"), rows.map { it.nick })
        assertTrue(rows[1].away)
    }

    @Test
    fun theFilterMatchesTheNickNotTheGlyphFoldingCaseAndTrimming() {
        val rows = MemberListModel.rows(listOf(Member("Alice", modes = listOf("o")), Member("malice"), Member("bob")))
        assertEquals(listOf("Alice", "malice"), MemberListModel.filter(rows, "  ALI ").map { it.nick })
        // `@` is a fact about the row, not part of the name.
        assertTrue(MemberListModel.filter(rows, "@").isEmpty())
        assertEquals(rows, MemberListModel.filter(rows, "   "))
    }

    @Test
    fun theFieldAppearsAtTheThresholdAndItsQueryGoesWithIt() {
        assertFalse(MemberListModel.wantsSearch(MemberListModel.SEARCH_THRESHOLD - 1))
        assertTrue(MemberListModel.wantsSearch(MemberListModel.SEARCH_THRESHOLD))
        // ⚠⚠ A netsplit that drops a filtered channel under the threshold must not leave it filtered.
        assertEquals("", MemberListModel.effectiveQuery("bob", MemberListModel.SEARCH_THRESHOLD - 1))
        assertEquals("bob", MemberListModel.effectiveQuery("bob", MemberListModel.SEARCH_THRESHOLD))
    }

    @Test
    fun theTitleCountsOnlyWhenThereIsSomeoneToCount() {
        assertEquals("Members", MemberListModel.title(0))
        assertEquals("Members (3)", MemberListModel.title(3))
    }

    @Test
    fun theEmptySentenceSaysWhichKindOfEmpty() {
        assertEquals("No members match.", MemberListModel.emptyText(BufferKind.Channel, searching = true))
        assertEquals("No members yet.", MemberListModel.emptyText(BufferKind.Channel, searching = false))
        assertEquals("Direct messages have no member list.", MemberListModel.emptyText(BufferKind.Dm, searching = false))
        assertEquals("A DCC chat has no member list.", MemberListModel.emptyText(BufferKind.Dcc, searching = false))
        assertEquals("This buffer has no member list.", MemberListModel.emptyText(BufferKind.Server, searching = false))
        assertEquals("This buffer has no member list.", MemberListModel.emptyText(BufferKind.System, searching = false))
    }

    @Test
    fun talkBackHearsTheRankInWordsAndAway() {
        assertEquals("alice, operator", MemberListModel.accessibilityLabel(MemberRow("alice", "@", away = false)))
        assertEquals("bob, half-operator, away", MemberListModel.accessibilityLabel(MemberRow("bob", "%", away = true)))
        assertEquals("carol", MemberListModel.accessibilityLabel(MemberRow("carol", "", away = false)))
        assertEquals("dan, owner", MemberListModel.accessibilityLabel(MemberRow("dan", "~", away = false)))
        assertEquals("erin, admin", MemberListModel.accessibilityLabel(MemberRow("erin", "&", away = false)))
        assertEquals("fay, voiced", MemberListModel.accessibilityLabel(MemberRow("fay", "+", away = false)))
    }

    @Test
    fun theFriendItemFollowsWhetherTheyAreOne() {
        assertEquals("Add to Friends", MemberListModel.friendActionTitle(isFriend = false))
        assertEquals("Remove from Friends", MemberListModel.friendActionTitle(isFriend = true))
    }

    @Test
    fun anIgnoreRuleHidesAMemberButNeverYou() {
        val ignores = IgnoreSet(global = listOf(IgnoreRule(mask = "*!*@shared.host", levels = listOf("ALL"))))
        val members = listOf(
            Member("me", user = "u", host = "shared.host"),
            Member("spammer", user = "x", host = "shared.host"),
            Member("alice", user = "a", host = "elsewhere"),
        )
        val visible = MemberListInputs.of(state(members, ignores), key).visible
        assertEquals(listOf("me", "alice"), visible.map { it.nick })
    }

    @Test
    fun inputsCompareByIdentityOfTheListAndTheIgnoreSet() {
        val members = listOf(Member("alice"))
        val base = state(members)
        val a = MemberListInputs.of(base, key)
        // An unrelated frame: same list, same set.
        assertTrue(MemberListInputs.same(a, MemberListInputs.of(base.copy(maxEventId = 9), key)))
        // A new list, even an equal one, is a change worth re-reading.
        assertFalse(MemberListInputs.same(a, MemberListInputs.of(base.copy(members = mapOf(key.id to listOf(Member("alice")))), key)))
        // A rule from another device decides who's listed.
        assertFalse(MemberListInputs.same(a, MemberListInputs.of(base.copy(ignores = IgnoreSet(global = emptyList())), key)))
        // So does our own nick.
        assertFalse(MemberListInputs.same(a, MemberListInputs.of(state(members, nick = "me_"), key)))
    }
}
