// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.NickNote
import net.amiantos.lurkerkit.model.NickNoteSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whois and nick notes end to end (lurker-ios#12): the wire in, the types they build, what the
 * store does with them, and the in-flight bookkeeping that decides whether a lookup can be
 * retried.
 *
 * The marker rules are ported from the web's `stores/whois.test.ts` rather than invented —
 * each was a shipped bug (lurker#818) before it was a rule, and each fails differently.
 */
class WhoisTests {

    // MARK: - Nick notes

    @Test
    fun testNotesFoldCaseButKeepTheirStoredCasingForDisplay() {
        val set = NickNoteSet(byNetwork = mapOf(1 to listOf(NickNote(nick = "Alice", note = "lives in Berlin"))))
        assertEquals("lives in Berlin", set.note(networkId = 1, nick = "ALICE")?.note)
        assertEquals("Alice", set.note(networkId = 1, nick = "alice")?.nick)
        assertTrue(set.hasNote(networkId = 1, nick = "alice"))
    }

    @Test
    fun testNotesAreScopedToOneNetwork() {
        // The same nick on two networks may be two people — the server keys them apart too.
        val set = NickNoteSet(byNetwork = mapOf(1 to listOf(NickNote(nick = "alice", note = "n"))))
        assertFalse(set.hasNote(networkId = 2, nick = "alice"))
        assertFalse(set.hasNote(networkId = null, nick = "alice"))
    }

    @Test
    fun testABlankNoteNeverBecomesAnEntry() {
        // An empty note is the server's spelling of "no note" — `set_nick_note` deletes the row
        // rather than storing a blank. A present-but-blank entry would make `hasNote` lie.
        val set = NickNoteSet(
            byNetwork = mapOf(1 to listOf(NickNote(nick = "alice", note = ""), NickNote(nick = "", note = "x")))
        )
        assertFalse(set.hasNote(networkId = 1, nick = "alice"))
        assertNull(set.note(networkId = 1, nick = ""))
    }

    @Test
    fun testApplyingWritesAndClearsOneNoteWithoutDisturbingTheOthers() {
        val set = NickNoteSet(
            byNetwork = mapOf(
                1 to listOf(NickNote(nick = "alice", note = "a"), NickNote(nick = "bob", note = "b")),
            )
        )
        val written = set.applying(networkId = 1, nick = "carol", note = "c", updatedAt = null)
        assertEquals("c", written.note(networkId = 1, nick = "carol")?.note)
        assertEquals("a", written.note(networkId = 1, nick = "alice")?.note)

        // An empty note is the delete — the same frame shape as a write.
        val cleared = written.applying(networkId = 1, nick = "ALICE", note = "", updatedAt = null)
        assertNull(cleared.note(networkId = 1, nick = "alice"))
        assertEquals("b", cleared.note(networkId = 1, nick = "bob")?.note)

        // And the original is untouched — these are replaced, never mutated, which is what
        // makes `===` a valid "did the notes change" test for the screens.
        assertEquals("a", set.note(networkId = 1, nick = "alice")?.note)
        assertNull(set.note(networkId = 1, nick = "carol"))
    }

    @Test
    fun testRewritingANoteReplacesItRatherThanAddingASecondRow() {
        val set = NickNoteSet(byNetwork = mapOf(1 to listOf(NickNote(nick = "alice", note = "old"))))
            .applying(networkId = 1, nick = "ALICE", note = "new", updatedAt = null)
        assertEquals("new", set.note(networkId = 1, nick = "alice")?.note)
        // The server's canonical casing wins, because that's what the echo carries.
        assertEquals("ALICE", set.note(networkId = 1, nick = "alice")?.nick)
    }

    @Test
    fun testRemovingANetworkDropsOnlyItsNotes() {
        val set = NickNoteSet(
            byNetwork = mapOf(
                1 to listOf(NickNote(nick = "alice", note = "a")),
                2 to listOf(NickNote(nick = "alice", note = "b")),
            )
        ).removing(networkId = 1)
        assertNull(set.note(networkId = 1, nick = "alice"))
        assertEquals("b", set.note(networkId = 2, nick = "alice")?.note)
    }

    // Waiting on WhoisResult: testHostmaskFillsAMissingHalfWithAStarAndIsNilWithNeither,
    // testChannelsIsOneSpaceSeparatedStringNotAnArray, testAGreedySigilPeelWouldEatTheChannelSigil,
    // testAnUnprefixedChannelKeepsItsOwnSigil, testSplitPrefersTheLargestPeelThatStillLeavesAChannel,
    // testASigilOnlyTokenIsDroppedRatherThanBecomingATappableBlank,
    // testATokenOnAnUnknownChannelTypeIsKeptUnpeeledRatherThanDropped, testAnAbsentChannelsLineIsNoChannels
    //
    // Waiting on FrameParser, ServerFrame (and WhoisResult):
    // testParsesTheFullReplyUsingIrcFrameworksFieldNames,
    // testIdleAndSignonArriveAsStringsBecauseIrcParametersAreText,
    // testIdleAndSignonAlsoAcceptRealJsonNumbers, testAnAbsentSignonIsNilRatherThanTheEpoch,
    // testTheNotFoundMissIsAnOrdinaryReplyCarryingAnError, testWhoisResultSurvivesCarryingNoTarget,
    // testAReplyWeCannotAddressIsRefused, testParsesTheNoteUpdateFrame, testANoteAboutNobodyIsRefused,
    // testAFrameWithNoReadableNoteIsRefusedRatherThanTreatedAsAClear,
    // testTheSnapshotSeedsNotesAndDropsUnusableRows
    //
    // Waiting on LurkerStore, ChatState, ChatViewModel (and the `viewModel()` helper):
    // testAReplyIsCachedUnderTheServersCasingAndFoundUnderAnyOther, testAReplyFreesTheInFlightSlot,
    // testANotFoundFreesTheSlotToo, testAReplyForOneNickLeavesAnotherLookupPending,
    // testTheSameNickOnTwoNetworksIsTwoLookups,
    // testDroppingANetworkForgetsItsRepliesNotesAndPendingLookups,
    // testRequestingAWhoisWithNoSocketClaimsNothing, testRequestingAWhoisForNobodyDoesNothing,
    // testAPaddedNickIsKeyedTheWayTheServerWillAnswerIt,
    // testASocketDropFreesEveryLookupThatWasOutOverIt, testTheNoteUpdateFramePatchesOneNick,
    // testTheSnapshotReplacesNotesWholesale
}
