// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.Replies
import net.amiantos.lurkerkit.model.ReplyContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** IRCv3 replies (lurker-ios#184): the wire, the display rules, the Reply gate, the send. */
class RepliesTests {

    private fun line(
        id: Long = 1,
        nick: String = "bob",
        text: String = "hi",
        type: EventType = EventType.Message,
        isSelf: Boolean = false,
        msgid: String? = "m1",
        e2e: Boolean = false,
        replyTo: ReplyContext? = null,
    ): Message =
        Message(id = id, type = type, nick = nick, text = text, isSelf = isSelf, msgid = msgid, isE2E = e2e, replyTo = replyTo)

    // MARK: - Wire

    // MARK: - Text

    @Test
    fun testExcerptIsOnePlainLine() {
        assertEquals("bold and more", Replies.excerpt("\u0002bold\u0002 and\n\n  more\n"))
    }

    @Test
    fun testConsumes() {
        assertTrue(Replies.consumes("hello"))
        assertTrue(Replies.consumes("//not a command"))
        assertTrue(Replies.consumes("/me waves"))
        assertTrue(Replies.consumes("/ME waves"))
        assertFalse(Replies.consumes("/me "))
        assertFalse(Replies.consumes("/mean"))
        assertFalse(Replies.consumes("/whois bob"))
        assertFalse(Replies.consumes("/slap bob"))
        assertFalse(Replies.consumes(""))
    }

    // MARK: - Shown

    // MARK: - Reply gate

    @Test
    fun testReplyableNeedsAStampedConversationLine() {
        assertTrue(Replies.replyable(line(type = EventType.Notice), target = "#c"))
        assertFalse(Replies.replyable(line(0), target = "#c"))
        assertFalse(Replies.replyable(line(msgid = null), target = "#c"))
        assertFalse(Replies.replyable(line(e2e = true), target = "#c"))
        assertFalse(Replies.replyable(line(type = EventType.Join), target = "#c"))
        assertFalse(Replies.replyable(line(), target = ":server:1"))
    }

    // MARK: - Refusals give the reply back

    // Waiting on FrameParser, ServerFrame: testRowsCarryReplyToAndTheStamp
    //
    // Waiting on NickCompletion (`Replies.stripAddress`, `Replies.continues`, and the `alice`
    // fixture): testStripAddressNeedsPunctuationAfterTheNick, testASplitReplyIsQuotedOnce
    //
    // Waiting on IgnoreSet, IgnoreRule, RelayBotSet, NickCompletion (`Replies.shown`, and the
    // private `shown` helper): testQuoteAndStrippedText,
    // testNoQuoteKeepsTheAddressItIsTheOnlySignOfWhoItsTo, testAnActionKeepsItsText,
    // testIgnoredSinceHidesTheQuoteButNotYourOwnLine, testARelayedParentQuotesThePersonInside
    //
    // Waiting on MessageActions, MessageActionScope (and the private `replyTitle` helper):
    // testChannelReplyIsAlwaysOffered, testYourOwnLineIsTagOnly, testADmIsTagOnly
    //
    // Waiting on UnsentCorrelator, UnsentLine, LurkerStore: testARefusedLineComesHomeWithItsReply
}

// Waiting on IgnoreSet, RelayBotSet, NickCompletion (`Replies.presenting`): the whole
// `ReplyPresentingTests` suite — testPresentingSetsTheQuoteAndTextAndPassesOthersThrough
//
// Waiting on NickCompletion: the whole `RemovingAddressTests` suite —
// testTakesBackTheAddressAndNothingElse, testLeavesADraftThatNoLongerOpensWithIt,
// testIsAddressedStillAgrees
//
// Waiting on NickCompletion, IgnoreSet, RelayBotSet, MessageActions: the whole
// `ReplyReviewTests` suite — testCopyKeepsTheAddressTheQuoteHides, testNicksFoldAsciiOnly,
// testStripAddressTakesEverySpaceAfterTheMark
