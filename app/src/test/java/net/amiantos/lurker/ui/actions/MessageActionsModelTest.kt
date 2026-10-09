// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.actions

import net.amiantos.lurkerkit.commands.IgnoreArgs
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageActionKey
import net.amiantos.lurkerkit.model.MessageActionScope
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.TagSupport
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus
import net.amiantos.lurkerkit.support.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The actions sheet's rows, header and dispatch, and the Ignore choices (lurker-android#37). */
class MessageActionsModelTest {

    private fun line(
        nick: String? = "alice",
        text: String? = "hello",
        type: EventType = EventType.Message,
        isSelf: Boolean = false,
        userhost: String? = "alice!~al@example.org",
        id: Long = 42,
    ) = Message(id = id, type = type, nick = nick, text = text, isSelf = isSelf, userhost = userhost, msgid = "m$id")

    private val allTags = TagSupport(canAddReaction = true, canRemoveReaction = true, canReply = true)

    private fun scope(networkId: Int? = 1, saved: Boolean = false, target: String = "#lurker", support: TagSupport = allTags) =
        MessageActionScope(networkId = networkId, isBookmarked = saved, target = target, support = support)

    private fun titles(subject: ActionSubject) = MessageActionsModel.rows(subject).map { it.title }

    // MARK: - Rows

    @Test
    fun `someone else's line offers the kit's actions in its order, then Ignore`() {
        assertEquals(
            listOf("Reply to alice", "React", "Copy Text", "Save Message", "Profile of alice", "Ignore alice…"),
            titles(ActionSubject.Line(line(), scope())),
        )
    }

    /**
     * lurker#1101: the press reads all three answers off the store, so irc.so — a reply's tag and a new
     * reaction, no take-back — offers Reply to yourself (tag-only) and React on your own line. Read as
     * one "can react" flag (both directions) it offered neither.
     */
    @Test
    fun `the press reads each tag's own answer — irc_so offers a tag-only reply and React`() {
        val ircSo = TagSupport(canAddReaction = true, canRemoveReaction = false, canReply = true)
        val state = ChatState(
            connection = SocketStatus.Connected,
            networks = mapOf(1 to Network(id = 1, name = "irc.so", state = ConnectionState.Connected, nick = "me", tagSupport = ircSo)),
        )
        val key = BufferKey(1, "#lurker")
        val scope = MessageActionsModel.scope(state, key, isBookmarked = false)
        assertEquals(ircSo, scope.support)
        val own = titles(ActionSubject.Line(line(nick = "me", isSelf = true), scope))
        assertTrue(own.contains("Reply to yourself"))
        assertTrue(own.contains("React"))
        // A network that's down carries nothing, whatever it last said.
        val down = state.copy(connection = SocketStatus.Reconnecting)
        val offline = titles(ActionSubject.Line(line(nick = "me", isSelf = true), MessageActionsModel.scope(down, key, isBookmarked = false)))
        assertFalse(offline.contains("Reply to yourself"))
        assertFalse(offline.contains("React"))
    }

    @Test
    fun `your own line offers no Ignore`() {
        val rows = MessageActionsModel.rows(ActionSubject.Line(line(nick = "me", isSelf = true), scope()))
        assertTrue(rows.none { it.key == ActionKey.Ignore })
    }

    @Test
    fun `a system-buffer line offers no Ignore — there's no IRC subject`() {
        val rows = MessageActionsModel.rows(ActionSubject.Line(line(), scope(networkId = null, target = ":system:")))
        assertTrue(rows.none { it.key == ActionKey.Ignore })
    }

    @Test
    fun `activity narration offers no Ignore — only speech does`() {
        val rows = MessageActionsModel.rows(ActionSubject.Line(line(type = EventType.Join, text = null), scope()))
        assertTrue(rows.none { it.key == ActionKey.Ignore })
    }

    @Test
    fun `a relayed line ignores the bridge, not the person on screen`() {
        val relayed = line(nick = "relaybot", userhost = "relaybot!bot@bridge.example").relayed("alice", "hi", "relaybot", "Discord")
        assertEquals("Ignore relaybot…", titles(ActionSubject.Line(relayed, scope())).last())
        assertEquals("relaybot", MessageActionsModel.ignoreSubject(relayed, scope()))
        assertEquals("*!bot@bridge.example", MessageActionsModel.defaultIgnoreMask(relayed, "relaybot"))
    }

    @Test
    fun `a saved line offers Remove Bookmark with the filled glyph`() {
        val row = MessageActionsModel.rows(ActionSubject.Line(line(), scope(saved = true))).first {
            it.key == ActionKey.Kit(MessageActionKey.Bookmark)
        }
        assertEquals("Remove Bookmark", row.title)
        assertEquals(ActionGlyph.Bookmarked, row.glyph)
    }

    @Test
    fun `a link offers Open, Copy and Share`() {
        val rows = MessageActionsModel.rows(ActionSubject.Link("https://lurker.chat/x"))
        assertEquals(listOf("Open Link", "Copy Link", "Share Link"), rows.map { it.title })
        assertEquals(listOf(ActionGlyph.OpenLink, ActionGlyph.Copy, ActionGlyph.Share), rows.map { it.glyph })
    }

    @Test
    fun `every kit symbol maps to its own glyph`() {
        assertEquals(ActionGlyph.Reply, MessageActionsModel.glyph("arrowshape.turn.up.left"))
        assertEquals(ActionGlyph.React, MessageActionsModel.glyph("face.smiling"))
        assertEquals(ActionGlyph.Bookmark, MessageActionsModel.glyph("bookmark"))
        assertEquals(ActionGlyph.Bookmarked, MessageActionsModel.glyph("bookmark.fill"))
        assertEquals(ActionGlyph.Profile, MessageActionsModel.glyph("person.crop.circle"))
    }

    // MARK: - Header

    @Test
    fun `the header names the speaker and shows the line without its codes`() {
        val header = MessageActionsModel.header(ActionSubject.Line(line(text = "\u000304ALERT\u0003 disk full"), scope()))
        assertEquals("alice", header.title)
        assertEquals("ALERT disk full", header.detail)
    }

    @Test
    fun `the header never prints a hidden spoiler — it's masked, and announced as one`() {
        val text = "the ending is \u000301,01he was a ghost\u0003 by the way"
        val header = MessageActionsModel.header(ActionSubject.Line(line(text = text), scope()))
        assertEquals("the ending is ██████████████ by the way", header.detail)
        assertEquals("the ending is hidden spoiler by the way", header.spokenDetail)
        assertFalse(header.detail!!.contains("ghost"))
    }

    @Test
    fun `a relayed line's header names its bridge, and a nickless line says Message`() {
        val relayed = line(nick = "relaybot").relayed("alice", "hi", "relaybot", null)
        assertEquals("alice via relaybot", MessageActionsModel.header(ActionSubject.Line(relayed, scope())).title)
        assertEquals("Message", MessageActionsModel.header(ActionSubject.Line(line(nick = null), scope())).title)
    }

    @Test
    fun `a link's header is its host, or Link when it has none`() {
        assertEquals(ActionHeader("lurker.chat", "https://lurker.chat/a?b"), MessageActionsModel.header(ActionSubject.Link("https://lurker.chat/a?b")))
        assertEquals("Link", MessageActionsModel.header(ActionSubject.Link("mailto:bob@example.org")).title)
    }

    // MARK: - Ignore

    @Test
    fun `the default mask is the sender's identity, else the nick with wildcards`() {
        assertEquals("*!~al@example.org", MessageActionsModel.defaultIgnoreMask(line(), "alice"))
        assertEquals("alice!*@*", MessageActionsModel.defaultIgnoreMask(line(userhost = null), "alice"))
        // A half-mask (the server stores an empty ident) is no identity at all.
        assertEquals("alice!*@*", MessageActionsModel.defaultIgnoreMask(line(userhost = "alice!@host"), "alice"))
    }

    @Test
    fun `the command is the kit's own grammar, global unless scoped`() {
        assertEquals("/ignore *!~al@example.org", MessageActionsModel.ignoreCommand(" *!~al@example.org ", thisNetwork = false))
        assertEquals("/ignore -network *!~al@example.org", MessageActionsModel.ignoreCommand("*!~al@example.org", thisNetwork = true))
        // And the kit reads it back as exactly that rule.
        val parsed = IgnoreArgs.parse("-network *!~al@example.org") as Result.Success
        assertEquals("*!~al@example.org", parsed.value.rule.mask)
        assertTrue(parsed.value.scopeNetwork)
    }

    @Test
    fun `a mask that wouldn't arrive as the mask alone can't be sent`() {
        assertNull(MessageActionsModel.ignoreCommand("   ", thisNetwork = false))
        assertNull(MessageActionsModel.ignoreCommand("bob carol", thisNetwork = false))
        assertNull(MessageActionsModel.ignoreCommand("#lurker", thisNetwork = false))
        // A nick that spells a level token is eaten as the level — the full mask isn't.
        assertNull(MessageActionsModel.ignoreCommand("Quit", thisNetwork = false))
        assertEquals("/ignore Quit!*@*", MessageActionsModel.ignoreCommand("Quit!*@*", thisNetwork = false))
        assertNull(MessageActionsModel.ignoreCommand("bob JOINS", thisNetwork = false))
        assertNull(MessageActionsModel.ignoreCommand("-time", thisNetwork = false))
    }

    @Test
    fun `the preview says where it applies`() {
        assertEquals("Messages matching bob!*@* will be hidden on every network.", MessageActionsModel.ignorePreview("bob!*@*", false))
        assertEquals("Messages matching ∅ will be hidden on this network.", MessageActionsModel.ignorePreview(" ", true))
    }

    // MARK: - Run

    private class Recorder {
        val calls = mutableListOf<String>()
        val effects = MessageActionsModel.LineEffects(
            reply = { calls += "reply ${it.nick}" },
            copy = { calls += "copy $it" },
            setBookmark = { id, saved -> calls += "bookmark $id $saved" },
            showProfile = { calls += "profile $it" },
            react = { calls += "react ${it.id}" },
            ignore = { _, subject -> calls += "ignore $subject" },
        )

        fun run(key: ActionKey, subject: ActionSubject) = MessageActionsModel.run(
            key, subject, effects,
            open = { calls += "open $it" }, copyLink = { calls += "copyLink $it" }, share = { calls += "share $it" },
        )
    }

    @Test
    fun `Bookmark goes the way the sheet said, from the scope it was built with`() {
        val recorder = Recorder()
        recorder.run(ActionKey.Kit(MessageActionKey.Bookmark), ActionSubject.Line(line(), scope(saved = false)))
        recorder.run(ActionKey.Kit(MessageActionKey.Bookmark), ActionSubject.Line(line(), scope(saved = true)))
        assertEquals(listOf("bookmark 42 true", "bookmark 42 false"), recorder.calls)
    }

    @Test
    fun `each line action reaches its effect, and Ignore names its subject`() {
        val recorder = Recorder()
        val subject = ActionSubject.Line(line(text = "\u0002raw\u0002"), scope())
        for (key in listOf(MessageActionKey.Reply, MessageActionKey.React, MessageActionKey.Copy, MessageActionKey.Profile)) {
            recorder.run(ActionKey.Kit(key), subject)
        }
        recorder.run(ActionKey.Ignore, subject)
        assertEquals(listOf("reply alice", "react 42", "copy \u0002raw\u0002", "profile alice", "ignore alice"), recorder.calls)
    }

    @Test
    fun `Ignore on a line that offers none does nothing`() {
        val recorder = Recorder()
        recorder.run(ActionKey.Ignore, ActionSubject.Line(line(isSelf = true), scope()))
        assertTrue(recorder.calls.isEmpty())
    }

    @Test
    fun `a link's actions go to the platform`() {
        val recorder = Recorder()
        val link = ActionSubject.Link("https://x.example")
        recorder.run(ActionKey.Kit(MessageActionKey.OpenLink), link)
        recorder.run(ActionKey.Kit(MessageActionKey.CopyLink), link)
        recorder.run(ActionKey.Kit(MessageActionKey.ShareLink), link)
        assertEquals(listOf("open https://x.example", "copyLink https://x.example", "share https://x.example"), recorder.calls)
    }
}
