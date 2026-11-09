// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit

import net.amiantos.lurkerkit.model.EventType
import net.amiantos.lurkerkit.model.Message
import net.amiantos.lurkerkit.model.MessageOrder
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * A buffer's rows are shown by time, id as the tiebreak (lurker#1290). The web's
 * `messageOrder.test.ts`, and lurker-ios's `MessageOrderTests` for the rows with no clock.
 */
class MessageOrderTests {

    private fun at(minute: Long): Instant = Instant.parse("2026-11-06T09:00:00Z").plusSeconds(minute * 60)

    private fun msg(id: Long, minute: Long?, text: String = "line") =
        Message(id = id, type = EventType.Message, nick = "alice", text = text, date = minute?.let(::at))

    private fun ids(rows: List<Message>) = rows.map { it.id }

    @Test
    fun `a buffer with nothing replayed is left as it is, the same list`() {
        val rows = listOf(msg(1, 1), msg(2, 2), msg(3, 2))
        assertSame(rows, MessageOrder.byTime(rows))
    }

    @Test
    fun `a replayed line goes by its time, above the join it was stored after`() {
        val rows = listOf(
            msg(10, 18, "before"),
            msg(20, 22, "join"),
            msg(21, 19, "gap one"),
            msg(22, 20, "gap two"),
            msg(23, 23, "after"),
        )
        assertEquals(listOf("before", "gap one", "gap two", "join", "after"), MessageOrder.byTime(rows).map { it.text })
        // The held list is untouched: it is the id order paging relies on.
        assertEquals(listOf(10L, 20L, 21L, 22L, 23L), ids(rows))
    }

    @Test
    fun `a shared time breaks by id`() {
        assertEquals(listOf(1L, 2L, 4L), ids(MessageOrder.byTime(listOf(msg(2, 5), msg(1, 5), msg(4, 6)))))
    }

    @Test
    fun `a row with a time and no id is last among its time`() {
        assertEquals(listOf(1L, 2L, 0L), ids(MessageOrder.byTime(listOf(msg(2, 5), msg(0, 5), msg(1, 3)))))
    }

    @Test
    fun `a row with no time rides the row it arrived after`() {
        // The app's own line, written under the join; the gap's lines go above both.
        val rows = listOf(msg(10, 18), msg(20, 22, "join"), msg(0, null, "unknown command"), msg(21, 19), msg(22, 20))
        assertEquals(listOf(10L, 21L, 22L, 20L, 0L), ids(MessageOrder.byTime(rows)))
    }

    @Test
    fun `rows with no time at the head of the list stay there`() {
        assertEquals(listOf(0L, 21L, 20L), ids(MessageOrder.byTime(listOf(msg(0, null, "local"), msg(20, 22), msg(21, 19)))))
    }
}
