// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.actions

import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.ReactionGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(setOf("👍"), ReactionSheetInputs(groups, canReact = true).mine)
        assertEquals("👍, 2: bob, me", ReactionSheetModel.spoken(groups[0]))
        assertEquals("bob, me", ReactionSheetModel.names(groups[0]))
    }
}
