// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.actions

import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageReaction
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.model.ReactionGroup
import net.amiantos.lurkerkit.model.TagSupport
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reaction sheet's words, its quick-pick grid and its field (lurker-ios#183). */
class ReactionSheetModelTest {

    private fun line(nick: String? = "alice", isSelf: Boolean = false, type: EventType = EventType.Message, msgid: String? = "x") =
        Message(id = 7, type = type, nick = nick, text = "\u0002shipped\u0002 it", isSelf = isSelf, msgid = msgid)

    @Test
    fun `the title names who you're reacting to`() {
        assertEquals("React to alice", ReactionSheetModel.title(line()))
        assertEquals("React to your message", ReactionSheetModel.title(line(nick = "me", isSelf = true)))
        assertEquals("React", ReactionSheetModel.title(line(nick = null)))
        assertEquals("shipped it", ReactionSheetModel.quote(line())?.shown)
    }

    @Test
    fun `the quote never prints a hidden spoiler`() {
        val spoiler = Message(id = 7, type = EventType.Message, nick = "alice", text = "it was \u000300,00\u0002Ros\u0002ebud\u0003!", msgid = "x")
        val quote = ReactionSheetModel.quote(spoiler)!!
        assertEquals("it was ███████!", quote.shown)
        // One box, though a bold splits it into runs: announced once.
        assertEquals("it was hidden spoiler!", quote.spoken)
    }

    @Test
    fun `the quick picks are two rows of four`() {
        val rows = ReactionSheetModel.quickRows()
        assertEquals(listOf(4, 4), rows.map { it.size })
        assertEquals(listOf("👍", "❤️", "😂", "🎉"), rows[0])
        assertEquals(listOf(listOf("a", "b"), listOf("c")), ReactionSheetModel.quickRows(listOf("a", "b", "c")))
    }

    @Test
    fun `offline blames the line when the line is the reason`() {
        assertEquals("This network can't carry reactions right now.", ReactionSheetModel.offline(line(), "#lurker"))
        assertEquals("This line can't take reactions.", ReactionSheetModel.offline(line(type = EventType.Notice), "#lurker"))
        assertEquals("This line can't take reactions.", ReactionSheetModel.offline(line(msgid = null), "#lurker"))
        assertEquals("This line can't take reactions.", ReactionSheetModel.offline(line(), "=bob"))
    }

    @Test
    fun `the field trims, and refuses what the server wouldn't take`() {
        val typed = TypedReaction.of("  lol \n")
        assertEquals("lol", typed.value)
        assertTrue(typed.canSubmit)
        assertFalse(TypedReaction.of("   ").canSubmit)
        assertFalse(TypedReaction.of("   ").tooLong)
        val long = TypedReaction.of("x".repeat(65))
        assertTrue(long.tooLong)
        assertFalse(long.canSubmit)
        // Counted in grapheme clusters: 64 family emoji are 64, not 704.
        assertTrue(TypedReaction.of("👨‍👩‍👧‍👦".repeat(64)).canSubmit)
        assertTrue(TypedReaction.of("a\nb").tooLong)
    }

    @Test
    fun `ours are the groups we're in, and the spoken form names everyone`() {
        val groups = listOf(ReactionGroup("👍", listOf("bob", "me"), mine = true), ReactionGroup("🎉", listOf("carol"), mine = false))
        assertEquals(setOf("👍"), ReactionSheetInputs(groups, canAdd = true, canRemove = true).mine)
        assertEquals("👍, 2: bob, me", ReactionSheetModel.spoken(groups[0]))
        assertEquals("bob, me", ReactionSheetModel.names(groups[0]))
    }

    // MARK: - What the network takes (lurker#1101)

    private val key = BufferKey(1, "#lurker")
    private val ircSo = TagSupport(canAddReaction = true, canRemoveReaction = false, canReply = true)
    private val allTags = TagSupport(canAddReaction = true, canRemoveReaction = true, canReply = true)

    /** Line 7 carries our 👍 and bob's 🎉, on a connected network that takes [support]. */
    private fun state(support: TagSupport, connected: Boolean = true) = ChatState(
        connection = if (connected) SocketStatus.Connected else SocketStatus.Reconnecting,
        networks = mapOf(1 to Network(id = 1, name = "irc.so", state = ConnectionState.Connected, nick = "me", tagSupport = support)),
        reactions = mapOf(
            7L to listOf(MessageReaction("me", "👍", isSelf = true), MessageReaction("bob", "🎉", isSelf = false)),
        ),
    )

    /**
     * irc.so takes a new reaction and refuses a take-back: the picks and the field stay, our own value
     * doesn't go, and the sheet says why under the list. Read as one flag, the sheet either hid
     * everything (the server's `canReact` is false there) or offered a take-back it refuses in silence.
     */
    @Test
    fun `on irc_so the sheet adds but never offers taking ours back`() {
        val inputs = ReactionSheetInputs.of(state(ircSo), line(), key)
        assertTrue("the picks and the field are there", inputs.canAdd)
        assertFalse(inputs.canRemove)
        assertFalse("ours: a take-back", inputs.works("👍"))
        assertTrue("bob's adds ours", inputs.works("🎉"))
        assertTrue("a new one", inputs.works("😂"))
        assertTrue(inputs.noTakeBack)

        val all = ReactionSheetInputs.of(state(allTags), line(), key)
        assertTrue(all.works("👍"))
        assertFalse(all.noTakeBack)
        // Nothing of ours standing: nothing to explain.
        assertFalse(ReactionSheetInputs(emptyList(), canAdd = true, canRemove = false).noTakeBack)
        // Nothing goes out at all: the offline line says so instead.
        val down = ReactionSheetInputs.of(state(ircSo, connected = false), line(), key)
        assertFalse(down.canAdd)
        assertFalse(down.noTakeBack)
    }

    /** A typed value that's ours is a take-back too, refused at the field rather than sent to nothing. */
    @Test
    fun `typing our own value on irc_so says why and won't submit`() {
        val inputs = ReactionSheetInputs.of(state(ircSo), line(), key)
        val ours = TypedReaction.of(" 👍 ", inputs)
        assertTrue(ours.refused)
        assertFalse(ours.canSubmit)
        assertEquals(ReactionSheetModel.NO_TAKE_BACK, ours.problem)
        val fresh = TypedReaction.of("lol", inputs)
        assertTrue(fresh.canSubmit)
        assertNull(fresh.problem)
        // Too long says that, not the take-back.
        assertEquals(ReactionSheetModel.TOO_LONG, TypedReaction.of("x".repeat(65), inputs).problem)
        // Where the network takes it back, ours is a take-back like any other.
        assertTrue(TypedReaction.of("👍", ReactionSheetInputs.of(state(allTags), line(), key)).canSubmit)
    }

    /**
     * The tap is re-checked against the store and goes through the kit's one rule: on irc.so our own
     * value is refused without anything going out; anything else goes, and a send with no socket says so.
     */
    @Test
    fun `a choice the network would refuse never reaches the socket`() {
        val sent = mutableListOf<String>()
        fun choose(state: ChatState, value: String, goesOut: Boolean = true) =
            ReactionSheetModel.choose(state, line(), key, value) {
                sent.add(value)
                goesOut
            }
        assertEquals(ReactionChoice.Refused, choose(state(ircSo), "👍"))
        assertEquals(ReactionChoice.Sent, choose(state(ircSo), "🎉"))
        assertEquals(ReactionChoice.NotConnected, choose(state(ircSo), "😂", goesOut = false))
        assertEquals(ReactionChoice.Sent, choose(state(allTags), "👍"))
        assertEquals(ReactionChoice.Refused, choose(state(allTags, connected = false), "🎉"))
        assertEquals(listOf("🎉", "😂", "👍"), sent)
    }
}
