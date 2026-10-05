// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.client.ChannelSnapshot
import net.amiantos.lurkerkit.client.FrameParser
import net.amiantos.lurkerkit.client.NetworkSnapshot
import net.amiantos.lurkerkit.client.ServerFrame
import net.amiantos.lurkerkit.client.UploadLimits
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.session.ChatViewModel
import net.amiantos.lurkerkit.store.LurkerStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import java.time.Instant

/**
 * Wire fields the kit wasn't reading (the client sweep's protocol batch, L10/L21/L22/L41): the
 * fresh connect's resume cursor, a provisional nicklist, a 421 naming the command it refused, and
 * an invitation addressed to us. The web read all four; the kit dropped them.
 */
class ProtocolSweepTests {

    private val channel = BufferKey(networkId = 1, target = "#lurker")

    private fun viewModel(ignoring: List<IgnoreRule> = emptyList()): ChatViewModel {
        val model = testViewModel()
        model.handle(ServerFrame.SocketOpen)
        model.handle(
            ServerFrame.Snapshot(
                listOf(
                    NetworkSnapshot(
                        id = 1, state = ConnectionState.Connected, nick = "me",
                        channels = listOf(ChannelSnapshot(name = "#lurker", topic = null, members = emptyList())),
                        ignoredMasks = ignoring,
                    ),
                ),
                globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated,
            ),
        )
        return model
    }

    private fun snapshot(members: List<Member>, pending: Boolean = false, cursor: Long? = null): ServerFrame =
        ServerFrame.Snapshot(
            listOf(
                NetworkSnapshot(
                    id = 1, state = ConnectionState.Connected, nick = "me",
                    channels = listOf(
                        ChannelSnapshot(name = "#lurker", topic = null, members = members, membersPending = pending),
                    ),
                ),
            ),
            globalIgnores = emptyList(), uploadLimits = UploadLimits.unstated, cursor = cursor,
        )

    // L10: the snapshot's cursor

    @Test
    fun testTheSnapshotCursorIsParsedAndAbsentOnAResume() {
        val fresh = FrameParser.parseWs("""{"kind":"snapshot","networks":[],"cursor":4821}""")
        if (fresh !is ServerFrame.Snapshot) fail("got $fresh")
        assertEquals(4821L, fresh.cursor)

        val resume = FrameParser.parseWs("""{"kind":"snapshot","networks":[]}""")
        if (resume !is ServerFrame.Snapshot) fail("got $resume")
        assertNull(resume.cursor)
    }

    /**
     * A fresh connect's channels come as shells with no rows, so the cursor is what moves the
     * resume point past the server logs. A reconnect before anything live then asks from here,
     * not from the newest server-log id.
     */
    @Test
    fun testAFreshConnectsCursorRaisesTheResumePoint() {
        val store = LurkerStore()
        store.apply(ServerFrame.Live(networkId = 1, target = ":server:1", message = Message(id = 120, type = EventType.Notice, nick = "srv", text = "welcome")))
        assertEquals(120L, store.state.maxEventId)
        store.apply(snapshot(emptyList(), cursor = 4821))
        assertEquals(4821L, store.state.maxEventId)

        // Never lowered: the cursor is the server's max when it sent the snapshot, and a row
        // held from before can't be newer, but a stale number must not pull the watermark back.
        store.apply(ServerFrame.Live(networkId = 1, target = "#lurker", message = Message(id = 5000, type = EventType.Message, nick = "a", text = "x")))
        store.apply(snapshot(emptyList(), cursor = 4821))
        assertEquals(5000L, store.state.maxEventId)

        // A resume's snapshot carries none and moves nothing.
        store.apply(snapshot(emptyList()))
        assertEquals(5000L, store.state.maxEventId)
    }

    // L21: membersPending

    @Test
    fun testMembersPendingIsParsedOnTheSnapshotAndOnNames() {
        val frame = FrameParser.parseWs(
            """{"kind":"snapshot","networks":[{"networkId":1,"state":"connected","nick":"me","channels":[{"name":"#a","members":[],"membersPending":true},{"name":"#b","members":[]}]}]}""",
        )
        if (frame !is ServerFrame.Snapshot) fail("got $frame")
        assertEquals(listOf(true, false), frame.networks[0].channels.map { it.membersPending })

        val names = FrameParser.parseWs(
            """{"kind":"irc","networkId":1,"target":"#a","type":"names","members":[{"nick":"me","modes":[]}],"membersPending":true}""",
        )
        if (names !is ServerFrame.ChannelMembers) fail("got $names")
        assertTrue(names.pending)
    }

    /**
     * After an engine re-attach the server has heard no channel's NAMES, so what it sends is us
     * plus whoever has joined since. Taking it reads as everyone leaving.
     */
    @Test
    fun testAPendingListNeverReplacesAHeldOne() {
        val store = LurkerStore()
        store.apply(snapshot(listOf(Member(nick = "me"), Member(nick = "alice"), Member(nick = "bob"))))

        store.apply(snapshot(listOf(Member(nick = "me")), pending = true))
        assertEquals(listOf("me", "alice", "bob"), store.state.members[channel.id]?.map { it.nick }, "snapshot")

        store.apply(ServerFrame.ChannelMembers(networkId = 1, target = "#lurker", members = listOf(Member(nick = "me")), pending = true))
        assertEquals(listOf("me", "alice", "bob"), store.state.members[channel.id]?.map { it.nick }, "names")

        // The definitive list, when it lands, is the list.
        store.apply(ServerFrame.ChannelMembers(networkId = 1, target = "#lurker", members = listOf(Member(nick = "me"), Member(nick = "carol"))))
        assertEquals(listOf("me", "carol"), store.state.members[channel.id]?.map { it.nick })
    }

    /** With nothing held, a provisional list beats none. */
    @Test
    fun testAPendingListIsTakenWhenNoneIsHeld() {
        val store = LurkerStore()
        store.apply(snapshot(listOf(Member(nick = "me")), pending = true))
        assertEquals(listOf("me"), store.state.members[channel.id]?.map { it.nick })

        val other = LurkerStore()
        other.apply(ServerFrame.ChannelMembers(networkId = 1, target = "#lurker", members = listOf(Member(nick = "me")), pending = true))
        assertEquals(listOf("me"), other.state.members[channel.id]?.map { it.nick })
    }

    // L22: unknownCommand

    @Test
    fun testUnknownCommandIsReadOffAnErrorLineOnly() {
        val error = FrameParser.parseWs(
            """{"kind":"irc","networkId":1,"target":":server:1","type":"error","id":50,"text":"unknown_command FROBNICATE — Unknown command","unknownCommand":"FROBNICATE"}""",
        )
        if (error !is ServerFrame.Live) fail("got $error")
        assertEquals("FROBNICATE", error.message.unknownCommand)

        val notice = FrameParser.parseWs(
            """{"kind":"irc","networkId":1,"target":":server:1","type":"notice","id":51,"nick":"srv","text":"x","unknownCommand":"FROBNICATE"}""",
        )
        if (notice !is ServerFrame.Live) fail("got $notice")
        assertNull(notice.message.unknownCommand)
    }

    private fun unknownCommand(verb: String, id: Long = 50): ServerFrame =
        ServerFrame.Live(
            networkId = 1, target = ":server:1",
            message = Message(
                id = id, type = EventType.Error, nick = null, text = "unknown_command $verb — Unknown command",
                unknownCommand = verb,
            ),
        )

    /** The 421 lands in the server log; the buffer the command was typed in says so too, once. */
    @Test
    fun testA421SaysSoWhereTheCommandWasTyped() {
        val model = viewModel()
        model.sendRawSeam = { true }
        model.send(channel, text = "/frobnicate now")
        model.handle(unknownCommand("FROBNICATE"))
        assertEquals("Unknown command: /frobnicate", model.state.messages[channel.id]?.lastOrNull()?.text)

        val count = model.state.messages[channel.id]?.size
        model.handle(unknownCommand("FROBNICATE", id = 51))
        assertEquals(count, model.state.messages[channel.id]?.size, "one send, one notice")
    }

    /** Another device's `/frobnicate` 421s on this socket too; that device says so, not this one. */
    @Test
    fun testA421ForALineThisDeviceDidntSendSaysNothing() {
        val model = viewModel()
        model.handle(unknownCommand("FROBNICATE"))
        assertNull(model.state.messages[channel.id]?.lastOrNull())
    }

    /** Typed in the server log, the 421 already shows where it was typed: one line, not two. */
    @Test
    fun testA421ForALineTypedInTheServerLogAddsNothing() {
        val model = viewModel()
        model.sendRawSeam = { true }
        val server = BufferKey(networkId = 1, target = ":server:1")
        model.send(server, text = "/frobnicate")
        model.handle(unknownCommand("FROBNICATE"))
        assertNotNull(model.state.buffers[server.id], "the 421 made the row, so only the guard is left to say no")
        assertEquals(0, model.state.messages[server.id]?.count { it.id == 0L } ?: 0, "only the server's own line")
    }

    /**
     * A raw line the ircd accepted, or whose 421 was lost with the socket, doesn't wait forever:
     * past the window, a 421 for that verb is another device's.
     */
    @Test
    fun testA421PastTheWindowIsSomeoneElses() {
        val model = viewModel()
        model.sendRawSeam = { true }
        model.send(channel, text = "/frobnicate")
        val frame = unknownCommand("FROBNICATE") as ServerFrame.Live
        model.noteUnknownCommand(
            frame.networkId, frame.message, now = Instant.now().plus(ChatViewModel.unknownCommandWindow).plusSeconds(1),
        )
        assertNull(model.state.messages[channel.id]?.lastOrNull())
    }

    /** `/raw` lets a tag block or a source prefix come first; the command is the word after them. */
    @Test
    fun testTheRawVerbSkipsTagsAndPrefix() {
        assertEquals("frobnicate", ChatViewModel.rawVerb("frobnicate now"))
        assertEquals("FROBNICATE", ChatViewModel.rawVerb("@label=x FROBNICATE now"))
        assertEquals("FROBNICATE", ChatViewModel.rawVerb("@label=x :me FROBNICATE"))
        assertNull(ChatViewModel.rawVerb("@label=x"))
    }

    /** A line that went nowhere came back to the composer; there is no 421 to wait for. */
    @Test
    fun testARawLineThatWentNowhereIsntWaitedOn() {
        val model = viewModel()
        model.sendRawSeam = { false }
        model.send(channel, text = "/frobnicate")
        model.handle(unknownCommand("FROBNICATE"))
        assertNull(model.state.messages[channel.id]?.lastOrNull())
    }

    // L41: invitations

    @Test
    fun testAnInviteNamingUsIsItsOwnFrameAndAChannelsInviteLineIsALine() {
        assertEquals(
            ServerFrame.Invited(networkId = 1, channel = "#secret", from = "bob", userhost = "bob!b@example.org"),
            FrameParser.parseWs(
                """{"kind":"irc","networkId":1,"target":":server:1","type":"invite","channel":"#secret","from":"bob","userhost":"bob!b@example.org"}""",
            ),
        )
        val line = FrameParser.parseWs(
            """{"kind":"irc","networkId":1,"target":"#lurker","type":"invite","id":9,"nick":"alice","invited":"carol"}""",
        )
        if (line !is ServerFrame.Live) fail("got $line")
        assertEquals("#lurker", line.target)
        assertEquals("carol", line.message.invited)
        assertTrue(line.message.isRenderable)
    }

    @Test
    fun testAnInvitationIsOfferedUnlessWereAlreadyIn() {
        val model = viewModel()
        val offered = mutableListOf<String>()
        model.onInvited = { networkId, channel, from -> offered.add("$networkId $channel $from") }
        model.handle(ServerFrame.Invited(networkId = 1, channel = "#secret", from = "bob"))
        model.handle(ServerFrame.Invited(networkId = 1, channel = "#lurker", from = "bob"))
        assertEquals(listOf("1 #secret bob"), offered)
    }

    /** Someone ignored outright doesn't get a prompt; the system buffer still has the line. */
    @Test
    fun testAnIgnoredInvitersInvitationIsNotOffered() {
        val model = viewModel(ignoring = listOf(IgnoreRule(mask = "troll!*@*")))
        val offered = mutableListOf<String>()
        model.onInvited = { _, channel, from -> offered.add("$channel $from") }
        model.handle(ServerFrame.Invited(networkId = 1, channel = "#spam", from = "troll", userhost = "troll!t@example.org"))
        model.handle(ServerFrame.Invited(networkId = 1, channel = "#secret", from = "bob", userhost = "bob!b@example.org"))
        assertEquals(listOf("#secret bob"), offered)
    }
}
