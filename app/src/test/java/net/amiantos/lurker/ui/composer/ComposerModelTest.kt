// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.composer

import net.amiantos.lurkerkit.model.AwayState
import net.amiantos.lurkerkit.model.AwayStrip
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ComposerDraft
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.PendingReply
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The composer's decisions — lurker-ios's `ComposerBar` and the composer half of `ChatViewController`. */
class ComposerModelTest {

    // MARK: - Completion

    @Test
    fun `the verb, a channel argument and a nick argument are each their own completion`() {
        assertEquals(Completion.Command("jo"), ComposerModel.completion("/jo", 3, 3))
        assertEquals(Completion.ChannelArg("#li"), ComposerModel.completion("/join #li", 9, 9))
        assertEquals(Completion.NickArg("al"), ComposerModel.completion("/msg al", 7, 7))
    }

    @Test
    fun `free text in a command falls through to a mention`() {
        assertEquals(Completion.Mention("al"), ComposerModel.completion("/me waves at @al", 16, 16))
        assertEquals(Completion.Mention(""), ComposerModel.completion("hey @", 5, 5))
    }

    @Test
    fun `a selection, an email and plain text complete nothing`() {
        assertNull(ComposerModel.completion("/jo", 1, 3))
        assertNull(ComposerModel.completion("mail me@host", 12, 12))
        assertNull(ComposerModel.completion("hello there", 11, 11))
        // `//` is an escaped literal, not a command.
        assertNull(ComposerModel.completion("//jo", 4, 4))
    }

    @Test
    fun `suggestions ask only the source the completion needs`() {
        val channels = ComposerModel.suggestions(Completion.ChannelArg("li"), channels = { listOf("#linux") }, nicks = { error("not asked") })
        assertEquals(listOf(Suggestion.channel("#linux")), channels)
        val nicks = ComposerModel.suggestions(Completion.Mention("a"), channels = { error("not asked") }, nicks = { listOf("alice") })
        assertEquals(listOf(Suggestion("alice", "alice", Suggestion.Kind.Nick, "Insert alice")), nicks)
        assertTrue(ComposerModel.suggestions(null, channels = { error("no") }, nicks = { error("no") }).isEmpty())
    }

    @Test
    fun `a bare slash offers the featured starter set, by canonical name`() {
        val chips = ComposerModel.suggestions(Completion.Command(""), channels = { emptyList() }, nicks = { emptyList() })
        assertEquals(listOf("/join", "/msg", "/me", "/nick", "/topic", "/away"), chips.map { it.title })
        assertEquals("join", chips.first().value)
        assertTrue(chips.first().accessibility.startsWith("Command join, "))
    }

    @Test
    fun `channel candidates match past any sigil, on this network only, capped at four`() {
        val buffers = listOf(
            Buffer(networkId = 1, target = "#Linux", kind = BufferKind.Channel),
            Buffer(networkId = 1, target = "&lisp", kind = BufferKind.Channel),
            Buffer(networkId = 1, target = "+libre", kind = BufferKind.Channel),
            Buffer(networkId = 1, target = "!lima", kind = BufferKind.Channel),
            Buffer(networkId = 1, target = "#lizard", kind = BufferKind.Channel),
            Buffer(networkId = 2, target = "#lights", kind = BufferKind.Channel),
            Buffer(networkId = 1, target = "lily", kind = BufferKind.Dm),
        )
        // Sorted as iOS sorts them — the whole lowercased name, sigil included.
        assertEquals(listOf("!lima", "#Linux", "#lizard", "&lisp"), ComposerModel.channelCandidates(buffers, 1, "li"))
        assertEquals(listOf("&lisp"), ComposerModel.channelCandidates(buffers, 1, "#lis"))
    }

    // MARK: - Insertion

    @Test
    fun `picking a command leaves the caret where the first argument goes`() {
        assertEquals(FieldEdit("/join ", 6), ComposerModel.completeCommand("/jo", 3, "join"))
        // The whole verb, not just up to the caret — `/j|o` doesn't weld into `/joino`.
        assertEquals(FieldEdit("/join  #a", 6), ComposerModel.completeCommand("/jo #a", 2, "join"))
    }

    @Test
    fun `picking an argument swallows the token and trails a space`() {
        assertEquals(FieldEdit("/join #linux ", 13), ComposerModel.completeArgument("/join #li", 9, "#linux"))
        assertEquals(FieldEdit("/msg alice  hi", 11), ComposerModel.completeArgument("/msg al hi", 7, "alice"))
    }

    @Test
    fun `a mention at the head of a line addresses, mid-sentence it just spaces`() {
        assertEquals(FieldEdit("alice: ", 7), ComposerModel.completeMention("@al", 3, "alice", ":"))
        assertEquals(FieldEdit("hi alice ", 9), ComposerModel.completeMention("hi @al", 6, "alice", ":"))
        // On a fresh line of a multi-line draft, too.
        assertEquals(FieldEdit("ok\nalice, ", 10), ComposerModel.completeMention("ok\n@a", 5, "alice", ","))
    }

    @Test
    fun `the nick suffix is punctuation only — the space is always ours`() {
        assertEquals(FieldEdit("alice ", 6), ComposerModel.completeMention("@al", 3, "alice", ""))
        // `@al|ice` swallows the tail rather than welding the pick onto it.
        assertEquals(FieldEdit("alice: ", 7), ComposerModel.completeMention("@alice", 3, "alice", ":"))
    }

    @Test
    fun `a stale pick inserts nothing`() {
        assertNull(ComposerModel.pick("hello", 5, 5, Completion.Mention("al"), "alice", ":"))
        assertNull(ComposerModel.pick("@al", 0, 3, Completion.Mention("al"), "alice", ":"))
        assertNull(ComposerModel.pick("@al", 3, 3, null, "alice", ":"))
    }

    // MARK: - Replies

    @Test
    fun `address prepends once, caret at the end, and says whether it did`() {
        assertEquals(FieldEdit("bob: sure", 9) to true, ComposerModel.address("sure", "bob", ":"))
        // Already addressed — by this setting's mark or another client's — stays as it is.
        assertEquals(FieldEdit("bob: sure", 9) to false, ComposerModel.address("bob: sure", "bob", ":"))
        assertEquals(FieldEdit("bob, sure", 9) to false, ComposerModel.address("bob, sure", "bob", ":"))
        assertNull(ComposerModel.address("sure", "", ":"))
    }

    @Test
    fun `cancelling takes the address back, and leaves a rewritten draft alone`() {
        assertEquals(FieldEdit("sure", 4), ComposerModel.removeAddress("bob: sure", "bob", ":"))
        assertNull(ComposerModel.removeAddress("sure, bob", "bob", ":"))
    }

    private fun line(nick: String = "alice", isSelf: Boolean = false, msgid: String? = "m1", id: Long = 7) =
        Message(id = id, type = EventType.Message, nick = nick, text = "hi there", isSelf = isSelf, msgid = msgid)

    @Test
    fun `a channel reply starts pending and addresses its author`() {
        val plan = ComposerModel.replyPlan(line(), "#lurker", canReact = true, pending = null)!!
        assertFalse(plan.cancelFirst)
        assertEquals(7L, plan.start?.messageId)
        assertFalse(plan.start!!.addressed)
        assertEquals("alice", plan.address)
        assertTrue(plan.marksAddressed)
    }

    @Test
    fun `without tags right now a channel Reply only addresses — no promise in the strip`() {
        val plan = ComposerModel.replyPlan(line(), "#lurker", canReact = false, pending = null)!!
        assertNull(plan.start)
        assertEquals("alice", plan.address)
        assertFalse(plan.marksAddressed)
        // A line with no msgid can't be replied to either.
        assertNull(ComposerModel.replyPlan(line(msgid = null), "#lurker", canReact = true, pending = null)!!.start)
    }

    @Test
    fun `your own line and a DM address nobody`() {
        assertNull(ComposerModel.replyPlan(line(isSelf = true), "#lurker", canReact = true, pending = null)!!.address)
        val dm = ComposerModel.replyPlan(line(), "alice", canReact = true, pending = null)!!
        assertNull(dm.address)
        assertEquals(7L, dm.start?.messageId)
    }

    @Test
    fun `replying again to the same author keeps the address, anyone else cancels first`() {
        val pending = PendingReply(messageId = 3, nick = "Alice", type = EventType.Message, text = "x", isSelf = false, addressed = true)
        val again = ComposerModel.replyPlan(line(), "#lurker", canReact = true, pending = pending)!!
        assertFalse(again.cancelFirst)
        assertTrue(again.start!!.addressed)
        val other = ComposerModel.replyPlan(line(nick = "bob"), "#lurker", canReact = true, pending = pending)!!
        assertTrue(other.cancelFirst)
        assertFalse(other.start!!.addressed)
    }

    @Test
    fun `a line with no author has nothing to reply to`() {
        assertNull(ComposerModel.replyPlan(line(nick = ""), "#lurker", canReact = true, pending = null))
    }

    @Test
    fun `the reply wins the strip, and says yourself for your own line`() {
        val away = AwayStrip(lead = "Away", detail = " since 2:32 PM")
        val reply = PendingReply(messageId = 1, nick = "amiantos", type = EventType.Message, text = "\u0002bold\u0002\nnext", isSelf = true)
        val strip = ComposerModel.strip(reply, away)
        assertEquals(Strip.Reply(name = "yourself", excerpt = "bold next"), strip)
        assertEquals("Replying to yourself: bold next", (strip as Strip.Reply).accessibility)
        assertEquals(Strip.Away("Away", " since 2:32 PM"), ComposerModel.strip(null, away))
        assertEquals(Strip.None, ComposerModel.strip(null, null))
        assertEquals("Replying to bob", Strip.Reply("bob", "").accessibility)
    }

    // MARK: - The prompt

    private val channel = BufferKey(1, "#lurker")

    @Test
    fun `the prompt is your nick, with your rank in a channel`() {
        val chrome = ComposerChrome(nick = "amiantos", ownModes = listOf("v", "o"), dccSession = null, away = null)
        assertEquals("@amiantos", ComposerModel.placeholder(chrome, channel, BufferKind.Channel))
        assertEquals("amiantos", ComposerModel.placeholder(chrome.copy(ownModes = emptyList()), channel, BufferKind.Channel))
        assertEquals("Message", ComposerModel.placeholder(ComposerChrome.Empty, channel, BufferKind.Channel))
    }

    @Test
    fun `the system buffer invites a command, and a DCC chat names itself`() {
        assertEquals("Type a command…", ComposerModel.placeholder(ComposerChrome.Empty, BufferKey(null, "*system*"), BufferKind.System))
        val dcc = BufferKey(1, "=bob")
        val live = ComposerChrome(nick = "me", ownModes = emptyList(), dccSession = true, away = null)
        assertEquals("DCC Chat", ComposerModel.placeholder(live, dcc, BufferKind.Dcc))
        assertEquals("DCC Chat", ComposerModel.placeholder(live.copy(dccSession = null), dcc, BufferKind.Dcc))
        assertEquals("Not connected — /dcc chat bob", ComposerModel.placeholder(live.copy(dccSession = false), dcc, BufferKind.Dcc))
    }

    @Test
    fun `the chrome reads only your own modes, and its inputs compare the nicklist by identity`() {
        val members = listOf(Member(nick = "Amiantos", modes = listOf("o")), Member(nick = "bob"))
        val away = AwayState(active = true, since = Instant.EPOCH)
        val inputs = ComposerChrome.Inputs(nick = "amiantos", members = members, dccSession = null, away = away)
        assertEquals(listOf("o"), ComposerChrome.of(inputs).ownModes)
        assertTrue(ComposerChrome.Inputs.same(inputs, ComposerChrome.Inputs("amiantos", members, null, away)))
        assertFalse(ComposerChrome.Inputs.same(inputs, ComposerChrome.Inputs("amiantos", members.toList(), null, away)))
    }

    // MARK: - Send

    @Test
    fun `only something other than whitespace sends, trimmed`() {
        assertNull(ComposerModel.sendable(" \n\t"))
        assertEquals("hi", ComposerModel.sendable("  hi\n"))
        assertTrue(ComposerModel.isBlank(" \n"))
    }

    @Test
    fun `keep position bites only when set and reading history`() {
        val on = Settings(registry = emptyMap(), values = mapOf("chat.keep_position_on_send" to SettingValue.Bool(true)))
        assertTrue(ComposerModel.keepsPositionWhileReading(on, nearBottom = false))
        assertFalse(ComposerModel.keepsPositionWhileReading(on, nearBottom = true))
        assertFalse(ComposerModel.keepsPositionWhileReading(Settings(), nearBottom = false))
    }

    @Test
    fun `a send re-attaches a detached slice whatever the setting says`() {
        assertEquals(SendScroll.Reattach, ComposerModel.sendScroll(detached = true, keepsPosition = true))
        assertEquals(SendScroll.Stay, ComposerModel.sendScroll(detached = false, keepsPosition = true))
        assertEquals(SendScroll.ToBottom, ComposerModel.sendScroll(detached = false, keepsPosition = false))
    }

    @Test
    fun `a refused line never lands over what you've written, nor under a pending reply`() {
        assertTrue(ComposerModel.canRestoreRefused(" \n", null))
        assertFalse(ComposerModel.canRestoreRefused("new words", null))
        val reply = PendingReply(messageId = 1, nick = "a", type = EventType.Message, text = "", isSelf = true)
        assertFalse(ComposerModel.canRestoreRefused("", reply))
    }

    // MARK: - Drafts

    @Test
    fun `a stored draft repaints only when it changed and nothing of ours is held`() {
        val seen = ComposerDraft(body = "hello")
        assertFalse(ComposerModel.repaintsDraft(seen, seen, protected = false))
        assertTrue(ComposerModel.repaintsDraft(ComposerDraft(body = "from the web"), seen, protected = false))
        assertFalse(ComposerModel.repaintsDraft(ComposerDraft(body = "from the web"), seen, protected = true))
        // Another device emptying it is a change too.
        assertTrue(ComposerModel.repaintsDraft(null, seen, protected = false))
    }
}
