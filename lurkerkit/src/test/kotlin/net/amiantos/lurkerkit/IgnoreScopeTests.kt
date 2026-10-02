// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.IgnoreInput
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.NickCompletion
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ignore rules from the wire to the answer: how the two scopes union, how the frames seed and
 * replace them, and the two surfaces that read them through the store.
 *
 * The scope half is derived from the web client's `stores/ignores.test.ts` (lurker #350) —
 * the bug it exists to prevent is a global rule, which is what a bare `/ignore` creates, being
 * stored somewhere the per-network read never looks.
 */
class IgnoreScopeTests {

    private fun rule(mask: String, levels: List<String> = listOf("ALL")): IgnoreRule =
        IgnoreRule(mask = mask, levels = levels)

    private fun input(nick: String, target: String = "#chan"): IgnoreInput =
        IgnoreInput(
            nick = nick, userhost = "$nick!u@h", target = target,
            text = "hello", type = EventType.Message, isDm = false,
        )

    // MARK: - Scope

    @Test
    fun testAGlobalRuleAppliesOnEveryNetworkAndANetworkRuleOnlyOnItsOwn() {
        val set = IgnoreSet(global = listOf(rule(mask = "spammer")), byNetwork = mapOf(1 to listOf(rule(mask = "local"))))
        assertTrue(set.isHidden(networkId = 1, input(nick = "spammer")))
        assertTrue(set.isHidden(networkId = 2, input(nick = "spammer")))
        assertTrue(set.isHidden(networkId = 1, input(nick = "local")))
        assertFalse(
            set.isHidden(networkId = 2, input(nick = "local")),
            "a network-scoped rule must not leak to a sibling network",
        )
    }

    /**
     * The system buffer has no network and its lines have no IRC sender, so the whole feature
     * sits out — matching the web, whose render filter is gated on a truthy `networkId`.
     */
    @Test
    fun testANilNetworkIsNeverFiltered() {
        val set = IgnoreSet(global = listOf(rule(mask = "*")))
        assertTrue(set.isEmpty(null))
        assertFalse(set.isHidden(networkId = null, input(nick = "anyone")))
    }

    @Test
    fun testAnAccountWithNoRulesReadsEmptyForEveryNetwork() {
        assertTrue(IgnoreSet.empty.isEmpty(1))
        // Globals alone still count as rules for every network, including one we've never
        // heard of — that's what makes the fast path safe to take.
        assertFalse(IgnoreSet(global = listOf(rule(mask = "x"))).isEmpty(99))
    }

    @Test
    fun testReplacingOneBucketLeavesTheOtherStanding() {
        val set = IgnoreSet(global = listOf(rule(mask = "spammer")), byNetwork = mapOf(1 to listOf(rule(mask = "local"))))

        val globalsReplaced = set.replacing(networkId = null, rules = emptyList())
        assertFalse(globalsReplaced.isHidden(networkId = 1, input(nick = "spammer")))
        assertTrue(
            globalsReplaced.isHidden(networkId = 1, input(nick = "local")),
            "clearing globals must not clear the network bucket",
        )

        val networkReplaced = set.replacing(networkId = 1, rules = emptyList())
        assertTrue(networkReplaced.isHidden(networkId = 1, input(nick = "spammer")))
        assertFalse(networkReplaced.isHidden(networkId = 1, input(nick = "local")))
    }

    /**
     * An `-except` in one bucket has to beat a hide in the other, which only holds if the two
     * are evaluated as one set rather than consulted in turn.
     */
    @Test
    fun testAnExceptInOneBucketVetoesAHideInTheOther() {
        val set = IgnoreSet(
            global = listOf(IgnoreRule(mask = "*!*@spam", levels = listOf("ALL"))),
            byNetwork = mapOf(1 to listOf(IgnoreRule(mask = "bob!*@spam", levels = listOf("ALL"), isExcept = true))),
        )
        assertFalse(
            set.isHidden(
                networkId = 1,
                IgnoreInput(
                    nick = "bob", userhost = "bob!u@spam", target = "#chan",
                    text = "hi", type = EventType.Message, isDm = false,
                ),
            ),
        )
    }

    // MARK: - Listing (lurker-ios#86)

    @Test
    fun testListingPutsGlobalsFirstAndTagsEachRuleWithItsBucket() {
        // The order is `/unignore <n>`'s addressing scheme, so it's the contract, not a
        // detail: a listing that ordered the buckets the other way would have `/unignore 1`
        // delete a different rule than the one printed as #1.
        val set = IgnoreSet(
            global = listOf(rule(mask = "spammer")),
            byNetwork = mapOf(1 to listOf(rule(mask = "local")), 2 to listOf(rule(mask = "elsewhere"))),
        )
        val listing = set.listing(1)
        assertEquals(listOf<String?>("spammer", "local"), listing.map { it.rule.mask })
        assertEquals(listOf(null, 1), listing.map { it.scope })
    }

    @Test
    fun testListingFromTheSystemBufferShowsGlobalsOnly() {
        // Nothing network-scoped is in scope where there's no network — and a global rule is
        // exactly what a bare `/ignore` there creates, so the list stays actionable.
        val set = IgnoreSet(global = listOf(rule(mask = "spammer")), byNetwork = mapOf(1 to listOf(rule(mask = "local"))))
        assertEquals(listOf<String?>("spammer"), set.listing(null).map { it.rule.mask })
        assertTrue(IgnoreSet.empty.listing(null).isEmpty())
    }

    @Test
    fun testListingCarriesTheServerRowIdThatByIndexRemovalNeeds() {
        val set = IgnoreSet(byNetwork = mapOf(1 to listOf(IgnoreRule(id = 42, mask = "bob"))))
        assertEquals(42, set.listing(1).firstOrNull()?.rule?.id)
    }

    // MARK: - Frames

    // MARK: - Store-level readers

    @Test
    fun testAMutedBufferIsReportedByTheSetTheBufferListReads() {
        val set = IgnoreSet(
            byNetwork = mapOf(1 to listOf(IgnoreRule(channels = listOf("#loud"), levels = listOf("NOUNREAD", "NONOTIFY")))),
        )
        assertTrue(set.mutesUnread(networkId = 1, target = "#loud"))
        assertFalse(set.mutesUnread(networkId = 1, target = "#quiet"))
        assertFalse(set.mutesUnread(networkId = 2, target = "#loud"))
        assertFalse(set.mutesUnread(networkId = null, target = "#loud"))
    }

    /**
     * The feeds' reader. A self-authored or sender-less row is never hidden, and level rules
     * apply here too — the whole reason it isn't just `isIgnored`.
     */
    @Test
    fun testIsMessageHiddenJudgesAFeedRowInItsOwnBuffer() {
        val set = IgnoreSet(global = listOf(rule(mask = "bob", levels = listOf("PUBLIC"))))
        fun message(nick: String?, isSelf: Boolean = false): Message =
            Message(
                id = 1, type = EventType.Message, nick = nick, text = "hi", isSelf = isSelf,
                userhost = nick?.let { "$it!u@h" },
            )
        assertTrue(
            set.isMessageHidden(networkId = 1, message = message(nick = "bob"), target = "#chan"),
        )
        assertFalse(
            set.isMessageHidden(networkId = 1, message = message(nick = "bob"), target = "alice"),
            "PUBLIC covers channel messages; a DM row is MSGS",
        )
        assertFalse(
            set.isMessageHidden(networkId = 1, message = message(nick = null), target = "#chan"),
        )
        assertFalse(
            set.isMessageHidden(
                networkId = 1, message = message(nick = "bob", isSelf = true), target = "#chan",
            ),
            "your own line is never hidden by a rule that happens to cover your nick",
        )
    }

    // MARK: - The message list's filter

    /**
     * `visible` is what the message list actually calls, and the only caller of
     * `unhighlighted()` — so without this the whole client half of NOHIGHLIGHT was unverified:
     * changing the demote to a plain pass-through left every suite green while every mention
     * wash a rule was written to remove stayed on screen.
     */
    @Test
    fun testVisibleDropsHiddenRowsAndDemotesNohighlightedOnes() {
        fun message(id: Long, nick: String, matched: Boolean = false): Message =
            Message(
                id = id, type = EventType.Message, nick = nick, text = "hi",
                matched = matched, userhost = "$nick!u@h",
            )
        val messages = listOf(message(1, "bob", matched = true), message(2, "alice", matched = true))

        val hiding = IgnoreSet(global = listOf(rule(mask = "bob")))
        assertEquals(
            listOf(2L), hiding.visible(messages, networkId = 1, target = "#chan").map { it.id },
            "a hide rule drops the row",
        )

        val quieting = IgnoreSet(global = listOf(rule(mask = "bob", levels = listOf("NOHIGHLIGHT"))))
        val quieted = quieting.visible(messages, networkId = 1, target = "#chan")
        assertEquals(listOf(1L, 2L), quieted.map { it.id }, "NOHIGHLIGHT keeps the row")
        assertFalse(quieted[0].matched, "…but takes its mention wash off")
        assertTrue(quieted[1].matched, "and leaves everyone else's alone")
    }

    /**
     * Your own lines survive a rule broad enough to cover them — the exemption `verdict`
     * states once for every surface.
     */
    @Test
    fun testVisibleNeverHidesYourOwnLines() {
        val mine = Message(
            id = 1, type = EventType.Message, nick = "me", text = "hi", isSelf = true, userhost = "me!u@h",
        )
        val set = IgnoreSet(global = listOf(rule(mask = "*")))
        assertEquals(listOf(1L), set.visible(listOf(mine), networkId = 1, target = "#chan").map { it.id })
    }

    /**
     * The jump target survives whatever the rules say. Landing resolves the anchor against
     * the RENDERED rows, so filtering it out doesn't just hide a line — it strands the jump
     * and, with it, the buffer's hydration for the life of the screen.
     */
    @Test
    fun testVisibleKeepsTheJumpTargetButStillDemotesIt() {
        val target = Message(
            id = 7, type = EventType.Message, nick = "bob", text = "hi", matched = true, userhost = "bob!u@h",
        )
        val other = Message(id = 8, type = EventType.Message, nick = "bob", text = "hi", userhost = "bob!u@h")
        val set = IgnoreSet(global = listOf(rule(mask = "bob")))
        assertEquals(
            listOf(7L),
            set.visible(listOf(target, other), networkId = 1, target = "#chan", keeping = 7).map { it.id },
            "the exempt id survives; its neighbour from the same sender does not",
        )

        // The exemption is about the row existing, not about overriding the verdict's styling.
        val quieting = IgnoreSet(global = listOf(rule(mask = "bob", levels = listOf("NOHIGHLIGHT"))))
        val kept = quieting.visible(listOf(target), networkId = 1, target = "#chan", keeping = 7)
        assertFalse(kept[0].matched)
    }

    /**
     * The exemption is off unless something is being jumped to, and an ephemeral can never be
     * the thing jumped to — id 0 is the absence of an address, so matching on it would exempt
     * every ephemeral in the buffer at once.
     */
    @Test
    fun testTheJumpExemptionCoversNothingWhenThereIsNoJump() {
        val ephemeral = Message(id = 0, type = EventType.Message, nick = "bob", text = "hi", userhost = "bob!u@h")
        val persisted = Message(id = 9, type = EventType.Message, nick = "bob", text = "hi", userhost = "bob!u@h")
        val set = IgnoreSet(global = listOf(rule(mask = "bob")))
        assertTrue(
            set.visible(listOf(ephemeral, persisted), networkId = 1, target = "#chan").isEmpty(),
            "no `keeping` means no exemption",
        )
        assertTrue(
            set.visible(listOf(ephemeral), networkId = 1, target = "#chan", keeping = 0).isEmpty(),
            "id 0 is not an address and must not be exempted by a 0 `keeping`",
        )
    }

    /**
     * An expired rule stops applying through `IgnoreSet` too, not just when `IgnoreMatch` is
     * driven directly — the `now` plumbing is threaded through five methods and was untested
     * on all of them.
     */
    @Test
    fun testExpiryIsHonoredThroughTheSet() {
        val past = Instant.ofEpochSecond(946_684_800)
        val future = Instant.ofEpochSecond(32_503_680_000)
        val set = IgnoreSet(global = listOf(IgnoreRule(mask = "bob", levels = listOf("ALL"), expiresAt = past)))
        val message = Message(id = 1, type = EventType.Message, nick = "bob", text = "hi", userhost = "bob!u@h")
        assertEquals(1, set.visible(listOf(message), networkId = 1, target = "#chan").size)
        assertFalse(set.isIgnored(networkId = 1, nick = "bob", userhost = "bob!u@h"))

        val live = IgnoreSet(global = listOf(IgnoreRule(mask = "bob", levels = listOf("ALL"), expiresAt = future)))
        assertEquals(0, live.visible(listOf(message), networkId = 1, target = "#chan").size)
        assertTrue(live.isIgnored(networkId = 1, nick = "bob", userhost = "bob!u@h"))
    }

    /**
     * All four RFC 2811 sigils are channels to the matcher, exactly as they are to the
     * server's `isDmTarget` (lurker-ios#98). On iOS this test asserted the opposite while the
     * twin was `#`-only: a DMs-level rule hid an `&local` channel there and left it visible on
     * the web, off one rule on one account.
     */
    @Test
    fun testEveryChannelSigilIsPublicToTheMatcherAsItIsToTheServer() {
        val message = Message(id = 1, type = EventType.Message, nick = "bob", text = "hi", userhost = "bob!u@h")
        val publicRule = IgnoreSet(global = listOf(rule(mask = "bob", levels = listOf("PUBLIC"))))
        val dmRule = IgnoreSet(global = listOf(rule(mask = "bob", levels = listOf("MSGS"))))

        for (target in listOf("#chan", "&local", "+nomodes", "!12345safe")) {
            assertTrue(
                publicRule.isMessageHidden(networkId = 1, message = message, target = target),
                "PUBLIC should cover $target",
            )
            assertFalse(
                dmRule.isMessageHidden(networkId = 1, message = message, target = target),
                "MSGS should not cover $target",
            )
        }
        // The other half of the split: a real DM is still MSGS and not PUBLIC.
        assertTrue(dmRule.isMessageHidden(networkId = 1, message = message, target = "alice"))
        assertFalse(publicRule.isMessageHidden(networkId = 1, message = message, target = "alice"))
    }

    // MARK: - Completion

    @Test
    fun testNickCompletionDropsAnIgnoredCandidate() {
        val members = listOf(
            Member(nick = "bobby", user = "u", host = "h"),
            Member(nick = "bonnie", user = "u", host = "h"),
        )
        val messages = listOf(Message(id = 1, type = EventType.Message, nick = "bobby", text = "hi"))
        val set = IgnoreSet(global = listOf(rule(mask = "bobby")))
        val candidates = NickCompletion.candidates(
            messages = messages, members = members, selfNick = "me", query = "bo", isChannel = true,
            ignores = set, networkId = 1,
        )
        assertEquals(listOf("bonnie"), candidates)
    }

    /**
     * A hostmask-only rule reaches a member (whose user/host the server sent) and not a
     * speaker who has since left the channel and carries no mask — the same information the
     * web has at the same point, so the same answer.
     */
    @Test
    fun testACompletionCandidateIsJudgedOnItsReconstructedHostmask() {
        val members = listOf(Member(nick = "bobby", user = "spam", host = "evil.example"))
        val set = IgnoreSet(global = listOf(rule(mask = "*!spam@evil.example")))
        assertEquals(
            emptyList(),
            NickCompletion.candidates(
                messages = emptyList(), members = members, selfNick = "me", query = "bo", isChannel = true,
                ignores = set, networkId = 1,
            ),
        )
    }

    // Port-only:

    /**
     * `IgnoreSet` has value equality here, where LurkerKit's is a class compared by reference:
     * it sits in published state, and a `StateFlow` drops a value that equals the one it
     * holds. Equal means both buckets hold the same rules — and nothing less.
     */
    @Test
    fun testSetsWithTheSameRulesInTheSameBucketsAreEqual() {
        val set = IgnoreSet(global = listOf(rule(mask = "spammer")), byNetwork = mapOf(1 to listOf(rule(mask = "local"))))
        val same = IgnoreSet(global = listOf(rule(mask = "spammer")), byNetwork = mapOf(1 to listOf(rule(mask = "local"))))
        assertEquals(set, same)
        assertEquals(set.hashCode(), same.hashCode())
        assertEquals(IgnoreSet.empty, IgnoreSet())

        assertFalse(set == set.replacing(networkId = null, rules = emptyList()), "a global rule removed is a change")
        assertFalse(set == set.replacing(networkId = 1, rules = emptyList()), "a network rule removed is a change")
        assertFalse(
            set == set.replacing(networkId = 1, rules = listOf(rule(mask = "local", levels = listOf("JOINS")))),
            "one field of one rule is a change",
        )
        assertFalse(
            set == IgnoreSet(global = listOf(rule(mask = "local")), byNetwork = mapOf(1 to listOf(rule(mask = "spammer")))),
            "the same rules in the other buckets are a different set",
        )
    }

    // Waiting on LurkerStore, ServerFrame (`NetworkSnapshot`):
    // testTheSnapshotSeedsBothBucketsAndReplacesThemWholesale,
    // testIgnoreListUpdatedReplacesOnlyTheScopeItNames
    //
    // Waiting on FrameParser: testTheWireShapeParsesIntoARule,
    // testAnIgnoreUpdateWithoutAUsableMasksArrayIsDroppedNotReadAsEmpty,
    // testANullNetworkIdParsesAsTheGlobalScope, testAMinimalRuleParsesWithEverythingUnconstrained
    //
    // Waiting on ChatState: testAnIgnoredPeerIsNotReportedAsTyping,
    // testVisibleMembersDropsIgnoredPeopleButNeverYou
}
