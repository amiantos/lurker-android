// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageReaction
import net.amiantos.lurkerkit.model.ReactionGroup
import net.amiantos.lurkerkit.model.Reactions
import net.amiantos.lurkerkit.support.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** IRCv3 reactions (lurker-ios#183): the wire, the side map, and the gates. */
class ReactionsTests {

    private fun line(
        id: Long,
        msgid: String? = "m",
        type: EventType = EventType.Message,
        isSelf: Boolean = false,
        isE2E: Boolean = false,
        reactions: List<MessageReaction>? = null,
    ): Message =
        Message(
            id = id, type = type, nick = "alice", text = "hi", isSelf = isSelf,
            msgid = msgid, isE2E = isE2E, reactions = reactions,
        )

    // MARK: - Wire

    // MARK: - Side map

    @Test
    fun testGroupsKeepFirstReactedOrderAndMarkOurs() {
        val groups = Reactions.groups(
            listOf(
                MessageReaction(nick = "bob", value = "👍", isSelf = false),
                MessageReaction(nick = "carol", value = "lol", isSelf = false),
                MessageReaction(nick = "me", value = "👍", isSelf = true),
            )
        )
        assertEquals(
            listOf(
                ReactionGroup(value = "👍", nicks = listOf("bob", "me"), mine = true),
                ReactionGroup(value = "lol", nicks = listOf("carol"), mine = false),
            ),
            groups,
        )
    }

    // MARK: - canReact

    // MARK: - Gates

    @Test
    fun testValueRules() {
        assertTrue(Reactions.isValidValue("👍"))
        assertTrue(Reactions.isValidValue("lol"))
        assertTrue(Reactions.isValidValue("👨‍👩‍👧‍👦"), "one grapheme, however many scalars")
        assertFalse(Reactions.isValidValue("  "))
        assertFalse(Reactions.isValidValue("a\nb"))
        assertTrue(Reactions.isValidValue("x".repeat(64)))
        assertFalse(Reactions.isValidValue("x".repeat(65)))
    }

    @Test
    fun testSendGate() {
        val ok = line(1)
        assertTrue(Reactions.canSend(ok, target = "#lurker", networkCanReact = true))
        assertTrue(Reactions.canSend(ok, target = "bob", networkCanReact = true), "a DM")
        assertTrue(Reactions.canSend(line(1, type = EventType.Action), target = "#lurker", networkCanReact = true))
        assertFalse(Reactions.canSend(ok, target = "#lurker", networkCanReact = false))
        assertFalse(Reactions.canSend(line(1, msgid = null), target = "#lurker", networkCanReact = true))
        assertFalse(Reactions.canSend(line(0), target = "#lurker", networkCanReact = true))
        assertFalse(Reactions.canSend(line(1, isE2E = true), target = "#lurker", networkCanReact = true))
        assertFalse(Reactions.canSend(line(1, type = EventType.Notice), target = "#lurker", networkCanReact = true))
        assertFalse(Reactions.canSend(ok, target = ":server:1", networkCanReact = true))
        assertFalse(Reactions.canSend(ok, target = "=bob", networkCanReact = true), "DCC chat")
    }

    // Port-only:

    /**
     * Pins the unit of the limit. Swift's `count` is grapheme clusters for free; a UTF-16
     * length would refuse 64 family emoji (each is eleven units), and a code-point count 64
     * flags (each is two). The expectations are the Swift's own answers for the same strings
     * (LurkerKit's `Reactions`, compiled and run on a Mac).
     *
     * ⚠ Needs a host JDK of 20 or later: before that `java.text.BreakIterator` splits a ZWJ
     * sequence into its parts, and the first assertion fails. The device's segmenter is ICU —
     * a candidate for the instrumented suite (see the Port note on `Reactions.graphemeCount`).
     */
    @Test
    fun testTheLimitCountsGraphemeClustersNotUnits() {
        val family = "👨‍👩‍👧‍👦"
        val flag = "🇺🇸"
        assertTrue(Reactions.isValidValue(family.repeat(64)))
        assertFalse(Reactions.isValidValue(family.repeat(65)))
        assertTrue(Reactions.isValidValue(flag.repeat(64)))
        assertFalse(Reactions.isValidValue(flag.repeat(65)))
        assertTrue(Reactions.isValidValue("e\u0301".repeat(64)))
        assertFalse(Reactions.isValidValue("e\u0301".repeat(65)))
        assertTrue(Reactions.isValidValue("x".repeat(63) + family))
        assertFalse(Reactions.isValidValue("x".repeat(64) + family))
    }

    // Waiting on FrameParser, ServerFrame: testRowsCarryMsgidE2eAndReactions, testReactionFrameParses,
    // testReactionFrameThatAddressesNothingIsIgnored, testReactionsSyncParses,
    // testReactSupportAndSnapshotCanReactParse
    //
    // Waiting on LurkerStore, ChatState, ServerFrame (and the private `backlog`/`change` helpers,
    // `chanKey` and `thumbs`): testARowIsAuthoritativeForItselfInBothDirections,
    // testSilenceAboutALineIsNotARemoval, testSystemRowsNeverTouchTheMap,
    // testLiveReactionsAddDedupeAndRemove, testOurUnreactMatchesSelfNotNick,
    // testAReactionWeAlreadyHoldChangesNothing, testSyncIsAuthoritativeForEveryIdItNames,
    // testClosingABufferDropsItsLinesReactions, testSyncIdsAreTheNewestOfEachNetworkBuffer,
    // testCanReactNeedsTheFlagAndALiveLink, testCanReactNeedsOurOwnSocket,
    // testANickCollisionNeverFoldsIntoOurReaction, testAReactionToALineNobodyLoadedIsDropped,
    // testARevisionIsPerBuffer, testDroppingTheSystemBufferLeavesNetworkReactionsAlone
    //
    // Waiting on MessageActions: testReactActionFollowsTheSendGate
}

// Waiting on FrameParser (`parseActivity`), FeedReaction, FeedCursor: the whole
// `ActivityFeedParsingTests` suite — testHighlightAndReactionRowsAndThePairedCursor,
// testOneSidedCursorStillPagesAndNullEnds, testAReactionRowWithoutAValueIsDropped

/** `/react` (lurker-ios#183). */
class ReactCommandTests {

    private fun line(
        id: Long,
        type: EventType = EventType.Message,
        isSelf: Boolean = false,
        msgid: String? = "m",
        e2e: Boolean = false,
    ): Message =
        Message(id = id, type = type, nick = if (isSelf) "me" else "bob", text = "x", isSelf = isSelf, msgid = msgid, isE2E = e2e)

    @Test
    fun testLandsOnTheLastLineSomeoneElseSaid() {
        val target = Reactions.commandTarget(
            listOf(
                line(1), line(2), line(3, isSelf = true), line(4, EventType.Join),
                Message(id = 0, type = EventType.System, nick = null, text = "local"),
            )
        )
        assertEquals(2L, (target as? Result.Success)?.value?.id)
    }

    @Test
    fun testSaysWhyRatherThanReachingBack() {
        assertEquals(
            Result.Failure(Reactions.CommandRefusal("can't react to a notice")),
            Reactions.commandTarget(listOf(line(1), line(2, EventType.Notice))),
        )
        assertEquals(
            Result.Failure(Reactions.CommandRefusal("can't react to an encrypted line")),
            Reactions.commandTarget(listOf(line(1), line(2, e2e = true))),
        )
        assertEquals(
            Result.Failure(Reactions.CommandRefusal("can't react to that line (no message id)")),
            Reactions.commandTarget(listOf(line(1), line(2, msgid = null))),
        )
        assertEquals(
            Result.Failure(Reactions.CommandRefusal("nothing here to react to")),
            Reactions.commandTarget(listOf(line(3, isSelf = true))),
        )
    }

    // Waiting on CommandParser, CommandEffect (and the private `effects` helper): testParses
}

// Waiting on LurkerStore, ServerFrame: the whole `ReactionRenameTests` suite —
// testARenameCarriesTheRevision
