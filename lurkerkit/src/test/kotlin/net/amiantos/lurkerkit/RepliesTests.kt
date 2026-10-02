// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageActionContext
import net.amiantos.lurkerkit.model.MessageActionKey
import net.amiantos.lurkerkit.model.MessageActionScope
import net.amiantos.lurkerkit.model.MessageActions
import net.amiantos.lurkerkit.model.NickCompletion
import net.amiantos.lurkerkit.model.PendingReply
import net.amiantos.lurkerkit.model.RelayBotSet
import net.amiantos.lurkerkit.model.Replies
import net.amiantos.lurkerkit.model.ReplyContext
import net.amiantos.lurkerkit.model.ReplyParent
import net.amiantos.lurkerkit.model.UnsentCorrelator
import net.amiantos.lurkerkit.store.LurkerStore
import net.amiantos.lurkerkit.store.UnsentLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

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

    private val alice = ReplyParent(id = 7, nick = "alice", type = EventType.Message, text = "has anyone tried it?", userhost = "alice!a@host")

    // MARK: - Wire

    @Test
    fun testRowsCarryReplyToAndTheStamp() {
        val frame = FrameParser.parseWs(
            """
            {"kind":"backlog","networkId":1,"target":"#c","hasMoreOlder":false,"events":[
              {"id":2,"type":"message","nick":"bob","text":"alice: yes","replyTo":{"msgid":"p1","parent":{"id":7,"nick":"alice","type":"action","text":"waves","userhost":"alice!a@h","self":true}},"replyToSelf":true,"matched":true},
              {"id":3,"type":"message","nick":"bob","text":"x","replyTo":{"msgid":"gone","parent":null}},
              {"id":4,"type":"message","nick":"bob","text":"plain"}
            ]}
            """.trimIndent(),
        )
        if (frame !is ServerFrame.Backlog) fail("$frame")
        val messages = frame.messages
        assertEquals(
            ReplyContext(
                msgid = "p1",
                parent = ReplyParent(
                    id = 7, nick = "alice", type = EventType.Action, text = "waves", userhost = "alice!a@h", isSelf = true,
                ),
            ),
            messages[0].replyTo,
        )
        assertTrue(messages[0].replyToSelf)
        assertEquals(
            ReplyContext(msgid = "gone", parent = null),
            messages[1].replyTo,
            "a reply with nothing to quote is still a reply",
        )
        assertNull(messages[2].replyTo)
        assertFalse(messages[2].replyToSelf)
    }

    // MARK: - Text

    @Test
    fun testStripAddressNeedsPunctuationAfterTheNick() {
        assertEquals("yes", Replies.stripAddress("alice: yes", nick = "alice"))
        assertEquals("yes", Replies.stripAddress("ALICE, yes", nick = "alice"), "case-folded")
        assertEquals("will you come?", Replies.stripAddress("will you come?", nick = "will"), "a word, not an address")
        assertEquals("bob_: hi", Replies.stripAddress("bob_: hi", nick = "bob"), "bob_ is somebody else")
        assertEquals("alice: ", Replies.stripAddress("alice: ", nick = "alice"), "never strips to nothing")
        assertEquals("x", Replies.stripAddress("a.b: x", nick = "a.b"), "the nick is matched literally")
    }

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

    private fun shown(
        reply: Message,
        ignores: IgnoreSet = IgnoreSet.empty,
        relayBots: RelayBotSet = RelayBotSet.empty,
        ownNick: String? = "me",
    ): Replies.Shown =
        Replies.shown(
            reply.replyTo!!, line = reply, networkId = 1, target = "#c",
            ignores = ignores, relayBots = relayBots, ownNick = ownNick,
        )

    @Test
    fun testQuoteAndStrippedText() {
        val reply = line(text = "alice: yes", replyTo = ReplyContext(msgid = "p", parent = alice))
        val result = shown(reply)
        assertEquals("alice", result.quote?.nick)
        assertEquals(7L, result.quote?.id)
        assertEquals("yes", result.text)
    }

    @Test
    fun testNoQuoteKeepsTheAddressItIsTheOnlySignOfWhoItsTo() {
        val result = shown(line(text = "alice: yes", replyTo = ReplyContext(msgid = "p", parent = null)))
        assertNull(result.quote)
        assertEquals("alice: yes", result.text)
    }

    @Test
    fun testAnActionKeepsItsText() {
        val result = shown(line(text = "alice: waves", type = EventType.Action, replyTo = ReplyContext(msgid = "p", parent = alice)))
        assertEquals("alice: waves", result.text)
    }

    @Test
    fun testIgnoredSinceHidesTheQuoteButNotYourOwnLine() {
        val ignores = IgnoreSet(global = listOf(IgnoreRule(id = 1, mask = "alice!*@*")), byNetwork = emptyMap())
        val result = shown(line(text = "alice: yes", replyTo = ReplyContext(msgid = "p", parent = alice)), ignores = ignores)
        assertNull(result.quote, "ignored after the reply arrived")
        assertEquals("alice: yes", result.text)

        val mine = ReplyParent(id = 7, nick = "alice", type = EventType.Message, text = "x", userhost = "alice!a@host", isSelf = true)
        assertNotNull(shown(line(replyTo = ReplyContext(msgid = "p", parent = mine)), ignores = ignores).quote)
    }

    @Test
    fun testARelayedParentQuotesThePersonInside() {
        val bots = RelayBotSet.empty.applying(networkId = 1, nick = "bridge", marked = true, pattern = "")
        val parent = ReplyParent(id = 7, nick = "bridge", type = EventType.Message, text = "<carol> hello there")
        val viaSpeaker = shown(line(text = "carol: hi", replyTo = ReplyContext(msgid = "p", parent = parent)), relayBots = bots)
        assertEquals("carol", viaSpeaker.quote?.nick)
        assertEquals("hello there", viaSpeaker.quote?.text)
        assertEquals("bridge", viaSpeaker.quote?.relayBot)
        assertEquals("hi", viaSpeaker.text)
        // halloy addresses the bot, which knows nothing of relay marks.
        val viaBot = shown(line(text = "bridge: hi", replyTo = ReplyContext(msgid = "p", parent = parent)), relayBots = bots)
        assertEquals("hi", viaBot.text)
        // The person inside is you when they carry your nick.
        val echo = ReplyParent(id = 7, nick = "bridge", type = EventType.Message, text = "<me> mine")
        assertEquals(true, shown(line(replyTo = ReplyContext(msgid = "p", parent = echo)), relayBots = bots).quote?.isSelf)
    }

    @Test
    fun testASplitReplyIsQuotedOnce() {
        val context = ReplyContext(msgid = "p", parent = alice)
        val first = line(1, replyTo = context)
        assertTrue(Replies.continues(line(2, replyTo = context), previous = first))
        assertFalse(Replies.continues(line(2, nick = "carol", replyTo = context), previous = first))
        assertFalse(Replies.continues(line(2, replyTo = ReplyContext(msgid = "q", parent = null)), previous = first))
        assertFalse(Replies.continues(line(2, type = EventType.Action, replyTo = context), previous = first))
        assertFalse(Replies.continues(line(2, replyTo = context), previous = line(1)))
        assertFalse(Replies.continues(line(2, replyTo = context), previous = null))
    }

    // MARK: - Reply gate

    private fun replyTitle(message: Message, target: String, canReact: Boolean): String? =
        MessageActions.build(
            message,
            scope = MessageActionScope(networkId = 1, isBookmarked = false, target = target, canReact = canReact),
        ).firstOrNull { it.key == MessageActionKey.Reply }?.title

    @Test
    fun testChannelReplyIsAlwaysOffered() {
        assertEquals("Reply to bob", replyTitle(line(), target = "#c", canReact = false))
        assertEquals("Reply to bob", replyTitle(line(msgid = null), target = "#c", canReact = false), "it still addresses them")
    }

    @Test
    fun testYourOwnLineIsTagOnly() {
        assertEquals("Reply to yourself", replyTitle(line(isSelf = true), target = "#c", canReact = true))
        assertNull(replyTitle(line(isSelf = true), target = "#c", canReact = false))
        assertNull(replyTitle(line(isSelf = true, msgid = null), target = "#c", canReact = true))
    }

    @Test
    fun testADmIsTagOnly() {
        assertEquals("Reply to bob", replyTitle(line(), target = "bob", canReact = true))
        assertNull(replyTitle(line(), target = "bob", canReact = false))
        assertNull(replyTitle(line(e2e = true), target = "bob", canReact = true))
        assertNull(replyTitle(line(), target = "=bob", canReact = true), "a DCC chat carries no tags")
    }

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

    @Test
    fun testARefusedLineComesHomeWithItsReply() {
        val correlator = UnsentCorrelator()
        val key = BufferKey(networkId = 1, target = "#c")
        val pending = PendingReply(
            messageId = 7, nick = "alice", type = EventType.Message, text = "x", isSelf = false, addressed = true,
        )
        val id = correlator.track(key, line = "alice: yes", reply = pending)
        val origin = correlator.resolve(clientId = id, ok = false)
        assertEquals(pending, origin?.reply)

        val store = LurkerStore()
        store.holdUnsent(key, text = "alice: yes", reply = pending)
        assertEquals(UnsentLine(text = "alice: yes", reply = pending), store.takeUnsentLine(key))
    }
}

class ReplyPresentingTests {
    @Test
    fun testPresentingSetsTheQuoteAndTextAndPassesOthersThrough() {
        val parent = ReplyParent(id = 7, nick = "alice", type = EventType.Message, text = "q")
        val reply = Message(id = 2, type = EventType.Message, nick = "bob", text = "alice: yes", replyTo = ReplyContext(msgid = "p", parent = parent))
        val plain = Message(id = 3, type = EventType.Message, nick = "bob", text = "alice: plain")
        val out = Replies.presenting(
            listOf(reply, plain), networkId = 1, target = "#c",
            ignores = IgnoreSet.empty, relayBots = RelayBotSet.empty, ownNick = "me",
        )
        assertEquals("alice", out[0].replyQuote?.nick)
        assertEquals("yes", out[0].text)
        assertEquals(2L, out[0].id)
        assertEquals(plain, out[1])
    }
}

/** Cancelling a pending reply takes back only the address its Reply put there (lurker-ios#184). */
class RemovingAddressTests {
    @Test
    fun testTakesBackTheAddressAndNothingElse() {
        assertEquals("hi there", NickCompletion.removingAddress("alice: hi there", nick = "alice", punctuation = ":"))
        assertEquals("", NickCompletion.removingAddress("alice: ", nick = "alice", punctuation = ":"))
        assertEquals("hi", NickCompletion.removingAddress("Alice, hi", nick = "alice", punctuation = ":"), "any mark, folded")
        assertEquals("hi", NickCompletion.removingAddress("alice-> hi", nick = "alice", punctuation = "->"), "the configured mark verbatim")
    }

    @Test
    fun testLeavesADraftThatNoLongerOpensWithIt() {
        assertEquals("hey alice: hi", NickCompletion.removingAddress("hey alice: hi", nick = "alice", punctuation = ":"))
        assertEquals("alice_: hi", NickCompletion.removingAddress("alice_: hi", nick = "alice", punctuation = ":"))
        assertEquals("will you come", NickCompletion.removingAddress("will you come", nick = "will", punctuation = ":"))
    }

    @Test
    fun testIsAddressedStillAgrees() {
        assertTrue(NickCompletion.isAddressed("alice: hi", nick = "alice", punctuation = ":"))
        assertFalse(NickCompletion.isAddressed("alice hi", nick = "alice", punctuation = ":"))
        assertTrue(NickCompletion.isAddressed("alice hi", nick = "alice", punctuation = ""))
    }
}

class ReplyReviewTests {
    /** Copy pastes what was SENT, a reply's address included — the row only hides it. */
    @Test
    fun testCopyKeepsTheAddressTheQuoteHides() {
        val parent = ReplyParent(id = 7, nick = "alice", type = EventType.Message, text = "q")
        val reply = Message(
            id = 2, type = EventType.Message, nick = "bob", text = "alice: try 1.2.3", msgid = "m",
            replyTo = ReplyContext(msgid = "p", parent = parent),
        )
        val shown = Replies.presenting(
            listOf(reply), networkId = 1, target = "#c",
            ignores = IgnoreSet.empty, relayBots = RelayBotSet.empty, ownNick = null,
        )[0]
        assertEquals("try 1.2.3", shown.text)
        var copied: String? = null
        MessageActions.run(
            MessageActionKey.Copy, shown, scope = MessageActionScope(networkId = 1, isBookmarked = false),
            context = MessageActionContext(reply = { _ -> }, copy = { copied = it }, setBookmark = { _, _ -> }, showProfile = { _ -> }),
        )
        assertEquals("alice: try 1.2.3", copied)
        assertEquals("alice: try 1.2.3", reply.copyText, "a line never presented copies its own text")
    }

    @Test
    fun testNicksFoldAsciiOnly() {
        assertTrue(NickCompletion.sameNick("Alice", "aLICE"))
        assertFalse(NickCompletion.sameNick("alice", "alice_"))
        assertFalse(NickCompletion.sameNick("Émile", "émile"), "IRC folds ASCII, not Unicode")
    }

    @Test
    fun testStripAddressTakesEverySpaceAfterTheMark() {
        assertEquals("yes", Replies.stripAddress("alice:   yes", nick = "alice"))
        assertEquals("alice yes", Replies.stripAddress("alice yes", nick = "alice"), "a mark is required")
    }
}
