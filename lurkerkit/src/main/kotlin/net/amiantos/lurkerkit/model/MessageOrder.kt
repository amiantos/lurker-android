// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurkerkit.model

import java.time.Instant

/**
 * The order a buffer's rows are shown in: by the time the server stamped them, with id as the
 * tiebreak (lurker#1290; the web's `lib/messageOrder`, lurker-ios's `MessageOrder`).
 *
 * Ids are the order rows were STORED in, and the two agree for everything heard live. They part
 * on a line fetched after the fact (`replay`, lurker#1283): a gap filled from the network's
 * history after a reconnect holds lines from before the rejoin, stored after its JOIN row with
 * fresh ids, and shown by id they would sit under the join they precede. Ids stay what they are
 * everywhere else: the paging cursor, the dedupe key, the unread boundary (CLIENT_PROTOCOL §9).
 * The store's list is never reordered; this is applied where the rows are built.
 */
object MessageOrder {
    private class Key(val date: Instant, val id: Long, val offset: Int) : Comparable<Key> {
        override fun compareTo(other: Key): Int {
            val byDate = date.compareTo(other.date)
            if (byDate != 0) return byDate
            val byId = id.compareTo(other.id)
            // The offset is the last word, so rows that tie keep the order they were held in.
            return if (byId != 0) byId else offset.compareTo(other.offset)
        }
    }

    /**
     * Where a row sorts. A row with no clock (a line the app wrote itself, `appendLocal`) has
     * nothing to be placed by, so it rides the row it arrived after and moves with it. A row
     * with a clock and no id (an ephemeral) goes by its time, last among rows of that time.
     */
    private fun key(message: Message, previous: Key?, offset: Int): Key {
        val date = message.date
            ?: return Key(previous?.date ?: Instant.MIN, previous?.id ?: 0L, offset)
        return Key(date, if (message.id != 0L) message.id else Long.MAX_VALUE, offset)
    }

    /**
     * [rows] in display order. The list itself when it already is, which is every buffer that
     * holds nothing replayed, so a build costs one pass.
     */
    fun byTime(rows: List<Message>): List<Message> {
        val keys = ArrayList<Key>(rows.size)
        var ordered = true
        var previous: Key? = null
        for ((offset, message) in rows.withIndex()) {
            val next = key(message, previous, offset)
            if (previous != null && previous > next) ordered = false
            keys.add(next)
            previous = next
        }
        if (ordered) return rows
        return keys.sorted().map { rows[it.offset] }
    }
}
