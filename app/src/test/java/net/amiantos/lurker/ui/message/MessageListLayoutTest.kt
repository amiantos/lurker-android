// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.message

import androidx.compose.ui.text.AnnotatedString
import net.amiantos.lurker.ui.theme.LurkerColors
import net.amiantos.lurkerkit.model.ConsolidationSummary
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageRow
import net.amiantos.lurkerkit.model.MessageRows
import net.amiantos.lurkerkit.model.ReplyContext
import net.amiantos.lurkerkit.model.RunPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import net.amiantos.lurkerkit.model.MemberPrefix

/**
 * `MessageListLayout` — the decisions lurker-ios's `MessageListRenderer` makes per row: who heads a
 * block, where one ends, when a reply is quoted again, and the keys the lazy list is handed.
 */
class MessageListLayoutTest {

    private val style = MessageTextStyle(colors = LurkerColors.Dark)
    private val base = Instant.parse("2026-07-25T14:41:00Z")
    private var nextId = 0L

    private fun line(
        nick: String?,
        seconds: Long?,
        type: EventType = EventType.Message,
        text: String = "x",
        relayBot: Boolean = false,
        replyTo: ReplyContext? = null,
        id: Long = ++nextId,
    ): Message {
        val message = Message(id = id, type = type, nick = nick, text = text, date = seconds?.let(base::plusSeconds), replyTo = replyTo)
        return if (relayBot) message.relayed(speaker = nick ?: "", text = text, bot = "bridge", source = "github") else message
    }

    private fun rows(vararg messages: Message): List<MessageRow> =
        MessageRows.build(messages.toList(), dividerAfterId = null, hasMoreOlder = true, zone = ZoneOffset.UTC)
            .filter { it !is MessageRow.DateDivider }

    private fun context(rows: List<MessageRow>, modePrefixes: Map<String, MemberPrefix.Mark> = emptyMap()) =
        MessageListContext.over(rows, style = style, modePrefixes = modePrefixes, zone = ZoneOffset.UTC, today = LocalDate.of(2026, 7, 25))

    private fun plans(rows: List<MessageRow>, modePrefixes: Map<String, MemberPrefix.Mark> = emptyMap()): List<RowPlan> {
        val context = context(rows, modePrefixes)
        return rows.mapIndexed { index, row -> MessageListLayout.plan(row, index, context) }
    }

    private fun RowPlan.header() = (this as RowPlan.Compact).header

    // MARK: - Headers

    @Test
    fun `a run under one minute stacks under one header`() {
        val plans = plans(rows(line("alice", 0), line("alice", 20), line("alice", 40)))
        assertEquals("alice", plans[0].header()?.nick)
        assertEquals("14:41", plans[0].header()?.time)
        assertNull(plans[1].header())
        assertNull(plans[2].header())
    }

    @Test
    fun `a minute change forces a header mid-run, with the time`() {
        val plans = plans(rows(line("alice", 0), line("alice", 70)))
        val header = plans[1].header()
        assertNotNull(header)
        assertEquals("alice", header?.nick)
        assertEquals("14:42", header?.time)
    }

    @Test
    fun `an author change heads a block, and the time shows only when the minute moved`() {
        val plans = plans(rows(line("alice", 0), line("bob", 10), line("carol", 65)))
        assertEquals("bob", plans[1].header()?.nick)
        assertNull(plans[1].header()?.time)
        assertEquals("14:42", plans[2].header()?.time)
    }

    @Test
    fun `an undated line never counts as a minute change, and the first dated one after it does`() {
        assertFalse(MessageListLayout.changedMinute(null, base))
        assertTrue(MessageListLayout.changedMinute(base, null))
        assertFalse(MessageListLayout.changedMinute(base, base.plusSeconds(59), ZoneOffset.UTC))
        assertTrue(MessageListLayout.changedMinute(base.plusSeconds(60), base, ZoneOffset.UTC))
    }

    @Test
    fun `narration is header-less and starts its own block`() {
        val plans = plans(rows(line("alice", 0), line("alice", 5, type = EventType.Action)))
        val me = plans[1] as RowPlan.Compact
        assertNull(me.header)
        assertTrue(me.startsBlock)
        assertFalse(me.indentsBody)
    }

    @Test
    fun `a mode glyph heads a member's line, never a relayed one`() {
        val prefixes = mapOf("alice" to MemberPrefix.Mark("@", MemberPrefix.Tier.Op))
        val plain = plans(rows(line("alice", 0)), prefixes)[0].header()!!
        assertEquals("@alice", plain.nick)
        assertEquals("@", plain.modeMark?.glyph)
        val relayed = plans(rows(line("alice", 0, relayBot = true)), prefixes)[0].header()!!
        assertEquals("alice", relayed.nick)
        assertNull(relayed.modeMark)
        assertEquals("github", relayed.relaySource)
    }

    /**
     * A notice's caption takes no glyph, and its `-alice-` must not wear one either — with a network
     * whose op symbol is `-` it "starts with" it (lurker-ios#191's Codex finding).
     */
    @Test
    fun `a notice never wears a rank mark, whatever the network's symbols`() {
        val prefixes = mapOf("alice" to MemberPrefix.Mark("-", MemberPrefix.Tier.Op))
        val notice = plans(rows(line("alice", 0, type = EventType.Notice)), prefixes)[0].header()!!
        assertEquals("-alice-", notice.nick)
        assertNull(notice.modeMark)
    }

    @Test
    fun `server text with nothing to call it has no header`() {
        val plans = plans(rows(line(null, 0, type = EventType.Motd)))
        assertNull(plans[0].header())
    }

    // MARK: - Blocks

    @Test
    fun `a block ends where the next header begins, and at the very end`() {
        val plans = plans(rows(line("alice", 0), line("alice", 10), line("bob", 20))).map { it as RowPlan.Compact }
        assertFalse(plans[0].endsBlock)
        assertTrue(plans[1].endsBlock)
        assertTrue(plans[2].endsBlock)
    }

    @Test
    fun `a run of status narration is one block`() {
        val settingsOff = net.amiantos.lurkerkit.model.Settings(
            registry = emptyMap(),
            values = mapOf("chat.consolidate_joins" to net.amiantos.lurkerkit.model.SettingValue.Bool(false)),
        )
        val rows = MessageRows.build(
            listOf(line("a", 0, type = EventType.Join), line("b", 1, type = EventType.Join), line("c", 2, type = EventType.Join)),
            dividerAfterId = null,
            hasMoreOlder = true,
            settings = settingsOff,
            zone = ZoneOffset.UTC,
        ).filter { it !is MessageRow.DateDivider }
        val plans = plans(rows).map { it as RowPlan.Compact }
        assertEquals(3, plans.size)
        assertEquals(listOf(true, false, false), plans.map { it.startsBlock })
        assertEquals(listOf(false, false, true), plans.map { it.endsBlock })
    }

    @Test
    fun `a matched line carries the wash`() {
        val matched = Message(id = 1, type = EventType.Message, nick = "bob", text = "hey alice", date = base, matched = true)
        assertTrue((plans(rows(matched))[0] as RowPlan.Compact).highlighted)
    }

    // MARK: - Replies

    @Test
    fun `a reply is quoted once per run of chunks answering the same line`() {
        val reply = ReplyContext(msgid = "p", parent = null)
        val plans = plans(rows(line("alice", 0, replyTo = reply), line("alice", 5, replyTo = reply))).map { it as RowPlan.Compact }
        assertNotNull(plans[0].reply)
        assertNull(plans[1].reply)
        // With no quote to show, it says so rather than vanishing.
        assertNull(plans[0].reply?.quote)
    }

    @Test
    fun `a reply after someone else's line is quoted again, but a minute change alone doesn't re-quote`() {
        val reply = ReplyContext(msgid = "p", parent = null)
        val interrupted = plans(rows(line("alice", 0, replyTo = reply), line("bob", 5), line("alice", 10, replyTo = reply)))
            .map { it as RowPlan.Compact }
        assertNotNull(interrupted[2].reply)
        // Same author run across a minute: a new header, but still the same reply's next chunk.
        val sameRun = plans(rows(line("alice", 0, replyTo = reply), line("alice", 70, replyTo = reply))).map { it as RowPlan.Compact }
        assertNotNull(sameRun[1].header)
        assertNull(sameRun[1].reply)
    }

    // MARK: - Markers

    @Test
    fun `markers say what they mark`() {
        val at = Instant.parse("2026-07-25T00:00:00Z")
        val rows = listOf(
            MessageRow.UnreadDivider,
            MessageRow.StartOfHistory,
            MessageRow.DateDivider(at),
            MessageRow.AwayDivider(at, "lunch"),
        )
        val plans = plans(rows).map { it as RowPlan.Marker }
        assertEquals(RowPlan.Marker("New messages", MarkerTone.Unread), plans[0])
        assertEquals(RowPlan.Marker("— start of history —", MarkerTone.Faint), plans[1])
        assertEquals(RowPlan.Marker("Today", MarkerTone.Muted), plans[2])
        assertEquals(RowPlan.Marker("You went away: lunch", MarkerTone.Muted), plans[3])
    }

    // MARK: - Keys

    @Test
    fun `keys are unique, ephemerals numbered, duplicates suffixed`() {
        val at = Instant.parse("2026-07-25T00:00:00Z")
        val summary = ConsolidationSummary(groups = emptyList(), date = null, firstId = 3, lastId = 5)
        val ephemeral = Message(id = 0, type = EventType.System, nick = null, text = "local")
        val keys = MessageListLayout.rowKeys(
            listOf(
                MessageRow.DateDivider(at),
                MessageRow.Bubble(line("a", 0, id = 9), RunPosition.solo),
                MessageRow.Bubble(line("a", 0, id = 9), RunPosition.solo),
                MessageRow.Consolidated(summary),
                MessageRow.Line(ephemeral),
                MessageRow.Line(ephemeral),
                MessageRow.Typing(listOf("x")),
            ),
        )
        assertEquals(listOf("d${at.epochSecond}", "m9", "m9#1", "c5", "e0", "e1", "typing"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    // MARK: - Speech

    @Test
    fun `a row is spoken as name, line, time`() {
        val header = CompactHeader("alice", style.colors.fg, time = "14:42")
        assertEquals("alice, hello, 14:42", MessageListLayout.spokenRow(header, AnnotatedString("hello")).text)
        val relayed = CompactHeader("alice", style.colors.fg, time = null, relaySource = "github")
        assertEquals("alice from github, hello", MessageListLayout.spokenRow(relayed, AnnotatedString("hello")).text)
        assertEquals("hello", MessageListLayout.spokenRow(null, AnnotatedString("hello")).text)
    }
}
