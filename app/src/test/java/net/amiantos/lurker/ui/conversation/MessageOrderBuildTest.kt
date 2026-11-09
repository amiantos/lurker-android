// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.ui.conversation

import net.amiantos.lurkerkit.model.Buffer
import net.amiantos.lurkerkit.model.BufferKey
import net.amiantos.lurkerkit.model.BufferKind
import net.amiantos.lurkerkit.model.ConnectionState
import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageRow
import net.amiantos.lurkerkit.model.Network
import net.amiantos.lurkerkit.store.ChatState
import net.amiantos.lurkerkit.store.SocketStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * What showing a buffer's rows by time (lurker#1290, the kit's `MessageOrder`) has to hold through
 * on the way to the screen: the build, the unread divider, a consolidated run and the badge.
 * lurker-ios's `MessageOrderTests`, second half.
 */
class MessageOrderBuildTest {

    private val channel = BufferKey(1, "#lurker")
    private val kind = BufferKind.of(channel.networkId, channel.target)

    private fun at(minute: Long): Instant = Instant.parse("2026-11-06T09:00:00Z").plusSeconds(minute * 60)

    private fun msg(id: Long, minute: Long, text: String = "line", nick: String = "alice", type: EventType = EventType.Message) =
        Message(id = id, type = type, nick = nick, text = text, date = at(minute))

    /** A join at :22 stored before the two lines of the gap it closed, said at :19 and :20. */
    private val filledGap = listOf(
        msg(10, 18, "before"),
        msg(20, 22, "join", nick = "me", type = EventType.Join),
        msg(21, 19, "gap one"),
        msg(22, 20, "gap two"),
        msg(23, 23, "after"),
    )

    private fun state(messages: List<Message>, lastReadId: Long = 0): ChatState {
        val buffer = Buffer(
            networkId = channel.networkId,
            target = channel.target,
            kind = kind,
            hydrated = true,
            readStateKnown = true,
            lastReadId = lastReadId,
            joined = true,
        )
        return ChatState(
            connection = SocketStatus.Connected,
            snapshotSinceOpen = true,
            backlogComplete = true,
            networks = mapOf(1 to Network(id = 1, name = "Libera", state = ConnectionState.Connected, nick = "me")),
            buffers = mapOf(buffer.key.id to buffer),
            messages = mapOf(channel.id to messages),
        )
    }

    private fun build(state: ChatState, options: RowOptions = RowOptions()): BuiltRows =
        ConversationModel.built(ConversationProjector(channel, kind).project(state), options, zone = ZoneOffset.UTC)

    private fun shown(built: BuiltRows): List<String> = built.rows.mapNotNull { row ->
        when (row) {
            is MessageRow.Bubble, is MessageRow.Line -> "${row.message?.id}"
            is MessageRow.Consolidated -> "summary"
            MessageRow.UnreadDivider -> "unread"
            is MessageRow.DateDivider -> null
            else -> "other"
        }
    }

    @Test
    fun `the build shows a filled gap where it was said, and leaves the held list alone`() {
        val built = build(state(filledGap))
        assertEquals(listOf(10L, 21L, 22L, 20L, 23L), built.visible.map { it.id })
        assertEquals(listOf("10", "21", "22", "20", "23"), shown(built))
        // The store's list is the id order paging and the tail rule read.
        assertEquals(listOf(10L, 20L, 21L, 22L, 23L), built.inputs.messages.orEmpty().map { it.id })
    }

    @Test
    fun `the unread divider goes above the first unread row shown, over a join already read`() {
        // Read through the join (20); the gap's lines are unread and sit above it.
        val built = build(state(filledGap, lastReadId = 20), RowOptions(dividerAfterId = 20))
        assertEquals(listOf("10", "unread", "21", "22", "20", "23"), shown(built))
        assertEquals(21L, built.firstUnreadRow?.let { built.rows[it].message?.id })
    }

    @Test
    fun `a consolidated run answers for the events in it, not for the ids between them`() {
        // A part at :10 (id 40) and a replayed join at :11 (id 105) fold into one run; ids 41
        // and 42 are lines said later and drawn below it.
        val rows = listOf(
            msg(40, 10, nick = "bob", type = EventType.Part),
            msg(41, 12, "later one"),
            msg(42, 13, "later two"),
            msg(105, 11, nick = "carol", type = EventType.Join),
        )
        val built = build(state(rows))
        assertEquals(listOf("summary", "41", "42"), shown(built))
        assertEquals(built.rowIndex(40), built.rowIndex(105))
        assertEquals(41L, built.rowIndex(41)?.let { built.rows[it].message?.id })
        assertEquals(42L, built.rowIndex(42)?.let { built.rows[it].message?.id })
    }

    @Test
    fun `an id no row draws lands on the run that spans it`() {
        // A part and a join fold into one run; between them sits a row a channel doesn't render,
        // held and drawn by nothing.
        val rows = listOf(
            msg(49, 10, nick = "bob", type = EventType.Part),
            msg(50, 11, nick = "bob", type = EventType.System),
            msg(51, 12, nick = "bob", type = EventType.Join),
            msg(52, 13, "said"),
        )
        val built = build(state(rows))
        assertEquals(listOf("summary", "52"), shown(built))
        assertNotNull(built.rowIndex(49))
        assertEquals(built.rowIndex(49), built.rowIndex(50))
        assertNull(built.rowIndex(53))
    }

    @Test
    fun `the badge counts what landed below the last row shown, once`() {
        val scroll = ConversationScroll(kind)
        scroll.onFrame(state(filledGap, lastReadId = 23), channel, seq = 0)
        scroll.onRows(build(state(filledGap), scroll.options), wasNearBottom = false)
        // The last row SHOWN is 23, and so is the newest id. A live line lands: one new.
        scroll.onRows(build(state(filledGap + msg(24, 24)), scroll.options), wasNearBottom = false)
        assertEquals(1, scroll.newWhileAway)
        // A late line of the gap lands above (id 25, said at :21): not something below.
        scroll.onRows(build(state(filledGap + msg(24, 24) + msg(25, 21)), scroll.options), wasNearBottom = false)
        assertEquals(1, scroll.newWhileAway)
        // The last row shown is still 24, though the newest id is 25: 24 isn't counted again.
        scroll.onRows(build(state(filledGap + msg(24, 24) + msg(25, 21) + msg(26, 25)), scroll.options), wasNearBottom = false)
        assertEquals(2, scroll.newWhileAway)
    }
}
