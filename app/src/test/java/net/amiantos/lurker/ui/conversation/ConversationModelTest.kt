// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.conversation

import net.amiantos.lurker.ui.message.MessageListContext
import net.amiantos.lurker.ui.message.MessageListLayout
import net.amiantos.lurker.ui.message.MessageTextStyle
import net.amiantos.lurker.ui.message.ReactionContext
import net.amiantos.lurker.ui.shell.StateModel
import net.amiantos.lurker.ui.shell.StateSymbol
import net.amiantos.lurker.ui.theme.LurkerColors
import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.BufferPlaceholder
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.IgnoreRule
import net.amiantos.lurkerkit.model.IgnoreSet
import net.amiantos.lurkerkit.model.Member
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageReaction
import net.amiantos.lurkerkit.model.MessageRow
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.PresenceState
import net.amiantos.lurkerkit.model.Reactions
import net.amiantos.lurkerkit.model.RunPosition
import net.amiantos.lurkerkit.model.SettingValue
import net.amiantos.lurkerkit.model.Settings
import net.amiantos.lurkerkit.model.StatusLight
import net.amiantos.lurkerkit.model.TagSupport
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import net.amiantos.lurkerkit.model.MemberPrefix

/**
 * The conversation's decisions: hydrating, noticing a buffer leave, the rows it builds, and what its
 * title and empty state say. Ports of lurker-ios's `ChatViewController` rules — `hydrateIfNeeded` and
 * `handleBufferDisappeared` in particular carry a comment per bug that shipped.
 */
class ConversationModelTest {

    private val channel = BufferKey(1, "#lurker")
    private val libera = Network(id = 1, name = "Libera", state = ConnectionState.Connected, nick = "me")

    private fun row(
        key: BufferKey = channel,
        hydrated: Boolean = false,
        bufferId: Int? = 40,
        target: String = key.target,
    ) = Buffer(
        networkId = key.networkId,
        target = target,
        kind = BufferKind.of(key.networkId, key.target),
        hydrated = hydrated,
        bufferId = bufferId,
        joined = true,
    )

    private fun message(id: Long, nick: String?, text: String?, type: EventType = EventType.Message) =
        Message(id = id, type = type, nick = nick, text = text, date = Instant.parse("2026-07-25T14:41:00Z").plusSeconds(id))

    private fun state(
        buffers: List<Buffer> = listOf(row()),
        messages: Map<String, List<Message>> = emptyMap(),
        backlogComplete: Boolean = true,
        connection: SocketStatus = SocketStatus.Connected,
        networks: Map<Int, Network> = mapOf(1 to libera),
        members: Map<String, List<Member>> = emptyMap(),
        ignores: IgnoreSet = IgnoreSet.empty,
        settings: Settings = Settings(),
        keysById: Map<Int, String> = emptyMap(),
        peerPresence: Map<Int, Map<String, PresenceState>> = emptyMap(),
    ) = ChatState(
        connection = connection,
        snapshotSinceOpen = true,
        backlogComplete = backlogComplete,
        networks = networks,
        buffers = buffers.associateBy { it.key.id },
        messages = messages,
        members = members,
        ignores = ignores,
        settings = settings,
        keysById = keysById,
        peerPresence = peerPresence,
    )

    // MARK: - Hydrate

    @Test
    fun `a shell is hydrated once per burst, by its stored casing`() {
        val gate = HydrateGate(BufferKind.Channel)
        val stored = row(target = "#Lurker")
        assertEquals(BufferKey(1, "#Lurker"), gate.check(SocketStatus.Connected, stored, burstGeneration = 1))
        // Asked: the window before the reply lands is not a reason to ask again.
        assertNull(gate.check(SocketStatus.Connected, stored, burstGeneration = 1))
        // A new burst voids the request, even with the socket reading Connected throughout.
        assertEquals(BufferKey(1, "#Lurker"), gate.check(SocketStatus.Connected, stored, burstGeneration = 2))
    }

    @Test
    fun `nothing is asked over no socket, or for a row that isn't there, or one already hydrated`() {
        val gate = HydrateGate(BufferKind.Channel)
        assertNull(gate.check(SocketStatus.Reconnecting, row(), 1))
        assertNull(gate.check(SocketStatus.Connected, null, 1))
        assertNull(gate.check(SocketStatus.Connected, row(hydrated = true), 1))
    }

    @Test
    fun `a drop re-arms the request`() {
        val gate = HydrateGate(BufferKind.Channel)
        assertEquals(channel, gate.check(SocketStatus.Connected, row(), 1))
        assertNull(gate.check(SocketStatus.Reconnecting, row(), 1))
        assertEquals(channel, gate.check(SocketStatus.Connected, row(), 1))
    }

    @Test
    fun `a row that vanishes and comes back as a shell is asked for again`() {
        val gate = HydrateGate(BufferKind.Channel)
        assertEquals(channel, gate.check(SocketStatus.Connected, row(), 1))
        assertNull(gate.check(SocketStatus.Connected, null, 1))
        assertEquals(channel, gate.check(SocketStatus.Connected, row(), 1))
    }

    @Test
    fun `a hydrated row going unhydrated (a rename merge) is asked for again`() {
        val gate = HydrateGate(BufferKind.Channel)
        assertEquals(channel, gate.check(SocketStatus.Connected, row(), 1))
        assertNull(gate.check(SocketStatus.Connected, row(hydrated = true), 1))
        assertEquals(channel, gate.check(SocketStatus.Connected, row(hydrated = false), 1))
    }

    @Test
    fun `the system buffer and a server log never ask`() {
        assertNull(HydrateGate(BufferKind.System).check(SocketStatus.Connected, Buffer.system, 1))
        val server = row(BufferKey(1, Buffer.serverTarget(1)))
        assertNull(HydrateGate(BufferKind.Server).check(SocketStatus.Connected, server, 1))
    }

    @Test
    fun `a pending jump hydrates through its own slice instead`() {
        assertNull(HydrateGate(BufferKind.Channel).check(SocketStatus.Connected, row(), 1, jumpPending = true))
    }

    // MARK: - The buffer leaving

    @Test
    fun `no row yet is waiting until the burst says it isn't coming`() {
        val watch = BufferWatch(channel, BufferKind.Channel)
        assertEquals(BufferWatch.Verdict.Waiting, watch.check(state(buffers = emptyList(), backlogComplete = false)))
        assertEquals(BufferWatch.Verdict.Gone, watch.check(state(buffers = emptyList(), backlogComplete = true)))
    }

    @Test
    fun `a row taken away is gone, settled or not`() {
        val watch = BufferWatch(channel, BufferKind.Channel)
        assertEquals(BufferWatch.Verdict.Present, watch.check(state(backlogComplete = false)))
        assertEquals(BufferWatch.Verdict.Gone, watch.check(state(buffers = emptyList(), backlogComplete = false)))
    }

    @Test
    fun `a renamed row is followed by its id`() {
        val watch = BufferWatch(channel, BufferKind.Channel)
        assertEquals(BufferWatch.Verdict.Present, watch.check(state()))
        val renamed = row(BufferKey(1, "#lurker-dev"))
        val moved = watch.check(state(buffers = listOf(renamed), keysById = mapOf(40 to renamed.key.id)))
        assertEquals(BufferWatch.Verdict.Moved(BufferKey(1, "#lurker-dev")), moved)
    }

    @Test
    fun `a server log with no row yet waits while its network exists`() {
        val log = BufferKey(1, Buffer.serverTarget(1))
        val watch = BufferWatch(log, BufferKind.Server)
        assertEquals(BufferWatch.Verdict.Waiting, watch.check(state(buffers = emptyList())))
        assertEquals(BufferWatch.Verdict.Gone, watch.check(state(buffers = emptyList(), networks = emptyMap())))
    }

    @Test
    fun `the system buffer is never gone, row or no row`() {
        val watch = BufferWatch(Buffer.system.key, BufferKind.System)
        assertEquals(BufferWatch.Verdict.Waiting, watch.check(state(buffers = emptyList(), backlogComplete = true)))
    }

    // MARK: - Rows

    private fun inputs(state: ChatState, key: BufferKey = channel) =
        ConversationProjector(key, BufferKind.of(key.networkId, key.target)).project(state)

    private fun rowsOf(inputs: ConversationInputs) = ConversationModel.built(inputs, RowOptions()).rows

    private fun texts(rows: List<MessageRow>) = rows.mapNotNull { it.message?.text }

    @Test
    fun `a channel draws its speech and leaves out what a channel never shows`() {
        val messages = listOf(
            message(1, "alice", "hi"),
            message(2, null, "system line", type = EventType.System),
            message(3, "bob", "   "),
            message(4, "bob", "yo"),
        )
        val rows = rowsOf(inputs(state(messages = mapOf(channel.id to messages))))
        assertEquals(listOf("hi", "yo"), texts(rows))
    }

    @Test
    fun `an ignore rule hides its lines at render time`() {
        val ignores = IgnoreSet(byNetwork = mapOf(1 to listOf(IgnoreRule(id = 1, mask = "spammer"))))
        val messages = listOf(message(1, "spammer", "buy"), message(2, "alice", "hi"))
        val rows = rowsOf(inputs(state(messages = mapOf(channel.id to messages), ignores = ignores)))
        assertEquals(listOf("hi"), texts(rows))
    }

    @Test
    fun `history exhausted says so at the top, and an unhydrated buffer doesn't claim it`() {
        val messages = mapOf(channel.id to listOf(message(1, "alice", "hi")))
        val unknown = rowsOf(inputs(state(messages = messages, buffers = emptyList())))
        assertFalse(unknown.contains(MessageRow.StartOfHistory))
        val exhausted = rowsOf(
            inputs(state(messages = messages, buffers = listOf(row(hydrated = true).copy(hasMoreOlder = false)))),
        )
        assertEquals(MessageRow.StartOfHistory, exhausted.first())
    }

    // MARK: - Title

    @Test
    fun `a channel's title names it, with its network and status`() {
        val title = ConversationModel.title(state(), channel, BufferKind.Channel)
        assertEquals("#lurker", title.title)
        assertEquals(StatusLight.Good, title.status)
        assertEquals("Libera · Online", title.subtitle)
    }

    @Test
    fun `a DM's subtitle is the peer, a server log is its network, the system buffer is Lurker`() {
        val dm = BufferKey(1, "alice")
        val dmTitle = ConversationModel.title(
            state(buffers = listOf(row(dm)), peerPresence = mapOf(1 to mapOf("alice" to PresenceState.Away))),
            dm,
            BufferKind.Dm,
        )
        assertEquals("Libera · Away", dmTitle.subtitle)
        val log = BufferKey(1, Buffer.serverTarget(1))
        assertEquals("Libera", ConversationModel.title(state(), log, BufferKind.Server).title)
        val system = ConversationModel.title(state(), Buffer.system.key, BufferKind.System)
        assertEquals("Lurker", system.title)
        assertEquals("Connected", system.subtitle)
    }

    @Test
    fun `a dropped network turns the light red whatever the socket says`() {
        val down = mapOf(1 to libera.copy(state = ConnectionState.Disconnected))
        assertEquals(StatusLight.Bad, ConversationModel.title(state(networks = down), channel, BufferKind.Channel).status)
    }

    // MARK: - Empty and loading

    @Test
    fun `the empty state invites per kind`() {
        assertEquals(StateModel("No messages yet", StateSymbol.Conversation, "Messages in #lurker will show up here."), ConversationModel.emptyState(BufferKind.Channel, "#lurker"))
        assertEquals(StateModel("No messages yet", StateSymbol.Conversation, "Say hello to alice."), ConversationModel.emptyState(BufferKind.Dm, "alice"))
        assertEquals(StateModel("No messages yet", StateSymbol.Conversation, "A direct chat with bob."), ConversationModel.emptyState(BufferKind.Dcc, "=bob"))
        assertEquals(StateModel("Nothing from the server yet", StateSymbol.Server), ConversationModel.emptyState(BufferKind.Server, ":server:1"))
        assertEquals(
            StateModel("Welcome to Lurker", StateSymbol.Welcome, "Run /commands to see what you can do."),
            ConversationModel.emptyState(BufferKind.System, ":system:"),
        )
    }

    @Test
    fun `a shell is loading until its history lands, then empty`() {
        assertEquals(BufferPlaceholder.Loading, ConversationModel.placeholder(false, inputs(state())))
        assertEquals(BufferPlaceholder.Empty, ConversationModel.placeholder(false, inputs(state(buffers = listOf(row(hydrated = true))))))
        assertEquals(BufferPlaceholder.None, ConversationModel.placeholder(true, inputs(state())))
    }

    // MARK: - Nick colouring and glyphs

    @Test
    fun `the highlighter looks for members, never you`() {
        val members = listOf(Member("alice"), Member("ME"), Member("bob"))
        assertEquals(listOf("alice", "bob"), ConversationModel.highlighterNicks(BufferKind.Channel, channel, members, ownNick = "me"))
        assertEquals(listOf("alice"), ConversationModel.highlighterNicks(BufferKind.Dm, BufferKey(1, "alice"), emptyList(), "me"))
        assertEquals(listOf("bob"), ConversationModel.highlighterNicks(BufferKind.Dcc, BufferKey(1, "=bob"), emptyList(), "me"))
        assertTrue(ConversationModel.highlighterNicks(BufferKind.Server, channel, members, "me").isEmpty())
    }

    @Test
    fun `mode glyphs are drawn only when the setting asks, and only in a channel`() {
        val members = listOf(Member("alice", modes = listOf("o")), Member("bob"))
        assertTrue(ConversationModel.modePrefixes(BufferKind.Channel, members, showsPrefix = false, prefix = null).isEmpty())
        assertEquals(mapOf("alice" to MemberPrefix.Mark("@", MemberPrefix.Tier.Op)), ConversationModel.modePrefixes(BufferKind.Channel, members, showsPrefix = true, prefix = null))
        assertTrue(ConversationModel.modePrefixes(BufferKind.Dm, members, showsPrefix = true, prefix = null).isEmpty())
        val on = Settings(registry = emptyMap(), values = mapOf("look.nick.show_mode_prefix" to SettingValue.Bool(true)))
        val projected = inputs(state(settings = on, members = mapOf(channel.id to members)))
        assertEquals(mapOf("alice" to MemberPrefix.Mark("@", MemberPrefix.Tier.Op)), projected.modePrefixes)
    }

    // MARK: - Inputs

    @Test
    fun `an unchanged frame is the same, and a new message list is not`() {
        val messages = listOf(message(1, "alice", "hi"))
        val first = state(messages = mapOf(channel.id to messages))
        val projector = ConversationProjector(channel, BufferKind.Channel)
        val a = projector.project(first)
        assertTrue(ConversationInputs.same(a, projector.project(first.copy(error = "elsewhere"))))
        val appended = first.copy(messages = mapOf(channel.id to messages + message(2, "bob", "yo")))
        assertFalse(ConversationInputs.same(a, projector.project(appended)))
    }

    @Test
    fun `the tail is followed only from the newest row`() {
        assertTrue(ConversationModel.followsTail(0, 0, 200))
        assertTrue(ConversationModel.followsTail(0, 150, 200))
        assertFalse(ConversationModel.followsTail(0, 250, 200))
        assertFalse(ConversationModel.followsTail(1, 0, 200))
    }

    // MARK: - Reaction chips (lurker#1101)

    /**
     * Each chip asks the kit's toggle rule with its own `mine`: on irc.so — a reaction goes out, a
     * take-back doesn't — our own chip can't toggle (its tap opens the sheet, which says why) while
     * anyone else's still adds ours. One "can react" flag for the whole row would have sent a removal
     * the server refuses in silence.
     */
    @Test
    fun `on irc_so our own chip opens the sheet and anyone else's adds ours`() {
        val ircSo = TagSupport(canAddReaction = true, canRemoveReaction = false, canReply = true)
        val line = Message(
            id = 7, type = EventType.Message, nick = "alice", text = "hi", msgid = "x",
            reactions = listOf(MessageReaction("me", "👍", isSelf = true), MessageReaction("bob", "🎉", isSelf = false)),
        )
        val rows = listOf(MessageRow.Bubble(line, RunPosition.solo))
        fun chips(support: TagSupport): List<Pair<String, Boolean>> {
            val context = MessageListContext.over(
                rows,
                style = MessageTextStyle(colors = LurkerColors.Dark),
                reactions = ReactionContext(
                    groups = { Reactions.groups(it.reactions.orEmpty()) },
                    canToggle = { message -> ConversationModel.chipToggles(message, channel.target, support) },
                    showsAdd = { true },
                    onToggle = { _, _ -> },
                    onOpen = {},
                ),
            )
            return MessageListLayout.reactions(line, context)!!.chips.map { it.group.value to it.canToggle }
        }
        assertEquals(listOf("👍" to false, "🎉" to true), chips(ircSo))
        assertEquals(
            listOf("👍" to true, "🎉" to true),
            chips(TagSupport(canAddReaction = true, canRemoveReaction = true, canReply = true)),
        )
        assertEquals(listOf("👍" to false, "🎉" to false), chips(TagSupport.nothing))
    }

    /**
     * The frame's inputs carry what goes out right now, and compare on it: our own socket dropping
     * changes no network row, yet every chip stops toggling.
     */
    @Test
    fun `the inputs carry tag support and compare on it`() {
        val ircSo = TagSupport(canAddReaction = true, canRemoveReaction = false, canReply = true)
        val state = ChatState(
            connection = SocketStatus.Connected,
            networks = mapOf(1 to libera.copy(tagSupport = ircSo)),
        )
        val projector = ConversationProjector(channel, BufferKind.Channel)
        val first = projector.project(state)
        assertEquals(ircSo, first.support)
        val dropped = projector.project(state.copy(connection = SocketStatus.Reconnecting))
        assertEquals(TagSupport.nothing, dropped.support)
        assertFalse(ConversationInputs.same(first, dropped))
    }
}
